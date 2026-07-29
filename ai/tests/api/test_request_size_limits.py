"""요청 크기 상한 (D7) — 계획 `2026-07-29-wiki-context-hardening.md` Task 1.

`FR-WIKI-002` 가 v2.9 에서 "최대 5개 선택" 을 없앴다. Spring 이 범위 위키를 전량 실어
보낼 수 있게 되었고 `selectedWikis` 에 상한이 없어 **AI 코드 변경 없이 그대로 받는다.**

**개수로 막지 않는다.** `FR-WIKI-002` 가 "선택 개수 상한은 두지 않는다" 로 개정됐으므로
개수 상한은 요구사항 위반이다. 실제 자원 한계인 **바이트**로 막는다.

상한을 넘긴 요청은 500 이 아니라 400 으로 나가야 한다 — 계약이 정한 상태는 400·401·500
뿐이고, 그 밖으로 나가면 Spring 분기에서 `UNEXPECTED_STATUS` 로 뭉개진다.
"""

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from wiki_api.app import create_app
from wiki_api.schemas import (
    MAX_DOCUMENT_MARKDOWN_BYTES,
    MAX_REQUEST_CONTEXT_BYTES,
    MAX_WIKI_CONTENT_BYTES,
    EditRequest,
    TransformRequest,
)


def _wiki(wiki_id: str, content: str) -> dict:
    return {"wikiId": wiki_id, "title": f"위키 {wiki_id}", "contentMarkdown": content}


def _filler(byte_target: int) -> str:
    """UTF-8 로 `byte_target` 바이트가 되는 한국어 본문.

    한글은 UTF-8 3바이트다. 영문으로 채우면 바이트/글자 비율이 달라 한국어 요청의
    실제 크기를 3분의 1로 과소평가한다 — 상한이 바이트 기준이므로 채움도 바이트로 맞춘다.
    """
    return "가" * (byte_target // 3)


# ---- TransformRequest: 총 바이트 -------------------------------------------

def test_transform_request_rejects_context_over_the_total_byte_cap():
    """개수가 아니라 총 바이트가 상한이다. 위키를 몇 장 보냈는지는 묻지 않는다."""
    page = _filler(MAX_WIKI_CONTENT_BYTES)
    count = MAX_REQUEST_CONTEXT_BYTES // MAX_WIKI_CONTENT_BYTES + 1
    with pytest.raises(ValidationError) as excinfo:
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         parsedMarkdown="본문",
                         selectedWikis=[_wiki(str(i), page) for i in range(count)])
    error = excinfo.value.errors()[0]
    assert error["loc"] == ("selectedWikis",)
    assert "selectedWikis" in error["msg"] or "문맥" in error["msg"]


def test_transform_request_accepts_two_hundred_wikis_within_the_cap():
    """상한 안이면 통과한다 — 위키 200장 · 각 8KB(관측 중앙값 8,177B) 수준.

    이 테스트가 빨간불이면 상한이 관측된 규모조차 막고 있다는 뜻이다."""
    page = _filler(8 * 1024)
    req = TransformRequest(jobId="43", documentId="15", scopeKey="9",
                           parsedMarkdown="본문",
                           selectedWikis=[_wiki(str(i), page) for i in range(200)])
    assert len(req.selectedWikis) == 200


def test_transform_request_does_not_cap_the_wiki_count():
    """`FR-WIKI-002` — 선택 개수 상한은 두지 않는다. 작은 위키 1,000장은 통과해야 한다."""
    req = TransformRequest(jobId="43", documentId="15", scopeKey="9",
                           parsedMarkdown="본문",
                           selectedWikis=[_wiki(str(i), "짧은 본문")
                                          for i in range(1000)])
    assert len(req.selectedWikis) == 1000


# ---- TransformRequest: 개별 본문 -------------------------------------------

def test_transform_request_rejects_a_single_oversized_wiki_body():
    """총 바이트 안이어도 한 장이 상한을 넘으면 거부한다.

    실측 최대 페이지가 26,924B 다. 상한을 넘는 한 장은 위키가 아니라 결함이다."""
    with pytest.raises(ValidationError) as excinfo:
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         parsedMarkdown="본문",
                         selectedWikis=[_wiki("101",
                                              _filler(MAX_WIKI_CONTENT_BYTES + 3))])
    error = excinfo.value.errors()[0]
    assert error["loc"] == ("selectedWikis", 0, "contentMarkdown")


