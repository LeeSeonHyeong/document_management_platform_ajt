"""요청 크기 상한 (D7) — 계획 `2026-07-29-wiki-context-hardening.md` Task 1.

**재는 대상이 하나로 줄었다** (S15P11B106-175). 위키 본문 총량과 위키 한 장의 상한이
여기 있었는데, 요청이 위키를 싣지 않게 되면서 둘 다 잴 것이 없어졌다 — 라이브 위키는
Wiki 조회 API 가 준다. 남은 것은 이 요청이 실제로 실어 오는 **원본문서 파싱 본문**뿐이다.

**글자가 아니라 바이트로 막는다.** 한글은 UTF-8 3바이트라 글자 수로 재면 한국어 요청의
실제 메모리 사용량을 3분의 1로 과소평가한다.

상한을 넘긴 요청은 500 이 아니라 400 으로 나가야 한다 — 계약이 정한 상태는 400·401·500
뿐이고, 그 밖으로 나가면 Spring 분기에서 `UNEXPECTED_STATUS` 로 뭉개진다.
"""

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from wiki_api.app import create_app
from wiki_api.schemas import MAX_DOCUMENT_MARKDOWN_BYTES, TransformRequest

CAPABILITY = "cap-1"
SCOPE_VERSION = 47


def _filler(byte_target: int) -> str:
    """UTF-8 로 `byte_target` 바이트가 되는 한국어 본문."""
    return "가" * (byte_target // 3)


def _transform(**overrides) -> dict:
    base = {"jobId": "43", "documentId": "15", "scopeKey": "9",
            "wikiCapability": CAPABILITY, "scopeVersion": SCOPE_VERSION,
            "parsedMarkdown": "본문"}
    return {**base, **overrides}


# ---- TransformRequest: 원본문서 본문 ---------------------------------------

def test_transform_request_rejects_an_oversized_parsed_markdown():
    """원본문서 본문에도 상한이 있다. `FR-DOC-002` 가 파일당 20MB 를 허용하므로
    파싱 결과가 커질 수 있지만 무제한은 아니다."""
    with pytest.raises(ValidationError) as excinfo:
        TransformRequest(**_transform(
            parsedMarkdown=_filler(MAX_DOCUMENT_MARKDOWN_BYTES + 3)))
    assert excinfo.value.errors()[0]["loc"] == ("parsedMarkdown",)


def test_transform_request_rejects_an_oversized_removed_markdown():
    with pytest.raises(ValidationError) as excinfo:
        TransformRequest(**_transform(
            changeType="document_removed", parsedMarkdown="",
            removedParsedMarkdown=_filler(MAX_DOCUMENT_MARKDOWN_BYTES + 3)))
    assert excinfo.value.errors()[0]["loc"] == ("removedParsedMarkdown",)


def test_transform_request_accepts_the_observed_scale():
    """상한 안이면 통과한다 — 관측 최대 파싱본이 31,514B 였다.

    이 테스트가 빨간불이면 상한이 관측된 규모조차 막고 있다는 뜻이다."""
    request = TransformRequest(**_transform(parsedMarkdown=_filler(64 * 1024)))
    assert request.parsedMarkdown


def test_the_size_check_counts_bytes_not_characters():
    """글자 수로 재면 한국어 본문이 상한의 3배까지 통과한다.

    상한 직하(바이트 기준)를 한국어로 채워 통과시키고, 거기서 한 글자만 더해 막힌다.
    글자 수로 재는 구현이라면 둘 다 통과한다."""
    just_under = _filler(MAX_DOCUMENT_MARKDOWN_BYTES)
    assert TransformRequest(**_transform(parsedMarkdown=just_under))
    with pytest.raises(ValidationError):
        TransformRequest(**_transform(parsedMarkdown=just_under + "가"))


# ---- HTTP 층: 400 으로 나가야 한다 -----------------------------------------

API_KEY = "secret-key"


@pytest.fixture
def client():
    return TestClient(create_app(api_key=API_KEY, backend_base_url="http://backend.test"),
                      raise_server_exceptions=False)


def _post(client, path: str, payload: dict):
    return client.post(path, json=payload, headers={"X-Internal-API-Key": API_KEY})


def test_an_oversized_request_goes_out_as_400_with_the_contract_code(client):
    """계약이 정한 상태는 400·401·500 뿐이다. 상한 초과가 500 이나 422 로 나가면 Spring
    분기에서 `UNEXPECTED_STATUS` 로 뭉개진다 — 어느 문서가 왜 실패했는지 사라진다."""
    response = _post(client, "/internal/v1/wiki-transformations", _transform(
        parsedMarkdown="가" * (MAX_DOCUMENT_MARKDOWN_BYTES // 3 + 1)))
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "INVALID_WIKI_TRANSFORMATION_REQUEST"
    assert body["fieldErrors"], "어느 필드가 상한을 넘었는지 담겨야 한다"
    assert "상한" in body["fieldErrors"][0]["reason"]
    assert [e["field"] for e in body["fieldErrors"]] == ["parsedMarkdown"]


# ---- 상한 값 자체 ---------------------------------------------------------

def test_the_cap_leaves_room_for_the_observed_scale():
    """상한이 관측 규모를 막지 않는지 못 박는다.

    관측 — 원본문서 파싱본 최대 31,514B. `FR-DOC-002` 는 파일당 20MB 를 허용하지만
    파싱 결과는 그보다 훨씬 작다. 아래 하한은 **선택한 목표**이지 측정값이 아니다."""
    assert MAX_DOCUMENT_MARKDOWN_BYTES >= 100 * 31_514
