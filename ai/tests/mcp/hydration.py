"""테스트가 `SpringVaultFS` 의 라이브 층을 직접 채우는 헬퍼.

프로덕션에는 이런 경로가 없다 — 라이브 층의 유일한 출처는 Wiki 조회 API 이고 그 하이드레이션은
`FederatedVaultFS.open` 이 한다 (S15P11B106-175). 그 전에는 `SpringVaultFS.open` 이
`pages=`·`index_markdown=` 을 받아 같은 일을 했고, 요청이 위키를 실어 보내던 push 경로가
사라지면서 함께 지워졌다.

**그래도 이 헬퍼가 있는 이유는 아래 저장 계층이 그대로 남아 있어서다.** 청크 색인·참조
그래프·2단 SQLite·staleness 는 조회 API 경로도 똑같이 쓰고(`FederatedVaultFS` 가
`SpringVaultFS` 를 상속한다), 그 계층의 성질을 검증하려면 라이브 행을 넣어 줘야 한다.
`_insert_live` → `_sync_page_references` → `_clear_staleness` 순서는 지워진
`_hydrate_from_pages` 가 하던 것과 같다 — 전부 넣은 뒤에 한 번 훑어야 아직 안 들어온
페이지로 가는 링크가 빠지지 않고, 그다음 stale 을 걷어야 t0 스냅샷이 stale 로 잡히지
않는다.
"""

from __future__ import annotations

from pathlib import Path

from wiki_mcp.vaultfs.local import INDEX_ADDRESS, PAGES_PREFIX
from wiki_mcp.vaultfs.spring import SpringVaultFS, address_from_wiki_path


async def open_with_pages(root: str | Path, scope_key: str, job_id: str | None,
                          pages: list[dict], index_markdown: str = "") -> str:
    """임시 색인을 열고 `pages` 로 라이브 층을 채운다. `scope_id` 를 돌려준다.

    `pages` 항목은 조회 API 목록 + 본문을 한 dict 로 합친 모양이다 —
    `wikiId`·`title`·`contentMarkdown`·`categoryName?`·`wikiPath?`. `wikiPath` 가 있으면
    그 접미사가 주소이고, 없으면 `pages/{wikiId}.md` 다.
    """
    scope_id = await SpringVaultFS.open(root, scope_key, job_id)
    fs = SpringVaultFS(scope_key, job_id)
    for page in pages:
        wiki_path = page.get("wikiPath")
        wiki_id = page.get("wikiId")
        address = (address_from_wiki_path(wiki_path, scope_key) if wiki_path
                   else f"{PAGES_PREFIX}{wiki_id}.md")
        await fs._insert_live(scope_id, address, page.get("contentMarkdown") or "",
                              wiki_id=wiki_id, title=page.get("title"),
                              category=page.get("categoryName"))
    await fs._insert_live(scope_id, INDEX_ADDRESS, index_markdown,
                          title="위키 목차", category="목차")
    await fs._sync_page_references(scope_id)
    await fs._clear_staleness(scope_id)
    return scope_id