def test_transform_request_reports_the_offending_index():
    """어느 위키가 문제인지 `fieldErrors` 에 남아야 한다 — Spring 이 사람에게 번역한다."""
    with pytest.raises(ValidationError) as excinfo:
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         parsedMarkdown="본문",
                         selectedWikis=[_wiki("101", "짧다"),
                                        _wiki("102", "짧다"),
                                        _wiki("103",
                                              _filler(MAX_WIKI_CONTENT_BYTES + 3))])
    assert excinfo.value.errors()[0]["loc"] == ("selectedWikis", 2, "contentMarkdown")


# ---- TransformRequest: 원본문서 본문 ---------------------------------------

def test_transform_request_rejects_an_oversized_parsed_markdown():
    """원본문서 본문에도 상한이 있다. `FR-DOC-002` 가 파일당 20MB 를 허용하므로
    파싱 결과가 커질 수 있지만 무제한은 아니다."""
    with pytest.raises(ValidationError) as excinfo:
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         parsedMarkdown=_filler(MAX_DOCUMENT_MARKDOWN_BYTES + 3))
    assert excinfo.value.errors()[0]["loc"] == ("parsedMarkdown",)


def test_transform_request_rejects_an_oversized_removed_markdown():
    with pytest.raises(ValidationError) as excinfo:
        TransformRequest(jobId="43", documentId="15", scopeKey="9",
                         changeType="document_removed",
                         selectedWikis=[_wiki("101", "본문")],
                         removedParsedMarkdown=_filler(
                             MAX_DOCUMENT_MARKDOWN_BYTES + 3))
    assert excinfo.value.errors()[0]["loc"] == ("removedParsedMarkdown",)


# ---- EditRequest ---------------------------------------------------------

def _edit(**overrides) -> dict:
    base = {
        "wikiId": "101",
        "scopeKey": "9",
        "instruction": "연차 항목을 보완해 주세요",
        "currentWiki": {"title": "휴가 규정", "contentMarkdown": "# 휴가 규정\n"},
    }
    return {**base, **overrides}


def test_edit_request_accepts_the_normal_shape():
    req = EditRequest(**_edit())
    assert req.evidenceDocuments == []


def test_edit_request_rejects_an_oversized_current_wiki():
    """관리자 수정도 같은 규칙을 받는다 — 같은 세션·같은 임시 디스크를 쓴다."""
    with pytest.raises(ValidationError) as excinfo:
        EditRequest(**_edit(currentWiki={
            "title": "휴가 규정",
            "contentMarkdown": _filler(MAX_WIKI_CONTENT_BYTES + 3)}))
    assert excinfo.value.errors()[0]["loc"] == ("currentWiki", "contentMarkdown")


def test_edit_request_rejects_evidence_over_the_total_byte_cap():
    doc = _filler(MAX_DOCUMENT_MARKDOWN_BYTES)
    count = MAX_REQUEST_CONTEXT_BYTES // MAX_DOCUMENT_MARKDOWN_BYTES + 1
    with pytest.raises(ValidationError) as excinfo:
        EditRequest(**_edit(evidenceDocuments=[
            {"documentId": str(i), "originalFileName": f"{i}.md",
             "parsedMarkdown": doc} for i in range(count)]))
    assert excinfo.value.errors()[0]["loc"] == ("evidenceDocuments",)


def test_edit_request_rejects_a_single_oversized_evidence_document():
    with pytest.raises(ValidationError) as excinfo:
        EditRequest(**_edit(evidenceDocuments=[
            {"documentId": "15", "originalFileName": "규정.md",
             "parsedMarkdown": _filler(MAX_DOCUMENT_MARKDOWN_BYTES + 3)}]))
    assert excinfo.value.errors()[0]["loc"] == (
        "evidenceDocuments", 0, "parsedMarkdown")


# ---- HTTP 층: 400 으로 나가야 한다 -----------------------------------------

API_KEY = "secret-key"


@pytest.fixture
def client():
    return TestClient(create_app(api_key=API_KEY), raise_server_exceptions=False)


