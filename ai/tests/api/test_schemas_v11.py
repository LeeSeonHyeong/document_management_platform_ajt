"""계약 v1.1.0 스키마 개편 — `docs/superpowers/specs/2026-07-28-ai-server-v1.1-adaptation.md` §1.

`SelectionRequest`/`SelectionResponse`/`SelectedWiki` 신설, `TransformRequest` 에
`selectedWikis`·`changeType`·`removedParsedMarkdown` 을 더하고 `parsedMarkdown` 을
선택적으로 만든다(removed 때 빈 값 허용). `ReconcileRequest` 는 v1.1.0 계약에서
`changeType` 분기로 흡수돼 삭제됐다.
"""

import pytest
from pydantic import ValidationError

from wiki_api.errors import _FAILURE_CODES, _VALIDATION_CODES
from wiki_api.schemas import (
    SelectedWiki,
    SelectionRequest,
    SelectionResponse,
    TransformRequest,
)


SELECTED_WIKI = {
    "wikiId": "101",
    "categoryId": "9",
    "title": "커뮤니케이션 가이드",
    "summary": "비동기 우선 소통",
    "contentMarkdown": "# 커뮤니케이션 가이드\n\n비동기 우선.\n",
    "documentRefs": ["15"],
    "wikiRefs": ["102"],
}


# ---- SelectedWiki ----------------------------------------------------

def test_selected_wiki_accepts_the_full_shape():
    wiki = SelectedWiki(**SELECTED_WIKI)
    assert wiki.wikiId == "101"
    assert wiki.documentRefs == ["15"]
    assert wiki.wikiRefs == ["102"]


def test_selected_wiki_allows_optional_fields_to_be_absent():
    wiki = SelectedWiki(wikiId="101", title="커뮤니케이션 가이드",
                        contentMarkdown="본문")
    assert wiki.categoryId is None
    assert wiki.summary is None
    assert wiki.documentRefs == []
    assert wiki.wikiRefs == []


def test_selected_wiki_rejects_unknown_fields():
    with pytest.raises(ValidationError):
        SelectedWiki(**{**SELECTED_WIKI, "extra": "필드"})


# ---- TransformRequest --------------------------------------------------

def test_transform_request_accepts_selected_wikis_and_change_type():
    req = TransformRequest(jobId="43", documentId="15", scopeKey="9",
                           parsedMarkdown="본문", currentIndex="",
                           selectedWikis=[SELECTED_WIKI],
                           changeType="document_added")
    assert req.selectedWikis[0].wikiId == "101"
    assert req.changeType == "document_added"
    assert req.removedParsedMarkdown is None


def test_transform_request_change_type_defaults_to_document_added():
    req = TransformRequest(jobId="43", documentId="15", scopeKey="9",
                           parsedMarkdown="본문")
    assert req.changeType == "document_added"
    assert req.selectedWikis == []


def test_transform_request_rejects_unknown_change_type():
    with pytest.raises(ValidationError):
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         parsedMarkdown="본문", changeType="document_exploded")


def test_transform_request_removed_allows_missing_parsed_markdown():
    """removed 때는 걷어낼 원본문서만 있고 새 원본문서 본문이 없다 — `parsedMarkdown`
    생략(빈 문자열 기본값)을 허용해야 한다.

    `selectedWikis` 는 채운다 — removed 는 인용 위키를 요구한다(아래 I3)."""
    req = TransformRequest(jobId="43", documentId="15", scopeKey="9",
                           changeType="document_removed",
                           selectedWikis=[SELECTED_WIKI],
                           removedParsedMarkdown="사라진 원본문서 본문")
    assert req.parsedMarkdown == ""
    assert req.removedParsedMarkdown == "사라진 원본문서 본문"


def test_transform_request_added_requires_a_body():
    """I2. `document_added` 인데 본문이 없으면 변환할 것이 없다 — 조용한 200 대신 400 이다.
    `parsedMarkdown` 을 선택적으로 만든 것은 removed 를 위한 완화였고, added 에까지 번지면
    "아무것도 안 한 성공"을 Spring 이 성공으로 기록한다."""
    with pytest.raises(ValidationError) as excinfo:
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         parsedMarkdown="   ")
    assert excinfo.value.errors()[0]["loc"] == ("parsedMarkdown",)


