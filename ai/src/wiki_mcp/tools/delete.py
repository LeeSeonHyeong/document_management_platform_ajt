"""Remove wiki pages. From lucas-llmwiki `mcp/tools/delete.py`.

Sources are not deletable — the backend owns uploads and the store refuses a
source address outright. `index.md` is structural and refuses too.

Removal is hard to undo (FR-WIKI-006 provides no restore, and the only repair
channel is the review chat), so this asks the agent to check the reference graph
first and points it at `edit` for anything merely out of date.
"""

from __future__ import annotations

from mcp.server.fastmcp import Context, FastMCP

from wiki_mcp.vaultfs import VaultError, VaultFS

from .helpers import MATCH_ALL, glob_match, label, normalize_address


class DeleteHandler:
    def __init__(self, fs: VaultFS, scope: dict):
        self.fs = fs
        self.scope_id = str(scope["id"])
        self.scope_key = scope["scope_key"]

    async def delete(self, pattern: str) -> str:
        if not pattern or pattern in MATCH_ALL:
            return "오류: 전체 삭제는 거부한다. 주소를 더 좁힌다."

        targets = await self._find(pattern)
        if not targets:
            return f"`{pattern}`에 해당하는 것이 {self.scope_key} 범위에 없다."

        removed, refused = [], []
        for doc in targets:
            try:
                if await self.fs.remove(self.scope_id, doc["address"]):
                    removed.append(doc)
            except VaultError as exc:
                refused.append((doc, str(exc)))

        if not removed:
            return "\n".join(f"`{d['address']}` 거부 — {why}" for d, why in refused)

        lines = [f"{len(removed)}건 제거:\n"]
        lines.extend(f"  {d['address']} — {label(d)}" for d in removed)
        if refused:
            lines.append("")
            lines.extend(f"  거부: `{d['address']}` — {why}" for d, why in refused)
        return "\n".join(lines)

    async def _find(self, pattern: str) -> list[dict]:
        if "*" in pattern or "?" in pattern:
            docs = await self.fs.list_documents(self.scope_id)
            return [d for d in docs if glob_match(d["address"], pattern)]
        doc = await self.fs.get(self.scope_id, normalize_address(pattern))
        return [doc] if doc else []


def register(mcp: FastMCP, get_scope_key, fs_factory) -> None:

    @mcp.tool(
        name="delete",
        description=(
            "위키 페이지를 제거한다.\n\n"
            '주소 하나 또는 glob: `path="pages/a3f2c1d4.md"`, `path="pages/*"`\n\n'
            "제약:\n"
            "- 원본문서는 지울 수 없다 — 백엔드 소유다\n"
            "- `index.md`는 구조 페이지라 지울 수 없다 (`edit`으로 내용을 고친다)\n\n"
            "**되돌릴 수단이 없다.** 내용이 낡았을 뿐이면 `edit`으로 고치고, 같은 주제가 흩어져 "
            "있으면 `merge`를 쓴다. 지우기 전에 `search(mode=\"references\")`로 이 페이지를 "
            "참조하는 곳이 있는지 확인한다."
        ),
    )
    async def delete(ctx: Context, scope: str, path: str) -> str:
        fs = fs_factory(get_scope_key(ctx))
        row = await fs.resolve_scope(scope)
        if not row:
            return f"범위 '{scope}'를 찾을 수 없다."
        try:
            return await DeleteHandler(fs, row).delete(path)
        except VaultError as exc:
            return f"오류: {exc}"