def _post(client, path: str, payload: dict):
    return client.post(path, json=payload, headers={"X-Internal-API-Key": API_KEY})


@pytest.mark.parametrize("path,payload,code", [
    ("/internal/v1/wiki-transformations",
     {"jobId": "43", "documentId": "15", "scopeKey": "9",
      "parsedMarkdown": "본문",
      "selectedWikis": [{"wikiId": "101", "title": "위키",
                         "contentMarkdown": "가" * (MAX_WIKI_CONTENT_BYTES // 3 + 1)}]},
     "INVALID_WIKI_TRANSFORMATION_REQUEST"),
    ("/internal/v1/wiki-context-selections",
     {"jobId": "43", "documentId": "15", "scopeKey": "9", "currentIndex": "",
      "parsedMarkdown": "가" * (MAX_DOCUMENT_MARKDOWN_BYTES // 3 + 1)},
     "INVALID_WIKI_CONTEXT_SELECTION_REQUEST"),
    ("/internal/v1/wiki-edits",
     {"wikiId": "101", "scopeKey": "9", "instruction": "보완",
      "currentWiki": {"title": "휴가 규정",
                      "contentMarkdown": "가" * (MAX_WIKI_CONTENT_BYTES // 3 + 1)}},
     "INVALID_WIKI_EDIT_REQUEST"),
])
def test_oversized_requests_go_out_as_400_with_the_contract_code(
        client, path, payload, code):
    """계약이 정한 상태는 400·401·500 뿐이다. 상한 초과가 500 이나 422 로 나가면 Spring
    분기에서 `UNEXPECTED_STATUS` 로 뭉개진다 — 어느 문서가 왜 실패했는지 사라진다."""
    response = _post(client, path, payload)
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == code
    assert body["fieldErrors"], "어느 필드가 상한을 넘었는지 담겨야 한다"
    assert "상한" in body["fieldErrors"][0]["reason"]


def test_the_field_error_names_the_offending_wiki_index(client):
    """배열 안 위치가 `fieldErrors[].field` 에 점 표기로 남아야 한다."""
    payload = {
        "jobId": "43", "documentId": "15", "scopeKey": "9", "parsedMarkdown": "본문",
        "selectedWikis": [
            {"wikiId": "101", "title": "짧은 위키", "contentMarkdown": "짧다"},
            {"wikiId": "102", "title": "큰 위키",
             "contentMarkdown": "가" * (MAX_WIKI_CONTENT_BYTES // 3 + 1)},
        ],
    }
    fields = [e["field"] for e in _post(
        client, "/internal/v1/wiki-transformations", payload).json()["fieldErrors"]]
    assert "selectedWikis.1.contentMarkdown" in fields


# ---- 상한 값 자체 ---------------------------------------------------------

def test_the_caps_leave_room_for_the_observed_scale():
    """상한이 관측 규모를 막지 않는지 못 박는다.

    관측 — 위키 페이지 평균 9,734B · 최대 26,924B (영어 코퍼스에서 생성한 44장).
    한국어는 합성 코퍼스 100장 평균 7,946자로, 사내 문서가 아니다.
    하이드레이션은 100장에 712ms(7.1ms/장)이고 에이전트 루프는 문서 1건에 126~894초다.
    문맥 준비는 실행 시간의 0.1~0.7% 라서 속도는 결정 변수가 아니다 — 상한의 목적은
    메모리·임시 디스크 소진을 막는 것뿐이다.

    아래 하한은 **선택한 목표**다. 실제 사내 위키가 몇 장까지 커지는지는 아직 모른다."""
    # 한국어 페이지 1,000장 규모는 들어가야 한다 (23KB × 1,000 = 23MB).
    assert MAX_REQUEST_CONTEXT_BYTES >= 1000 * 23 * 1024
    # 관측 최대 페이지의 넉넉한 배수여야 한다.
    assert MAX_WIKI_CONTENT_BYTES >= 10 * 26_924
    # 개별 상한이 총 상한보다 작아야 한다 — 아니면 개별 검사가 죽은 코드다.
    assert MAX_WIKI_CONTENT_BYTES < MAX_REQUEST_CONTEXT_BYTES
    assert MAX_DOCUMENT_MARKDOWN_BYTES < MAX_REQUEST_CONTEXT_BYTES
