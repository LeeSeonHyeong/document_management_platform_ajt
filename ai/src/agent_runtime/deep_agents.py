"""Runtime backed by DeepAgents.

Adapted from the deepagents-lucas-llmwiki spike that preceded this repo, where
DeepAgents driving the *Lucas* MCP tools was verified. **This server has not
been verified with it** — the tool names, the `scope` argument and the
`pages/{pageKey}.md` addressing are all ours, so the first run against it is a
smoke test, not a measurement.

The load-bearing detail is `EXCLUDED_BUILTIN_TOOLS`. DeepAgents ships its own
filesystem and shell tools; left enabled, the agent writes pages with `write_file`
and bypasses the MCP server entirely — and with it the permission boundary, the
reference graph, and the chunk index. The MCP tools have to be the only way to
touch anything, and `verify_tool_surface` checks that at startup rather than
trusting the profile to have been applied.

This is the runtime that ships. The CLI runtime exists because there is no API
budget yet, but `claude -p` as a subprocess is not a deployment: API convention
2.1 puts a FastAPI AI server here, and a subscription login is not available in a
server environment. So everything the requirements ask of a run — a time limit
(NFR-PERF-002), an explainable failure (NFR-AI-003), a traceable step
(NFR-MNT-002) — has to work here, and this module is where it lives.

Optional dependency: install with `uv sync --extra deepagents`.
"""

from __future__ import annotations

import asyncio
import logging
import tempfile
import time
import sys
from pathlib import Path
from urllib.parse import urlsplit

from pydantic import BaseModel, Field

from wiki_mcp.telemetry import read_counts
from wiki_mcp.vaultfs.query_client import ScopeChangedError

from .base import DEFAULT_COMPLETE_TIMEOUT, FAST, QUALITY, CompletionResult, RunResult
from .reply_sanitizer import sanitize_admin_reply

logger = logging.getLogger("llmwiki.deepagents")

# 제공자별 티어 기본값. **에이전트 모델의 제공자를 따라간다** — 하나만 지정한 배포가
# 첫 단발 호출에서 다른 벤더의 키를 찾다 죽는 것을 막는다 (2026-07-31 실측).
#
# 이름은 게이트웨이가 가진 것에 묶인다 — GMS 실측(2026-07-29)으로 확인된 이름만 쓴다.
# 짧은 별칭(`claude-haiku-4-5`)은 400 이고 `claude-sonnet-5` 는 GMS 에 없다.
DEFAULT_TIER_MODELS = {
    "anthropic": {
        FAST: "anthropic:claude-haiku-4-5-20251001",
        QUALITY: "anthropic:claude-sonnet-4-6",
    },
    "openai": {
        FAST: "openai:gpt-4o-mini",
        QUALITY: "openai:gpt-4o-mini",
    },
}
TIERS = (FAST, QUALITY)

# DeepAgents' built-ins. Every one of these is a way around the MCP server.
EXCLUDED_BUILTIN_TOOLS = frozenset({
    "write_todos", "ls", "read_file", "write_file", "edit_file",
    "glob", "grep", "execute", "task",
})

SERVER_MODULE = "wiki_mcp.local_server"

# NFR-PERF-002: a document over ten minutes must be failed. The CLI runtime gets
# this from a subprocess timeout; here it has to be enforced in the loop.
CALL_TIMEOUT_SECONDS = 600

# A turn cap is a runaway guard, not a quality lever.
#
# 60 은 검색 루프·압축 붕괴를 고치기 전(7월 평균 34턴) 세계의 값이다. 고친 뒤(2026-08-06)
# 정상 성공은 8~18턴이고 25턴 초과가 이상 신호다 — 즉 40 까지 가는 실행은 전부 병든
# 실행이며, 컨텍스트가 턴마다 자라 비용은 턴 수의 제곱에 가깝다. 실제로 2026-08-07 삭제
# 데드락에서 60턴이 토큰 339만 개를 태워 팀 GMS 키를 고갈시켰다. 40 은 정상 최대(18)의
# 2.2배 — 압축 회복(이력 되읽기 +2~3턴)까지 여유가 있고, 폭발 반경은 절반 이하가 된다.
MAX_TURNS = 40

# `recursion_limit` 배수. LangGraph 는 **턴이 아니라 superstep** 을 센다.
#
# 상한은 `turn_limit_middleware` 가 맡는다. 이 값은 **백스톱**이므로 넉넉해야 한다 —
# 여기가 먼저 걸리면 `GraphRecursionError` 가 나고 그것이 고장으로 뭉개져 상한 도달이
# 이름을 잃는다. 정확히 그 상태였다(2026-08-06 01:00).
#
# **턴당 superstep 은 추정하지 않는다 — 실측이다.** 트레이스에서 3개(`model` ·
# `TodoListMiddleware.after_model` · `tools`)로 보고 2배(120)를 썼는데, 그 값이 실효 상한을
# 모델 호출 40회로 만들었다. 4배로 고친 뒤에도 `tests/runtime/test_turn_limit.py` 의 가짜
# 모델이 **240 superstep / 모델 호출 47회 = 5.1** 을 보여 여전히 백스톱이 먼저 걸렸다.
# 요약 미들웨어가 도는 턴은 더 쓴다. 8배는 그 실측(5.1)에 여유를 얹은 것이고, 그래도
# 폭주는 미들웨어가 `MAX_TURNS` 회에서 끊으므로 이 값이 커진다고 잡이 길어지지 않는다.
RECURSION_LIMIT_PER_TURN = 8


# 실패가 관리자 화면(`failure_reason`)에 닿을 때의 번역 원칙: **첫 문장은 관리자의
# 다음 행동, 괄호 꼬리는 개발팀용 진단 표식.** 2026-08-06 에 ai_job·document 의
# failure_reason 을 전수 조사했더니 GMS 크기벽 400 이 「Model not found」 문구 그대로
# 5번, GraphRecursionError 원문이 8번, SDK 예외 원문이 그대로 관리자에게 나가 있었다 —
# 전부 읽을 수 없는 말이고, 크기벽 400 은 모델 설정 오류로 오독되기까지 한다.
_SPLIT_GUIDANCE = ("문서가 다루는 내용이 많아 정해진 작업 한도 안에 위키 반영을 "
                   "끝내지 못했습니다. 문서를 주제별로 나눠 다시 업로드해 주세요. "
                   "나눠서 올려도 같은 문제가 반복되면 시스템 문제일 수 있으니 "
                   "개발팀에 알려 주세요.")


