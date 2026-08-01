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

from pydantic import TypeAdapter

from wiki_api.app import create_app
from wiki_api.schemas import RelationChange, TransformResponse

from .chat_fakes import (
    ScriptedAgentRuntime,
    use_fake_backend,
    wiki_and_schedule_backend,
)

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


# ---- 챗봇 답변 (계약 1.8.0 — 엔드포인트 하나) --------------------------------
#
# 계약 1.8.0 이 1단계(`answer-context-selections`)를 지웠다. 그 경로가 앱에 남아 있으면
# Spring 이 계속 부르고, 우리는 지운 줄 알고 있게 된다 — 아래 마지막 테스트가 그것을 막는다.

@pytest.fixture
def chat_client(monkeypatch):
    """가짜 백엔드를 끼운 앱의 클라이언트를 만드는 픽스처.

    조회 클라이언트는 요청마다 새로 생기므로 인스턴스가 아니라 **생성 자리**를 감싼다
    (`chat_fakes.use_fake_backend`).
    """
    def make(runtime, transport=None):
        use_fake_backend(monkeypatch, transport or wiki_and_schedule_backend(
            wikis=_wikis_of_example(), schedules=_schedules_of_example()))
        app = create_app(api_key=API_KEY, backend_base_url="http://backend")
        app.state.runtime = runtime
        return TestClient(app, raise_server_exceptions=False)

    return make


def _example_runtime():
    """계약 200 예시와 같은 답을 내는 런타임. 예시의 출처를 실제로 읽는다."""
    example = _example("답변 생성", 200)
    calls = []
    for source in example["sources"]:
        if source["type"] == "wiki":
            calls.append(("read_wiki", {"scopeKey": "D1-D2",
                                        "wikiId": source["wikiId"]}))
        else:
            calls.append(("read_schedule", {"scheduleId": source["scheduleId"]}))
    report = {
        "answer": example["answer"],
        "usedWikiIds": [s["wikiId"] for s in example["sources"]
                        if s["type"] == "wiki"],
        "usedScheduleIds": [s["scheduleId"] for s in example["sources"]
                            if s["type"] == "schedule"],
        "questionType": example["questionType"],
    }
    return ScriptedAgentRuntime(calls, report=report)


def _wikis_of_example() -> dict:
    example = _example("답변 생성", 200)
    return {s["wikiId"]: s["title"] for s in example["sources"]
            if s["type"] == "wiki"}


def _schedules_of_example() -> dict:
    example = _example("답변 생성", 200)
    return {s["scheduleId"]: s["title"] for s in example["sources"]
            if s["type"] == "schedule"}


def _post_contract_example(chat_client, body=None):
    client = chat_client(_example_runtime())
    return client.post("/internal/v1/answers", json=body or _request_body("답변 생성"),
                       headers={SPRING_HEADER: API_KEY})


def test_계약의_요청_예시가_그대로_통과한다(chat_client):
    response = _post_contract_example(chat_client)
    assert response.status_code == 200, response.text
    assert response.json() == _example("답변 생성", 200)


def test_응답_키가_계약과_같다(chat_client):
    example = _example("답변 생성", 200)
    assert set(_post_contract_example(chat_client).json()) == set(example)


def test_출처는_안_쓰는_ID_를_실어_보내지_않는다(chat_client):
    """계약 예시의 wiki 출처에는 scheduleId 키가 아예 없다. null 로 보내면 Spring 이
    `answer_source` 에 빈 컬럼을 쓰려 든다."""
    for source in _post_contract_example(chat_client).json()["sources"]:
        assert ("wikiId" in source) != ("scheduleId" in source)
        assert None not in source.values()
        assert source["title"]          # `answer_source.source_title` 은 NOT NULL 이다


# ---- 오류 표면 --------------------------------------------------------------

