"""Replaces lucas-llmwiki `mcp/tools/list.py`.

Upstream let the agent create and rename knowledge bases. Here it cannot: a scope
is a visibility partition derived from departments (DR-018, `wiki_scope`), and
only the backend creates one. What is left is enumeration, so the agent learns
which `scope` value to pass.
"""

from mcp.server.fastmcp import Context, FastMCP


def register(mcp: FastMCP, get_scope_key, fs_factory) -> None:

    @mcp.tool(
        name="list_scopes",
        description=(
            "이 서버가 다루는 공개 범위를 나열한다.\n\n"
            "다른 모든 도구가 `scope` 인자를 받는다 — 유효한 값을 모를 때 이걸 먼저 부른다. "
            "범위는 부서 조합에서 나온 공개 단위이고 에이전트가 만들 수 없다."
        ),
    )
    async def list_scopes(ctx: Context) -> str:
        fs = fs_factory(get_scope_key(ctx))
        scopes = await fs.list_scopes()
        if not scopes:
            return "범위가 없다. 백엔드가 먼저 범위를 만들어야 한다."
        return "\n".join(
            f"- **{s['scope_key']}** "
            f"({'전사' if s['visibility_type'] == 'ALL' else '부서 한정'}) — "
            f"원본문서 {s.get('source_count', 0)}건, 위키 {s.get('page_count', 0)}페이지, "
            f"목차 `{s['index_path']}`"
            for s in scopes
        )