def _admin_error_message(exc: Exception, last_stage: str) -> str:
    """예외 1건 → 관리자가 읽고 행동할 수 있는 문장.

    분류는 문구 기반이다 — GMS 는 오류를 전용 예외 타입이 아니라 400 본문 문구로만
    구분해 주기 때문이다. 문구가 바뀌면 일반 분기로 떨어질 뿐 삼켜지지는 않는다.
    """
    text = str(exc)
    if "Model not found in request" in text:
        # GMS 요청 크기벽의 오류 문구다 (job 7 사고 기록과 대조로 확정). 문구 그대로
        # 내보내면 모델 설정 오류로 오독된다 — 실제 처방은 「나눠라」다.
        return ("문서가 길거나 참조할 내용이 많아 한 번에 처리할 수 있는 용량을 "
                "넘었습니다. 문서를 주제별로 나눠 다시 업로드해 주세요. "
                f"(요청 용량 초과, 마지막 도달 단계: {last_stage})")
    if "credit balance" in text.lower():
        # 관리자가 고칠 수 없는 문제 — 재시도 유도가 아니라 문의 안내가 맞다.
        return ("AI 서비스 사용량 한도 문제로 처리가 중단되었습니다. 개발팀에 알려 "
                f"주세요. (API 과금 한도, 마지막 도달 단계: {last_stage})")
    if type(exc).__name__ == "GraphRecursionError":
        # 턴 상한의 백스톱. 미들웨어(`turn_limit_middleware`)가 먼저 걸리는 것이
        # 정상이지만, 배수 회귀로 백스톱이 먼저 걸려도 관리자 안내는 같아야 한다.
        return _SPLIT_GUIDANCE + f" (recursion 백스톱, 마지막 도달 단계: {last_stage})"
    # 번역표에 없는 오류 — 뭉개지 않는다. 관리자용 첫 문장 뒤에 개발팀용 원문을 남긴다.
    # failure_reason 은 1,000자 제한이라 꼬리를 자른다.
    return ("AI 처리 중 예상하지 못한 오류가 발생했습니다. 다시 시도해 보시고, "
            "반복되면 개발팀에 알려 주세요. "
            f"({type(exc).__name__}, 마지막 도달 단계: {last_stage}: {text[:300]})")


def turn_limit_middleware():
    """위키 경로의 턴 상한. 챗봇 경로(`_run_with_tools`)와 같은 이유로 미들웨어다.

    `recursion_limit` 만으로는 상한 도달이 `GraphRecursionError` 로 나고, 그것이 위키 경로의
    `except Exception` 에 걸려 「모델 호출 실패」로 뭉개진다 — 상한 도달과 고장을 구분할 수
    없게 된다 (2026-08-06 01:00 실패가 그렇게 보고됐다).

    `exit_behavior="error"` 다. `"end"` 는 영어 안내문(`Model call limits exceeded: ...`)을
    마지막 AI 메시지로 끼워 정상 종료처럼 끝내는데, 위키 경로에서 그 메시지는 관리자가 읽는
    작업 요약이 될 수 있다.
    """
    from langchain.agents.middleware import ModelCallLimitMiddleware

    return ModelCallLimitMiddleware(thread_limit=MAX_TURNS, exit_behavior="error")

# 문맥 압축 설정. **트리거는 프로바이더마다 다르다** — 게이트웨이의 요청 크기벽은 모델의
# 컨텍스트 창과 다른 제약이라, 하나의 값으로 둘을 만족시킬 수 없다.
#
# 게이트웨이(GMS)에는 요청 크기벽(~42K 토큰)이 있다. 긴 병합은 이력이 누적돼 벽을 넘고, 그때
# GMS 가 400 을 내며 잡이 죽는다 — 실측으로 그때까지 쓴 2,404 크레딧을 잃었다(job 7).
# deepagents 기본 트리거는 170K/0.85 라 벽보다 훨씬 높아 한 번도 안 터진다. 그래서 벽 아래로
# 낮춘다. 34K = 벽까지 실측 최대 1턴 증가폭(~4.6K) 여유.
# (spec: docs/superpowers/specs/2026-08-02-gms-context-compaction-design.md)
COMPACTION_TRIGGER_CAPPED = ("tokens", 34000)

# 직접 API 에는 그 벽이 없다(컨텍스트 1M). 여기서 34K 를 유지하면 손해가 두 가지다 —
# 요약 LLM 콜이 몇 턴마다 추가로 돌고, 압축이 이력을 다시 쓰는 순간 프롬프트 캐시가 통째로
# 무효화된다(캐싱은 접두사 일치라 앞이 한 글자 달라지면 뒤가 다 날아간다). 캐시 읽기 단가는
# 입력의 0.1배이므로 이력을 길게 들고 가는 편이 오히려 싸다 — 직접 API 에서 캐싱이 실제로
# 붙는 것은 2026-08-04 에 확인했다(생성 5402 → 다음 호출 읽기 5402). 그래서 압축을 라이브러리
# 기본값 자리로 되돌린다.
COMPACTION_TRIGGER_DIRECT = ("tokens", 170000)

COMPACTION_KEEP = ("messages", 6)

# 요청 크기벽이 **없다고 아는** 호스트. 목록에 없으면 벽이 있다고 본다 — 기본값이 안전한
# 쪽이어야 한다. 잘못 좁히면 요약 콜을 몇 번 더 도는 낭비로 끝나지만, 잘못 넓히면 벽에서
# 잡이 죽고 그때까지 쓴 비용을 전액 잃는다. 게이트웨이를 다시 쓰게 돼도 이 판단은 자동이라
# 사람이 설정을 되돌리는 것을 잊어도 안전하다.
UNCAPPED_API_HOSTS = frozenset({"api.anthropic.com"})


def compaction_trigger_for(base_url: str) -> tuple[str, int]:
    """`base_url` 로 압축 트리거를 고른다.

    빈 값은 SDK 기본 엔드포인트를 뜻하므로 직접 API 다 — 게이트웨이를 쓸 때는 `base_url` 을
    반드시 주기 때문에, "값이 없다"와 "게이트웨이"가 겹치지 않는다.
    """
    if not base_url:
        return COMPACTION_TRIGGER_DIRECT
    host = urlsplit(base_url if "//" in base_url else f"//{base_url}").hostname or ""
    return (COMPACTION_TRIGGER_DIRECT if host.lower() in UNCAPPED_API_HOSTS
            else COMPACTION_TRIGGER_CAPPED)

