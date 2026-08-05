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

# glob 한 번으로 지울 수 있는 상한. 실제 병합·정리가 몇 장을 건드리는지에서 왔다 —
# 실측 위키 작업이 한 번에 다루는 페이지는 1~3장이고(`experiments/INDEX.md`), 정리 작업도
# 열 장을 넘지 않는다. 넘으면 의도가 아니라 오타일 가능성이 크다.
MAX_GLOB_DELETE = 5


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
        if len(targets) > MAX_GLOB_DELETE:
            # `MATCH_ALL` 만 막던 것으로는 부족했다. `pages/*` 는 그 집합에 없어서
            # 통과했고, 원본문서와 `index.md` 만 거부되므로 그 한 번이 범위의 위키를
            # 전부 묘비 처리했다 — 되돌릴 수단이 없는 도구다(모듈 docstring).
            #
            # 개수로 막는다. 무엇이 지워질지 세어 보이고 주소를 명시하게 하면, 의도한
            # 대량 삭제는 그대로 되면서 오타 한 번으로 범위가 비는 일은 없다.
            return (f"오류: `{pattern}`이 {len(targets)}건에 맞는다 — 한 번에 지울 수 있는 "
                    f"상한은 {MAX_GLOB_DELETE}건이다. 지울 주소를 하나씩 준다.\n\n"
                    + "\n".join(f"  {d['address']} — {label(d)}"
                                for d in targets[:MAX_GLOB_DELETE + 5])
                    + (f"\n  ... {len(targets) - MAX_GLOB_DELETE - 5}건 더"
                       if len(targets) > MAX_GLOB_DELETE + 5 else ""))

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
            '주소 하나: `path="pages/a3f2c1d4.md"`\n\n'
            "제약:\n"
            "- 원본문서는 지울 수 없다 — 백엔드 소유다\n"
            "- `index.md`는 구조 페이지라 지울 수 없다 (`edit`으로 내용을 고친다)\n\n"
            f"glob(`pages/a*`)도 되지만 **한 번에 {MAX_GLOB_DELETE}건까지**다. 더 맞으면 "
            "무엇이 맞았는지 보여주고 거부한다 — 그때는 주소를 하나씩 준다.\n\n"
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
