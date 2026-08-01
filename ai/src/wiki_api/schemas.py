"""계약 요청·응답 모델. `docs/FastAPI명세서.json` v1.1.0 을 따른다.

계약과 다른 곳을 전부 적는다 — 모두 협의 대상이다:

  * `currentWikis` 를 받지 않는다 — 읽기 4개로 되물으니 중복이다
  * `wikiChanges[].evidence` 를 더한다 — FR-AI-009 의 「각 변경의 근거 문서 위치」를
    `summary` 문자열 하나로는 담을 수 없다
  * `relationChanges` 의 스키마를 채운다 — 계약 예시가 빈 배열뿐이다
  * `wikiChanges[].wikiPath` 를 더한다 — DR-016 저장 컬럼이자, 신규 페이지 사이의 본문
    링크를 Spring 이 실제 `wikiId` 로 치환하려면 `pageKey` ↔ 변경의 대응이 필요하다
  * `TransformRequest.changeType` / `removedParsedMarkdown`
    — **계약 미반영**. `docs/FastAPI명세서.json` 에는 없는 필드다. v1.1.0 적응 설계
    (`docs/superpowers/specs/2026-07-28-ai-server-v1.1-adaptation.md` §1)가 "changeType이
    변환 요청에도 실린다는 가정으로 짓는다 (회신에 명기, 뒤집히면 필드 이동만)"라고 적어둔
    작업 가정이다 — v1.2 반영 대기, 회신 문서에 명기. Spring 확정이 뒤집히면 필드 위치만
    옮기면 된다

ID 는 전부 문자열이다. `BIGINT UNSIGNED` 가 JSON number 로 나가면 정밀도를 잃는다.
"""

from __future__ import annotations

from typing import Literal

from pydantic import (BaseModel, ConfigDict, Field, ValidationError,
                      model_validator)
from pydantic_core import InitErrorDetails, PydanticCustomError


class Strict(BaseModel):
    """정의되지 않은 필드는 거부한다 (API_컨벤션 4.1)."""

    model_config = ConfigDict(extra="forbid")


# ---- 요청 크기 상한 (D7) ----------------------------------------------------
#
# **개수로 막지 않는다.** `FR-WIKI-002` 가 v2.9 에서 "선택 개수 상한은 두지 않는다" 를
# 명시했으므로 개수 상한은 요구사항 위반이다. 실제 자원 한계인 바이트로 막는다 — 요청
# 본문은 파싱되어 메모리에 올라가고, 임시 디스크에 파일로 쓰인다.
#
# **재는 대상이 하나로 줄었다** (S15P11B106-175). 위키 본문 총량(`MAX_REQUEST_CONTEXT_BYTES`)
# 과 위키 한 장(`MAX_WIKI_CONTENT_BYTES`)의 상한이 여기 있었는데, 요청이 위키를 싣지
# 않게 되면서 둘 다 잴 것이 없어졌다 — 라이브 위키는 조회 API 가 준다. 남은 것은 이 요청이
# 실제로 실어 오는 원본문서 파싱 본문뿐이다.

# 원본문서 파싱 본문 하나. `FR-DOC-002` 가 파일당 20MB 를 허용하므로 파싱 결과가 클 수
# 있다. 관측 최대는 31,514B 였다 — 관측 규모를 훨씬 넘는 자리에 둔다.
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


# `CategoryRef`(요청의 `currentCategories[]` 1건)는 S15P11B106-175 가 지웠다. 요청이
# 카테고리를 실어 보내지 않으므로 — 하이드레이션이
# `GET /wiki-spaces/{scopeKey}/categories` 로 직접 읽는다 — 받을 모양 자체가 없다.
# 그 클래스가 안고 있던 `categoryId`/`wikiCategoryId` 철자 협의도 함께 사라졌다:
# 조회 API 응답은 `wikiCategoryId` 한 가지다.

ChangeType = Literal["document_added", "document_removed", "document_replaced"]


