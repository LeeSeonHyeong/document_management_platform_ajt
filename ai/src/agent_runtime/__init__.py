from .base import RunResult, Runtime, ingest_instruction, runs_tools_in_process
from .claude_code import ClaudeCodeRuntime

__all__ = ["Runtime", "RunResult", "ingest_instruction", "runs_tools_in_process",
           "ClaudeCodeRuntime", "load_runtime"]


# Exact names, not aliases: `opus` and `sonnet` resolve to whatever the CLI calls
# latest today, which silently breaks a comparison between two runs.
#
# Sonnet, not Opus (2026-08-04): a caller that forgets `AI_MODEL`/`--model` used to
# fall back to Opus silently — the most expensive tier, with no warning. Sonnet is
# the tier this deployment actually runs on (`src/.env`'s `AI_MODEL`), so an unset
# override now costs the same as the configured default instead of silently costing
# more.
DEFAULT_CLI_MODEL = "claude-sonnet-4-6"
DEFAULT_DEEPAGENTS_MODEL = "anthropic:claude-sonnet-4-6"


def load_runtime(name: str, model: str | None = None, *,
                 effort: str | None = None,
                 fast_model: str | None = None, quality_model: str | None = None,
                 credentials: dict[str, tuple[str, str]] | None = None) -> Runtime:
    """Resolve a runtime by name. DeepAgents is imported lazily because it is an
    optional dependency — installing it must not be required to run the CLI path.

    `effort` 는 `claude-code` 전용이다. 안 넘기면 CLI 기본값으로 돈다 — 기본값을 여기서
    정하지 않는 이유는 `claude_code.py` 의 생성자 docstring 에 있다.

    `deepagents` 에는 대응 개념이 없어 넘기면 거부한다. 조용히 무시하면 `manifest.json` 에
    `effort: "low"` 가 적히는데 실행은 그것과 무관해지고, 그 기록이 거짓이 된다.

    `credentials` 는 **프로바이더 이름 → (api_key, base_url)** 표다. 단일 `(api_key,
    base_url)` 쌍이 아니라 표를 받는 이유는 에이전트 모델과 티어 모델(`fast_model`·
    `quality_model`)의 프로바이더가 다를 수 있어서다 — 예: 에이전트는
    `anthropic:claude-opus-4-6`, fast 티어는 `openai:gpt-5.4-mini`. 단일 쌍을 두 경로에
    똑같이 쓰면 Anthropic 키가 OpenAI 클라이언트로 간다. 평범한 `dict` 로 받는 이유는
    `agent_runtime` 이 `wiki_api` 를 임포트하면 안 되기 때문이다 (의존 방향은
    `wiki_api → agent_runtime → wiki_mcp` 단방향).
    """
    if name == "claude-code":
        if credentials:
            raise ValueError(
                "claude-code 런타임은 로그인 세션으로 과금한다 — 모델 API 키를 "
                "넘기지 않는다. 그 키로 도는 줄 알게 된다")
        return ClaudeCodeRuntime(model=model or DEFAULT_CLI_MODEL, effort=effort)
    if name == "deepagents":
        if effort is not None:
            raise ValueError("deepagents 런타임에는 effort 개념이 없다 — 넘기지 않는다")
        from .deep_agents import DeepAgentsRuntime

        return DeepAgentsRuntime(model=model or DEFAULT_DEEPAGENTS_MODEL,
                                 fast_model=fast_model, quality_model=quality_model,
                                 credentials=credentials or {})
    raise ValueError(f"알 수 없는 런타임: {name}")