def test_400_본문이_Spring_레코드_필드를_모두_채운다(chat_client):
    body = dict(_request_body("답변 생성"), notAField="x")
    response = _post_contract_example(chat_client, body)
    assert response.status_code == 400
    actual = response.json()
    assert SPRING_ERROR_FIELDS <= set(actual)
    assert actual["code"] == "INVALID_ANSWER_GENERATION_REQUEST"
    assert actual["path"] == "/internal/v1/answers"
    assert actual["error"] == "Bad Request"


def test_500_본문이_계약_예시와_같은_키를_갖는다(chat_client):
    """계약이 실은 대표 500 은 「위키도 일정도 읽지 않았다」다 — 도구 호출이 0건인 실행."""
    example = _example("답변 생성", 500)
    client = chat_client(ScriptedAgentRuntime([], text="아마 15일일 것입니다."))
    response = client.post("/internal/v1/answers",
                           json=_request_body("답변 생성"),
                           headers={SPRING_HEADER: API_KEY})
    assert response.status_code == 500
    actual = response.json()
    assert set(actual) == set(example), f"{set(actual) ^ set(example)}"
    assert SPRING_ERROR_FIELDS <= set(actual)
    assert actual["code"] == example["code"]
    assert actual["path"] == example["path"]
    assert actual["error"] == "Internal Server Error"


def test_예상_못한_고장은_경로의_실패_코드로_나간다(chat_client):
    client = chat_client(ScriptedAgentRuntime([], raises=RuntimeError("게이트웨이 거부")))
    response = client.post("/internal/v1/answers",
                           json=_request_body("답변 생성"),
                           headers={SPRING_HEADER: API_KEY})
    assert response.status_code == 500
    assert response.json()["code"] == "MODEL_CALL_FAILED"


def test_키가_없으면_401_이다(chat_client):
    client = chat_client(_example_runtime())
    response = client.post("/internal/v1/answers", json=_request_body("답변 생성"))
    assert response.status_code == 401
    assert response.json()["code"] == "INVALID_INTERNAL_API_KEY"


def test_계약이_정한_세_상태만_낸다(chat_client):
    """400·401·500 밖을 내면 Spring 분기에서 UNEXPECTED_STATUS 로 뭉개진다."""
    body = _request_body("답변 생성")
    seen = set()
    for send in (lambda c: c.post("/internal/v1/answers", json=body,
                                  headers={SPRING_HEADER: API_KEY}),
                 lambda c: c.post("/internal/v1/answers", json=dict(body, notAField=1),
                                  headers={SPRING_HEADER: API_KEY}),
                 lambda c: c.post("/internal/v1/answers", json=body),
                 lambda c: c.post("/internal/v1/answers", json=body,
                                  headers={SPRING_HEADER: "wrong"})):
        seen.add(send(chat_client(_example_runtime())).status_code)
    assert seen <= {200, 400, 401, 500}, seen


def test_응답_헤더에_요청_ID_가_돌아온다(chat_client):
    """`X-Request-Id` 는 본문이 아니라 헤더로만 나간다 (API_컨벤션 6.4)."""
    response = chat_client(_example_runtime()).post(
        "/internal/v1/answers", json=_request_body("답변 생성"),
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


def test_계약에_정의된_엔드포인트가_앱에_등록됐다():
    """`app.routes` 를 보지 않는다 — 이 FastAPI 버전은 `_IncludedRouter` 로 감싸서
    평탄화하지 않는다. OpenAPI 스키마가 실제로 노출되는 표면이다."""
    paths = create_app(api_key=API_KEY).openapi()["paths"]
    assert "/internal/v1/answers" in paths
    # 1단계는 계약 1.8.0 이 지웠다. 남겨두면 백엔드가 계속 부른다.
    assert "/internal/v1/answer-context-selections" not in paths
    # 위키 변환의 1단계도 S15P11B106-175 가 지웠다 — 남은 것은 변환 1개다.
    assert "/internal/v1/wiki-context-selections" not in paths
    # 기존 3개는 그대로 있어야 한다 — 라우터 등록 순서를 바꾸며 가리는 사고를 막는다.
    for path in ("/internal/v1/source-parses",
                 "/internal/v1/wiki-transformations", "/internal/v1/wiki-edits"):
        assert path in paths, path
