"""계약 요청·응답 모델. `docs/FastAPI명세서.json` v1.1.0 을 따른다.

계약과 다른 곳을 전부 적는다 — 모두 협의 대상이다:

  * `currentWikis` 를 받지 않는다 — 읽기 4개로 되물으니 중복이다
  * `wikiChanges[].evidence` 를 더한다 — FR-AI-009 의 「각 변경의 근거 문서 위치」를
    `summary` 문자열 하나로는 담을 수 없다
  * `relationChanges` 의 스키마를 채운다 — 계약 예시가 빈 배열뿐이다
  * `wikiChanges[].wikiPath` 를 더한다 — DR-016 저장 컬럼이자, 신규 페이지 사이의 본문
    링크를 Spring 이 실제 `wikiId` 로 치환하려면 `pageKey` ↔ 변경의 대응이 필요하다
  * `TransformRequest.changeType` / `SelectionRequest.changeType` / `removedParsedMarkdown`
    — **계약 미반영**. `docs/FastAPI명세서.json` 에는 없는 필드다. v1.1.0 적응 설계
    (`docs/superpowers/specs/2026-07-28-ai-server-v1.1-adaptation.md` §1)가 "changeType이
    변환 요청에도 실린다는 가정으로 짓는다 (회신에 명기, 뒤집히면 필드 이동만)"라고 적어둔
    작업 가정이다 — v1.2 반영 대기, 회신 문서에 명기. Spring 확정이 뒤집히면 필드 위치만
    옮기면 된다

ID 는 전부 문자열이다. `BIGINT UNSIGNED` 가 JSON number 로 나가면 정밀도를 잃는다.
"""

from __future__ import annotations

from typing import Literal

from pydantic import (AliasChoices, BaseModel, ConfigDict, Field, ValidationError,
                      model_validator)
from pydantic_core import InitErrorDetails, PydanticCustomError


class Strict(BaseModel):
    """정의되지 않은 필드는 거부한다 (API_컨벤션 4.1)."""

    model_config = ConfigDict(extra="forbid")


# ---- 요청 크기 상한 (D7) ----------------------------------------------------
#
# `FR-WIKI-002` 가 v2.9 에서 "최대 5개 선택" 을 없앴다. Spring 이 범위 위키를 전량 실어
# 보낼 수 있게 되었고 여기에 상한이 없어 AI 코드 변경 없이 그대로 받는다.
#
# **개수로 막지 않는다.** 같은 개정이 "선택 개수 상한은 두지 않는다" 를 명시했으므로 개수
# 상한은 요구사항 위반이다. 실제 자원 한계인 바이트로 막는다 — 요청 본문은 파싱되어
# 메모리에 올라가고, 하이드레이션이 임시 디스크에 파일로 쓴다.
#
# **측정한 것과 계산한 것을 구분해 적는다.** 아래 상한은 관측된 한계가 아니라 관측값에서
# 나눗셈으로 고른 자리다 — 사내 위키가 실제로 몇 장까지 커지는지는 아직 모른다.
#
# 측정:
#
#   * 위키 페이지 — 평균 9,734B · 중앙 8,177B · 최대 26,924B
#     출처는 `experiments/` 의 생성 결과 44장이고 원본 코퍼스가 **영어**다.
#   * 한국어 페이지 — 평균 7,946자. **내가 만든 합성 코퍼스 100장**이고 사내 문서가 아니다.
#   * 하이드레이션 — 100장에 712ms (7.1ms/장)
#   * 에이전트 루프 — 문서 1건에 126~894초
#
# 계산: 한국어 3바이트/자를 곱해 페이지당 약 23KB, 총량 상한을 그것으로 나눠 약 1,400장.
# 실제 위키가 1,400장인 것도, 그만큼을 돌려 본 것도 아니다.
#
# 문맥 준비가 실행 시간의 0.1~0.7% 라서 **속도는 결정 변수가 아니다.** 상한의 목적은
# 메모리·임시 디스크 소진을 막는 것뿐이므로 관측 규모를 훨씬 넘는 자리에 둔다.
#
# 상한을 올려야 할 근거가 생기면 `INDEX.md` 에 그 측정을 남기고 여기를 고친다. 상한을
# 내리려면 회귀 테스트(`tests/api/test_request_size_limits.py`)의 통과 조건을 먼저 본다.