def test_transform_request_removed_requires_selected_wikis():
    """I3. removed 에 인용 위키가 없으면 걷어낼 대상이 없다 — unlink 0건의 200 은 정직해
    보이지만 계약 위반이다(회신 #3: removed 때 Spring 이 인용 위키를 직접 보낸다).
    합법적인 대안을 메시지에 적어 준다."""
    with pytest.raises(ValidationError) as excinfo:
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         changeType="document_removed",
                         removedParsedMarkdown="사라진 원본문서 본문")
    error = excinfo.value.errors()[0]
    assert error["loc"] == ("selectedWikis",)
    assert "변환" in error["msg"] and "삭제" in error["msg"]


def test_transform_request_rejects_unknown_fields():
    with pytest.raises(ValidationError):
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         parsedMarkdown="본문", extra="필드")


# ---- SelectionRequest ---------------------------------------------------

def test_selection_request_accepts_the_contract_shape():
    req = SelectionRequest(jobId="43", documentId="15", scopeKey="9",
                           parsedMarkdown="본문", currentIndex="# 목차\n")
    assert req.changeType == "document_added"
    assert req.removedParsedMarkdown is None


def test_selection_request_accepts_removed_change_type_with_removed_markdown():
    req = SelectionRequest(jobId="43", documentId="15", scopeKey="9",
                           parsedMarkdown="", currentIndex="# 목차\n",
                           changeType="document_removed",
                           removedParsedMarkdown="사라진 원본문서 본문")
    assert req.changeType == "document_removed"
    assert req.removedParsedMarkdown == "사라진 원본문서 본문"


def test_selection_request_requires_current_index():
    with pytest.raises(ValidationError):
        SelectionRequest(jobId="43", documentId="15", scopeKey="9",
                         parsedMarkdown="본문")


def test_selection_request_rejects_unknown_fields():
    with pytest.raises(ValidationError):
        SelectionRequest(jobId="43", documentId="15", scopeKey="9",
                         parsedMarkdown="본문", currentIndex="", extra="필드")


# ---- SelectionResponse ---------------------------------------------------

def test_selection_response_accepts_wiki_ids_and_reason():
    res = SelectionResponse(wikiIds=["101", "102"], reason="가장 관련 있는 문서")
    assert res.wikiIds == ["101", "102"]


def test_selection_response_does_not_enforce_the_five_item_cap():
    """≤5는 검증 대상이 아니다 — 생성 측(선택 LLM 호출)이 보장한다 (브리프)."""
    res = SelectionResponse(wikiIds=[str(i) for i in range(10)], reason="사유")
    assert len(res.wikiIds) == 10


def test_selection_response_rejects_unknown_fields():
    with pytest.raises(ValidationError):
        SelectionResponse(wikiIds=[], reason="사유", extra="필드")


# ---- 오류 코드: 계약 Saved Example 과 문자 그대로 일치해야 한다 ------------

def test_wiki_context_selections_error_code_matches_the_contract_example_exactly():
    """`docs/FastAPI명세서.json` 의 400 예시(`code`·`message`)를 그대로 못 박는다 —
    리뷰에서 발견된 실수(`INVALID_WIKI_CONTEXT_REQUEST` 를 잘못 썼던 것)가 재발하면
    이 테스트가 바로 빨간불이 된다."""
    code, message = _VALIDATION_CODES["/internal/v1/wiki-context-selections"]
    assert code == "INVALID_WIKI_CONTEXT_SELECTION_REQUEST"
    assert message == "Wiki 문맥 선택 요청 구조가 올바르지 않습니다."


# ---- ReconcileRequest: 계약상 폐지 -----------------------------------------

def test_reconcile_request_is_gone():
    """`wiki-reconciliations` 는 v1.1.0 계약에서 `changeType` 분기로 흡수됐다 (설계 §1).
    이름이 남아 있으면 새 코드가 그것을 다시 붙잡는다 — Task 3 에서 라우터·테스트와 함께
    지웠다."""
    import wiki_api.schemas as schemas

    assert not hasattr(schemas, "ReconcileRequest")
    assert "/internal/v1/wiki-reconciliations" not in _VALIDATION_CODES
    # M1. 실패 코드 표에도 남아 있으면 안 된다 — 없는 경로의 코드는 죽은 분기이고,
    # Spring 이 그 이름으로 분기를 짜면 영원히 오지 않는 값을 기다린다.
    assert "/internal/v1/wiki-reconciliations" not in _FAILURE_CODES
