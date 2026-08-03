"""schedule-extractions — 일정 문서에서 초안을 뽑는다.

상태가 없다. 요청 본문의 parsedMarkdown 하나만 보고 결과를 낸다 — VaultFS·MCP·작업층을
쓰지 않는다. LLM 경계는 app.state.schedule_provider 라서 테스트가 그것을 교체한다
(test_api_edits.py 의 app.state.runtime 과 같은 방식).
"""

from fastapi.testclient import TestClient

from wiki_api.app import create_app

API_KEY = "test-internal-key"

DOC = """# 2026년 8월 사내 일정

## 하계 워크샵
- 일시: 8월 12일 09:00 ~ 8월 13일 18:00
- 장소: 강원도 속초 리조트
- 대상: 개발부 전 직원
"""

REQUEST = {
    "sourceGroupKey": "schedule-source-20260727-01",
    "parsedMarkdown": DOC,
    "visibilityType": "department",
    "departmentIds": ["1", "2"],
}


class FakeProvider:
    """complete_json 하나만 흉내낸다. 포트가 함수 하나라 가짜도 짧다."""

    def __init__(self, payload=None, error=None):
        self.payload = payload if payload is not None else {"schedules": [], "warnings": []}
        self.error = error
        self.calls = 0

    async def complete_json(self, prompt: str, schema: dict) -> dict:
        self.calls += 1
        if self.error is not None:
            raise self.error
        return self.payload


def _client(provider):
    app = create_app(api_key=API_KEY)
    app.state.schedule_provider = provider
    return TestClient(app, raise_server_exceptions=False)


def test_no_api_key_is_401():
    response = _client(FakeProvider()).post(
        "/internal/v1/schedule-extractions", json=REQUEST)
    assert response.status_code == 401
    assert response.json()["code"] == "INVALID_INTERNAL_API_KEY"


def test_blank_parsed_markdown_is_400():
    response = _client(FakeProvider()).post(
        "/internal/v1/schedule-extractions",
        json={**REQUEST, "parsedMarkdown": "   "},
        headers={"X-Internal-Api-Key": API_KEY})
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_SCHEDULE_EXTRACTION_REQUEST"
    assert response.json()["message"] == "일정 추출 요청 구조가 올바르지 않습니다."
    assert [error["field"] for error in response.json()["fieldErrors"]] == ["parsedMarkdown"]


def test_unknown_visibility_type_is_400():
    response = _client(FakeProvider()).post(
        "/internal/v1/schedule-extractions",
        json={**REQUEST, "visibilityType": "personal"},
        headers={"X-Internal-Api-Key": API_KEY})
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_SCHEDULE_EXTRACTION_REQUEST"


def test_extra_field_is_400_not_422():
    """FastAPI 기본값은 422 다. 팀 규약은 400 + 공통 구조다 (errors.py 도입부)."""
    response = _client(FakeProvider()).post(
        "/internal/v1/schedule-extractions",
        json={**REQUEST, "referenceDate": "2026-08-01"},
        headers={"X-Internal-Api-Key": API_KEY})
    assert response.status_code == 400


def test_empty_document_returns_no_schedule():
    """날짜가 없는 문서에 모델이 0건을 냈다 — 일정이 없는 문서다 (§3.3 첫째 줄)."""
    provider = FakeProvider({"schedules": [], "warnings": []})
    response = _client(provider).post(
        "/internal/v1/schedule-extractions",
        json={**REQUEST, "parsedMarkdown": "# 문의처\n인사팀 내선 1234\n"},
        headers={"X-Internal-Api-Key": API_KEY})
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "no_schedule"
    assert body["schedules"] == []
    assert body["warnings"] == []
    assert provider.calls == 1


def test_fabricated_schedule_in_a_dateless_document_is_no_schedule_not_500():
    """모델이 날짜 없는 문서에 일정을 발명해도 근거가 없으니 버린다 (NFR-AI-002).

    raw_count 도 0 으로 내려가야 한다 — 그러지 않으면 _reject_if_the_extraction_collapsed
    가 이것을 "탈락"으로 보고 500 을 내고, 관리자가 영구히 재시도하게 된다.
    """
    payload = {"schedules": [{"title": "신년 행사",
                              "startLocal": "2026-01-01T00:00",
                              "endLocal": "2026-01-01T23:59", "allDay": True}],
               "warnings": []}
    response = _post(FakeProvider(payload),
                     {**REQUEST, "parsedMarkdown": "# 사내 편의시설 안내\n카페테리아는 2층에 있습니다.\n"})
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "no_schedule"
    assert body["schedules"] == []


ONE_ITEM = {
    "schedules": [{"title": "하계 워크샵", "content": "전사 워크샵",
                   "targetText": "개발부 전 직원", "location": "속초",
                   "startLocal": "2026-08-12T09:00",
                   "endLocal": "2026-08-13T18:00", "allDay": False}],
    "warnings": [],
}


