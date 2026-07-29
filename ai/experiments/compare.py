"""Compare two runs. Disposable, like backend_sim.py.

What it exists to answer: when output quality differs between two runs, was it
the model or the guide? Tool counts separate those. "Never called search" is a
guide problem; "called search, read the page, wrote a duplicate anyway" is a
model problem.

    uv run python compare.py 2026-07-27-opus46-2docs 2026-07-27-opus46-12docs
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
# 이관 주석: 옛 트리에서 ROOT 는 ai-server/ 였다. 지금은 experiments/ 이고
# 패키지는 ../src/ 에 있다 (wiki_mcp·agent_runtime·wiki_api).
sys.path.insert(0, str(ROOT.parent / "src"))

from experiment import resolve_root  # noqa: E402
from wiki_mcp.tools.lint import LintHandler  # noqa: E402
from wiki_mcp.tools.references import parse_citation  # noqa: E402
from wiki_mcp.vaultfs import LocalVaultFS  # noqa: E402

_FOOTNOTE_DEF_RE = re.compile(r"^\[\^([^\]]+)\]:\s*(.+)$", re.MULTILINE)
_WIKI_LINK_RE = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
FRAGMENT_CHARS = 1500


async def inspect_root(root: Path, scope_key: str = "ALL") -> dict:
    """Everything measurable about a finished wiki, read from the index.

    Read-only, so this can be pointed at a run that is still going."""
    await LocalVaultFS.open_readonly(root, scope_key)
    try:
        fs = LocalVaultFS(scope_key)
        scope = await fs.resolve_scope(scope_key)
        docs = await fs.list_documents(scope["id"], with_content=True)

        pages = [d for d in docs if d["kind"] == "page"]
        sources = [d for d in docs if d["kind"] == "source"]

        page_to_page = 0
        citations = 0
        located_citations = 0
        quoted_citations = 0
        with_table = 0
        with_mermaid = 0
        for page in pages:
            content = page.get("content") or ""
            # A link out of the body to another page. Zero of these means the
            # wiki is a pile of pages rather than a graph.
            for href in _WIKI_LINK_RE.findall(content):
                if not href.startswith(("http", "#", "mailto:", "data:")):
                    page_to_page += 1
            for _fid, raw in _FOOTNOTE_DEF_RE.findall(content):
                citations += 1
                # Use the real parser: a comma test called `file.md — "quote"`
                # location-less and understated opus by three citations.
                parsed = parse_citation(raw)
                if parsed["location"] or parsed["page"] is not None:
                    located_citations += 1
                if parsed["quote"]:
                    quoted_citations += 1
            with_table += 1 if "\n|" in content else 0
            with_mermaid += 1 if "```mermaid" in content else 0

        lint = await LintHandler(fs, scope).run()
        # Errors and warnings separately: a run with zero errors is finished, a
        # warning is maintenance debt. Collapsing both into one boolean made a
        # clean opus run look like a failure.
        errors = len(re.findall(r"^- \[", lint.split("**Warnings**")[0], re.MULTILINE))
        warnings = len(re.findall(r"^- \[", lint.split("**Warnings**")[-1], re.MULTILINE)) \
            if "**Warnings**" in lint else 0
        return {
            "sources": len(sources),
            "pages": len(pages),
            "categories": sorted({p.get("category") or "(없음)" for p in pages}),
            "titles": sorted(p.get("title") or p["address"] for p in pages),
            "chars": sum(len(p.get("content") or "") for p in pages),
            "fragments": sum(1 for p in pages if len(p.get("content") or "") < FRAGMENT_CHARS),
            "pageToPageLinks": page_to_page,
            "citations": citations,
            "citationsWithLocation": located_citations,
            "citationsWithQuote": quoted_citations,
            "withTable": with_table,
            "withMermaid": with_mermaid,
            "lintErrors": 0 if "lint 통과" in lint else errors,
            "lintWarnings": 0 if "lint 통과" in lint else warnings,
            "lint": lint,
        }
    finally:
        await LocalVaultFS.close()


# NFR-PERF-002: one document over ten minutes must be failed. Counting the
# breaches is the point — the 12-document run put two documents past it.
PERF_LIMIT_SECONDS = 600


# 옛 리포트가 멱등 판정을 error 에 넣은 흔적. 실패로 세지 않는다.
_NOT_FAILURES = ("변경 없음",)


def _is_failure(document: dict) -> bool:
    error = document.get("error")
    if not error:
        return False
    return not any(mark in str(error) for mark in _NOT_FAILURES)


def _totals(report: dict) -> dict:
    docs = report["documents"]
    tools: dict[str, int] = {}
    changes: dict[str, int] = {}
    for d in docs:
        for name, n in (d.get("toolCalls") or {}).items():
            tools[name] = tools.get(name, 0) + n
        for change in d.get("committed") or []:
            changes[change["type"]] = changes.get(change["type"], 0) + 1
    elapsed = [d["elapsedSeconds"] for d in docs] or [0]
    return {
        "model": report.get("model") or "?",
        "docs": len(docs),
        # **"변경 없음"은 실패가 아니다.** 옛 backend_sim 은 멱등 판정을 error 필드에 넣었고
        # (`2026-07-27-reingest`), 지금 판본은 unchanged 로 따로 센다. 구분하지 않으면
        # 멱등성 통과가 실패로 집계돼 표가 거짓 신호를 낸다.
        "failed": sum(1 for d in docs if _is_failure(d)),
        "seconds": round(sum(elapsed), 1),
        "slowestDoc": round(max(elapsed), 1),
        "overPerfLimit": sum(1 for s in elapsed if s > PERF_LIMIT_SECONDS),
        "costUsd": round(sum(d.get("costUsd", 0) for d in docs), 2),
        "turns": sum(d.get("turns", 0) for d in docs),
        "tools": tools,
        "toolTotal": sum(tools.values()),
        "changes": changes,
    }


def _row(label: str, a, b) -> str:
    mark = "  " if a == b else " *"
    return f"{label:24s} {str(a):>28s} {str(b):>28s}{mark}"


def main() -> None:
    parser = argparse.ArgumentParser(description="두 run 비교")
    parser.add_argument("a", help="실험 slug 또는 report.json 경로")
    parser.add_argument("b", help="실험 slug 또는 report.json 경로")
    args = parser.parse_args()

    from experiment import Experiment

    def _resolve(ref: str) -> tuple[str, dict, Path | None]:
        """A slug or a bare report path — the slug also gives us its workspace."""
        experiment = Experiment(ref)
        if experiment.exists():
            return ref, experiment.report(), experiment.data
        return Path(ref).stem, json.loads(Path(ref).read_text(encoding="utf-8")), None

    resolved = [_resolve(args.a), _resolve(args.b)]
    names = [r[0] for r in resolved]
    reports = [r[1] for r in resolved]
    roots = [r[2] for r in resolved]
    runs = [_totals(r) for r in reports]

    print(f"{'':24s} {names[0]:>28s} {names[1]:>28s}")
    print("-" * 84)
    for label, key in (("모델", "model"), ("문서", "docs"), ("실패", "failed"),
                       ("소요(초)", "seconds"), ("최장 문서(초)", "slowestDoc"),
                       ("10분 초과", "overPerfLimit"), ("비용($)", "costUsd"),
                       ("턴", "turns"), ("툴 호출 합", "toolTotal")):
        print(_row(label, runs[0][key], runs[1][key]))

    for tool in sorted(set(runs[0]["tools"]) | set(runs[1]["tools"])):
        print(_row(f"  {tool}", runs[0]["tools"].get(tool, 0), runs[1]["tools"].get(tool, 0)))

    # create / update / merge / remove — whether consolidation actually happened.
    print()
    for kind in ("create", "update", "merge", "remove"):
        print(_row(f"반영 {kind}", runs[0]["changes"].get(kind, 0),
                   runs[1]["changes"].get(kind, 0)))

    if not all(roots):
        print("\n(리포트만 비교했다 — 위키 지표를 보려면 실험 slug 를 넘긴다)")
        return

    print()
    states = [asyncio.run(inspect_root(r)) for r in roots]
    print("-" * 84)
    for label, key in (("원본문서", "sources"), ("위키 페이지", "pages"),
                       (f"조각(<{FRAGMENT_CHARS}자)", "fragments"), ("본문 합(자)", "chars"),
                       ("페이지간 링크", "pageToPageLinks"), ("각주", "citations"),
                       ("위치 있는 각주", "citationsWithLocation"),
                       ("원문 인용한 각주", "citationsWithQuote"),
                       ("표 포함", "withTable"), ("mermaid 포함", "withMermaid"),
                       ("lint error", "lintErrors"), ("lint warn", "lintWarnings")):
        print(_row(label, states[0][key], states[1][key]))

    for name, state in zip(names, states):
        print(f"\n[{name}] 카테고리 {state['categories']}")
        for title in state["titles"]:
            print(f"    {title}")
        if state["lintErrors"] or state["lintWarnings"]:
            print(f"    {state['lint'][:400]}")

    shared = set(states[0]["titles"]) & set(states[1]["titles"])
    union = set(states[0]["titles"]) | set(states[1]["titles"])
    if union:
        # Same documents, same order — a low number means the model, not the
        # input, decided the page boundaries.
        print(f"\n제목 일치(Jaccard): {len(shared) / len(union):.3f}  공통 {sorted(shared)}")


if __name__ == "__main__":
    main()