# 압축 요약에 **작업 진행 상태를 반드시 남기게** 하는 추가 지시 (2026-08-06 job 41).
#
# 기본 요약 프롬프트는 범용 컨텍스트 추출이라, 압축이 터지면 「내가 무엇을 만들었고 이미
# 끝났는지」가 요약에서 사라졌다. 실측: 문서 48 반영이 13턴에 사실상 끝났는데(create·
# edit·lint 통과) 압축 후 에이전트가 그 사실을 잃고 guide 4회·lint 6회·이력 되읽기 5회를
# 돌다 죽었다. 이력 원문을 되읽는 도구(093c866)는 동작했지만 원문은 「끝났다」를 말해 주지
# 않는다 — 그 판단은 요약이 들고 있어야 한다.
#
# deepagents 자신이 media 안내를 끼우는 방식과 같다: `<messages>` 마커 **앞**에 splice 한다.
# 그 마커 자리에 실제 대화가 삽입되므로 마커를 깨면 요약 전체가 고장난다.
_WIKI_STATE_SUMMARY_ADDENDUM = """<wiki_work_state>
이것은 사내 위키 편집 작업의 문맥이다. 요약에 아래 네 가지를 **반드시** 담는다.
하나라도 빠지면 이후 턴이 이미 끝난 작업을 처음부터 다시 시작한다.

1. 반영 중인 원본문서의 주소 (예: `sources/48/parsed/content.md`)
2. 이번 작업에서 **만들었거나 고친** 위키 페이지의 주소와 제목, 각각에 무엇을 했는지
3. 마지막 `lint` 결과 — 통과했으면 「통과」라고 명시한다
4. **남은 일** — 구체적으로 적는다. 다 끝났으면 「남은 일 없음 — 최종 보고만 남았다」라고
   적는다. 이 줄이 요약의 마지막 줄이어야 한다

이미 완료한 단계(문서 읽기, 검색, 페이지 생성·수정, lint)는 **반복하지 않는다**고
명시한다. guide 를 다시 부를 필요도 없다 — 작업 방식은 이미 확인했다.
</wiki_work_state>"""


def _wiki_summary_prompt() -> str:
    """작업 상태 보존 지시를 끼운 요약 프롬프트. deepagents 가 있어야 계산된다."""
    from deepagents.middleware.summarization import DEEPAGENTS_DEFAULT_SUMMARY_PROMPT

    return DEEPAGENTS_DEFAULT_SUMMARY_PROMPT.replace(
        "\n<messages>\n",
        f"\n{_WIKI_STATE_SUMMARY_ADDENDUM}\n\n<messages>\n",
        1,
    )


def __getattr__(name: str):
    # `WIKI_SUMMARY_PROMPT` 는 deepagents 문자열에서 유도되는데 deepagents 는 선택
    # 의존성이다 — 모듈 상수로 두면 미설치 환경에서 이 모듈 import 자체가 죽는다.
    # PEP 562 로 첫 접근 때 계산한다.
    if name == "WIKI_SUMMARY_PROMPT":
        return _wiki_summary_prompt()
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


_COMPACTION_MW_CLASS = None


def _compaction_middleware_class():
    """`SummarizationMiddleware` 의 서브클래스(다른 정확-타입)를 캐시해 돌려준다.

    create_deep_agent 은 기본 요약 미들웨어(같은 클래스)를 무조건 넣는다. 프로필에서 그 base
    클래스를 정확-타입으로 exclude 하면 base 는 빠지지만, 이 서브클래스는 다른 타입이라 살아
    남는다 — 그래야 우리 낮은 트리거(34K) 요약 하나만 남고 「중복 미들웨어」 어서션을 피한다
    (`deepagents/_excluded_middleware.py::_apply_excluded_middleware` docstring). 캐시로 타입을
    고정한다 — 호출마다 새 타입을 만들면 exclude 대상과의 정확-타입 비교가 흔들린다."""
    global _COMPACTION_MW_CLASS
    if _COMPACTION_MW_CLASS is None:
        from deepagents.middleware.summarization import SummarizationMiddleware

        class _CompactionSummarizationMiddleware(SummarizationMiddleware):
            """trim 이 비어도 상태보존 요약이 침묵 소멸하지 않게 한다 (S15P11B106-313).

            기본 trim 예산(4,000 근사)은 한국어 장문 read 결과 하나(근사 ~4.6K)보다
            작아서, 프로덕션에서 압축을 가장 자주 유발하는 모양에서 trim 이 빈 목록을
            내고 베이스가 **LLM 호출 없이** "too long to summarize" 폴백으로 이력을
            갈아치웠다 — !273 의 프롬프트가 실행될 기회가 없었다
            (findings/2026-08-07-compaction-summary-starvation.md, 가짜 모델 실측).
            """

            # 한국어 근사 8K ≈ 실 32K 토큰 — GMS 요청 크기벽(~42K 실토큰) 안에 드는
            # 최대 규모다. 요약 모델(FAST haiku)의 컨텍스트로는 어느 쪽이든 충분하다.
            _TRIM_BUDGET_TOKENS = 8_000

            def __init__(self, *args, **kwargs):
                kwargs.setdefault("trim_tokens_to_summarize", self._TRIM_BUDGET_TOKENS)
                super().__init__(*args, **kwargs)
                # 요약 생성은 `_lc_helper`(langchain 미들웨어 인스턴스)에 위임된다 —
                # 이 클래스에 같은 이름의 메서드를 정의해도 위임 경로는 그것을 안 부른다.
                # 그래서 헬퍼 인스턴스의 trim 을 직접 감싼다. 사설 속성 의존이지만
                # 아래 폴백이 없으면 요약이 침묵 소멸하므로, 계약은 한국어 장문 모양의
                # 가짜 모델 테스트가 고정한다 (라이브러리가 바뀌면 그 테스트가 먼저 빨개진다).
                helper = self._lc_helper
                original_trim = helper._trim_messages_for_summary
                budget_chars = self._TRIM_BUDGET_TOKENS * 2  # 근사 1토큰 ≈ 한국어 1~2자

                def trim_with_tail_fallback(messages):
                    trimmed = original_trim(messages)
                    if trimmed:
                        return trimmed
                    # 예산보다 큰 단일 메시지(또는 human 시작점 부재) 때문에 비었다 —
                    # 꼬리 메시지를 글자수로 잘라서라도 준다. 불완전한 요약이 요약 없음보다
                    # 낫다: 폴백 문자열은 「무엇이 끝났는지」를 전부 버려 에이전트가
                    # 처음부터 다시 시작한다 (job 41 의 guide 4회·lint 6회).
                    from langchain_core.messages import HumanMessage
                    tail = messages[-1]
                    text = str(getattr(tail, "content", tail))
                    return [HumanMessage(content=text[-budget_chars:])]

                helper._trim_messages_for_summary = trim_with_tail_fallback

        _COMPACTION_MW_CLASS = _CompactionSummarizationMiddleware
    return _COMPACTION_MW_CLASS


def _server_config(root: Path, scope_key: str, job_id: str,
                   tool_log: Path | None = None) -> dict:
    """Same stdio server the CLI runtime launches, described for MCP adapters.

    `sys.executable`, not `uv run --project`. The CLI runtime hit this first
    (`claude_code.py` trap 5): `uv run` re-resolves the project on every spawn and
    the MCP client gave up before the handshake finished — the server showed as
    `pending`, no tools arrived, and the agent flailed with its own file tools
    while the run still reported no error. The same spawn is used here, so the
    same fix applies. **Unverified on this runtime** — DeepAgents is not installed
    in the measurement environment yet (plan Task 7).
    """
    args = [
        "-m", SERVER_MODULE,
        "--root", str(root), "--scope", scope_key, "--job-id", job_id,
    ]
    if tool_log:
        args += ["--tool-log", str(tool_log)]
    return {"wiki": {"transport": "stdio", "command": sys.executable, "args": args}}


