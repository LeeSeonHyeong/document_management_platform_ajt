"""문맥 압축 미들웨어 — 트리거를 프로바이더별로 재설정한다.

deepagents 는 기본으로 요약 미들웨어를 넣지만 트리거가 170K/0.85 라 GMS ~42K 벽보다 훨씬
높아 우리가 벽에서 죽을 때까지 한 번도 안 터진다. 우리 런타임은 트리거를 재설정한 미들웨어를
만들어 create_deep_agent 에 넘겨 기본을 이름 기준으로 교체한다.

**게이트웨이면 34K, 직접 API 면 기본값 자리(170K)로 되돌린다.** 벽은 게이트웨이의 제약이고,
직접 API 에서 34K 를 유지하면 요약 콜이 더 돌고 프롬프트 캐시가 계속 무효화돼 손해다.
호스트를 모를 때는 벽이 있다고 본다 — 잘못 좁히면 낭비로 끝나지만 잘못 넓히면 잡이 죽는다.

`spec: docs/superpowers/specs/2026-08-02-gms-context-compaction-design.md`
"""

import tempfile

import pytest

pytest.importorskip("deepagents")

from deepagents.backends import FilesystemBackend  # noqa: E402

from agent_runtime.deep_agents import DeepAgentsRuntime  # noqa: E402


def _runtime(base_url="http://gms.example/anthropic"):
    return DeepAgentsRuntime(
        model="anthropic:claude-sonnet-4-6",
        credentials={"anthropic": ("test-key", base_url)},
    )


def _backend():
    return FilesystemBackend(root_dir=tempfile.mkdtemp(), virtual_mode=True)


def test_게이트웨이면_34K에서_터지고_최근_6메시지를_남긴다():
    from deepagents.middleware.summarization import SummarizationMiddleware

    mw = _runtime()._summarization_middleware(_backend())

    # 기본 요약(같은 base 클래스)을 프로필에서 정확-타입으로 뺄 때 살아남도록, 이건 base 의
    # **서브클래스**(다른 타입)여야 한다.
    assert isinstance(mw, SummarizationMiddleware)
    assert type(mw) is not SummarizationMiddleware
    # GMS 42K 벽 아래 — 기본 170K 가 아니라 34K.
    assert mw._lc_helper.trigger == ("tokens", 34000)
    assert mw._lc_helper.keep == ("messages", 6)


@pytest.mark.parametrize("base_url", [
    "",                                    # SDK 기본 엔드포인트 = 직접 API
    "https://api.anthropic.com",
    "https://api.anthropic.com/v1/",       # 경로가 붙어도 호스트로 판단한다
])
def test_직접_API면_라이브러리_기본값_자리로_되돌린다(base_url):
    mw = _runtime(base_url)._summarization_middleware(_backend())

    # 벽이 없으므로 좁힐 이유가 없다. 좁히면 요약 콜이 더 돌고 캐시가 계속 깨진다.
    assert mw._lc_helper.trigger == ("tokens", 170000)
    assert mw._lc_helper.keep == ("messages", 6)


@pytest.mark.parametrize("base_url", [
    "http://gms.example/anthropic",
    "https://api.anthropic.com.evil.example",   # 접미사 일치로 넓혀지지 않는다
    "https://openrouter.example/v1",            # 모르는 호스트는 벽이 있다고 본다
])
def test_모르는_호스트는_벽이_있다고_보고_좁힌다(base_url):
    mw = _runtime(base_url)._summarization_middleware(_backend())

    assert mw._lc_helper.trigger == ("tokens", 34000)


def test_요약은_haiku로_돈다():
    mw = _runtime()._summarization_middleware(_backend())

    # 요약 콜 자체를 싸게 — FAST 티어(haiku)로.
    model = mw._lc_helper.model
    name = getattr(model, "model", None) or getattr(model, "model_name", "")
    assert "haiku" in str(name)
