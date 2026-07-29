from .base import RunResult, Runtime, ingest_instruction
from .claude_code import ClaudeCodeRuntime

__all__ = ["Runtime", "RunResult", "ingest_instruction", "ClaudeCodeRuntime", "load_runtime"]


# Exact names, not aliases: `opus` and `sonnet` resolve to whatever the CLI calls
# latest today, which silently breaks a comparison between two runs.
DEFAULT_CLI_MODEL = "claude-opus-4-6"
DEFAULT_DEEPAGENTS_MODEL = "anthropic:claude-opus-4-6"


def load_runtime(name: str, model: str | None = None) -> Runtime:
    """Resolve a runtime by name. DeepAgents is imported lazily because it is an
    optional dependency — installing it must not be required to run the CLI path."""
    if name == "claude-code":
        return ClaudeCodeRuntime(model=model or DEFAULT_CLI_MODEL)
    if name == "deepagents":
        from .deep_agents import DeepAgentsRuntime

        return DeepAgentsRuntime(model=model or DEFAULT_DEEPAGENTS_MODEL)
    raise ValueError(f"알 수 없는 런타임: {name}")
