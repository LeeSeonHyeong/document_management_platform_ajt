"""오류 구조와 인증.

`API_컨벤션.md` 6.2 가 본문 모양을 고정한다. FastAPI 기본 422 를 400 으로 바꾸는 것이
361행 규정이고, `requestId` 는 본문이 아니라 `X-Request-Id` 헤더로만 나간다(6.4).

`failureStage` 는 내부 API 에만 있다. FR-AI-001·NFR-AI-003 이 실패 단계 저장을 요구하는데
계약 오류 본문에 그것을 담을 필드가 없어서 추가한다.
"""

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from wiki_api.app import create_app
from wiki_api.errors import FailureStage, InternalError, install_error_handlers

API_KEY = "secret-key"


@pytest.fixture
def client():
    return TestClient(create_app(api_key=API_KEY), raise_server_exceptions=False)


def test_missing_api_key_is_401(client):
    response = client.post("/internal/v1/wiki-transformations", json={})
    assert response.status_code == 401
    assert response.json()["code"] == "INVALID_INTERNAL_API_KEY"


def test_wrong_api_key_is_401(client):
    response = client.post("/internal/v1/wiki-transformations", json={},
                           headers={"X-Internal-API-Key": "wrong"})
    assert response.status_code == 401


def test_validation_error_is_400_not_422(client):
    """FastAPI 기본은 422 다. 팀 규약은 400 이다 (API_컨벤션 361행)."""
    response = client.post("/internal/v1/wiki-transformations", json={"jobId": "42"},
                           headers={"X-Internal-API-Key": API_KEY})
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "INVALID_WIKI_TRANSFORMATION_REQUEST"
    assert body["status"] == 400
    assert body["error"] == "Bad Request"
    assert body["path"] == "/internal/v1/wiki-transformations"
    assert body["fieldErrors"], "누락 필드가 fieldErrors 에 담겨야 한다"
    assert {"field", "reason"} <= set(body["fieldErrors"][0])


def test_error_body_has_the_agreed_shape(client):
    response = client.post("/internal/v1/wiki-transformations", json={},
                           headers={"X-Internal-API-Key": API_KEY})
    assert set(response.json()) == {
        "timestamp", "status", "error", "code", "message", "path", "fieldErrors",
    }


def test_request_id_echoes_in_the_header_not_the_body(client):
    response = client.post("/internal/v1/wiki-transformations", json={},
                           headers={"X-Internal-API-Key": API_KEY,
                                    "X-Request-Id": "req-42"})
    assert response.headers["X-Request-Id"] == "req-42"
    assert "requestId" not in response.json()


def test_request_id_is_generated_when_absent(client):
    response = client.post("/internal/v1/wiki-transformations", json={},
                           headers={"X-Internal-API-Key": API_KEY})
    assert response.headers.get("X-Request-Id")


def test_internal_error_carries_a_failure_stage():
    """lint_failed 가 「승인 게이트 없음, 단 기계 검증은 거친다」의 구현이다."""
    app = FastAPI()
    install_error_handlers(app)

    @app.get("/boom")
    async def boom():
        raise InternalError("WIKI_TRANSFORMATION_FAILED", "검증에 실패했습니다.",
                            FailureStage.LINT_FAILED)

    response = TestClient(app, raise_server_exceptions=False).get("/boom")
    assert response.status_code == 500
    assert response.json()["failureStage"] == "lint_failed"
    assert response.json()["code"] == "WIKI_TRANSFORMATION_FAILED"


def test_unexpected_exception_becomes_the_common_error_body():
    """핸들러가 없으면 FastAPI 가 그대로 500 을 던지고 본문이 규약 구조가 아니다 —
    Spring 은 `code` 로 분기하므로 파싱이 깨진다 (API_컨벤션 6.2)."""
    app = FastAPI()
    install_error_handlers(app)

    @app.get("/kaboom")
    async def kaboom():
        raise ValueError("아무도 예상하지 않은 것")

    response = TestClient(app, raise_server_exceptions=False).get("/kaboom")
    assert response.status_code == 500
    body = response.json()
    assert set(body) == {"timestamp", "status", "error", "code", "message",
                         "path", "fieldErrors"}
    assert body["code"]
    assert body["path"] == "/kaboom"


def test_unexpected_exception_on_a_contract_path_keeps_the_contract_code():
    app = FastAPI()
    install_error_handlers(app)

    @app.post("/internal/v1/wiki-transformations")
    async def kaboom():
        raise ValueError("조립 밖에서 터졌다")

    response = TestClient(app, raise_server_exceptions=False).post(
        "/internal/v1/wiki-transformations")
    assert response.json()["code"] == "WIKI_TRANSFORMATION_FAILED"


def test_failure_stage_values_are_lower_snake_case():
    assert [s.value for s in FailureStage] == [
        "context_load", "agent_start", "agent_timeout",
        "agent_error", "lint_failed", "assemble", "scope_changed",
    ]


# ----- 계약 v1.3.0: 상태·코드 집합 ---------------------------------------------


def test_no_route_can_answer_outside_the_contract_status_set():
    """계약은 엔드포인트마다 400·401·500 만 정의한다. 그 밖의 상태를 내면 Spring 은
    `UNEXPECTED_STATUS` 로 뭉갠다 — 어떤 실패인지 백엔드가 알 수 없다."""
    from wiki_api import errors, session

    sources = (errors.__file__, session.__file__)
    offenders = []
    for path in sources:
        with open(path, encoding="utf-8") as handle:
            for number, line in enumerate(handle, start=1):
                if "status=" not in line:
                    continue
                for allowed in ("status=400", "status=401", "status=500"):
                    if allowed in line:
                        break
                else:
                    offenders.append(f"{path}:{number} {line.strip()}")
    assert offenders == []


def test_every_endpoint_has_its_own_failure_code():
    """500 의 `code` 는 계약이 엔드포인트별로 지정한다. 빠진 경로는 공용
    `INTERNAL_SERVER_ERROR` 로 떨어져 계약 밖 코드가 된다."""
    from wiki_api.errors import _FAILURE_CODES, failure_code_for

    # `/internal/v1/wiki-context-selections` 는 없다 — S15P11B106-175 가 1단계 문맥
    # 선택을 지웠다. 없는 경로의 코드는 죽은 분기이고, Spring 이 그 이름으로 분기를
    # 짜면 영원히 오지 않는 값을 기다린다.
    assert failure_code_for("/internal/v1/wiki-transformations") == \
        "WIKI_TRANSFORMATION_FAILED"
    assert failure_code_for("/internal/v1/wiki-edits") == "WIKI_EDIT_FAILED"
    assert "INTERNAL_SERVER_ERROR" not in _FAILURE_CODES.values()