# 한 요청이 실어 올 수 있는 문맥 총량. 위 계산으로 한국어 페이지 약 1,400장 규모다.
# 그만큼이면 하이드레이션이 10초인데 에이전트 루프가 최소 126초라 무의미한 비용이다.
MAX_REQUEST_CONTEXT_BYTES = 32 * 1024 * 1024

# 위키 한 장의 본문. 관측 최대 페이지(26,924B)의 약 78배다. 이보다 큰 한 장은 위키가
# 아니라 결함이다 — 하이드레이션·청킹·각주 원문 대조가 전부 이 한 장에 매달린다.
MAX_WIKI_CONTENT_BYTES = 2 * 1024 * 1024

# 원본문서 파싱 본문 하나. 위키보다 크게 잡는다 — `FR-DOC-002` 가 파일당 20MB 를
# 허용하므로 파싱 결과가 위키 한 장보다 클 수 있다. 관측 최대는 31,514B 였다.
MAX_DOCUMENT_MARKDOWN_BYTES = 8 * 1024 * 1024


def _utf8_size(text: str | None) -> int:
    """UTF-8 바이트 수. `len(text)` 이 아닌 이유는 한글이 3바이트라서다 — 글자 수로 재면
    한국어 요청의 실제 메모리 사용량을 3분의 1로 과소평가한다."""
    return len(text.encode("utf-8")) if text else 0


def _too_large(loc: tuple, what: str, actual: int, limit: int) -> InitErrorDetails:
    """상한 초과 오류 1건.

    `PydanticCustomError` 로 만드는 것은 `loc` 을 붙이기 위해서다 — 어느 필드(그리고
    배열이면 몇 번째)가 문제인지 `fieldErrors` 에 남아야 Spring 이 사람에게 번역할 수
    있다 (API_컨벤션 6.2).
    """
    return InitErrorDetails(
        type=PydanticCustomError(
            "payload_too_large",
            "{what}이 상한을 넘었습니다 — {actual}바이트, 상한 {limit}바이트.",
            {"what": what, "actual": actual, "limit": limit}),
        loc=loc,
        input=actual)


class CategoryRef(Strict):
    """현재 카테고리 1건.

    **철자가 두 가지다.** v1.1.0 계약(`docs/FastAPI명세서.json` 의 변환 요청 예시)은
    `categoryId` 로 쓰고, 우리 응답·기존 픽스처·`docs/erdTable.sql`(`wiki_category_id`)은
    `wikiCategoryId` 로 쓴다. `extra="forbid"` 라서 둘 중 하나만 받으면 **계약 예시를 그대로
    보낸 첫 실호출이 400** 이다. 둘 다 받는다 — 내부 이름은 `wikiCategoryId` 하나로 두고
    (`populate_by_name=True` 라 기존 코드·픽스처는 그대로), 계약 철자는 별칭으로 받는다.

    협의 목록: 어느 철자가 정본인지 확정되면 별칭을 지운다.
    """

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    wikiCategoryId: str = Field(
        validation_alias=AliasChoices("categoryId", "wikiCategoryId"))
    name: str
    description: str | None = None


ChangeType = Literal["document_added", "document_removed", "document_replaced"]


class SelectedWiki(Strict):
    """1단계 선택(`wiki-context-selections`)이 고른 위키 1건 — 2단계 변환에 라이브 층 대신
    실어 보낸다 (설계 §2). Spring 이 되묻지 않도록 필요한 필드를 전부 담는다."""

    wikiId: str
    categoryId: str | None = None
    title: str
    summary: str | None = None
    contentMarkdown: str
    documentRefs: list[str] = Field(default_factory=list)
    wikiRefs: list[str] = Field(default_factory=list)