class TransformRequest(Strict):
    jobId: str
    documentId: str
    scopeKey: str
    # 계약 1.6.0 에 선택 필드로 들어왔고 S15P11B106-175 에서 필수가 됐다. 요청이 위키
    # 본문을 싣지 않으므로 이것이 없으면 에이전트가 볼 위키가 아예 없다 — 선택으로
    # 두면 빠진 요청이 빈 문맥으로 돌아 라이브를 덮는다.
    # **로그·예외·telemetry 에 남기지 않는다.**
    wikiCapability: str
    scopeVersion: int
    # removed 때는 걷어낼 원본문서가 없어질 뿐 새 원본문서 본문은 없다 — 빈 값을 허용한다.
    parsedMarkdown: str = ""
    changeType: ChangeType = "document_added"
    removedParsedMarkdown: str | None = None

    @model_validator(mode="after")
    def _the_request_must_be_actionable(self) -> "TransformRequest":
        """`changeType` 이 요구하는 재료가 실제로 왔는가.

        두 가지를 막는다 — 전부 「조용한 200」을 막기 위한 것이다. 재료가 없으면 에이전트가
        할 일이 없고, 변경 0으로 끝난 성공 응답은 Spring 의 `document_results` 에 「성공」으로
        남아 사라진 입력을 감춘다:

          * 삭제·교체에 옛 본문이 없다 — 각주 backlink 도 못 찾고 인용 원문 대조도 못 한다
          * 추가에 새 본문이 없다 (I2) — `parsedMarkdown` 을 선택적으로 만든 것은 removed 를
            위한 완화였고 added 에까지 번지면 안 된다
          * 삭제에 인용 위키가 없다 — **여기서 막지 않는다.** 요청이 위키를 싣지
            않으므로 접수 시점에 알 수 없고, 하이드레이션이 카탈로그를 받은 뒤
            판정한다 (`session._assert_the_scope_has_wikis_if_it_must`,
            S15P11B106-175)

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
        if errors:
            raise ValidationError.from_exception_data("TransformRequest", errors)
        return self

    @model_validator(mode="after")
    def _the_request_must_fit(self) -> "TransformRequest":
        """D7. 원본문서 본문 각각에 바이트 상한을 건다.

        위키 본문(`selectedWikis`)은 S15P11B106-175 로 요청에서 사라졌다 — 라이브 위키는
        하이드레이션이 조회 API 로 읽어 오므로 여기서 잴 것은 이 요청이 실제로 실어 오는
        `parsedMarkdown`·`removedParsedMarkdown` 뿐이다.
        """
        errors: list[InitErrorDetails] = []

        for name, limit in (("parsedMarkdown", MAX_DOCUMENT_MARKDOWN_BYTES),
                            ("removedParsedMarkdown", MAX_DOCUMENT_MARKDOWN_BYTES)):
            size = _utf8_size(getattr(self, name))
            if size > limit:
                errors.append(_too_large((name,), "원본문서 본문", size, limit))

        if errors:
            raise ValidationError.from_exception_data("TransformRequest", errors)
        return self


# `ReconcileRequest`(옛 `wiki-reconciliations`)는 여기 없다 — v1.1.0 계약이 그 엔드포인트를
# 없애고 `TransformRequest.changeType` 분기로 흡수했다 (설계 §1). 삭제·교체 요청은
# `changeType` + `removedParsedMarkdown` 으로 온다.


class ChatMessage(Strict):
    senderType: Literal["admin", "agent"]
    content: str


class EditRequest(Strict):
    wikiId: str
    scopeKey: str
    instruction: str
    # 계약 1.6.0. 변환과 같다 — S15P11B106-175 에서 필수가 됐다.
    wikiCapability: str
    scopeVersion: int
    chatHistory: list[ChatMessage] = Field(default_factory=list)


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
    """Wiki↔Wiki 관계 전용 (S15P11B106-157).

    한때 `type="wiki_document"`(Wiki↔원본문서) 항목도 있었지만 그 정보는 항상
    `wikiChanges[].evidence` 로도 나갔다 — Spring 은 evidence 를 `wiki.document_refs`
    에 추가한다(그리고 `evidenceDocumentIds` 는 `originDocumentId` 를 항상 포함한다).
    인용이 끊어진 항목을 걷어내는 경로는 Spring 에 아직 없다(별건)지만, `wiki_document`
    관계는 어차피 소비자 없는 중복이었다. 게다가 Spring `RelationChange` 레코드는
    애초에 `action`·`sourceWikiRef`·`targetWikiRef` 세 필드뿐이라 `type="wiki_document"`
    항목은 `targetWikiRef` 가 없어 반영 시점에 죽었다 — 이름을 맞춰도 이 항목 자체가
    문제였다. 그래서 AI 는 이제 위키↔위키 관계만 낸다.

    `action` 어휘는 Spring `WikiTransformationApplier` 의 `ACTION_ADD`/`ACTION_REMOVE`
    와 맞춘다 — `link`/`unlink` 가 아니다. Spring switch 의 `default` 는
    `IllegalArgumentException` 을 던진다.
    """
    action: Literal["add", "remove"]
    type: Literal["wiki_wiki"]
    sourceWikiRef: str
    targetWikiRef: str


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


# ---- 일정 추출 (schedule-extractions) --------------------------------------
#
# 계약 v1.3.1. 응답 필드 이름과 형태를 계약 Saved Example 이 정했다.
#
# `parsedMarkdown` 바이트 상한은 계약에 없다. 업로드 파일이 20MB 이하이고
# (FR-DOC-003) 파싱 결과가 원본보다 커지는 경우는 드물어 그로부터 유도한다.
# 계약상 유효한 요청을 막지 않도록 넉넉히 잡는다 — MR 협의 항목이다.
#
# **이 상한은 사실상 모델이 먼저 거절한다.** 문서를 청킹하지 않고 프롬프트에 그대로
# 넣으므로 실제 한계는 모델 컨텍스트다 — 20만 토큰이면 한글 문서 1MB 안쪽이다. 그보다
# 큰 문서는 여기를 통과하고 모델 호출에서 터져 SCHEDULE_EXTRACTION_FAILED 가 된다.
# 일정 문서가 그만큼 커질 일이 실제로 생기면 청킹을 넣는다 — 상한만 낮추면 계약상
# 유효한 요청을 우리가 먼저 막는 셈이라 더 나쁘다.
MAX_SCHEDULE_MARKDOWN_BYTES = 24 * 1024 * 1024

ScheduleVisibility = Literal["all", "department"]


class ScheduleExtractionRequest(Strict):
    sourceGroupKey: str = Field(min_length=1)
    parsedMarkdown: str
    visibilityType: ScheduleVisibility
    departmentIds: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def _the_document_must_have_content(self) -> "ScheduleExtractionRequest":
        """빈 문서로 부르면 모델을 태울 이유가 없다 — 400 으로 되돌린다.

        공백만 있는 경우도 막는다. 파싱이 실패했는데 빈 문자열로 성공한 것처럼
        온 경우가 여기서 걸린다.
        """
        errors: list[InitErrorDetails] = []
        if not self.parsedMarkdown.strip():
            errors.append(InitErrorDetails(
                type=PydanticCustomError(
                    "schedule_markdown_blank", "parsedMarkdown 이 비어 있습니다."),
                loc=("parsedMarkdown",), input=self.parsedMarkdown))
        size = len(self.parsedMarkdown.encode("utf-8"))
        if size > MAX_SCHEDULE_MARKDOWN_BYTES:
            errors.append(InitErrorDetails(
                type=PydanticCustomError(
                    "schedule_markdown_too_large",
                    "parsedMarkdown 이 상한을 넘었습니다: {size} 바이트",
                    {"size": size}),
                loc=("parsedMarkdown",), input=size))
        if errors:
            raise ValidationError.from_exception_data(self.__class__.__name__, errors)
        return self


class ExtractedScheduleOut(Strict):
    """계약 응답의 일정 1건. 시각은 문자열이다 — 백엔드가 Instant.parse 한다."""

    order: int
    title: str
    content: str | None = None
    targetText: str | None = None
    location: str | None = None
    visibilityType: ScheduleVisibility
    departmentIds: list[str] = Field(default_factory=list)
    startAt: str
    endAt: str


class ScheduleExtractionResponse(Strict):
    status: Literal["extracted", "no_schedule"]
    schedules: list[ExtractedScheduleOut] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)

# ---- 챗봇 답변 (계약 v1.3.0 「답변 생성」) ------------------------------------

ANSWER_PATH = "/internal/v1/answers"


class ConversationMessage(Strict):
    """이전 대화 1건.

    `wiki-edits` 의 `ChatMessage` 와 형태가 다르다 — 그쪽은 `senderType: admin|agent` 이고
    이쪽은 계약대로 `role: user|assistant` 다. 재사용하지 않는다.
    """

    role: Literal["user", "assistant"]
    content: str


class WikiIndexEntry(Strict):
    """범위 하나의 목차와 그 범위 조회 허가값.

    **허가값을 별도 배열로 두지 않고 이 행에 넣는다.** 두 배열로 나누면 한쪽에만 있는
    범위가 생기고, 그 경우 에이전트가 목차는 읽었는데 본문은 못 읽는 상태가 된다.
    """

    scopeKey: str
    indexMarkdown: str
    # **필수다.** 없으면 그 범위의 위키를 한 장도 못 읽어 답변 근거가 사라지고, 원인이
    # 「근거 없음」으로 나와 짐작하기 어렵다. 이 변경은 이미 엔드포인트를 지우는 비호환
    # 변경이므로 「백엔드 배포 전 호환」을 위해 선택 필드로 둘 이유가 없다.
    wikiCapability: str


class AnswerRequest(Strict):
    """챗봇 요청. **본문도 일정 목록도 오지 않는다** — 에이전트가 도구로 조회한다."""

    questionId: str
    conversationId: str
    question: str
    conversationMessages: list[ConversationMessage] = Field(default_factory=list)
    wikiIndexes: list[WikiIndexEntry] = Field(default_factory=list)


class AnswerSource(Strict):
    """출처 1건. 한쪽 ID 만 채운다 — `answer_source` 가 두 컬럼을 NULL 허용으로 둔 이유다."""

    type: Literal["wiki", "schedule"]
    wikiId: str | None = None
    scheduleId: str | None = None
    title: str


class AnswerResponse(Strict):
    answer: str
    sources: list[AnswerSource] = Field(default_factory=list)
    # 1단계 응답이던 값이 여기로 옮겨왔다. 백엔드가 `question` 테이블에 저장한다.
    questionType: Literal["wiki", "schedule", "mixed"]
