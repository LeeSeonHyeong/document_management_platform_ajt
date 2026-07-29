"""Derived from lucas-llmwiki `mcp/local_server.py`.

Local stdio MCP server. One process = one scope = one job.

Two properties come from that:

  * **Scope isolation is structural.** The process holds one scope's live
    directory, so no tool can reach another scope's pages. That is the local
    stand-in for the permission filter Spring applies, where an invisible page
    must 404 rather than 403 (FR-ACL-006, NFR-SEC-003).
  * **Writes cannot reach the served files.** Every write lands in
    `work/{jobId}/output` and the backend decides what is committed
    (DR-007/008, API convention 10.2).

Usage:
    uv run python -m wiki_mcp.local_server --root ./data --scope ALL --job-id 9001
"""

import argparse
import asyncio
import logging
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("llmwiki.mcp")


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="LLM Wiki MCP 서버 (로컬 stdio)")
    parser.add_argument("root", nargs="?", default=".", help="저장소 루트 (/data/ajt에 해당)")
    parser.add_argument("--root", dest="root_flag", default=None, help="저장소 루트")
    parser.add_argument("--scope", default="ALL", help="이 서버가 다루는 scope_key (ALL, D1, D1-D2)")
    parser.add_argument("--job-id", default=None, help="작업 ID. 없으면 읽기 전용 세션이다")
    parser.add_argument(
        "--tool-log", default=None,
        help="툴 호출을 한 줄씩 기록할 파일. 인자로 받는 이유는 telemetry.py 참고",
    )
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    root = str(Path(args.root_flag or args.root).resolve())

    from mcp.server.fastmcp import FastMCP

    from wiki_mcp.telemetry import count_tool_calls
    from wiki_mcp.tools import register
    from wiki_mcp.vaultfs import LocalVaultFS

    loop = asyncio.new_event_loop()
    try:
        loop.run_until_complete(LocalVaultFS.open(root, args.scope, args.job_id))
    finally:
        loop.close()

    mcp = FastMCP(
        name="LLM Wiki",
        instructions=(
            "사내 위키 서버다. 원본문서를 읽고 위키 페이지를 만들고 고칠 수 있다. "
            "`guide` 도구를 가장 먼저 불러 구조와 작업 순서를 확인한다."
        ),
    )

    # Scope and job come from the process, not the caller. A tool cannot ask for a
    # different scope, which is what makes the isolation hold under any permission
    # mode the client happens to run in.
    def _get_scope_key(ctx) -> str:
        return args.scope

    register(mcp, _get_scope_key, lambda key: LocalVaultFS(key, args.job_id))
    count_tool_calls(mcp, args.tool_log)

    logger.info("MCP 서버 시작 — root %s, scope %s, job %s", root, args.scope, args.job_id)
    asyncio.run(mcp.run_stdio_async())


if __name__ == "__main__":
    main()
