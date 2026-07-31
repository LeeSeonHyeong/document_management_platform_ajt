"""배포 경로의 단발 호출 — timeout 강제와 재시도 금지.

**로컬 HTTP mock 을 쓴다.** 두 가지를 정확히 재려면 지연과 실패를 우리가 정해야 한다.

  * timeout: "큰 프롬프트 + timeout=1" 은 불안정하다. 모델이 1초 안에 답하거나 입력
    오류를 즉시 돌려주면 통과해도 아무것도 증명하지 않는다
  * 재시도: 관측 로그는 논리적 호출당 한 줄이라 내부 재시도 3회도 한 줄이다. **세는 층이
    틀렸다.** mock 이 받은 요청을 세야 한다

크레딧을 쓰지 않는다. GMS 실호출은 별도 스모크다.
"""

import asyncio
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest

pytest.importorskip("langchain_anthropic")

from agent_runtime.base import FAST, QUALITY, CompletionResult  # noqa: E402
from agent_runtime.deep_agents import (  # noqa: E402
    DEFAULT_TIER_MODELS,
    DeepAgentsRuntime,
)

MESSAGES = [{"role": "user", "content": "연차 며칠?"}]


class _State:
    def __init__(self):
        self.requests: list[dict] = []
        self.delay = 0.0
        self.status = 200


def _make_handler(state: _State):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length)
            state.requests.append({"path": self.path, "body": json.loads(body or b"{}")})
            if state.delay:
                time.sleep(state.delay)
            if state.status != 200:
                data = (b'{"type":"error","error":{"type":"api_error",'
                        b'"message":"mock failure"}}')
                self.send_response(state.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            payload = {
                "id": "msg_mock", "type": "message", "role": "assistant",
                # 게이트웨이가 모델을 바꿔 끼울 수 있다는 것을 재현한다 — 요청한 이름과
                # 다른 값을 돌려주고, 결과형이 이쪽을 기록하는지 본다.
                "model": "mock-model-substituted",
                "content": [{"type": "text", "text": "연차는 15일입니다."}],
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 123, "output_tokens": 45,
                          "cache_read_input_tokens": 100,
                          "cache_creation_input_tokens": 7},
            }
            data = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *_args):
            pass

    return Handler


@pytest.fixture
def mock_anthropic(monkeypatch):
    state = _State()
    server = HTTPServer(("127.0.0.1", 0), _make_handler(state))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key-not-real")
    monkeypatch.setenv("ANTHROPIC_BASE_URL", f"http://{host}:{port}")
    yield state
    server.shutdown()
    server.server_close()


def test_결과형에_토큰과_응답_모델이_담긴다(mock_anthropic):
    result = DeepAgentsRuntime().complete(MESSAGES, tier=FAST, timeout=10)
    assert isinstance(result, CompletionResult)
    assert result.text == "연차는 15일입니다."
    # LangChain 이 캐시 토큰을 input_tokens 에 합산한다 — 123 + 100 + 7.
    # `claude_code.py` 함정 4 와 같은 의미론이다: "input_tokens is not the input"이고
    # **모델이 읽은 전부**가 맞는 값이다. 캐시 절약을 보려면 cache_read_tokens 를 본다.
    assert result.input_tokens == 230
    assert result.output_tokens == 45
    assert result.cache_read_tokens == 100
    assert result.cache_creation_tokens == 7
    # 요청한 이름이 아니라 응답이 말한 모델이어야 한다.
    assert result.model == "mock-model-substituted"
    assert result.elapsed_seconds >= 0


def test_timeout_이_실제로_끊는다(mock_anthropic):
    mock_anthropic.delay = 3.0
    with pytest.raises(Exception):  # noqa: B017 — 클라이언트 예외형은 버전에 따라 다르다
        DeepAgentsRuntime().complete(MESSAGES, tier=FAST, timeout=1)


def test_재시도하지_않는다(mock_anthropic):
    """mock 이 받은 요청 수가 1이어야 한다. 관측 로그로는 이걸 알 수 없다."""
    mock_anthropic.status = 500
    with pytest.raises(Exception):  # noqa: B017
        DeepAgentsRuntime().complete(MESSAGES, tier=FAST, timeout=10)
    assert len(mock_anthropic.requests) == 1


def test_tier_가_모델을_고른다(mock_anthropic):
    runtime = DeepAgentsRuntime()
    runtime.complete(MESSAGES, tier=FAST, timeout=10)
    runtime.complete(MESSAGES, tier=QUALITY, timeout=10)
    sent = [r["body"]["model"] for r in mock_anthropic.requests]
    assert sent[0] == DEFAULT_TIER_MODELS[FAST].removeprefix("anthropic:")
    assert sent[1] == DEFAULT_TIER_MODELS[QUALITY].removeprefix("anthropic:")