def _post(provider, request=None):
    return _client(provider).post(
        "/internal/v1/schedule-extractions", json=request or REQUEST,
        headers={"X-Internal-Api-Key": API_KEY})


def test_extracted_response_matches_the_contract():
    body = _post(FakeProvider(ONE_ITEM)).json()
    assert body["status"] == "extracted"
    assert body["warnings"] == []
    schedule = body["schedules"][0]
    assert schedule == {
        "order": 1,
        "title": "하계 워크샵",
        "content": "전사 워크샵",
        "targetText": "개발부 전 직원",
        "location": "속초",
        "visibilityType": "department",
        "departmentIds": ["1", "2"],
        "startAt": "2026-08-12T00:00:00Z",
        "endAt": "2026-08-13T09:00:00Z",
    }


def test_visibility_is_echoed_from_the_request():
    """백엔드가 응답 값을 안 믿지만 비어 있으면 거부한다 (RestClientAiClient:196)."""
    request = {**REQUEST, "visibilityType": "all", "departmentIds": []}
    schedule = _post(FakeProvider(ONE_ITEM), request).json()["schedules"][0]
    assert schedule["visibilityType"] == "all"
    assert schedule["departmentIds"] == []


def test_all_day_end_keeps_microseconds_in_the_response():
    payload = {"schedules": [{"title": "급여 지급",
                              "startLocal": "2026-08-25T00:00",
                              "endLocal": "2026-08-25T23:59", "allDay": True}],
               "warnings": []}
    schedule = _post(FakeProvider(payload)).json()["schedules"][0]
    assert schedule["startAt"] == "2026-08-24T15:00:00Z"
    assert schedule["endAt"] == "2026-08-25T14:59:59.999999Z"


def test_zero_items_with_dates_in_the_document_is_500():
    """모델이 흘렸을 가능성이 있다 — no_schedule 로 내면 관리자가 재시도하지 않는다."""
    response = _post(FakeProvider({"schedules": [], "warnings": []}))
    assert response.status_code == 500
    assert response.json()["code"] == "SCHEDULE_EXTRACTION_FAILED"


def test_a_broken_item_is_repaired_not_dropped():
    """제목·시각이 없어도 초안 한 건이 나간다 (S15P11B106-79 대체값 정책).

    예전에는 이 입력이 전건 탈락이라 500 이었다. 지금은 200 이고, 관리자가 목록에서
    `무제` 를 보고 고친다 — 500 이면 그 일정이 있었다는 사실 자체가 사라진다.
    """
    payload = {"schedules": [{"allDay": False}], "warnings": []}
    body = _post(FakeProvider(payload)).json()

    assert body["status"] == "extracted"
    assert [item["title"] for item in body["schedules"]] == ["무제"]
    assert body["schedules"][0]["startAt"].endswith("Z")
    assert any("제목" in warning for warning in body["warnings"])


def test_every_item_survives_even_when_one_is_malformed():
    """뒤집힌 기간도 고쳐서 내보낸다 — 백엔드가 뒤집힌 기간을 거부하기 때문이다."""
    payload = {"schedules": [
        ONE_ITEM["schedules"][0],
        {"title": "뒤집힌 일정", "startLocal": "2026-08-20T16:00",
         "endLocal": "2026-08-20T14:00", "allDay": False},
    ], "warnings": []}
    body = _post(FakeProvider(payload)).json()

    assert body["status"] == "extracted"
    assert [item["title"] for item in body["schedules"]] == ["하계 워크샵", "뒤집힌 일정"]
    assert body["schedules"][1]["startAt"] == "2026-08-20T07:00:00Z"
    assert body["schedules"][1]["endAt"] == "2026-08-20T08:00:00Z"
    assert any("뒤집힌 일정" in warning for warning in body["warnings"])


def test_provider_error_does_not_leak_internals():
    """errors.py:141 의 마지막 그물은 예외 문자열을 응답에 싣는다 (설계 §3.6)."""
    from schedule_extractor import ProviderError

    secret = "http://localhost:11434 model=qwen2.5 token=abcd"
    response = _post(FakeProvider(error=ProviderError(secret)))
    assert response.status_code == 500
    assert response.json()["code"] == "SCHEDULE_EXTRACTION_FAILED"
    assert secret not in response.text
    assert "11434" not in response.text


def test_unexpected_provider_exception_is_also_500_without_details():
    secret = "sk-ant-api03-do-not-leak"
    response = _post(FakeProvider(error=RuntimeError(secret)))
    assert response.status_code == 500
    assert response.json()["code"] == "SCHEDULE_EXTRACTION_FAILED"
    assert secret not in response.text


def test_order_is_sequential_across_many_items():
    payload = {"schedules": [
        {"title": f"일정 {index}", "startLocal": f"2026-08-{index:02d}T10:00",
         "endLocal": f"2026-08-{index:02d}T11:00", "allDay": False}
        for index in range(1, 6)
    ], "warnings": []}
    body = _post(FakeProvider(payload)).json()
    assert [item["order"] for item in body["schedules"]] == [1, 2, 3, 4, 5]
