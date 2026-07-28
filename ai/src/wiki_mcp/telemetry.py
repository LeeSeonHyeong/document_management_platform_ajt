"""Tool-call counting, done in the server rather than the client.

Why here: `claude -p --output-format json` does not report which tools were
called, and DeepAgents reports them in a different shape again. Counting on the
server side is runtime-agnostic and counts what actually arrived, not what a
client claims it sent.

Why it matters: when a page comes out duplicated or thin, "never called `search`"
and "called `search`, read nothing, wrote anyway" need different fixes, and only
the counts tell them apart. The earlier spike could not distinguish the two.

Off unless the server is started with `--tool-log PATH`. One line per call,
appended, so a crashed run still leaves a partial record.

**Not an environment variable.** Claude Code's `mcpServers.env` *replaces* the
child environment rather than extending it, so setting one variable there drops
`PATH` and the `uv` launcher is never found — the server dies and the agent sees
no tools at all, while the run still reports success. Cost one silent ingest that
wrote nothing. A command-line argument has no such failure mode.
"""

from __future__ import annotations

from collections import Counter
from pathlib import Path


def count_tool_calls(mcp, log_path: str | None) -> None:
    """Count every tool call by wrapping the dispatcher, not the tools.

    Wrapping the tool functions themselves does not work. FastMCP builds each
    tool's argument model from its annotations, and the modules use
    `from __future__ import annotations`, so those annotations are strings
    resolved against the *defining function's* globals. A wrapper defined here
    resolves them against this module instead, where names like `Context` and
    `Scope` do not exist. Every tool then fails to register with
    "`lintArguments` is not fully defined" — and the server still starts, serving
    zero tools, so the agent reports success having done nothing. That is how
    this was found, and it cost a silent ingest.

    `ToolManager.call_tool` is below all of that: one function, a stable
    signature, and every call goes through it.
    """
    if not log_path:
        return

    manager = mcp._tool_manager
    original = manager.call_tool

    async def counted(name, arguments, context=None, convert_result=False):
        _record(log_path, name)
        return await original(name, arguments, context=context,
                              convert_result=convert_result)

    manager.call_tool = counted


def _record(log_path: str, name: str) -> None:
    try:
        with open(log_path, "a", encoding="utf-8") as fh:
            fh.write(name + "\n")
    except OSError:
        # Telemetry must never take down a run.
        pass


def read_counts(log_path: str | Path) -> dict[str, int]:
    path = Path(log_path)
    if not path.exists():
        return {}
    return dict(Counter(path.read_text(encoding="utf-8").split()))