EXPECTED_TOOLS = frozenset({
    "append", "create", "delete", "edit", "guide", "lint", "list_scopes",
    "merge", "read", "search",
})


def verify_mcp_tools(tool_names: set[str]) -> None:
    """Fail loudly if the MCP server did not serve every tool.

    Worth checking because the failure is silent: a server that registers nothing
    still starts, and the agent then reports success having written nothing. That
    cost a whole run on the CLI side before anyone noticed.

    **This cannot verify that the built-ins are excluded** — the list it receives
    comes from the MCP client, so it only ever contains MCP tools. Whether a
    built-in `write_file` is reachable is checked after the fact instead, by
    `backend_sim`: pages appeared but no write tool was logged means something
    wrote around the server.
    """
    missing = EXPECTED_TOOLS - tool_names
    if missing:
        raise RuntimeError(f"MCP 툴이 빠졌다: {sorted(missing)}")


class AgentReport(BaseModel):
    """`arun()` 마지막 턴의 구조 강제 스키마 (S15P11B106-243 검증 중 실측 대응).

    프롬프트(`base.py`의 `edit_instruction`·`ingest_instruction`)가 이미 "비개발자
    용어로 쓰라"고 지시하지만, 그건 확률적이라 모델이 가끔 어긴다 — 실제로 lint 도구
    어휘(error·warn)나 내부 파일 경로(`pages/xxxx.md`)가 관리자 채팅에 그대로 새어나온
    사례가 있었다. `response_format=ToolStrategy(AgentReport)`로 마지막 답변을 이
    필드 하나로 강제하면, 자유 텍스트 마지막 메시지보다 그 필드에 잡소리가 섞일 여지가
    준다 — 완전한 차단은 아니라 `reply_sanitizer`를 이중으로 걸어둔다.
    """

    # **길이를 여기서 정하지 않는다.** 예전 설명은 "2~3문장 보고" 였는데, 그것이
    # `base.py::ingest_instruction` 의 표 양식(페이지·처리·카테고리·주요 내용·근거 5열 +
    # 보충 4줄)과 정면으로 모순됐다. 마지막 답변은 `ToolStrategy(AgentReport)` 로 이 필드
    # 하나에 담기므로 필드 설명이 프롬프트보다 가깝고, 그쪽이 이겨서 **표가 구조적으로 나올
    # 수 없었다** — 실기동 job 37 의 「반영 내용」이 "새 페이지 1장을 만들고 기존 페이지
    # 2장을 고쳤습니다" 한 줄로 끝나 무엇을 고쳤는지 알 수 없었다 (2026-08-06).
    #
    # 양식의 정본은 지시문이다. 여기서 다시 정의하면 또 갈라지므로 가리키기만 한다.
    # 이 필드가 존재하는 이유(용어·경로 누출 차단, S15P11B106-243)는 그대로 남긴다.
    summary: str = Field(description=(
        "비개발자 관리자에게 보여줄 작업 보고. **지시문이 준 양식을 그대로 지킨다** — "
        "한 줄 요약, 다룬 페이지마다 한 행인 표, 그다음 보충 항목이다. 길이를 줄이려고 "
        "표를 빼지 않는다. 시스템 용어(scope·frontmatter·lint·error·warn 등)나 내부 파일 "
        "경로(pages/*.md, document-N, sources/N 등)는 쓰지 않는다. 점검이 끝났다는 말로 "
        "시작하거나 끝맺지 않고, 첫 단어부터 곧바로 반영된 내용을 설명한다."
    ))


