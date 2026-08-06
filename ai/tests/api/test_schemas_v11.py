"""계약 v1.1.0 스키마 개편 — `docs/superpowers/specs/2026-07-28-ai-server-v1.1-adaptation.md` §1.

S15P11B106-175(위키 변환 단일 호출)가 이 파일이 다루던 1단계 문맥 선택
(`SelectionRequest`/`SelectionResponse`/`SelectedWiki`)을 지웠다 — 위키 변환이 하이드레이션
으로 라이브 위키를 직접 읽으므로 Spring 이 선택한 위키를 실어 보낼 이유가 없다. 그 자리에
`wikiCapability`·`scopeVersion`(Wiki 조회 API 열람 허가)이 `TransformRequest`·`EditRequest` 양쪽에서
필수가 됐다. `ReconcileRequest` 는 v1.1.0 계약에서 `changeType` 분기로 흡수돼 삭제됐다.
"""

import pytest
from pydantic import ValidationError

from wiki_api.errors import _FAILURE_CODES, _VALIDATION_CODES
from wiki_api.schemas import EditRequest, TransformRequest


# ---- TransformRequest --------------------------------------------------

def test_transform_request_requires_the_capability():
    """열람 허가(`wikiCapability`)는 필수다 — 없으면 400 이고 fieldErrors 가 어느 필드인지 알려준다."""
    with pytest.raises(ValidationError) as caught:
        TransformRequest(jobId="1", documentId="2", scopeKey="D1",
                         parsedMarkdown="본문")
    fields = {e["loc"][-1] for e in caught.value.errors()}
    assert {"wikiCapability", "scopeVersion"} <= fields


def test_transform_request_rejects_pushed_context():
    """본문을 실어 보내는 필드는 사라졌다. Strict 라 남아 있으면 400 이다."""
    with pytest.raises(ValidationError):
        TransformRequest(jobId="1", documentId="2", scopeKey="D1",
                         wikiCapability="c", scopeVersion=47,
                         parsedMarkdown="본문", selectedWikis=[])


def test_transform_request_minimal_payload_is_valid():
    payload = TransformRequest(jobId="1", documentId="2", scopeKey="D1",
                               wikiCapability="c", scopeVersion=47,
                               parsedMarkdown="본문")
    assert payload.changeType == "document_added"


def test_transform_request_change_type_defaults_to_document_added():
    req = TransformRequest(jobId="43", documentId="15", scopeKey="9",
                           wikiCapability="c", scopeVersion=47,
                           parsedMarkdown="본문")
    assert req.changeType == "document_added"


def test_transform_request_rejects_unknown_change_type():
    with pytest.raises(ValidationError):
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         wikiCapability="c", scopeVersion=47,
                         parsedMarkdown="본문", changeType="document_exploded")


def test_transform_request_removed_allows_missing_parsed_markdown():
    """removed 때는 걷어낼 원본문서만 있고 새 원본문서 본문이 없다 — `parsedMarkdown`
    생략(빈 문자열 기본값)을 허용해야 한다.

    인용 위키가 있는지는 여기서 묻지 않는다 — 요청이 위키를 싣지 않으므로 하이드레이션이
    카탈로그를 받은 뒤에야 판정할 수 있다 (S15P11B106-175)."""
    req = TransformRequest(jobId="43", documentId="15", scopeKey="9",
                           wikiCapability="c", scopeVersion=47,
                           changeType="document_removed",
                           removedParsedMarkdown="사라진 원본문서 본문")
    assert req.parsedMarkdown == ""
    assert req.removedParsedMarkdown == "사라진 원본문서 본문"


def test_transform_request_added_requires_a_body():
    """I2. `document_added` 인데 본문이 없으면 변환할 것이 없다 — 조용한 200 대신 400 이다.
    `parsedMarkdown` 을 선택적으로 만든 것은 removed 를 위한 완화였고, added 에까지 번지면
    "아무것도 안 한 성공"을 Spring 이 성공으로 기록한다."""
    with pytest.raises(ValidationError) as excinfo:
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         wikiCapability="c", scopeVersion=47,
                         parsedMarkdown="   ")
    fields = {e["loc"][-1] for e in excinfo.value.errors()}
    assert "parsedMarkdown" in fields


def test_transform_request_rejects_unknown_fields():
    with pytest.raises(ValidationError):
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         wikiCapability="c", scopeVersion=47,
                         parsedMarkdown="본문", extra="필드")


# ---- EditRequest ---------------------------------------------------------

def test_edit_request_requires_the_capability():
    with pytest.raises(ValidationError) as caught:
        EditRequest(wikiId="9", scopeKey="D1", instruction="요약을 고쳐라")
    fields = {e["loc"][-1] for e in caught.value.errors()}
    assert {"wikiCapability", "scopeVersion"} <= fields


def test_edit_request_rejects_pushed_context():
    with pytest.raises(ValidationError):
        EditRequest(wikiId="9", scopeKey="D1", instruction="고쳐라",
                    wikiCapability="c", scopeVersion=47,
                    evidenceDocuments=[])


def test_edit_request_minimal_payload_is_valid():
    """최소 필수 집합을 못 박는다. 새 필수 필드가 생기면 여기서 잡힌다.

    `adminInstructionDocumentId` 가 그렇게 잡혔다 — 관리자 지시를 원본문서로 승격하는
    변경(S15P11B106-267 계열)이 계약에 그 필드를 **필수 문자열**로 넣었고, 스키마는 따라갔는데
    이 테스트가 안 따라와 실패로 남아 있었다. 계약이 정본이므로 여기를 맞춘다.
    """
    req = EditRequest(wikiId="9", scopeKey="D1", instruction="요약을 고쳐라",
                      wikiCapability="c", scopeVersion=47,
                      adminInstructionDocumentId="817")
    assert req.chatHistory == []


# ---- 오류 코드: 1단계 문맥 선택은 지워졌다 -----------------------------------

def test_wiki_context_selections_route_is_gone():
    """`wiki-context-selections` 는 S15P11B106-175 가 지웠다 — 위키 변환이 하이드레이션으로
    라이브 위키를 직접 읽으므로 1단계 선택 자체가 없다. 이름이 남아 있으면 Spring 이
    계속 그 경로를 부른다."""
    assert "/internal/v1/wiki-context-selections" not in _VALIDATION_CODES
    assert "/internal/v1/wiki-context-selections" not in _FAILURE_CODES


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
