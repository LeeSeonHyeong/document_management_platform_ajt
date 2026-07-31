"""계약 파일과 Spring 클라이언트에 대한 적합성.

**예시를 손으로 옮겨 적지 않는다.** `docs/api/` 컬렉션을 직접 파싱해서 그 본문을 그대로
보낸다 — 손으로 베끼면 계약이 바뀌어도 테스트가 통과해 드리프트를 놓친다.

Spring 쪽에서 확인하는 것은 세 가지다.

  * 헤더 이름 — `AiApiConfig` 가 `X-Internal-API-Key` 를 보낸다
  * 오류 본문 — `AiApiErrorResponse` 레코드가 읽는 필드가 다 있어야 한다
  * 상태 코드 — `AiClientErrorMapper` 가 400/401/5xx 로 분기한다. 그 밖을 내면 뭉개진다

Spring 에 챗봇 클라이언트 코드는 아직 없다 (티켓 S15P11B106-86). 그래서 종단 호출은 못
하고, 대신 Spring 이 의존하는 표면을 계약 기준으로 고정한다.
"""

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from agent_runtime.base import CompletionResult
from wiki_api.app import create_app
from pydantic import TypeAdapter

from wiki_api.schemas import RelationChange, TransformResponse

REPO = Path(__file__).resolve().parents[3]
COLLECTION = REPO / "docs" / "api" / "AJT-FastAPI-Internal-API.postman_collection.json"

API_KEY = "secret-key"
# Spring 이 실제로 보내는 헤더 이름 (`global/ai/config/AiApiConfig.java`).
SPRING_HEADER = "X-Internal-API-Key"

# Spring 의 `AiApiErrorResponse` 레코드 필드. 이 중 하나라도 없으면 그쪽에서 null 이 된다.
SPRING_ERROR_FIELDS = {"status", "error", "code", "message", "path", "fieldErrors"}


def _collection() -> dict:
    return json.loads(COLLECTION.read_text(encoding="utf-8"))


def _find(name: str) -> dict:
    """계약에서 엔드포인트 1건을 찾는다."""
    for group in _collection()["item"]:
        for item in group.get("item", []):
            if item["name"] == name:
                return item
    raise AssertionError(f"계약에 없는 엔드포인트: {name}")


def _request_body(name: str) -> dict:
    return json.loads(_find(name)["request"]["body"]["raw"])


def _example(name: str, code: int) -> dict:
    for response in _find(name)["response"]:
        if response.get("code") == code:
            return json.loads(response["body"])
    raise AssertionError(f"{name} 에 {code} 예시가 없다")


class FakeRuntime:
    """계약의 200 예시를 그대로 뱉는 런타임."""

    name = "fake-contract"

    def __init__(self, text: str):
        self.text = text

    def complete(self, messages, *, tier="quality", timeout=None):
        return CompletionResult(text=self.text)


def _client(runtime):
    app = create_app(api_key=API_KEY)
    app.state.runtime = runtime
    return TestClient(app, raise_server_exceptions=False)


# ---- 1단계 -----------------------------------------------------------------

def test_계약의_요청_예시가_그대로_통과한다():
    body = _request_body("답변 자료 선택")
    example = _example("답변 자료 선택", 200)
    # 계약의 200 예시와 같은 선택을 모델이 냈다고 가정한다.
    runtime = FakeRuntime(json.dumps(example, ensure_ascii=False))
    response = _client(runtime).post("/internal/v1/answer-context-selections",
                                     json=body, headers={SPRING_HEADER: API_KEY})
    assert response.status_code == 200, response.text
    assert response.json() == example


def test_1단계_응답_키가_계약과_같다():
    example = _example("답변 자료 선택", 200)
    body = _request_body("답변 자료 선택")
    runtime = FakeRuntime(json.dumps(example, ensure_ascii=False))
    response = _client(runtime).post("/internal/v1/answer-context-selections",
                                     json=body, headers={SPRING_HEADER: API_KEY})
    assert set(response.json()) == set(example)


# ---- 2단계 -----------------------------------------------------------------

def test_2단계_계약_요청_예시가_그대로_통과한다():
    body = _request_body("답변 생성")
    example = _example("답변 생성", 200)
    runtime = FakeRuntime(json.dumps(example, ensure_ascii=False))
    response = _client(runtime).post("/internal/v1/answers", json=body,
                                     headers={SPRING_HEADER: API_KEY})
    assert response.status_code == 200, response.text
    assert response.json() == example