class DeepAgentsRuntime:
    """Drives one ingest through a single DeepAgents coordinator."""

    name = "deepagents"

    # `run()` 은 `_server_config` 로 MCP 서버를 별도 프로세스에 띄우고, `arun()` 은 도구를
    # 이 프로세스에서 만든다. 창구 모드는 후자로만 성립한다 (`base.py::runs_tools_in_process`).

    def __init__(self, model: str = "anthropic:claude-sonnet-4-6", *,
                 fast_model: str | None = None, quality_model: str | None = None,
                 credentials: dict[str, tuple[str, str]] | None = None,
                 chat_model=None):
        """모델과 자격증명을 **인자로 받는다.** `os.environ` 을 읽지 않는다.

        읽던 시절에는 이 클래스를 테스트하려면 환경변수를 몽키패치해야 했고, 어떤
        설정에 의존하는지가 시그니처에 드러나지 않았다. 설정을 고르는 일은 조립
        지점(`wiki_api/serve.py`)의 몫이다.

        `credentials` 는 단일 `(api_key, base_url)` 쌍이 아니라 **프로바이더 이름 →
        (api_key, base_url) 표**다. 에이전트 모델(`model`)과 티어 모델(`fast_model`·
        `quality_model`)의 프로바이더가 다를 수 있기 때문이다 — 단일 쌍을 쓰면 한
        프로바이더의 키가 다른 프로바이더의 클라이언트로 간다 (벤더 교차 문제).
        `_credential_kwargs` 가 호출할 모델의 접두사로 그때그때 조회한다.
        """
        self.model = model
        self._tier_models = {FAST: fast_model, QUALITY: quality_model}
        self._credentials = credentials or {}
        # 완성된 모델 객체를 그대로 쓰는 자리. **가짜 모델을 끼우는 지점이다** — 모델 판단만
        # 가짜고 루프·도구·응답 조립은 진짜로 돈다. 배포에서는 넘기지 않는다.
        self._chat_model_override = chat_model

    def _model_for(self, tier: str) -> str:
        """tier → 모델 이름. 생성자 인자가 있으면 그것을 쓴다.

        인자가 없으면 **에이전트 모델과 같은 제공자의** 기본값을 쓴다. 모르는 제공자면
        에이전트 모델을 그대로 쓴다 — 다른 벤더로 새는 것보다 낫다.

        모르는 tier 를 조용히 기본 모델로 떨어뜨리지 않는다 — 오타 하나가 측정을
        무의미하게 만드는 것보다 즉시 터지는 쪽이 낫다.
        """
        if tier not in TIERS:
            raise ValueError(f"모르는 tier: {tier!r} (가능: {sorted(TIERS)})")
        chosen = self._tier_models.get(tier)
        if chosen:
            return chosen
        provider = (self.model or "").split(":", 1)[0]
        return DEFAULT_TIER_MODELS.get(provider, {}).get(tier, self.model)

    def _credential_kwargs(self, model: str) -> dict:
        """**호출할 모델의** `provider:name` 접두사로 자격증명 표를 조회한다.

        `complete()` 는 티어 모델로, `_run`(에이전트 경로)은 `self.model` 로 부른다 —
        단일 자격증명을 두 경로에 공유하면 에이전트와 티어의 프로바이더가 갈릴 때 한쪽이
        틀린 벤더의 키를 받는다.

        값이 있을 때만 넣는다. 빈 문자열을 넘기면 SDK 가 「빈 키」로 읽어 자기 폴백조차
        막는다.
        """
        provider = (model or "").split(":", 1)[0]
        api_key, base_url = self._credentials.get(provider, ("", ""))
        kwargs = {}
        if api_key:
            kwargs["api_key"] = api_key
        if base_url:
            kwargs["base_url"] = base_url
        return kwargs

    def complete(self, messages: list[dict], *, tier: str = QUALITY,
                 timeout: int | None = None) -> CompletionResult:
        """MCP 없는 단발 호출. 에이전트도 툴도 만들지 않고 모델만 부른다.

        `timeout` 을 **모델 클라이언트에** 건다. 호출자의 `asyncio.wait_for` 는 코루틴을
        풀어주지만 이미 떠난 HTTP 요청을 끊지 못한다 — 이 인자가 실제로 연결을 끊는
        유일한 지점이다 (`run` 의 subprocess timeout 과 같은 이유).

        `max_retries=0` 은 예산 방어다. 기본값은 재시도이므로 실패 1건이 조용히 2~3배
        청구된다. 재시도가 필요하면 호출자가 명시적으로 다시 부른다.
        """
        from langchain.chat_models import init_chat_model

        requested = self._model_for(tier)
        model = init_chat_model(requested,
                                timeout=timeout or DEFAULT_COMPLETE_TIMEOUT,
                                max_retries=0,
                                **self._credential_kwargs(requested))
        started = time.monotonic()
        reply = model.invoke(messages)
        usage = getattr(reply, "usage_metadata", None) or {}
        details = usage.get("input_token_details") or {}
        meta = getattr(reply, "response_metadata", None) or {}
        return CompletionResult(
            text=_text_of(reply.content),
            input_tokens=int(usage.get("input_tokens", 0) or 0),
            output_tokens=int(usage.get("output_tokens", 0) or 0),
            cache_read_tokens=int(details.get("cache_read", 0) or 0),
            cache_creation_tokens=int(details.get("cache_creation", 0) or 0),
            # 요청한 이름이 아니라 응답이 말한 모델. 게이트웨이가 바꿔 끼울 수 있다.
            model=str(meta.get("model") or meta.get("model_name") or requested),
            elapsed_seconds=round(time.monotonic() - started, 2),
        )

    def run(self, instruction: str, *, root: Path, scope_key: str,
            job_id: str, timeout: int | None = None) -> RunResult:
        """`timeout` 은 이 문서에 허용된 초 (`agent_runtime/limits.py`). 세션이 계산해 넘긴다 —
        이 런타임이 배포 형태이므로 NFR-PERF-002 의 강제가 여기서 일어난다. 안 넘기면
        기존 고정값 `CALL_TIMEOUT_SECONDS`."""
        limit = timeout or CALL_TIMEOUT_SECONDS
        started = time.monotonic()
        with tempfile.TemporaryDirectory(prefix="llmwiki-ingest-") as tmp:
            tool_log = Path(tmp) / "tools.log"
            try:
                text, usage, turns = asyncio.run(
                    self._run(instruction, root, scope_key, job_id, tool_log, limit)
                )
            except TimeoutError:
                # The work space keeps whatever was written; the backend discards
                # it because the job never reported success (DR-009).
                return RunResult(
                    text="", tool_calls=read_counts(tool_log),
                    elapsed_seconds=round(time.monotonic() - started, 1),
                    error=f"{limit}초 안에 끝나지 않았다 (NFR-PERF-002)",
                )
            except Exception as exc:
                counts = read_counts(tool_log)
                # NFR-AI-003 wants a step, not just a message. The last tool the
                # agent reached is the closest thing to one.
                last = max(counts, key=counts.get) if counts else "시작 전"
                return RunResult(
                    text="", tool_calls=counts,
                    elapsed_seconds=round(time.monotonic() - started, 1),
                    error=f"{type(exc).__name__} (마지막 도달 단계: {last}): {exc}",
                )
            counts = read_counts(tool_log)

        _log_usage(job_id, usage, turns=turns,
                   tool_calls=sum(counts.values()) if counts else 0,
                   elapsed_seconds=round(time.monotonic() - started, 1))
        return RunResult(
            text=text,
            tool_calls=counts,
            input_tokens=int(usage.get("input_tokens", 0) or 0),
            output_tokens=int(usage.get("output_tokens", 0) or 0),
            turns=turns,
            elapsed_seconds=round(time.monotonic() - started, 1),
        )

    def _chat_model(self, timeout: int):
        """에이전트 모델 객체. 생성자에 `chat_model=` 이 오면 그것을 그대로 쓴다.

        자격증명은 `_credential_kwargs` 로 **호출할 모델의 제공자** 몫만 넣는다 —
        `complete()` 와 같은 이유다 (벤더 교차 방지).
        """
        if self._chat_model_override is not None:
            return self._chat_model_override
        from langchain.chat_models import init_chat_model

        return init_chat_model(self.model, timeout=timeout, max_retries=0,
                               **self._credential_kwargs(self.model))

    def _summarization_middleware(self, backend):
        """문맥 압축 미들웨어. **트리거를 에이전트 모델의 `base_url` 로 고른다.**

        create_deep_agent 은 기본으로 요약 미들웨어를 넣지만 트리거가 170K/0.85 다. 게이트웨이
        (GMS)를 쓸 때는 그 값이 요청 크기벽(~42K)보다 훨씬 높아 벽에서 죽을 때까지 안 터지므로
        34K 로 낮춘다. 직접 API 에는 벽이 없어 낮춘 값이 오히려 손해라(요약 콜 추가 + 프롬프트
        캐시 무효화) 기본값 자리로 되돌린다 — 판단은 `compaction_trigger_for` 가 한다.

        **에이전트 모델의 주소로 판단한다.** 벽에 부딪히는 것은 이력이 실린 큰 요청, 즉 에이전트
        호출이다. 요약 콜은 FAST 티어(haiku)로 따로 싸게 도는데, 그쪽 프로바이더가 갈려도 압축을
        언제 걸어야 하는지는 에이전트 쪽 제약이 정한다.

        이름이 기본과 같아(`SummarizationMiddleware`) create_deep_agent 이 기본을 이것으로
        교체한다. 최근 6메시지는 어느 경우든 보존한다.
        (spec: docs/superpowers/specs/2026-08-02-gms-context-compaction-design.md)
        """
        from langchain.chat_models import init_chat_model

        if self._chat_model_override is not None:
            # 테스트가 주입한 모델을 요약에도 쓴다 — `_chat_model` 과 같은 규약.
            summary_model = self._chat_model_override
        else:
            fast = self._model_for(FAST)
            summary_model = init_chat_model(fast, max_retries=0,
                                            **self._credential_kwargs(fast))
        trigger = compaction_trigger_for(
            self._credential_kwargs(self.model).get("base_url", ""))
        logger.info("compaction trigger=%s keep=%s", trigger, COMPACTION_KEEP)
        return _compaction_middleware_class()(
            model=summary_model, backend=backend,
            trigger=trigger, keep=COMPACTION_KEEP,
            # 기본 요약은 범용 추출이라 「무엇을 끝냈는지」를 안 남긴다 — 압축 뒤 에이전트가
            # 완료한 작업을 처음부터 반복했다 (job 41, `_WIKI_STATE_SUMMARY_ADDENDUM` 주석).
            summary_prompt=_wiki_summary_prompt(),
        )

    async def arun_with_tools(self, guide: str, question: str, *, tools: list,
                              max_turns: int, timeout: int,
                              response_format=None) -> RunResult:
        """MCP 없이 도구를 직접 붙여 돈다. 챗봇 전용이다. **호출자의 루프에서 돈다.**

        `run` 과 달리 임시 디렉터리도 MCP 클라이언트도 만들지 않는다 — 쓰기가 없다.

        **비동기가 정본이고 `run_with_tools` 가 껍데기다.** 뒤집으면(요청마다 `asyncio.run`)
        모델 클라이언트의 연결 풀이 **닫힌 루프에 묶여** 두 번째 요청부터 전부
        `RuntimeError: Event loop is closed` 로 죽는다 — 그것이 `APIConnectionError` 로 감싸
        여 올라와 네트워크 장애처럼 보인다. Spring 실연동에서 확인했다 (2026-07-31): 서버
        기동 후 첫 질문만 답하고 그다음은 전부 실패했다.
        """
        started = time.monotonic()
        text, calls, structured, hit_limit = await self._run_with_tools(
            guide, question, tools, max_turns, timeout, response_format)
        return RunResult(text=text, tool_calls=calls, turns=sum(calls.values()),
                         structured=structured,
                         error="turn_limit" if hit_limit else None,
                         elapsed_seconds=round(time.monotonic() - started, 2))

    def run_with_tools(self, guide: str, question: str, *, tools: list,
                       max_turns: int, timeout: int,
                       response_format=None) -> RunResult:
        """`arun_with_tools` 의 sync 껍데기. **돌고 있는 루프 안에서는 부르지 않는다** —
        스크립트와 테스트용이다. 서버는 `arun_with_tools` 를 그대로 await 한다.
        """
        return asyncio.run(self.arun_with_tools(
            guide, question, tools=tools, max_turns=max_turns, timeout=timeout,
            response_format=response_format))

    async def _run_with_tools(self, guide: str, question: str, tools: list,
                              max_turns: int, timeout: int, response_format):
        from deepagents import create_deep_agent
        from langchain.agents.middleware import ModelCallLimitMiddleware
        from langchain.agents.middleware.model_call_limit import (
            ModelCallLimitExceededError,
        )

        # 턴 상한을 미들웨어로 건다. `recursion_limit` 만 쓰면 GraphRecursionError 가 나고
        # 그것이 일반 예외로 잡혀 「모델 호출 실패」로 뭉개진다 — 상한 도달과 고장을
        # 구분할 수 없게 된다.
        #
        # `exit_behavior="error"` 다. `"end"` 는 영어 안내문(`Model call limits
        # exceeded: ...`)을 마지막 AI 메시지로 끼워 정상 종료처럼 끝내므로, 상한 도달을
        # 알아내려면 그 문장을 문자열로 대조해야 한다 — 그 문장이 바뀌면 조용히 상한이
        # 「빈 답변」이나 심지어 사용자에게 나가는 영어 답변으로 새어나간다.
        limit = ModelCallLimitMiddleware(thread_limit=max_turns, exit_behavior="error")
        # **도구 호출은 우리가 센다.** 상한 예외로 끝나면 그래프 상태를 못 받아 「몇 번
        # 읽었나」가 사라지고, 호출자는 상한 도달과 「모델 호출이 매번 실패했다」를 구분할 수
        # 없게 된다 (그 둘은 이름이 달라야 한다).
        counted: dict[str, int] = {}
        agent = create_deep_agent(
            model=self._chat_model(timeout),
            tools=langchain_tools(tools, counted),
            subagents=[],
            system_prompt=guide,          # 지침은 여기에만. 질문과 겹쳐 싣지 않는다.
            middleware=[limit],
            response_format=response_format,
        )
        hit_limit = False
        try:
            result = await asyncio.wait_for(
                agent.ainvoke({"messages": [{"role": "user", "content": question}]}),
                timeout=timeout,
            )
        except ModelCallLimitExceededError:
            # 상한 도달은 고장이 아니라 상한 도달이다. 호출자가 자기 오류 이름으로 바꾼다.
            # 그때까지 실제로 읽은 횟수를 함께 낸다 — 도구를 한 번도 못 불렀다면 그것은
            # 상한이 아니라 모델 호출 실패이고, 이름이 달라야 한다.
            return "", dict(counted), None, True
        messages = result.get("messages") or []
        # 구조화 응답은 **도구 호출로 온다** (LangChain `ToolStrategy`): 모델이 스키마
        # 이름의 도구를 부르고 그 인자가 응답이 된다. 그것은 조회가 아니므로 세지 않는다 —
        # 세면 위키도 일정도 읽지 않은 실행이 「조회했다」로 통과한다 (2026-07-31 확인).
        # `counted` 는 우리가 감싼 도구만 세므로 그 문제가 애초에 없다.
        calls = dict(counted)
        # 안전망(S15P11B106-243 검증 중 실측): 지시문이 개발 용어·내부 파일 경로를 쓰지
        # 말라고 못 박아도 모델이 가끔 어긴다. 구조 강제가 없는 호출(`response_format`
        # 미지정)은 여기서 나가는 텍스트가 사용자에게 그대로 보일 수 있어 마지막에 한 번
        # 더 거른다.
        text = sanitize_admin_reply(_text_of(messages[-1].content)) if messages else ""
        structured = result.get("structured_response")
        # pydantic 인스턴스로 온다. 계약 조립 쪽은 dict 를 다루므로 여기서 맞춘다 —
        # `agent_runtime` 이 `wiki_api` 의 모델을 알 필요가 없게 하는 경계이기도 하다.
        if hasattr(structured, "model_dump"):
            structured = structured.model_dump()
        return text, calls, structured, hit_limit

    async def arun(self, instruction: str, *, fs, scope_id: str, root: Path,
                   scope_key: str, job_id: str,
                   timeout: int | None = None) -> RunResult:
        """MCP 하위 프로세스 없이 위키를 고친다 (S15P11B106-152).

        `run` 과 같은 일을 하되 도구를 `wiki_agent_tools` 로 이 프로세스 안에서 만든다.
        그래야 창구 모드(`FederatedVaultFS`)가 성립한다 — 열람 허가값과 중단 신호가
        프로세스 경계를 못 넘는다 (설계 §3.7, `wiki_tools.py` 헤더).

        **호출자의 루프에서 돈다.** `arun_with_tools` 와 같은 이유다: 요청마다
        `asyncio.run` 을 돌리면 모델 클라이언트의 연결 풀이 닫힌 루프에 묶여 두 번째
        요청부터 전부 죽는다 (2026-07-31 Spring 실연동에서 확인).

        `scope_id` 는 받아 두고 쓰지 않는다 — 도구가 `scope` 인자로 자기 것을 해석한다.
        세션이 넘기는 인자 모양(`session.py::run_agent`)을 그대로 받는다.
        """
        from deepagents import (
            GeneralPurposeSubagentProfile,
            HarnessProfile,
            create_deep_agent,
            register_harness_profile,
        )

        from deepagents.middleware.summarization import SummarizationMiddleware
        from langchain.agents.middleware.model_call_limit import (
            ModelCallLimitExceededError,
        )
        from langchain.agents.structured_output import ToolStrategy

        from .history_tool import history_read_tool
        from .wiki_tools import wiki_agent_tools

        limit = timeout or CALL_TIMEOUT_SECONDS
        started = time.monotonic()
        # 도구 호출 수를 우리가 센다. MCP 경로는 서버 측 로그(`read_counts`)로 셌는데
        # 여기는 서버가 없다. 그리고 상한 예외로 끝난 실행도 수는 남아야 한다.
        counts: dict = {}
        specs = wiki_agent_tools(fs, scope_key)
        verify_mcp_tools({spec.name for spec in specs})

        register_harness_profile(
            self.model,
            HarnessProfile(
                excluded_tools=EXCLUDED_BUILTIN_TOOLS,
                general_purpose_subagent=GeneralPurposeSubagentProfile(enabled=False),
                # 기본 요약(트리거 170K)을 정확-타입으로 뺀다 — 우리 서브클래스(34K)만 남긴다.
                # frozenset 이다 — 프로필 병합이 `|` 로 합집합하므로 list 면 TypeError.
                excluded_middleware=frozenset({SummarizationMiddleware}),
            ),
        )
        # GMS 요청 크기벽(~42K) 아래로 문맥을 유지한다. 위 프로필이 기본 요약(170K)을 빼고,
        # 여기서 낮은 트리거(34K) 요약 서브클래스를 넣는다. 요약 오프로드용 backend 는 이
        # 작업의 임시 루트에 둔다 — 텍스트 요약이라 거의 안 쓰인다.
        from deepagents.backends import FilesystemBackend
        compaction_backend = FilesystemBackend(root_dir=str(root), virtual_mode=True)
        # **압축이 버린 이력을 되읽을 길을 하나 준다.** 요약 메시지는 "전체 이력을
        # `/conversation_history/…md` 에 저장했다" 고 안내하는데, `EXCLUDED_BUILTIN_TOOLS`
        # 가 `read_file` 을 막아 그 안내가 막다른 길이었다 — 에이전트가 시킨 대로 읽으려다
        # 실패하고 `guide` 부터 다시 시작해 아무것도 못 고쳤다 (2026-08-06 job 40).
        #
        # 이 도구는 그 디렉터리 **하나만** 읽는다. `read_file` 을 푸는 것이 아니다 —
        # 그러면 `write_file` 과 함께 MCP 우회가 열린다 (`history_tool.py` 헤더).
        specs = [*specs, history_read_tool(compaction_backend)]
        tools = langchain_tools(specs, counts)
        agent = create_deep_agent(
            model=self._chat_model(limit),
            tools=tools,
            subagents=[],
            system_prompt="사내 위키 편집 에이전트다. `guide` 도구를 먼저 불러 작업 방식을 확인한다.",
            middleware=[self._summarization_middleware(compaction_backend),
                        turn_limit_middleware()],
            response_format=ToolStrategy(AgentReport),
        )

        def elapsed() -> float:
            return round(time.monotonic() - started, 1)

        try:
            result = await asyncio.wait_for(
                agent.ainvoke(
                    {"messages": [{"role": "user", "content": instruction}]},
                    {"recursion_limit": MAX_TURNS * RECURSION_LIMIT_PER_TURN},
                ),
                timeout=limit,
            )
        except ModelCallLimitExceededError:
            # 상한 도달은 고장이 아니라 상한 도달이다. `except Exception` 보다 위에 둔다 —
            # 아래로 내려가면 `GraphRecursionError` 때와 똑같이 뭉개진다. 그때까지 부른
            # 도구 수를 함께 낸다: 쓰기가 0건이면 검색 루프이고, 도구가 0건이면 상한이
            # 아니라 모델 호출 실패다 (2026-08-06 실측에서 그 둘을 구분할 수 없었다).
            # 이 문장은 `failure_reason` 으로 관리자 화면에 그대로 뜬다 — 읽는 사람은
            # 비개발자다. 「턴」은 내부 용어라 쓰지 않고, 실행 가능한 다음 행동(주제별
            # 분리 재업로드)과 반복 시 탈출구(문의)를 준다. 「작업 한도」가 진단용
            # 표식이다 — 로그에서 상한 도달을 grep 하는 쪽이 이 단어에 기댄다.
            return RunResult(
                text="", tool_calls=counts, elapsed_seconds=elapsed(),
                error=_SPLIT_GUIDANCE)
        except (TimeoutError, asyncio.TimeoutError):
            # 작업 공간에 쓰인 것은 남지만 작업이 성공을 보고하지 않았으므로 백엔드가
            # 버린다 (DR-009). `run` 과 같은 규약 — 예외가 아니라 `error` 문장이다.
            return RunResult(text="", tool_calls=counts, elapsed_seconds=elapsed(),
                             error=f"{limit}초 안에 끝나지 않았다 (NFR-PERF-002)")
        except ScopeChangedError:
            # 범위 변경은 재시도로 풀린다. 세션이 다른 단계로 기록해야 하므로 삼키지
            # 않는다 (`session.py::run_agent` 의 분기).
            raise
        except Exception as exc:
            last = max(counts, key=counts.get) if counts else "시작 전"
            # 원문은 로그에 남긴다 — 관리자에게는 번역문이 나가므로 여기가 유일한 원본이다.
            logger.warning("agent 실패 원문 — job=%s stage=%s: %r", job_id, last, exc)
            return RunResult(
                text="", tool_calls=counts, elapsed_seconds=elapsed(),
                error=_admin_error_message(exc, last))

        messages = result.get("messages") or []
        # 구조 강제(`response_format`)가 성공하면 답변은 도구 호출 인자로 온다 —
        # 자유 텍스트 마지막 메시지보다 잡소리가 섞일 여지가 준다(AgentReport 참고).
        # 스키마 검증이 끝내 실패해 `structured_response`가 안 채워진 경우에만
        # 마지막 메시지로 되돌아간다. 어느 경로든 안전망을 한 번 더 건다.
        structured = result.get("structured_response")
        if structured is not None:
            text = getattr(structured, "summary", None) or ""
        else:
            text = str(getattr(messages[-1], "content", "")) if messages else ""
        text = sanitize_admin_reply(text)
        usage = _usage(messages)
        turns = _turns(messages)
        _log_usage(job_id, usage, turns=turns,
                   tool_calls=sum(counts.values()) if counts else 0,
                   elapsed_seconds=elapsed())
        return RunResult(
            text=text, tool_calls=counts,
            input_tokens=int(usage.get("input_tokens", 0) or 0),
            output_tokens=int(usage.get("output_tokens", 0) or 0),
            turns=turns, elapsed_seconds=elapsed())

    async def _run(self, instruction: str, root: Path, scope_key: str,
                   job_id: str, tool_log: Path,
                   limit: int = CALL_TIMEOUT_SECONDS) -> tuple[str, dict, int]:
        from deepagents import (
            GeneralPurposeSubagentProfile,
            HarnessProfile,
            create_deep_agent,
            register_harness_profile,
        )
        from langchain.chat_models import init_chat_model
        from langchain_mcp_adapters.client import MultiServerMCPClient

        client = MultiServerMCPClient(_server_config(root, scope_key, job_id, tool_log))
        tools = await client.get_tools()

        # `register_harness_profile` keys its lookup off the model *name*, so it
        # still gets the string — only `create_deep_agent` needs the instance to
        # carry credentials (a string there means `os.environ` again).
        register_harness_profile(
            self.model,
            HarnessProfile(
                excluded_tools=EXCLUDED_BUILTIN_TOOLS,
                general_purpose_subagent=GeneralPurposeSubagentProfile(enabled=False),
            ),
        )
        model = init_chat_model(self.model, **self._credential_kwargs(self.model))
        agent = create_deep_agent(
            model=model,
            tools=tools,
            subagents=[],
            # Deliberately almost empty. The standards live in the `guide` tool so
            # both runtimes read the same copy — and so that a difference between
            # them is a harness difference, not a prompt difference.
            system_prompt="사내 위키 편집 에이전트다. `guide` 도구를 먼저 불러 작업 방식을 확인한다.",
        )

        verify_mcp_tools({getattr(t, "name", "") for t in tools})

        result = await asyncio.wait_for(
            agent.ainvoke(
                {"messages": [{"role": "user", "content": instruction}]},
                # Two turns per step in langgraph terms (model, then tools).
                {"recursion_limit": MAX_TURNS * 2},
            ),
            timeout=limit,
        )

        messages = result.get("messages") or []
        text = sanitize_admin_reply(
            str(getattr(messages[-1], "content", "")) if messages else "")
        return text, _usage(messages), _turns(messages)