def test_생성자_인자가_tier_모델을_덮는다(mock_anthropic):
    """환경변수 몽키패치 대신 생성자 인자로 덮는다 — `os.environ` 을 읽지 않는다."""
    runtime = DeepAgentsRuntime(fast_model="anthropic:claude-sonnet-4-5-20250929")
    runtime.complete(MESSAGES, tier=FAST, timeout=10)
    assert mock_anthropic.requests[0]["body"]["model"] == "claude-sonnet-4-5-20250929"


def test_모르는_tier_는_터진다():
    with pytest.raises(ValueError, match="모르는 tier"):
        DeepAgentsRuntime()._model_for("cheap")


def test_기본_모델은_게이트웨이에_있는_이름이다():
    """GMS 에 없는 이름을 기본값으로 두면 배포 첫 요청이 400 이다."""
    assert DEFAULT_TIER_MODELS[FAST] == "anthropic:claude-haiku-4-5-20251001"
    assert DEFAULT_TIER_MODELS[QUALITY] == "anthropic:claude-sonnet-4-6"


def test_tier_models_come_from_constructor_arguments():
    """`os.environ` 을 몽키패치하지 않고 티어 모델을 정할 수 있어야 한다."""
    from agent_runtime.deep_agents import FAST, QUALITY, DeepAgentsRuntime

    runtime = DeepAgentsRuntime(fast_model="openai:fast-x",
                                quality_model="openai:quality-y")

    assert runtime._model_for(FAST) == "openai:fast-x"
    assert runtime._model_for(QUALITY) == "openai:quality-y"


def test_tier_models_fall_back_to_the_measured_defaults():
    from agent_runtime.deep_agents import DEFAULT_TIER_MODELS, FAST, DeepAgentsRuntime

    assert DeepAgentsRuntime()._model_for(FAST) == DEFAULT_TIER_MODELS[FAST]


def test_credentials_are_passed_to_the_model_explicitly(monkeypatch):
    """LangChain 이 `os.environ` 을 읽게 두지 않는다. 인자로 넘긴다."""
    from agent_runtime.deep_agents import FAST, DeepAgentsRuntime

    seen = {}

    def _fake_init_chat_model(name, **kwargs):
        seen["name"] = name
        seen["kwargs"] = kwargs
        raise RuntimeError("stop here — 인자만 확인한다")

    monkeypatch.setattr("langchain.chat_models.init_chat_model", _fake_init_chat_model)

    runtime = DeepAgentsRuntime(
        fast_model="openai:fast-x",
        credentials={"openai": ("key-1", "https://gw.example")})
    with pytest.raises(RuntimeError):
        runtime.complete([{"role": "user", "content": "안녕"}], tier=FAST, timeout=5)

    assert seen["name"] == "openai:fast-x"
    assert seen["kwargs"]["api_key"] == "key-1"
    assert seen["kwargs"]["base_url"] == "https://gw.example"


def test_에이전트_모델과_티어_모델이_다른_프로바이더면_각자_키가_간다(monkeypatch):
    """벤더가 다른 자격증명이 섞이면 안 된다 — 리뷰의 재현 사례: 에이전트는 anthropic,
    fast 티어는 openai 인데 anthropic 키가 openai 클라이언트로 가던 문제 (Important 1)."""
    from agent_runtime.deep_agents import FAST, DeepAgentsRuntime

    seen = []

    def _fake_init_chat_model(name, **kwargs):
        seen.append((name, kwargs))
        raise RuntimeError("stop here — 인자만 확인한다")

    monkeypatch.setattr("langchain.chat_models.init_chat_model", _fake_init_chat_model)

    runtime = DeepAgentsRuntime(
        model="anthropic:claude-opus-4-6", fast_model="openai:gpt-5.4-mini",
        credentials={
            "anthropic": ("anthropic-key", "https://anthropic.example"),
            "openai": ("openai-key", "https://openai.example"),
        })

    with pytest.raises(RuntimeError):
        runtime.complete([{"role": "user", "content": "안녕"}], tier=FAST, timeout=5)

    name, kwargs = seen[0]
    assert name == "openai:gpt-5.4-mini"
    assert kwargs["api_key"] == "openai-key"
    assert kwargs["base_url"] == "https://openai.example"