class TransformRequest(Strict):
    jobId: str
    documentId: str
    scopeKey: str
    # removed 때는 걷어낼 원본문서가 없어질 뿐 새 원본문서 본문은 없다 — 빈 값을 허용한다.
    parsedMarkdown: str = ""
    currentIndex: str = ""
    currentCategories: list[CategoryRef] = Field(default_factory=list)
    selectedWikis: list[SelectedWiki] = Field(default_factory=list)
    changeType: ChangeType = "document_added"
    removedParsedMarkdown: str | None = None

    @model_validator(mode="after")
    def _the_request_must_be_actionable(self) -> "TransformRequest":
        """`changeType` 이 요구하는 재료가 실제로 왔는가.

        세 가지를 막는다 — 전부 「조용한 200」을 막기 위한 것이다. 재료가 없으면 에이전트가
        할 일이 없고, 변경 0으로 끝난 성공 응답은 Spring 의 `document_results` 에 「성공」으로
        남아 사라진 입력을 감춘다:

          * 삭제·교체에 옛 본문이 없다 — 각주 backlink 도 못 찾고 인용 원문 대조도 못 한다
          * 추가에 새 본문이 없다 (I2) — `parsedMarkdown` 을 선택적으로 만든 것은 removed 를
            위한 완화였고 added 에까지 번지면 안 된다
          * 삭제에 인용 위키가 없다 (I3) — 회신 #3 이 "removed 때 Spring 이 인용 위키를 직접
            보낸다"고 요구했으므로 빈 `selectedWikis` 로 removed 를 부르는 것 자체가 계약
            위반이다. 인용 위키가 진짜 0인 삭제는 합법이지만, 그때는 **변환을 부르지 않고
            원본문서만 지우면 된다** — 메시지에 그 대안을 적어 준다

        라우터가 아니라 모델에서 막는 이유는 `fieldErrors` 다: 어느 필드가 문제인지 적어 줘야
        Spring 이 사람에게 번역할 수 있다 (API_컨벤션 6.2).

        `ValidationError.from_exception_data` 를 쓰는 것은 `loc` 을 붙이기 위해서다 —
        `ValueError` 를 던지면 pydantic 이 모델 전체(`body`)를 가리킨다.
        """
        errors: list[InitErrorDetails] = []
        if self.changeType != "document_added" and \
                not (self.removedParsedMarkdown or "").strip():
            errors.append(InitErrorDetails(
                type=PydanticCustomError(
                    "removed_markdown_required",
                    "changeType이 {change_type}이면 removedParsedMarkdown이 필요합니다.",
                    {"change_type": self.changeType}),
                loc=("removedParsedMarkdown",),
                input=self.removedParsedMarkdown))
        if self.changeType == "document_added" and not self.parsedMarkdown.strip():
            errors.append(InitErrorDetails(
                type=PydanticCustomError(
                    "parsed_markdown_required",
                    "changeType이 document_added이면 parsedMarkdown이 필요합니다 — "
                    "본문이 없으면 변환할 원본문서가 없습니다."),
                loc=("parsedMarkdown",),
                input=self.parsedMarkdown))
        if self.changeType == "document_removed" and not self.selectedWikis:
            errors.append(InitErrorDetails(
                type=PydanticCustomError(
                    "selected_wikis_required",
                    "changeType이 document_removed이면 selectedWikis에 이 원본문서를 인용한 "
                    "위키를 실어 보내야 합니다 — 인용 위키가 없으면 변환 호출 없이 "
                    "원본문서 삭제만 하면 됩니다."),
                loc=("selectedWikis",),
                input=self.selectedWikis))
        if errors:
            raise ValidationError.from_exception_data("TransformRequest", errors)
        return self

    @model_validator(mode="after")
    def _the_request_must_fit(self) -> "TransformRequest":
        """D7. 문맥 총량과 개별 본문에 바이트 상한을 건다.

        **개수는 세지 않는다** — `FR-WIKI-002` 가 개수 상한을 금지한다. 작은 위키 1,000장은
        통과해야 하고 큰 위키 20장은 막혀야 한다.

        개별 검사를 총량 검사와 따로 두는 이유는 진단이다. 총량만 보면 "32MB 를 넘었다"
        까지만 알 수 있고, 한 장이 비정상인 경우와 정상 위키가 많은 경우를 구별할 수 없다.
        """
        errors: list[InitErrorDetails] = []

        for name, limit in (("parsedMarkdown", MAX_DOCUMENT_MARKDOWN_BYTES),
                            ("removedParsedMarkdown", MAX_DOCUMENT_MARKDOWN_BYTES)):
            size = _utf8_size(getattr(self, name))
            if size > limit:
                errors.append(_too_large((name,), "원본문서 본문", size, limit))

        total = 0
        for i, wiki in enumerate(self.selectedWikis):
            size = _utf8_size(wiki.contentMarkdown)
            if size > MAX_WIKI_CONTENT_BYTES:
                errors.append(_too_large(("selectedWikis", i, "contentMarkdown"),
                                         f"위키 {wiki.wikiId} 의 본문", size,
                                         MAX_WIKI_CONTENT_BYTES))
            total += size + _utf8_size(wiki.title) + _utf8_size(wiki.summary)
        if total > MAX_REQUEST_CONTEXT_BYTES:
            errors.append(_too_large(("selectedWikis",), "selectedWikis 문맥 총량",
                                     total, MAX_REQUEST_CONTEXT_BYTES))

        if errors:
            raise ValidationError.from_exception_data("TransformRequest", errors)
        return self