def langchain_tools(tools: list, counter: dict | None = None) -> list:
    """`AgentTool` 목록을 LangChain 도구로 감싼다.

    LangChain 임포트를 이 파일 안에 둔다 — 배포 의존성을 깔지 않은 설치에서도
    `agent_runtime.tools` 는 임포트돼야 한다.

    `counter` 를 주면 **호출될 때마다 이름별로 센다.** 그래프 상태에서 세지 않는 이유는
    상한 예외로 끝난 실행에서는 그 상태를 받지 못하기 때문이다.

    `call` 이 코루틴 함수면 `coroutine=` 으로 넘긴다. sync 자리에 넣으면 LangChain 이
    코루틴 객체를 그대로 도구 결과로 삼아 모델이 `<coroutine object ...>` 를 읽는다 —
    도구가 아무 일도 안 하고 성공한 것처럼 보인다. 위키 편집 도구가 전부 async 다
    (S15P11B106-152).
    """
    import inspect

    from langchain_core.tools import StructuredTool

    def wrap(tool):
        if inspect.iscoroutinefunction(tool.call):
            if counter is None:
                return {"coroutine": tool.call}

            async def acalled(**kwargs):
                counter[tool.name] = counter.get(tool.name, 0) + 1
                return await tool.call(**kwargs)

            return {"coroutine": acalled}

        if counter is None:
            return {"func": tool.call}

        def called(**kwargs):
            counter[tool.name] = counter.get(tool.name, 0) + 1
            return tool.call(**kwargs)

        return {"func": called}

    return [StructuredTool.from_function(
        **wrap(tool), name=tool.name, description=tool.description,
        args_schema=tool.input_schema) for tool in tools]


