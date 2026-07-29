"""Read-only graph endpoint for the viewer.

Ported from lucas-llmwiki `api/services/graph.py::get_graph_local` and
`api/routes/local_graph.py`, on starlette rather than FastAPI because starlette
and uvicorn are already installed and this needs no new dependency.

Two things differ from upstream, both because our graph carries more:

  * Upstream inferred a node's kind from its path (`concepts/`, `entities/`).
    Category is a real field here (DR-019 keeps it out of the path), so it is
    returned as-is and the viewer colours by it.
  * Upstream had one edge per (source, target, type). Ours is per footnote and
    carries `location` and `quote`, so the viewer can show the cited sentence
    itself — which is how a reader checks a claim without opening the source.

Only the live layer is exposed. A work layer is unverified by definition
(DR-007), and showing it would draw pages that may never be committed.

    uv run python -m wiki_mcp.graph_api --root ./data --scope ALL --port 8000
"""

from __future__ import annotations

import argparse
import asyncio
import logging
from pathlib import Path

from wiki_mcp.vaultfs import VaultError
from wiki_mcp.vaultfs.local import LocalVaultFS, _rows_to_dicts

logger = logging.getLogger("llmwiki.graph")


async def build_graph(root: Path, scope_key: str) -> dict:
    """Assemble `{nodes, edges}` for one scope from the live layer."""
    scope_id = await LocalVaultFS.open_readonly(root, scope_key)
    try:
        fs = LocalVaultFS(scope_key)
        db = LocalVaultFS._conn()

        cursor = await db.execute(
            "SELECT address, kind, title, category, tags, wiki_id, source_id, "
            "       original_file_name, length(content) AS chars "
            "FROM documents WHERE scope_id = ? AND layer = 'live' AND deleted = 0 "
            "ORDER BY kind, address",
            (scope_id,),
        )
        rows = _rows_to_dicts(cursor, await cursor.fetchall())

        cursor = await db.execute(
            "SELECT source_address, target_address, reference_type, footnote_label, "
            "       location, quote, page "
            "FROM document_references WHERE scope_id = ? "
            "ORDER BY reference_type, source_address, footnote_label",
            (scope_id,),
        )
        edge_rows = _rows_to_dicts(cursor, await cursor.fetchall())

        known = {r["address"] for r in rows}
        edges = [
            {
                "source": e["source_address"],
                "target": e["target_address"],
                "type": e["reference_type"],
                "footnote": e["footnote_label"],
                "location": e["location"],
                "quote": e["quote"],
                "page": e["page"],
            }
            # A dangling edge would make the force simulation invent a node.
            for e in edge_rows
            if e["source_address"] in known and e["target_address"] in known
        ]

        # Same judgement lint's `orphan-page` makes: nothing points here, so the
        # table of contents cannot reach it.
        incoming = {e["target"] for e in edges}
        citation_counts: dict[str, int] = {}
        for e in edges:
            if e["type"] == "cites":
                citation_counts[e["source"]] = citation_counts.get(e["source"], 0) + 1

        nodes = [
            {
                "id": r["address"],
                "kind": r["kind"],
                "title": r["title"] or r["original_file_name"] or r["address"],
                "category": r["category"],
                "tags": r["tags"] or [],
                "chars": r["chars"] or 0,
                "citations": citation_counts.get(r["address"], 0),
                "wikiId": r["wiki_id"],
                "sourceId": r["source_id"],
                "fileName": r["original_file_name"],
                "orphan": r["kind"] == "page" and r["address"] not in incoming,
            }
            for r in rows
        ]
        return {
            "scope": scope_key,
            "indexPath": fs.relative_path("index.md"),
            "nodes": nodes,
            "edges": edges,
        }
    finally:
        await LocalVaultFS.close()


def create_app(root: Path, scope_key: str):
    from starlette.applications import Starlette
    from starlette.middleware import Middleware
    from starlette.middleware.cors import CORSMiddleware
    from starlette.responses import JSONResponse
    from starlette.routing import Route

    async def graph(request):
        # Reopened per request rather than held: the index changes under us while
        # a run is going, and a viewer refresh should show the current state.
        try:
            data = await build_graph(root, request.query_params.get("scope") or scope_key)
        except VaultError as exc:
            return JSONResponse({"error": str(exc)}, status_code=404)
        return JSONResponse(data)

    async def scopes(request):
        scope_id = await LocalVaultFS.open_readonly(root, scope_key)  # noqa: F841
        try:
            return JSONResponse(await LocalVaultFS(scope_key).list_scopes())
        finally:
            await LocalVaultFS.close()

    return Starlette(
        routes=[Route("/graph", graph), Route("/scopes", scopes)],
        # The viewer runs on vite's dev port; without this the browser blocks it.
        middleware=[Middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["GET"])],
    )


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    parser = argparse.ArgumentParser(description="그래프 뷰어용 읽기 전용 API")
    parser.add_argument("--root", required=True, help="저장소 루트 (/data/ajt 에 해당)")
    parser.add_argument("--scope", default="ALL", help="scope_key")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--dump", default=None, help="서버를 띄우지 않고 JSON만 저장한다")
    args = parser.parse_args()

    root = Path(args.root).resolve()

    if args.dump:
        import json

        data = asyncio.run(build_graph(root, args.scope))
        Path(args.dump).parent.mkdir(parents=True, exist_ok=True)
        Path(args.dump).write_text(json.dumps(data, ensure_ascii=False, indent=2),
                                   encoding="utf-8")
        print(f"노드 {len(data['nodes'])} / 간선 {len(data['edges'])} → {args.dump}")
        return

    import uvicorn

    logger.info("그래프 API — root %s, scope %s, http://localhost:%d/graph",
                root, args.scope, args.port)
    uvicorn.run(create_app(root, args.scope), host="127.0.0.1", port=args.port,
                log_level="warning")


if __name__ == "__main__":
    main()