def test_에이전트_모델이_비어도_티어_모델의_키는_간다(monkeypatch):
    """`AI_MODEL` 을 비우고 티어만 지정해도 그 티어 모델의 자격증명이 실려야 한다 — 전에는
    자격증명이 에이전트 모델 기준 하나뿐이라 이 경우 자격증명이 통째로 비었다."""
    from agent_runtime.deep_agents import FAST, DeepAgentsRuntime

    seen = {}

    def _fake_init_chat_model(name, **kwargs):
        seen["name"] = name
        seen["kwargs"] = kwargs
        raise RuntimeError("stop here")

    monkeypatch.setattr("langchain.chat_models.init_chat_model", _fake_init_chat_model)

    runtime = DeepAgentsRuntime(model=None, fast_model="openai:gpt-5.4-mini",
                                credentials={"openai": ("openai-key", "")})

    with pytest.raises(RuntimeError):
        runtime.complete([{"role": "user", "content": "안녕"}], tier=FAST, timeout=5)

    assert seen["kwargs"]["api_key"] == "openai-key"
    assert "base_url" not in seen["kwargs"]


def test_empty_credentials_are_not_passed(monkeypatch):
    """빈 문자열을 넘기면 SDK 가 「빈 키」로 읽어 자기 폴백조차 막는다."""
    from agent_runtime.deep_agents import FAST, DeepAgentsRuntime

    seen = {}

    def _fake_init_chat_model(name, **kwargs):
        seen.update(kwargs)
        raise RuntimeError("stop here")

    monkeypatch.setattr("langchain.chat_models.init_chat_model", _fake_init_chat_model)

    with pytest.raises(RuntimeError):
        DeepAgentsRuntime().complete([{"role": "user", "content": "안녕"}],
                                     tier=FAST, timeout=5)

    assert "api_key" not in seen
    assert "base_url" not in seen


def _fake_mcp_client_class():
    """`_run` 이 실제 MCP 서버를 띄우지 않도록 `MultiServerMCPClient` 를 대신한다."""

    class _FakeMCPClient:
        def __init__(self, *_args, **_kwargs):
            pass

        async def get_tools(self):
            return []

    return _FakeMCPClient


def test_run_경로도_모델_인스턴스에_자격증명을_싣는다(monkeypatch):
    """`_run` 의 `create_deep_agent` 호출이 문자열이 아니라 자격증명이 실린 모델
    인스턴스를 받아야 한다 — 문자열만 넘기면 에이전트 경로에서 자격증명이 빠진다."""
    from agent_runtime.deep_agents import DeepAgentsRuntime

    seen = {}

    class _FakeModelInstance:
        pass

    def _fake_init_chat_model(name, **kwargs):
        seen["init_name"] = name
        seen["init_kwargs"] = kwargs
        instance = _FakeModelInstance()
        seen["instance"] = instance
        return instance

    def _fake_create_deep_agent(*, model, **_kwargs):
        seen["agent_model"] = model
        raise RuntimeError("stop here — 배선만 확인한다")

    monkeypatch.setattr("langchain.chat_models.init_chat_model", _fake_init_chat_model)
    monkeypatch.setattr("deepagents.create_deep_agent", _fake_create_deep_agent)
    monkeypatch.setattr("langchain_mcp_adapters.client.MultiServerMCPClient",
                        _fake_mcp_client_class())

    runtime = DeepAgentsRuntime(
        credentials={"anthropic": ("key-1", "https://gw.example")})

    with pytest.raises(RuntimeError):
        asyncio.run(runtime._run("지시", Path("."), "ALL", "job-1", None, limit=5))

    assert seen["init_name"] == runtime.model
    assert seen["init_kwargs"]["api_key"] == "key-1"
    assert seen["init_kwargs"]["base_url"] == "https://gw.example"
    # 문자열이 아니라 자격증명이 실린 그 인스턴스가 넘어가야 한다.
    assert seen["agent_model"] is seen["instance"]
    assert not isinstance(seen["agent_model"], str)


def test_run_경로에서도_빈_자격증명은_넘기지_않는다(monkeypatch):
    """`complete()` 와 같은 규칙 — 빈 문자열을 kwargs 로 넘기면 SDK 의 자기 폴백을
    막는다."""
    from agent_runtime.deep_agents import DeepAgentsRuntime

    seen = {}

    def _fake_init_chat_model(name, **kwargs):
        seen["init_kwargs"] = kwargs
        raise RuntimeError("stop here")

    monkeypatch.setattr("langchain.chat_models.init_chat_model", _fake_init_chat_model)
    monkeypatch.setattr("langchain_mcp_adapters.client.MultiServerMCPClient",
                        _fake_mcp_client_class())

    runtime = DeepAgentsRuntime()

    with pytest.raises(RuntimeError):
        asyncio.run(runtime._run("지시", Path("."), "ALL", "job-1", None, limit=5))

    assert "api_key" not in seen["init_kwargs"]
    assert "base_url" not in seen["init_kwargs"]
