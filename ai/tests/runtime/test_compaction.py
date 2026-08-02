"""GMS 요청 크기벽 대응 — 문맥 압축 미들웨어 재설정.

deepagents 는 기본으로 요약 미들웨어를 넣지만 트리거가 170K/0.85 라 GMS ~42K 벽보다 훨씬
높아 우리가 벽에서 죽을 때까지 한 번도 안 터진다. 우리 런타임은 트리거를 낮게(34K) 재설정한
미들웨어를 만들어 create_deep_agent 에 넘겨 기본을 이름 기준으로 교체한다.

`spec: docs/superpowers/specs/2026-08-02-gms-context-compaction-design.md`
"""

import tempfile

import pytest

pytest.importorskip("deepagents")

from deepagents.backends import FilesystemBackend  # noqa: E402

from agent_runtime.deep_agents import DeepAgentsRuntime  # noqa: E402


def _runtime():
    return DeepAgentsRuntime(
        model="anthropic:claude-sonnet-4-6",
        credentials={"anthropic": ("test-key", "http://gms.example/anthropic")},
    )


def _backend():
    return FilesystemBackend(root_dir=tempfile.mkdtemp(), virtual_mode=True)


def test_압축_미들웨어는_34K에서_터지고_최근_6메시지를_남긴다():
    from deepagents.middleware.summarization import SummarizationMiddleware

    mw = _runtime()._summarization_middleware(_backend())

    # 기본 요약(같은 base 클래스)을 프로필에서 정확-타입으로 뺄 때 살아남도록, 이건 base 의
    # **서브클래스**(다른 타입)여야 한다.
    assert isinstance(mw, SummarizationMiddleware)
    assert type(mw) is not SummarizationMiddleware
    # GMS 42K 벽 아래 — 기본 170K 가 아니라 34K.
    assert mw._lc_helper.trigger == ("tokens", 34000)
    assert mw._lc_helper.keep == ("messages", 6)


def test_요약은_haiku로_돈다():
    mw = _runtime()._summarization_middleware(_backend())

    # 요약 콜 자체를 싸게 — FAST 티어(haiku)로.
    model = mw._lc_helper.model
    name = getattr(model, "model", None) or getattr(model, "model_name", "")
    assert "haiku" in str(name)
