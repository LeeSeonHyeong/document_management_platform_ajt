"""Catalogue of measurements.

    uv run python experiments.py            모든 실험을 한 표로
    uv run python experiments.py <slug>     하나를 자세히

Metrics come from `compare.inspect_root` and `compare._totals` rather than being
recomputed here — two definitions of "조각" or "각주" that drift apart is worse than
no second view at all. `compare.py` stays the A/B tool; this is the index.

Disposable, like `backend_sim.py`.
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
# 이관 주석: 옛 트리에서 ROOT 는 ai-server/ 였다. 지금은 experiments/ 이고
# 패키지는 ../src/ 에 있다 (wiki_mcp·agent_runtime·wiki_api).
sys.path.insert(0, str(ROOT.parent / "src"))

from compare import PERF_LIMIT_SECONDS, _totals, inspect_root  # noqa: E402
from experiment import ARCHIVE, Experiment, all_slugs  # noqa: E402
from wiki_mcp.vaultfs import VaultError  # noqa: E402


def _state(experiment: Experiment) -> dict:
    """Wiki metrics, or why they are unavailable.

    An experiment whose index predates a schema change cannot be opened, and that
    has to show up as a note in the table rather than a crash — the whole point of
    the catalogue is to still see what exists.
    """
    try:
        return asyncio.run(inspect_root(experiment.data))
    except VaultError as exc:
        return {"error": str(exc)}
    except Exception as exc:  # sqlite raises its own types for a stale schema
        return {"error": f"{type(exc).__name__}: 열 수 없다 (스키마가 다를 수 있다)"}


def _rows() -> list[tuple[Experiment, dict, dict, dict]]:
    """위키 변환 측정만 표에 올린다.

    `data/` 가 없는 디렉터리는 위키 실험이 아니다 — 측정 입력(`corpus/`)이거나 위키를
    만들지 않는 실험(챗봇)이다. 그것들을 "index가 없다"로 표시하면 **진짜 깨진 위키
    실험이 같은 문장에 섞여** 안 보인다. `data/` 는 있는데 색인이 없는 것은 그대로
    보고한다 — 그건 실제 고장이다.
    """
    out = []
    for slug in all_slugs():
        experiment = Experiment(slug)
        if not experiment.data.exists():
            continue
        report = experiment.report()
        totals = _totals(report) if report.get("documents") else {}
        out.append((experiment, experiment.manifest(), totals, _state(experiment)))
    return out


def table() -> None:
    rows = _rows()
    if not rows:
        print("실험이 없다. backend_sim.py --experiment <slug> 로 만든다.")
        return

    # `문서` 는 투입 건수다. 실패가 있으면 `12!3` 처럼 뒤에 붙인다 — 안 붙이면 12건 투입에
    # 9건만 반영된 런을 12건 완주한 런과 나란히 읽어, 품질이 낮다고 잘못 판정한다.
    header = (f"{'실험':<32} {'모델':<17} {'문서':>6} {'페이지':>6} {'카':>3} "
              f"{'링크':>5} {'각주':>10} {'조각':>4} {'lint':>7} {'시간':>7} {'비용':>7}  guide")
    print(header)
    print("-" * len(header))
    for experiment, manifest, totals, state in rows:
        model = (manifest.get("model") or totals.get("model") or "?").replace("claude-", "")
        if state.get("error"):
            print(f"{experiment.slug:<32} {model:<17} {'—':>4} {state['error']}")
            continue
        docs = totals.get("docs", 0)
        failed = totals.get("failed", 0)
        docs_cell = f"{docs}!{failed}" if failed else str(docs)
        seconds = totals.get("seconds", 0)
        lint = f"{state['lintErrors']}e/{state['lintWarnings']}w"
        quoted = f"{state['citations']}({state['citationsWithQuote']})"
        guide = manifest.get("code", {}).get("guideSha", "—")
        guide = "사후기록" if guide.startswith("unknown") else guide
        print(f"{experiment.slug:<32} {model:<17} {docs_cell:>6} {state['pages']:>6} "
              f"{len(state['categories']):>3} {state['pageToPageLinks']:>5} {quoted:>10} "
              f"{state['fragments']:>4} {lint:>7} "
              f"{int(seconds)//60:>4}:{int(seconds)%60:02d} "
              f"${totals.get('costUsd', 0):>6.2f}  {guide}")

    print("\n각주 열은 전체(원문 인용한 것). 카 = 카테고리 수.")
    failed = [(e.slug, t["failed"], t["docs"]) for e, _, t, _ in rows if t.get("failed")]
    if failed:
        print("**투입 건수 뒤의 `!n` 은 실패 건수다** — 페이지·각주·링크가 그만큼 적게 나온다:")
        for slug, n, docs in failed:
            print(f"  {slug}: {docs}건 중 {n}건 실패 → {docs - n}건만 반영")
    slow = [(e.slug, t) for e, _, t, _ in rows if t.get("overPerfLimit")]
    if slow:
        print(f"NFR-PERF-002({PERF_LIMIT_SECONDS // 60}분) 초과: "
              + ", ".join(f"{slug} {t['overPerfLimit']}건" for slug, t in slow))
    if ARCHIVE.is_dir() and any(ARCHIVE.glob("*.json")):
        print(f"\n{ARCHIVE.name}/ 에 리팩토링 전 리포트가 있다 — 지표 대조 불가.")


def detail(slug: str) -> None:
    experiment = Experiment(slug)
    if not experiment.exists():
        raise SystemExit(f"없는 실험: {slug}\n있는 것: {', '.join(all_slugs())}")

    manifest = experiment.manifest()
    report = experiment.report()
    print(f"# {slug}\n")
    if manifest:
        code = manifest.get("code", {})
        print(f"런타임   {manifest.get('runtime')} · {manifest.get('model')}")
        print(f"시각     {manifest.get('startedAt')} → {manifest.get('finishedAt', '?')}")
        print(f"코퍼스   {len(manifest.get('corpus', []))}건")
        print(f"목적     {manifest.get('purpose')}")
        print(f"코드     guide {code.get('guideSha')} ({code.get('guideChars')}자) · "
              f"schema {code.get('schemaSha')} · 툴 {len(code.get('tools', []))}개")
    else:
        print("manifest 없음 — 이 실험은 --experiment 도입 전에 만들어졌다.")

    if report.get("documents"):
        totals = _totals(report)
        print(f"\n문서 {totals['docs']}건 · {int(totals['seconds'])//60}분 · "
              f"${totals['costUsd']} · 턴 {totals['turns']} · 툴 {totals['toolTotal']}회")
        print(f"반영 {totals['changes']}")
        print(f"최장 문서 {int(totals['slowestDoc'])//60}:{int(totals['slowestDoc'])%60:02d} · "
              f"10분 초과 {totals['overPerfLimit']}건")
        print("\n순번 문서                      결과        소요   툴  반영")
        for d in report["documents"]:
            print(f"{d['seq']:>3}  {d['document']:<24} {d.get('outcome', '?'):<10} "
                  f"{int(d['elapsedSeconds'])//60}:{int(d['elapsedSeconds'])%60:02d} "
                  f"{sum(d['toolCalls'].values()):>4} {len(d.get('committed') or []):>4}"
                  + (f"  {d['error']}" if d.get("error") else ""))

    state = _state(experiment)
    if state.get("error"):
        print(f"\n위키를 열 수 없다: {state['error']}")
    else:
        print(f"\n페이지 {state['pages']} · 원본문서 {state['sources']} · "
              f"카테고리 {len(state['categories'])} {state['categories']}")
        print(f"본문 {state['chars']:,}자 · 조각 {state['fragments']} · "
              f"페이지간 링크 {state['pageToPageLinks']}")
        print(f"각주 {state['citations']} (위치 {state['citationsWithLocation']}, "
              f"원문 인용 {state['citationsWithQuote']})")
        print(f"표 {state['withTable']} · mermaid {state['withMermaid']} · "
              f"lint error {state['lintErrors']} / warn {state['lintWarnings']}")

    if experiment.notes_path.is_file():
        print(f"\n--- {experiment.notes_path.name} ---")
        print(experiment.notes_path.read_text(encoding="utf-8").rstrip())


def main() -> None:
    if len(sys.argv) > 1:
        detail(sys.argv[1])
    else:
        table()


if __name__ == "__main__":
    main()