def test_출처는_안_쓰는_ID_를_실어_보내지_않는다():
    """계약 예시의 wiki 출처에는 scheduleId 키가 아예 없다. null 로 보내면 Spring 이
    `answer_source` 에 빈 컬럼을 쓰려 든다."""
    example = _example("답변 생성", 200)
    body = _request_body("답변 생성")
    runtime = FakeRuntime(json.dumps(example, ensure_ascii=False))
    response = _client(runtime).post("/internal/v1/answers", json=body,
                                     headers={SPRING_HEADER: API_KEY})
    for source in response.json()["sources"]:
        assert ("wikiId" in source) != ("scheduleId" in source)
        assert None not in source.values()


# ---- 오류 표면 --------------------------------------------------------------

@pytest.mark.parametrize("name,path", [
    ("답변 자료 선택", "/internal/v1/answer-context-selections"),
    ("답변 생성", "/internal/v1/answers"),
])
def test_400_본문이_계약_예시와_같은_키를_갖는다(name, path):
    example = _example(name, 400)
    runtime = FakeRuntime("{}")
    # 계약에 없는 필드를 넣어 검증 실패를 만든다.
    body = dict(_request_body(name), notAField="x")
    response = _client(runtime).post(path, json=body,
                                     headers={SPRING_HEADER: API_KEY})
    assert response.status_code == 400
    actual = response.json()
    assert set(actual) == set(example), f"{set(actual) ^ set(example)}"
    assert actual["code"] == example["code"]
    assert actual["path"] == path
    assert actual["status"] == 400
    assert actual["error"] == "Bad Request"


@pytest.mark.parametrize("name,path", [
    ("답변 자료 선택", "/internal/v1/answer-context-selections"),
    ("답변 생성", "/internal/v1/answers"),
])
def test_400_본문이_Spring_레코드_필드를_모두_채운다(name, path):
    runtime = FakeRuntime("{}")
    body = dict(_request_body(name), notAField="x")
    response = _client(runtime).post(path, json=body,
                                     headers={SPRING_HEADER: API_KEY})
    assert SPRING_ERROR_FIELDS <= set(response.json())


@pytest.mark.parametrize("path,code", [
    ("/internal/v1/answer-context-selections", "ANSWER_CONTEXT_SELECTION_FAILED"),
    ("/internal/v1/answers", "ANSWER_GENERATION_FAILED"),
])
def test_500_본문이_Spring_레코드_필드를_모두_채운다(path, code):
    class Boom:
        name = "boom"

        def complete(self, messages, *, tier="quality", timeout=None):
            raise RuntimeError("게이트웨이 거부")

    name = ("답변 자료 선택" if path.endswith("selections") else "답변 생성")
    response = _client(Boom()).post(path, json=_request_body(name),
                                    headers={SPRING_HEADER: API_KEY})
    assert response.status_code == 500
    actual = response.json()
    assert SPRING_ERROR_FIELDS <= set(actual)
    assert actual["code"] == code
    assert actual["error"] == "Internal Server Error"


@pytest.mark.parametrize("path", [
    "/internal/v1/answer-context-selections",
    "/internal/v1/answers",
])
def test_키가_없으면_401_이다(path):
    name = ("답변 자료 선택" if path.endswith("selections") else "답변 생성")
    response = _client(FakeRuntime("{}")).post(path, json=_request_body(name))
    assert response.status_code == 401
    assert response.json()["code"] == "INVALID_INTERNAL_API_KEY"


@pytest.mark.parametrize("path", [
    "/internal/v1/answer-context-selections",
    "/internal/v1/answers",
])
def test_계약이_정한_세_상태만_낸다(path):
    """400·401·500 밖을 내면 Spring 분기에서 UNEXPECTED_STATUS 로 뭉개진다."""
    name = ("답변 자료 선택" if path.endswith("selections") else "답변 생성")
    body = _request_body(name)
    client = _client(FakeRuntime("{}"))
    seen = {
        client.post(path, json=body, headers={SPRING_HEADER: API_KEY}).status_code,
        client.post(path, json=dict(body, notAField=1),
                    headers={SPRING_HEADER: API_KEY}).status_code,
        client.post(path, json=body).status_code,
        client.post(path, json=body, headers={SPRING_HEADER: "wrong"}).status_code,
    }
    assert seen <= {200, 400, 401, 500}, seen


def test_응답_헤더에_요청_ID_가_돌아온다():
    """`X-Request-Id` 는 본문이 아니라 헤더로만 나간다 (API_컨벤션 6.4)."""
    body = _request_body("답변 자료 선택")
    example = _example("답변 자료 선택", 200)
    response = _client(FakeRuntime(json.dumps(example, ensure_ascii=False))).post(
        "/internal/v1/answer-context-selections", json=body,
        headers={SPRING_HEADER: API_KEY, "X-Request-Id": "spring-rid-77"})
    assert response.headers["X-Request-Id"] == "spring-rid-77"
    assert "requestId" not in response.json()


