"""창구 어댑터 상한을 정하기 위한 측정. 설계 9.3·9.5.

100장 규모에서 검색 응답 한 건이 몇 행·몇 자인지, 그리고 `read` 한 번이 창구를 몇 번
부르는지 재고 그 값으로 상한을 정한다. 근거 없는 숫자를 코드에 박지 않기 위한 절차다.

    mkdir -p /tmp/ajt-corpus/wiki/ALL
    ln -sfn "$(pwd)/experiments/corpus-ko/pages" /tmp/ajt-corpus/wiki/ALL/pages
    uv run python experiments/measure_federated.py --corpus /tmp/ajt-corpus \
        --scope ALL --limit 20 --drafts 12                    # 링크 없음
    uv run python experiments/measure_federated.py --corpus /tmp/ajt-corpus \
        --scope ALL --link-density 1 --pages 100              # 사슬 링크
    uv run python experiments/measure_federated.py --corpus /tmp/ajt-corpus \
        --scope ALL --link-density 1 --pages 150              # 150장 대조

코퍼스를 옮기지 않고 심볼릭 링크로 게이트웨이가 기대하는 구조를 만든다 — 기존 측정
(`corpus-ko/evaluate_sqlite.py`·INDEX.md)이 원래 경로를 쓴다. `index.md` 는 없으므로
목차 하이드레이션은 빈 채로 돈다.

**국면을 나눠 잰다.** 브리프의 원안은 라이브 검색만 돌리는데, 그것만으로는
`MAX_WORK_SEARCH_ROWS`(작업층 상한)를 정할 수 없고(작업층이 비어 있으면 0행이다)
조회 예산의 실제 소비량도 드러나지 않는다(코퍼스에 내부 위키 링크가 0건이다).

    A. 라이브만       하이드레이션 직후. 창구 응답 크기.
    B. 작업층 무제한  초안을 쓴 뒤 내부 색인만 (`LocalVaultFS.search_chunks`).
                      이 값이 `MAX_WORK_SEARCH_ROWS` 의 근거다.
    C. 합친 응답      `FederatedVaultFS.search_chunks` — 에이전트가 실제로 받는 것.
                      검색한 뒤 상위 결과를 읽어 창구 호출 수까지 센다.
    D. read fan-out   **세션을 새로 연다.** `read` 한 번의 창구 호출 수와, 본문 적재가
                      작업층 검색 결과를 밀어내는지를 잰다. 본문을 당기면 그 뒤 모든
                      검색이 달라지므로 A~C 와 섞지 않는다.
    E. 실제 예산       예산을 켠 채로 read 한 번 + 검색 5회를 해 본다. "정상 작업 한
                      바퀴가 통과한다" 는 예산을 켜고서만 주장할 수 있다.

`--link-density N` 은 각 페이지가 다음 N장을 마크다운 링크로 가리키는 사슬을 만든다.
`_ensure_body` → `_sync_page_references` → `build_edges` → 링크마다 `fs.get` 경로 때문에
read 한 번이 링크 연결 성분 전체를 당기는데, `corpus-ko` 원본에는 내부 위키 링크가
**0건**이라 이 fan-out 이 측정에서 통째로 빠져 있었다.

가짜 게이트웨이(`query_gateway.py`)를 붙인 값이다. 진짜 백엔드가 아니므로 네트워크
지연은 포함되지 않고, 커서 페이지네이션(`nextCursor`)도 항상 `null` 이라 한 바퀴만 돈다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "src"))
sys.path.insert(0, str(ROOT))

import httpx  # noqa: E402

from query_gateway import build_gateway  # noqa: E402
from wiki_mcp.vaultfs import FederatedVaultFS, LocalVaultFS  # noqa: E402
from wiki_mcp.vaultfs import federated as federated_module  # noqa: E402
from wiki_mcp.vaultfs import query_client as query_client_module  # noqa: E402
from wiki_mcp.vaultfs.query_client import (QueryBudgetExceeded,  # noqa: E402
                                           WikiQueryClient)

QUERIES = ["연차", "연차 이월", "재택근무 승인", "장비 반출", "출장 정산"]

# 에이전트 한 작업이 만드는 초안 수. `experiments/` 의 기존 측정(2docs·12docs)에서
# 원본문서 1건이 위키 1~2장을 낳았으므로 12건 규모의 상한에 맞춰 잡는다.
WORK_DRAFTS = 12

# 측정 중에는 예산으로 죽지 않아야 한다 — 실제 소비량을 봐야 값을 정할 수 있다.
UNLIMITED = 10 ** 9

WIKI_LINK_RE = re.compile(r"(?<!!)\[[^\]]*\]\([^)]*\.md[^)]*\)")


def _stats(values: list[int]) -> dict:
    return {"max": max(values), "mean": round(sum(values) / len(values), 1)}


def _client(app, scope_key: str, *, unlimited: bool) -> WikiQueryClient:
    client = WikiQueryClient("http://gateway.test", api_key="key",
                             capability="cap", scope_key=scope_key,
                             scope_version=47,
                             transport=httpx.ASGITransport(app=app))
    if unlimited:
        client.call_budget = UNLIMITED
    return client


def materialize(corpus: Path, scope_key: str, pages: int, link_density: int,
                dest: Path) -> Path:
    """게이트웨이 구조로 코퍼스를 다시 쓴다. 링크 밀도·장수를 바꿀 때만 쓴다.

    원본을 건드리지 않는다. 코퍼스 장수보다 많이 달라고 하면 원본을 돌려 쓰며 파일명에
    회차를 붙인다 — 본문이 같아도 링크 연결 성분 크기에는 영향이 없다.
    """
    sources = sorted((corpus / "wiki" / scope_key / "pages").glob("*.md"))
    if not sources:
        raise SystemExit(f"코퍼스에 페이지가 없다: {corpus}")
    out = dest / "wiki" / scope_key / "pages"
    out.mkdir(parents=True, exist_ok=True)

    names = []
    for index in range(pages):
        source = sources[index % len(sources)]
        round_number = index // len(sources)
        names.append(f"{source.stem}.md" if round_number == 0
                     else f"{source.stem}-r{round_number}.md")

    for index, name in enumerate(names):
        text = sources[index % len(sources)].read_text(encoding="utf-8")
        # 원본에 링크가 섞여 있으면 밀도를 통제할 수 없다. 지우고 새로 붙인다.
        text = WIKI_LINK_RE.sub("", text)
        if link_density:
            targets = [names[(index + step) % pages]
                       for step in range(1, link_density + 1)]
            text += ("\n\n## 관련 문서\n\n"
                     + "\n".join(f"- [{t[:-3]}]({t})" for t in targets) + "\n")
        (out / name).write_text(text, encoding="utf-8")
    return dest


async def _phase_abc(corpus: Path, scope_key: str, limit: int,
                     drafts: int) -> dict:
    """A·B·C. 본문을 당기기 전의 검색 응답 크기와 시나리오 창구 호출 수."""
    app = build_gateway(corpus, scope_key=scope_key, capability="cap",
                        api_key="key")
    client = _client(app, scope_key, unlimited=True)

    # `_allocate` 의 `limit` 초과 분기가 실제로 타는지 센다. 단위 테스트가 덮는
    # 분기지만 통합 경로에서 도달하는지는 미검증이었다.
    overflow = {"calls": 0, "hits": 0}
    original_allocate = federated_module._allocate

    def counting_allocate(work, live, allocate_limit):
        overflow["calls"] += 1
        if len(work) + len(live) > allocate_limit:
            overflow["hits"] += 1
        return original_allocate(work, live, allocate_limit)

    federated_module._allocate = counting_allocate
    try:
        with tempfile.TemporaryDirectory() as work_root:
            scope_id = await FederatedVaultFS.open(Path(work_root), scope_key,
                                                   "job-1", client=client)
            fs = FederatedVaultFS(scope_key, "job-1", client)
            calls_after_hydrate = client.calls

            async def sweep(search) -> tuple[dict, dict]:
                rows_per_query, chars_per_query = [], []
                for query in QUERIES:
                    rows = await search(query)
                    rows_per_query.append(len(rows))
                    chars_per_query.append(
                        sum(len(row.get("content") or "") for row in rows))
                return _stats(rows_per_query), _stats(chars_per_query)

            # A. 라이브만.
            live_rows, live_chars = await sweep(
                lambda q: fs.search_chunks(scope_id, q, limit))

            # 작업층 초안. 코퍼스 페이지 본문을 그대로 쓴다 — 에이전트가 쓰는 위키와
            # 크기·어휘가 같아야 청크 수가 현실적이다.
            sources = sorted((corpus / "wiki" / scope_key / "pages")
                             .glob("*.md"))[:drafts]
            for source in sources:
                address = await fs.allocate_page(scope_id)
                await fs.write(scope_id, address,
                               source.read_text(encoding="utf-8"),
                               title=f"초안 {source.stem}")

            # B. 작업층 무제한 — 상한을 적용하지 않는 내부 색인 구현을 직접 부른다.
            # `LocalVaultFS` 를 명시한다: `SpringVaultFS` 에는 이 메서드가 없어
            # `SpringVaultFS.search_chunks` 로 부르면 MRO 로 우연히 같은 것이 잡힌다.
            async def work_only(query: str) -> list[dict]:
                rows = await LocalVaultFS.search_chunks(
                    fs, scope_id, query, 10_000, None)
                return [row for row in rows if row.get("layer") == "work"]

            work_rows, work_chars = await sweep(work_only)

            # C. 합친 응답 + 상위 결과 읽기. 에이전트가 실제로 하는 일에 가깝게.
            calls_before_agent = client.calls
            rows_stats, chars_stats = await sweep(
                lambda q: fs.search_chunks(scope_id, q, limit))
            for query in QUERIES:
                for row in (await fs.search_chunks(scope_id, query, 5))[:3]:
                    await fs.get(scope_id, row["address"])

            result = {
                "pages": len(app.state.gateway.pages),
                "queries": len(QUERIES),
                "limit": limit,
                "work_drafts": len(sources),
                "hydration_calls": calls_after_hydrate,
                "live_only": {"rows": live_rows, "chars": live_chars},
                "work_uncapped": {"rows": work_rows, "chars": work_chars},
                "combined": {"rows": rows_stats, "chars": chars_stats},
                "agent_phase_calls": client.calls - calls_before_agent,
                "body_fetches": client.body_fetches,
                "query_calls": client.calls,
                "allocate_calls": overflow["calls"],
                "allocate_limit_overflow": overflow["hits"],
            }
            await client.aclose()
            await FederatedVaultFS.close()
    finally:
        federated_module._allocate = original_allocate
    return result


async def _phase_d(corpus: Path, scope_key: str) -> dict:
    """D. `read` 한 번의 fan-out, 그리고 본문 적재가 작업층 검색을 밀어내는지."""
    app = build_gateway(corpus, scope_key=scope_key, capability="cap",
                        api_key="key")
    client = _client(app, scope_key, unlimited=True)
    with tempfile.TemporaryDirectory() as work_root:
        scope_id = await FederatedVaultFS.open(Path(work_root), scope_key,
                                               "job-1", client=client)
        fs = FederatedVaultFS(scope_key, "job-1", client)
        hydration_calls = client.calls
        # 예산 자체는 측정을 위해 무제한으로 덮어썼으므로 원래 값을 다시 계산해 적는다.
        budget = (query_client_module.QUERY_CALL_BUDGET_BASE
                  + query_client_module.QUERY_CALLS_PER_PAGE
                  * client.catalog_pages)

        # 작업층 초안 5장 — 밀려나는지 보려면 라이브와 같은 어휘여야 한다.
        sources = sorted((corpus / "wiki" / scope_key / "pages").glob("*.md"))
        for source in sources[:5]:
            address = await fs.allocate_page(scope_id)
            await fs.write(scope_id, address,
                           source.read_text(encoding="utf-8"),
                           title=f"초안 {source.stem}")

        def work_count(rows) -> int:
            return len([row for row in rows if row.get("origin") == "work"])

        async def index_work_count(limit: int) -> int:
            """내부 색인이 `LIMIT` 안에서 돌려주는 작업층 행 수.

            부모 SQL 은 층을 모른다 (`local.py` 의 `search_chunks`). 라이브 본문이
            색인되면 같은 `LIMIT` 을 라이브가 채워 작업층 행이 사라진다 — 그것을
            어댑터의 `origin` 필터가 아니라 색인 층에서 직접 센다.
            """
            rows = await LocalVaultFS.search_chunks(fs, scope_id, "연차", limit,
                                                    None)
            return len([row for row in rows if row.get("layer") == "work"])

        before = work_count(await fs.search_chunks(scope_id, "연차", 5))
        index_before = await index_work_count(5)

        # read 한 번.
        # 빈 카탈로그(코퍼스 경로가 잘못됐다)에서 `next(iter(...))` 는 코루틴 안에서
        # StopIteration 이 되어 조용히 멈춘다. 그 사고를 한 번 밟았다.
        target = next(iter(fs._catalog.wiki_id_by_address), None)
        if target is None:
            raise SystemExit("카탈로그가 비었다 — 코퍼스 경로를 확인한다")
        calls_before_read = client.calls
        bodies_before_read = client.body_fetches
        await fs.get(scope_id, target)
        first_read_calls = client.calls - calls_before_read
        first_read_bodies = client.body_fetches - bodies_before_read

        after = work_count(await fs.search_chunks(scope_id, "연차", 5))
        index_after = await index_work_count(5)

        # 두 번째 read 는 캐시에 걸린다 — 예산 소비가 장수에 한 번만 비례한다는 근거.
        calls_before_second = client.calls
        await fs.get(scope_id, target)
        second_read_calls = client.calls - calls_before_second

        total = calls_before_read + first_read_calls
        result = {
            "hydration_calls": hydration_calls,
            "catalog_pages": client.catalog_pages,
            "budget_for_this_catalog": budget,
            "first_read_calls": first_read_calls,
            "first_read_body_fetches": first_read_bodies,
            "second_read_calls": second_read_calls,
            "calls_after_first_read": total,
            "would_exceed_fixed_105": total > 105,
            "would_exceed_scaled_budget": total > budget,
            "work_rows_before_body_load": before,
            "work_rows_after_body_load": after,
            "index_work_rows_before_body_load": index_before,
            "index_work_rows_after_body_load": index_after,
        }
        await client.aclose()
        await FederatedVaultFS.close()
    return result


async def _phase_e(corpus: Path, scope_key: str,
                   fixed_budget: int | None = None) -> dict:
    """E. 실제 예산을 켠 채로 read 1회 + 검색 5회. 정상 작업 한 바퀴가 통과하는가.

    `fixed_budget` 을 주면 카탈로그 연동을 끄고 그 값으로 고정한다 — 첫 판본
    (`MAX_QUERY_CALLS = 105` 고정)이 정상 read 를 죽였다는 것을 그 자리에서 재현한다.
    """
    app = build_gateway(corpus, scope_key=scope_key, capability="cap",
                        api_key="key")
    client = _client(app, scope_key, unlimited=False)
    if fixed_budget is not None:
        client.call_budget = fixed_budget
        client.note_catalog_size = lambda pages: None  # 연동을 끈다
    with tempfile.TemporaryDirectory() as work_root:
        try:
            scope_id = await FederatedVaultFS.open(Path(work_root), scope_key,
                                                   "job-1", client=client)
            fs = FederatedVaultFS(scope_key, "job-1", client)
            target = next(iter(fs._catalog.wiki_id_by_address), None)
            if target is None:
                raise SystemExit("카탈로그가 비었다 — 코퍼스 경로를 확인한다")
            await fs.get(scope_id, target)
            for query in QUERIES:
                await fs.search_chunks(scope_id, query, 20)
            outcome = "ok"
        except QueryBudgetExceeded as exc:
            outcome = f"QueryBudgetExceeded: {exc}"
        result = {"outcome": outcome, "budget": client.call_budget,
                  "calls": client.calls}
        await client.aclose()
        await FederatedVaultFS.close()
    return result


async def measure(corpus: Path, scope_key: str, limit: int, drafts: int,
                  pages: int, link_density: int, phases: str) -> dict:
    with tempfile.TemporaryDirectory() as staging:
        if link_density or pages:
            existing = sorted(
                (corpus / "wiki" / scope_key / "pages").glob("*.md"))
            corpus = materialize(corpus, scope_key, pages or len(existing),
                                 link_density, Path(staging))
        result: dict = {"link_density": link_density, "phases": phases}
        # A~C 를 링크 있는 코퍼스에서 돌리면 fan-out 이 곱해져 아주 느리다 (read 15회 ×
        # 연결 성분 전체). 링크 측정에서는 `--phases de` 로 좁힌다.
        if "a" in phases:
            result.update(await _phase_abc(corpus, scope_key, limit, drafts))
        if "d" in phases:
            result["read_fanout"] = await _phase_d(corpus, scope_key)
        if "e" in phases:
            result["real_budget_run"] = await _phase_e(corpus, scope_key)
            # 같은 시나리오를 첫 판본의 고정 예산으로. 회귀를 재현해 남긴다.
            result["fixed_105_run"] = await _phase_e(corpus, scope_key,
                                                    fixed_budget=105)
    await LocalVaultFS.close()
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", required=True)
    parser.add_argument("--scope", default="ALL")
    parser.add_argument("--limit", type=int, default=20,
                        help="검색 툴이 넘기는 limit (기본 20 = tools/helpers.MAX_SEARCH)")
    parser.add_argument("--drafts", type=int, default=WORK_DRAFTS,
                        help=f"작업층에 써 넣을 초안 수 (기본 {WORK_DRAFTS})")
    parser.add_argument("--pages", type=int, default=0,
                        help="라이브 위키 장수. 0 이면 코퍼스 그대로")
    parser.add_argument("--link-density", type=int, default=0,
                        help="페이지마다 다음 N장을 링크하는 사슬을 만든다 (0 이면 원본)")
    parser.add_argument("--phases", default="abcde",
                        help="돌릴 국면. 링크 측정은 `de` 로 좁힌다 (A~C 는 fan-out 이 "
                             "곱해져 매우 느리다)")
    args = parser.parse_args()
    print(json.dumps(asyncio.run(measure(Path(args.corpus), args.scope,
                                         args.limit, args.drafts, args.pages,
                                         args.link_density, args.phases)),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