class SelectionRequest(Strict):
    """1단계 — 목차만 보고 이번 변환에 관련된 위키를 최대 5개 고른다 (설계 §5)."""

    jobId: str
    documentId: str
    scopeKey: str
    currentIndex: str
    parsedMarkdown: str = ""
    changeType: ChangeType = "document_added"
    removedParsedMarkdown: str | None = None

    @model_validator(mode="after")
    def _markdown_matches_change_type(self) -> "SelectionRequest":
        """어느 본문이 필요한지는 `changeType` 이 정한다 (계약 v1.3.0).

        제거에는 새 문서가 없고, 추가에는 이전 문서가 없다. 둘 다 무조건 요구하면 계약대로
        보낸 첫 호출이 400 이다. `TransformRequest` 와 같은 이유로 라우터가 아니라 모델에서
        막는다 — 어느 필드가 문제인지 `fieldErrors` 에 적어야 한다 (API_컨벤션 6.2).
        """
        errors: list[InitErrorDetails] = []
        if self.changeType != "document_removed" and not self.parsedMarkdown.strip():
            errors.append(InitErrorDetails(
                type=PydanticCustomError(
                    "parsed_markdown_required",
                    "changeType이 {change_type}이면 parsedMarkdown이 필요합니다.",
                    {"change_type": self.changeType}),
                loc=("parsedMarkdown",),
                input=self.parsedMarkdown))
        if self.changeType != "document_added" and \
                not (self.removedParsedMarkdown or "").strip():
            errors.append(InitErrorDetails(
                type=PydanticCustomError(
                    "removed_markdown_required",
                    "changeType이 {change_type}이면 removedParsedMarkdown이 필요합니다 — "
                    "무엇이 사라지는지 모르면 어느 위키가 걸리는지 고를 수 없습니다.",
                    {"change_type": self.changeType}),
                loc=("removedParsedMarkdown",),
                input=self.removedParsedMarkdown))
        if errors:
            raise ValidationError.from_exception_data("SelectionRequest", errors)
        return self

    @model_validator(mode="after")
    def _the_request_must_fit(self) -> "SelectionRequest":
        """D7. 1단계도 같은 문을 쓴다 — 본문 두 개가 `TransformRequest` 와 같은 출처에서
        온다. 여기만 열려 있으면 상한이 없는 것과 같다."""
        errors: list[InitErrorDetails] = []
        for name in ("parsedMarkdown", "removedParsedMarkdown"):
            size = _utf8_size(getattr(self, name))
            if size > MAX_DOCUMENT_MARKDOWN_BYTES:
                errors.append(_too_large((name,), "원본문서 본문", size,
                                         MAX_DOCUMENT_MARKDOWN_BYTES))
        if errors:
            raise ValidationError.from_exception_data("SelectionRequest", errors)
        return self


class SelectionResponse(Strict):
    wikiIds: list[str]
    reason: str


# `ReconcileRequest`(옛 `wiki-reconciliations`)는 여기 없다 — v1.1.0 계약이 그 엔드포인트를
# 없애고 `TransformRequest.changeType` 분기로 흡수했다 (설계 §1). 삭제·교체 요청은
# `changeType` + `removedParsedMarkdown` 으로 온다.


class WikiBody(Strict):
    title: str
    contentMarkdown: str