def _usage(messages: list) -> dict:
    """Sum token usage across the run.

    Unlike the CLI's numbers these are the real thing — no foreign system prompt
    inflating the cache fields — so they are comparable between DeepAgents runs and
    to the API baseline, but not to CLI runs.
    """
    total = {"input_tokens": 0, "output_tokens": 0,
             "cache_read": 0, "cache_creation": 0}
    for message in messages:
        usage = getattr(message, "usage_metadata", None) or {}
        total["input_tokens"] += int(usage.get("input_tokens", 0) or 0)
        total["output_tokens"] += int(usage.get("output_tokens", 0) or 0)
        # 캐시 적중을 빼면 예산이 몇 배 틀린다 — 같은 입력 토큰이라도 `cache_read` 로
        # 청구되면 정가의 일부다. `complete` 경로가 이미 이 값을 들고 다닌다.
        details = usage.get("input_token_details") or {}
        total["cache_read"] += int(details.get("cache_read", 0) or 0)
        total["cache_creation"] += int(details.get("cache_creation", 0) or 0)
    return total


def _log_usage(job_id: str, usage: dict, *, turns: int, tool_calls: int,
               elapsed_seconds: float) -> None:
    """토큰·턴을 한 줄로 남긴다. RunResult 가 담지만 아무도 찍지 않아 "왜 이 비용인가" 를
    사후에 알 방법이 없었다. cache_read 를 함께 내야 캐시가 도는지 보인다."""
    logger.info(
        "deepagents usage job=%s turns=%s input=%s output=%s "
        "cache_read=%s cache_creation=%s tool_calls=%s elapsed=%.1fs",
        job_id, turns,
        usage.get("input_tokens", 0), usage.get("output_tokens", 0),
        usage.get("cache_read", 0), usage.get("cache_creation", 0),
        tool_calls, elapsed_seconds,
    )


def _turns(messages: list) -> int:
    """Model turns, counted the way the CLI reports `num_turns`."""
    return sum(1 for m in messages if getattr(m, "type", "") == "ai")


def _text_of(content) -> str:
    """응답 content 를 문자열로. block 목록으로 오는 경우가 있다."""
    if isinstance(content, str):
        return content
    parts = []
    for block in content or []:
        if isinstance(block, str):
            parts.append(block)
        elif isinstance(block, dict) and block.get("type") == "text":
            parts.append(block.get("text", ""))
    return "".join(parts)