# ---- Wiki 변환 / Wiki 관리자 수정 (S15P11B106-157) --------------------------
#
# `relationChanges[]` 항목의 필드 이름은 배열 이름과 달리 계약 본문(설명)에는
# 오래도록 없었다 — 양쪽이 각자 이름을 붙였고 AI 는 `wikiRef`, Spring 은
# `sourceWikiRef` 로 역직렬화해 실 연동에서 `wikiRef`가 `null`이 되고
# `resolveWikiRef`가 던졌다. `RelationChange`는 `Strict`(extra="forbid")라
# 계약 예시에 정의 안 된 필드가 있거나 정의된 필드가 빠지면 파싱이 그 자리에서
# 터진다 — 이 테스트가 그 드리프트를 잡는다.

def test_wiki_변환_응답_예시가_AI_스키마와_같은_필드_이름을_쓴다():
    example = _example("Wiki 변환", 200)
    response = TransformResponse.model_validate(example)
    assert response.relationChanges, "예시에 relationChanges 항목이 있어야 필드를 검증한다"
    assert response.relationChanges[0].sourceWikiRef == "wiki-temp-1"


# `action` 값 자체가 Spring 어휘와 어긋난 적이 있다 (S15P11B106-157) — 필드 **이름**만
# 보고 값을 안 보면 못 잡는다. Spring `WikiTransformationApplier.ACTION_ADD`/
# `ACTION_REMOVE` (backend/.../ai/wiki/service/WikiTransformationApplier.java) 는
# `"add"`/`"remove"` 만 알고, 그 밖은 switch 의 `default` 가 `IllegalArgumentException`
# 을 던진다. `link`/`unlink` 로 되돌리면 이 단정들이 실패해야 한다 — 직접 되돌려 확인했다.
SPRING_RELATION_ACTIONS = {"add", "remove"}


def test_wiki_변환_응답_예시의_relationChanges_action_이_Spring_어휘다():
    example = _example("Wiki 변환", 200)
    response = TransformResponse.model_validate(example)
    assert response.relationChanges, "예시에 relationChanges 항목이 있어야 값을 검증한다"
    for relation in response.relationChanges:
        assert relation.action in SPRING_RELATION_ACTIONS, (
            f"Spring 이 모르는 action 값: {relation.action!r}")
        assert relation.type == "wiki_wiki"


def test_wiki_관리자_수정_응답_예시가_AI_스키마와_같은_필드_이름을_쓴다():
    """`EditResponse` 전체가 아니라 `relationChanges` 만 본다 — `summary`(응답에는
    실제로 실리지만 계약 예시에는 없는, 이 티켓과 무관한 별개의 드리프트) 때문에
    전체 스키마 검증이 여기서 막히면 정작 잡아야 할 `sourceWikiRef` 회귀를 못 잡는다."""
    example = _example("Wiki 관리자 수정", 200)
    relations = TypeAdapter(list[RelationChange]).validate_python(
        example["relationChanges"])
    assert relations, "예시에 relationChanges 항목이 있어야 필드를 검증한다"
    assert relations[0].sourceWikiRef == "100"


def test_wiki_관리자_수정_응답_예시의_relationChanges_action_이_Spring_어휘다():
    example = _example("Wiki 관리자 수정", 200)
    relations = TypeAdapter(list[RelationChange]).validate_python(
        example["relationChanges"])
    assert relations, "예시에 relationChanges 항목이 있어야 값을 검증한다"
    for relation in relations:
        assert relation.action in SPRING_RELATION_ACTIONS, (
            f"Spring 이 모르는 action 값: {relation.action!r}")
        assert relation.type == "wiki_wiki"


def test_계약에_정의된_두_엔드포인트가_앱에_등록됐다():
    """`app.routes` 를 보지 않는다 — 이 FastAPI 버전은 `_IncludedRouter` 로 감싸서
    평탄화하지 않는다. OpenAPI 스키마가 실제로 노출되는 표면이다."""
    paths = create_app(api_key=API_KEY).openapi()["paths"]
    assert "/internal/v1/answer-context-selections" in paths
    assert "/internal/v1/answers" in paths
    # 기존 4개도 그대로 있어야 한다 — 라우터 등록 순서를 바꾸며 가리는 사고를 막는다.
    for path in ("/internal/v1/source-parses", "/internal/v1/wiki-context-selections",
                 "/internal/v1/wiki-transformations", "/internal/v1/wiki-edits"):
        assert path in paths, path