class EvidenceDocument(Strict):
    documentId: str
    originalFileName: str
    parsedMarkdown: str


class ChatMessage(Strict):
    senderType: Literal["admin", "agent"]
    content: str


class EditRequest(Strict):
    wikiId: str
    scopeKey: str
    instruction: str
    currentWiki: WikiBody
    evidenceDocuments: list[EvidenceDocument] = Field(default_factory=list)
    chatHistory: list[ChatMessage] = Field(default_factory=list)
    # 계약에는 없다. 있으면 이미 있는 카테고리를 다시 만들지 않고(DR-019) 없으면 빈 목록이라
    # 예전과 같이 동작한다 — 수정 지시가 카테고리를 바꾸는 경우에만 의미가 있다.
    currentCategories: list[CategoryRef] = Field(default_factory=list)

    @model_validator(mode="after")
    def _the_request_must_fit(self) -> "EditRequest":
        """D7. 관리자 수정도 같은 상한을 받는다 — 같은 세션·같은 임시 디스크를 쓴다.

        `evidenceDocuments` 는 위키가 아니라 원본문서라서 개별 상한이 더 크다. 총량은
        같은 값을 공유한다 — 막으려는 것이 요청 하나가 쓰는 자원이지 그 안의 구성이 아니다.
        """
        errors: list[InitErrorDetails] = []

        size = _utf8_size(self.currentWiki.contentMarkdown)
        if size > MAX_WIKI_CONTENT_BYTES:
            errors.append(_too_large(("currentWiki", "contentMarkdown"),
                                     "수정 대상 위키의 본문", size,
                                     MAX_WIKI_CONTENT_BYTES))

        total = size
        for i, doc in enumerate(self.evidenceDocuments):
            doc_size = _utf8_size(doc.parsedMarkdown)
            if doc_size > MAX_DOCUMENT_MARKDOWN_BYTES:
                errors.append(_too_large(
                    ("evidenceDocuments", i, "parsedMarkdown"),
                    f"근거 문서 {doc.documentId} 의 본문", doc_size,
                    MAX_DOCUMENT_MARKDOWN_BYTES))
            total += doc_size
        if total > MAX_REQUEST_CONTEXT_BYTES:
            errors.append(_too_large(("evidenceDocuments",), "수정 요청 문맥 총량",
                                     total, MAX_REQUEST_CONTEXT_BYTES))

        if errors:
            raise ValidationError.from_exception_data("EditRequest", errors)
        return self


class Evidence(Strict):
    documentId: str | None = None
    documentName: str | None = None
    footnote: str | None = None
    location: str | None = None
    quote: str | None = None
    page: int | None = None


class CategoryChange(Strict):
    action: Literal["create", "update", "remove"]
    tempCategoryId: str | None = None
    wikiCategoryId: str | None = None
    name: str
    description: str | None = None


class WikiChange(Strict):
    action: Literal["create", "update", "merge", "remove"]
    tempWikiId: str | None = None
    wikiId: str | None = None
    # `wiki/{scopeKey}/pages/{pageKey}.md`. **`action == "create"` 에만 실린다** (I1).
    # 신규 페이지의 `pageKey` 는 에이전트가 발급한 값이라 `wikiId` 에서 유도할 수 없다 —
    # 이 필드가 없으면 Spring 이 DR-016 의 `wiki.wiki_path` 를 채울 수도, 본문에 남은 신규
    # 페이지 링크를 실제 `wikiId` 로 치환할 수도 없다 (설계 §5).
    #
    # 기존 위키에는 보내지 않는다. 이번 세션의 주소는 하이드레이션이 지은 `pages/{wikiId}.md`
    # 이고 그 위키의 **진짜** `wiki_path` 는 Spring 만 안다 — 우리 주소를 돌려주면 Spring 이
    # 그것을 저장해 실제 파일과 어긋나고, 그 위키가 404 가 된다. 계약 추가분 — 협의 목록.
    wikiPath: str | None = None
    title: str | None = None
    contentMarkdown: str | None = None
    wikiCategoryRef: str | None = None
    mergedIntoRef: str | None = None
    evidence: list[Evidence] = Field(default_factory=list)


