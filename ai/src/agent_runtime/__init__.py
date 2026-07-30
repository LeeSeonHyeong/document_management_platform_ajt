from .base import RunResult, Runtime, ingest_instruction, spawns_mcp_server
from .claude_code import ClaudeCodeRuntime

__all__ = ["Runtime", "RunResult", "ingest_instruction", "spawns_mcp_server",
           "ClaudeCodeRuntime", "load_runtime"]


# Exact names, not aliases: `opus` and `sonnet` resolve to whatever the CLI calls
# latest today, which silently breaks a comparison between two runs.
DEFAULT_CLI_MODEL = "claude-opus-4-6"
DEFAULT_DEEPAGENTS_MODEL = "anthropic:claude-opus-4-6"


def load_runtime(name: str, model: str | None = None, *,
                 effort: str | None = None) -> Runtime:
    """Resolve a runtime by name. DeepAgents is imported lazily because it is an
    optional dependency — installing it must not be required to run the CLI path.

    `effort` 는 `claude-code` 전용이다. 안 넘기면 CLI 기본값으로 돈다 — 기본값을 여기서
    정하지 않는 이유는 `claude_code.py` 의 생성자 docstring 에 있다.

    `deepagents` 에는 대응 개념이 없어 넘기면 거부한다. 조용히 무시하면 `manifest.json` 에
    `effort: "low"` 가 적히는데 실행은 그것과 무관해지고, 그 기록이 거짓이 된다.
    """
    if name == "claude-code":
        return ClaudeCodeRuntime(model=model or DEFAULT_CLI_MODEL, effort=effort)
    if name == "deepagents":
        if effort is not None:
            raise ValueError("deepagents 런타임에는 effort 개념이 없다 — 넘기지 않는다")
        from .deep_agents import DeepAgentsRuntime

        return DeepAgentsRuntime(model=model or DEFAULT_DEEPAGENTS_MODEL)
    raise ValueError(f"알 수 없는 런타임: {name}")
