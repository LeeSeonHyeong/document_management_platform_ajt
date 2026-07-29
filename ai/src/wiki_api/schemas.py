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