class RelationChange(Strict):
    action: Literal["link", "unlink"]
    type: Literal["wiki_document", "wiki_wiki"]
    wikiRef: str
    documentId: str | None = None
    targetWikiRef: str | None = None


class IndexEntry(Strict):
    wikiRef: str
    order: int
    title: str
    summary: str | None = None


class TransformResponse(Strict):
    summary: str
    categoryChanges: list[CategoryChange] = Field(default_factory=list)
    wikiChanges: list[WikiChange] = Field(default_factory=list)
    relationChanges: list[RelationChange] = Field(default_factory=list)
    indexEntries: list[IndexEntry] = Field(default_factory=list)


class EditResponse(TransformResponse):
    agentMessage: str


SourceType = Literal["wiki", "schedule"]


class SourceParseResponse(Strict):
    """`POST /internal/v1/source-parses` 응답.

    Spring 의 `SourceParseResponse` 가 다섯 필드를 전부 비어 있지 않게 검증하므로
    (`RestClientAiClient`), 필드를 늘리거나 이름을 바꾸면 그쪽에서 `INVALID_RESPONSE`
    가 된다. 요청의 `requestId`·`sourceType`·`sourceId` 를 그대로 되돌려준다 —
    Spring 이 비동기 응답을 자기 작업과 맞추는 열쇠다.
    """

    requestId: str
    sourceType: SourceType
    sourceId: str
    parsedMarkdown: str
    warnings: list[str] = Field(default_factory=list)


# ---- 챗봇 답변 (계약 v1.3.0 「답변 생성」) ------------------------------------

SELECTION_PATH = "/internal/v1/answer-context-selections"
ANSWER_PATH = "/internal/v1/answers"


class ConversationMessage(Strict):
    """이전 대화 1건.

    `wiki-edits` 의 `ChatMessage` 와 형태가 다르다 — 그쪽은 `senderType: admin|agent` 이고
    이쪽은 계약대로 `role: user|assistant` 다. 재사용하지 않는다.
    """

    role: Literal["user", "assistant"]
    content: str


class WikiIndexEntry(Strict):
    scopeKey: str
    indexMarkdown: str


class ScheduleSummary(Strict):
    """1단계에 오는 일정 후보. **본문은 없다** — 목차·요약만 보고 고른다."""

    scheduleId: str
    title: str
    startAt: str
    endAt: str
    targetText: str | None = None
    location: str | None = None


class AnswerContextRequest(Strict):
    questionId: str
    conversationId: str
    question: str
    conversationMessages: list[ConversationMessage] = Field(default_factory=list)
    wikiIndexes: list[WikiIndexEntry] = Field(default_factory=list)
    scheduleSummaries: list[ScheduleSummary] = Field(default_factory=list)


class AnswerContextResponse(Strict):
    questionType: Literal["wiki", "schedule", "mixed"]
    wikiIds: list[str] = Field(default_factory=list)
    scheduleIds: list[str] = Field(default_factory=list)
    reason: str = ""


class AnswerWiki(Strict):
    """2단계에 오는 위키 본문. **변환용 `SelectedWiki` 와 모양이 다르다** — 계약의 답변
    생성 요청은 세 필드뿐이고 카테고리·요약·참조가 없다."""

    wikiId: str
    title: str
    contentMarkdown: str


class AnswerSchedule(Strict):
    scheduleId: str
    title: str
    content: str
    startAt: str
    endAt: str
    targetText: str | None = None
    location: str | None = None


class AnswerRequest(Strict):
    questionId: str
    conversationId: str
    questionType: Literal["wiki", "schedule", "mixed"]
    question: str
    conversationMessages: list[ConversationMessage] = Field(default_factory=list)
    selectedWikis: list[AnswerWiki] = Field(default_factory=list)
    selectedSchedules: list[AnswerSchedule] = Field(default_factory=list)


class AnswerSource(Strict):
    """출처 1건. 한쪽 ID 만 채운다 — `answer_source` 가 두 컬럼을 NULL 허용으로 둔 이유다."""

    type: Literal["wiki", "schedule"]
    wikiId: str | None = None
    scheduleId: str | None = None
    title: str


class AnswerResponse(Strict):
    answer: str
    sources: list[AnswerSource] = Field(default_factory=list)
