"""문맥 압축 미들웨어 — 실토큰 눈금 카운터와 110K 트리거.

deepagents 기본 트리거(170K/0.85)는 글자수/4 근사 눈금이라 한국어 실토큰을 4배
과소평가했다 — 같은 값이 영문에선 컨텍스트의 17%, 한국어에선 발동 불능을 뜻했다.
그래서 CJK 1자당 +1토큰 할증 카운터(`_count_tokens_cjk`, count_tokens API 대조로 보정)로
눈금을 실토큰에 맞추고, 트리거는 프로바이더 무관 110K(컨텍스트 200K 의 55%) 하나다. 경위는 spec 의 2026-08-07 절.

`spec: docs/superpowers/specs/2026-08-02-gms-context-compaction-design.md`
"""

import tempfile

import pytest

pytest.importorskip("deepagents")

from deepagents.backends import FilesystemBackend  # noqa: E402
from langchain_core.messages import HumanMessage  # noqa: E402

from agent_runtime.deep_agents import DeepAgentsRuntime, _count_tokens_cjk  # noqa: E402


def _runtime(base_url="http://gms.example/anthropic"):
    return DeepAgentsRuntime(
        model="anthropic:claude-sonnet-4-6",
        credentials={"anthropic": ("test-key", base_url)},
    )


def _backend():
    return FilesystemBackend(root_dir=tempfile.mkdtemp(), virtual_mode=True)


def test_110K에서_터지고_최근_6메시지를_남긴다():
    from deepagents.middleware.summarization import SummarizationMiddleware

    mw = _runtime()._summarization_middleware(_backend())

    # 기본 요약(같은 base 클래스)을 프로필에서 정확-타입으로 뺄 때 살아남도록, 이건 base 의
    # **서브클래스**(다른 타입)여야 한다.
    assert isinstance(mw, SummarizationMiddleware)
    assert type(mw) is not SummarizationMiddleware
    assert mw._lc_helper.trigger == ("tokens", 110_000)
    assert mw._lc_helper.keep == ("messages", 6)


@pytest.mark.parametrize("base_url", [
    "",                                    # SDK 기본 엔드포인트 = 직접 API
    "https://api.anthropic.com",
    "http://gms.example/anthropic",        # 게이트웨이
    "https://openrouter.example/v1",       # 모르는 호스트
])
def test_트리거는_프로바이더와_무관하다(base_url):
    mw = _runtime(base_url)._summarization_middleware(_backend())

    # 직접 API 170K(근사) 분기는 번복됐다 — 한국어에서 압축이 컨텍스트 초과 죽음보다
    # 먼저 올 수 없는 값이었다. spec 2026-08-07 절 참조.
    assert mw._lc_helper.trigger == ("tokens", 110_000)
    assert mw._lc_helper.keep == ("messages", 6)


def test_카운터가_미들웨어에_주입된다():
    mw = _runtime()._summarization_middleware(_backend())

    assert mw._lc_helper.token_counter is _count_tokens_cjk


def test_한국어는_1자를_1토큰_이상으로_센다():
    ko = HumanMessage(content="가" * 1000)

    tokens = _count_tokens_cjk([ko])

    # 기본 근사(1000/4=250)가 아니라 1자≈1.25토큰 (실측: 한글 1자당 ~1.3토큰의 안전측).
    assert 1200 <= tokens <= 1350


def test_영문은_기본_근사를_유지한다():
    en = HumanMessage(content="a" * 1000)

    tokens = _count_tokens_cjk([en])

    # CJK 할증 없음 — 글자수/4 근사 그대로 (~250).
    assert tokens < 350


def test_혼합_텍스트는_CJK_부분만_할증한다():
    mixed = HumanMessage(content="가" * 400 + "a" * 400)

    tokens = _count_tokens_cjk([mixed])

    # 한국어 400자(≈500토큰) + 영문 400자(≈100토큰).
    assert 550 <= tokens <= 700


def test_블록_리스트_content_도_센다():
    msg = HumanMessage(content=[{"type": "text", "text": "가" * 800}])

    assert _count_tokens_cjk([msg]) >= 1000


def test_요약은_haiku로_돈다():
    mw = _runtime()._summarization_middleware(_backend())

    # 요약 콜 자체를 싸게 — FAST 티어(haiku)로.
    model = mw._lc_helper.model
    name = getattr(model, "model", None) or getattr(model, "model_name", "")
    assert "haiku" in str(name)
