# 에이전트가 목차를 관리하지 않는다 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 에이전트가 목차(`index.md`)를 읽지도 쓰지도 않게 한다. Spring 이 목차를 DB 로 그리게 됐으므로(S15P11B106-280) 에이전트가 목차를 손으로 관리하는 일은 전부 낭비이자 실패 지점이다.

**Architecture:** 하이드레이션이 목차를 라이브 층에 올리는 것을 멈추고, `rewrite_index_links` 를 삭제한다. `indexEntries` 는 계약이 요구하는 동안 계속 보내되 **마크다운을 파싱하지 않고 이번 작업이 손댄 페이지의 frontmatter `description` 에서 조립**한다 — 역할이 「목차 제안」에서 「요약 제안 채널」로 바뀐다. 에이전트 지침·지시문에서 목차 관리를 걷어낸다.

**Tech Stack:** Python 3.12, uv, pytest, FastAPI, MCP

## Global Constraints

- **계약 무변경.** `indexEntries` 의 모양(`wikiRef`·`order`·`title`·`summary`)은 그대로 보낸다. 역할만 바뀐다
- **백엔드 무변경.** S15P11B106-280 이 이미 목차를 DB 로 그린다. 이 계획은 `ai/` 만 다룬다
- **개발 도구 세션은 그대로 둔다.** 하네스·테스트가 쓰는 `LocalVaultFS` 는 계속 `index.md` 를 만든다(`bootstrap_scope`). 사람이 읽는 진단이고 해가 없다. **`lint` 의 목차 분기도 남긴다** — 그쪽에서 여전히 유효하다
- **요약을 지우지 않는다.** `description` 이 없는 페이지는 `indexEntries` 에서 **건너뛴다.** 빈 값을 보내면 Spring 이 `changeSummary(null)` 로 기존 요약을 지운다
- **내부 토큰 세척을 유지한다.** 요약은 위키 상세 화면 제목 바로 아래에 그대로 뜬다. `document-32` 같은 내부 토큰이 화면까지 간 사고가 있었다 — `scrub_internal_tokens` 를 계속 통과시킨다
- 명령: `cd ai && uv run pytest -m "not ocr"`. 기준선을 먼저 잡고, 내가 늘린 실패가 있는지 그 차이로만 판단한다
- 커밋 규칙: `../docs/conventions/git-convention.md`. 타입은 영어 소문자, 설명은 한국어, 마침표 없음, **커밋 메시지에 Jira 키를 쓰지 않는다**. stage 경로를 하나하나 명시한다 (`git add -A` 금지)
- 브랜치: `feature/S15P11B106-<티켓번호>-ai-index-cleanup`

## 근거 문서

- 설계: `ai/docs/superpowers/specs/2026-08-05-derived-data-ownership-design.md` §3① 의 **AI** 절
- 선행 완료: **S15P11B106-280** (Spring 이 목차를 DB 로 그린다, MR !256) — 이 계획의 전제다

---

## File Structure

| 파일 | 책임 | 변경 |
| --- | --- | --- |
| `ai/src/wiki_mcp/vaultfs/federated.py` | 하이드레이션. 목차를 올리지 않는다 | 수정 (Task 1) |
| `ai/src/wiki_mcp/vaultfs/spring.py` | `rewrite_index_links` 삭제 | 수정 (Task 1) |
| `ai/src/wiki_api/changes.py` | `indexEntries` 를 페이지에서 조립 | 수정 (Task 1) |
| `ai/tests/mcp/test_federated_vaultfs.py` | 하이드레이션 검증 | 수정 (Task 1) |
| `ai/tests/api/test_changes.py` | 조립 검증 | 수정 (Task 1) |
| `ai/src/wiki_mcp/tools/guide.py` | 지침에서 목차 관리 제거 | 수정 (Task 2) |
| `ai/src/agent_runtime/base.py` | 지시문에서 목차 갱신 제거 | 수정 (Task 2) |
| `ai/src/wiki_mcp/tools/lint.py` | `orphan-page` 문구 | 수정 (Task 3) |

---

## Task 0: 기준선과 착수 준비

- [ ] **Step 1: 기준선을 기록한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup/ai
env -u ANTHROPIC_BASE_URL uv run pytest -m "not ocr" -q 2>&1 | tail -3
```

> `env -u ANTHROPIC_BASE_URL` 이 필요하다. 셸에 그 변수가 있으면 `tests/api/test_settings.py` 4건이 환경 누출로 실패한다 — 코드 문제가 아니다.

실패 목록을 적어둔다. 이후 판단 기준은 "내가 늘렸는지"뿐이다.

- [ ] **Step 2: 티켓을 확보하고 브랜치 이름을 맞춘다**

백로그에서 관련 티켓을 먼저 찾는다. 없으면 만든다. **`ai/` 티켓은 버려진 것을 가져와 내용을 갈아 써도 된다** (백엔드 티켓은 그러지 않는다).

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup
git branch -m feature/S15P11B106-<티켓번호>-ai-index-cleanup
```

---

## Task 1: 목차를 올리지 않고, 요약을 페이지에서 조립한다

**Files:**
- Modify: `ai/src/wiki_mcp/vaultfs/federated.py` — `_hydrate_catalog` 의 목차 적재 제거
- Modify: `ai/src/wiki_mcp/vaultfs/spring.py` — `rewrite_index_links`·`_INDEX_LINK_RE` 삭제
- Modify: `ai/src/wiki_api/changes.py` — `parse_index_entries` → `index_entries_from_pages`
- Modify: `ai/tests/mcp/test_federated_vaultfs.py`, `ai/tests/api/test_changes.py`

**Interfaces:**
- Consumes: `fs.pending_changes(scope_id) -> list[dict]` (각 항목에 `address`·`type`·`title`), `fs.get(scope_id, address) -> dict | None`, `parse_frontmatter`·`extract_frontmatter_field` (`wiki_mcp.tools.write`), `scrub_internal_tokens`, `TempRefs.ref_for(key)`, `_page_key(address)`
- Produces: `async def index_entries_from_pages(fs, scope_id, changes, refs) -> list[IndexEntry]` — `changes.py` 안에서만 쓴다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ai/tests/mcp/test_federated_vaultfs.py` 에 추가한다. 이 파일의 기존 픽스처를 먼저 읽고 그대로 쓴다.

```python
@pytest.mark.asyncio
async def test_hydration_does_not_bring_the_index_into_the_vault(federated_scope):
    """목차는 Spring 이 DB 로 그린다 (S15P11B106-280). 올릴 이유가 없고, 올리면
    에이전트가 그것을 고치려 들다 dangling-link 로 잡을 죽인다."""
    fs, scope_id = federated_scope
    assert await fs.get(scope_id, "index.md") is None
```

`ai/tests/api/test_changes.py` 에 추가한다. 기존 테스트 `test_an_inline_wiki_link_becomes_an_undirected_wiki_wiki_relation` 의 픽스처(`vault`·`scope_row`)를 그대로 쓴다.

```python
async def test_index_entries_carry_the_frontmatter_description_of_touched_pages(vault, scope_row):
    """`indexEntries` 는 이제 「목차 제안」이 아니라 「요약 제안 채널」이다.
    목차 마크다운을 파싱하지 않고 페이지 자신의 frontmatter 에서 가져온다."""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address,
                   "---\ntitle: 연차 규정\ndescription: 연차 부여와 이월 기준\n"
                   "tags: [연차]\ncategory: 인사\n---\n\n# 연차 규정\n")

    response = await build_response(fs, scope_id, summary="요약")

    assert [(e.title, e.summary) for e in response.indexEntries] == [
        ("연차 규정", "연차 부여와 이월 기준")]


async def test_a_page_without_a_description_is_not_sent_as_an_index_entry(vault, scope_row):
    """빈 요약을 보내면 Spring 이 `changeSummary(null)` 로 기존 요약을 지운다."""
    _, scope_id, fs = vault
    address = await fs.allocate_page(scope_id)
    await fs.write(scope_id, address,
                   "---\ntitle: 연차 규정\ntags: [연차]\ncategory: 인사\n---\n\n# 연차 규정\n")

    response = await build_response(fs, scope_id, summary="요약")

    assert response.indexEntries == []
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup/ai
env -u ANTHROPIC_BASE_URL uv run pytest tests/mcp/test_federated_vaultfs.py tests/api/test_changes.py -q 2>&1 | tail -15
```

Expected: 하이드레이션 테스트는 `index.md` 가 있어서 FAIL, 조립 테스트 둘은 목차가 비어 `indexEntries` 가 `[]` 여서 첫째가 FAIL.

- [ ] **Step 3: 하이드레이션에서 목차를 걷어낸다**

`federated.py` 의 `_hydrate_catalog` 에서 목차 적재 세 줄(`rewrite_index_links` 호출, `_insert_live(..., INDEX_ADDRESS, ...)`, `sync_references(..., INDEX_ADDRESS, ...)`)을 지우고 그 자리에 이유를 남긴다.

```python
        # 목차는 올리지 않는다. Spring 이 DB 로 그리므로(S15P11B106-280) 에이전트가 손댈
        # 것이 없고, 올리면 그것을 고치려 들다 dangling-link 로 잡이 죽는다. 공간에 무엇이
        # 있는지는 `search(mode="list")` 가 카테고리별로 더 낫게 보여준다.
```

`self._client.index_markdown()` 호출이 사라지므로 `query_client` 의 그 메서드가 미사용이 되는지 확인하고, 미사용이면 **남긴다** — 계약이 제공하는 창구이고 지우면 계약 소비자가 줄어든 것으로 오해된다. 대신 그 docstring 에 「지금은 부르지 않는다」를 한 줄 적는다.

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup/ai
grep -rn "index_markdown" src/
```

- [ ] **Step 4: `rewrite_index_links` 를 삭제한다**

`spring.py` 에서 `rewrite_index_links` 와 `_INDEX_LINK_RE` 를 지운다. `federated.py` 의 임포트도 지운다. `address_from_wiki_path` 는 **남긴다** — 페이지 주소를 만드는 데 계속 쓴다.

```bash
grep -rn "rewrite_index_links\|_INDEX_LINK_RE" src/ tests/
```

Expected: 0건. 테스트에 그것을 검증하던 것이 있으면 삭제한다 — 대상 함수가 없어졌다.

- [ ] **Step 5: `indexEntries` 를 페이지에서 조립한다**

`changes.py` 의 `parse_index_entries` 와 그 헬퍼(`_parse_bullet`·`_parse_table_row`)를 삭제하고 다음을 넣는다.

```python
async def index_entries_from_pages(fs, scope_id: str, changes: list[dict],
                                   refs: TempRefs) -> list[IndexEntry]:
    """이번 작업이 손댄 페이지의 요약을 나른다.

    **목차 파일의 모양은 Spring 이 DB 로 그린다** (S15P11B106-280). 그래서 이 배열은
    「목차 제안」이 아니라 **요약 제안 채널**이다. 앞 판본은 에이전트가 쓴 목차 마크다운을
    파싱했는데, 형식이 실행마다 갈려 목차가 통째로 사라진 적이 있다(S15P11B106-165).
    요약은 원래 페이지 자신이 frontmatter `description` 으로 갖고 있다.

    **손대지 않은 페이지는 보내지 않는다.** Spring 은 받은 항목만 갱신하므로 기존 요약이
    그대로 남는다. `description` 이 없는 페이지도 건너뛴다 — 빈 값을 보내면 Spring 이
    `changeSummary(None)` 으로 기존 요약을 지운다.

    요약은 위키 상세 화면의 **제목 바로 아래**에 그대로 뜬다(프론트 `WikiDetail.jsx`).
    `document-32` 같은 내부 토큰이 화면까지 간 사고가 있어 `scrub_internal_tokens` 를
    반드시 통과시킨다.
    """
    from wiki_mcp.tools.write import extract_frontmatter_field, parse_frontmatter

    entries: list[IndexEntry] = []
    for change in changes:
        if change["type"] not in ("create", "update"):
            continue
        key = _page_key(change["address"])
        ref = refs.ref_for(key) if key else None
        if not ref:
            continue
        content = (await fs.get(scope_id, change["address"]) or {}).get("content") or ""
        meta = parse_frontmatter(content)
        description = (extract_frontmatter_field(meta, "description") or "").strip()
        if not description:
            continue
        title = (change.get("title")
                 or extract_frontmatter_field(meta, "title") or "").strip()
        if not title:
            continue
        entries.append(IndexEntry(wikiRef=ref, order=len(entries) + 1, title=title,
                                  summary=scrub_internal_tokens(description)))
    return entries
```

`build_response` 의 조립을 바꾼다. `index_row` 조회를 지운다 — 목차가 vault 에 없다.

```python
    return TransformResponse(
        summary=summary,
        categoryChanges=categories.changes(),
        wikiChanges=wiki_changes,
        relationChanges=_dedupe(relations),
        indexEntries=await index_entries_from_pages(fs, scope_id, changes, refs),
    )
```

`reply_sanitizer.py:109` 의 주석이 `parse_index_entries` 를 가리킨다. 이름을 새 함수로 고친다.

- [ ] **Step 6: 테스트를 돌려 통과를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup/ai
env -u ANTHROPIC_BASE_URL uv run pytest tests/mcp/test_federated_vaultfs.py tests/api/test_changes.py -q 2>&1 | tail -15
```

**목차를 전제한 기존 테스트가 깨진다.** `indexEntries` 를 목차 마크다운으로 검증하던 것들이다. **단정을 지우지 말고** 새 방식(페이지 frontmatter)으로 입력을 바꿔 같은 성질을 계속 검증한다. 목차 마크다운 파싱 자체를 검증하던 테스트는 대상 함수가 사라졌으므로 삭제한다.

- [ ] **Step 7: 전체 테스트로 회귀를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup/ai
env -u ANTHROPIC_BASE_URL uv run pytest -m "not ocr" -q 2>&1 | tail -5
```

Expected: Task 0 기준선 대비 새 실패 없음.

- [ ] **Step 8: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup
git add ai/src/wiki_mcp/vaultfs/federated.py ai/src/wiki_mcp/vaultfs/spring.py \
        ai/src/wiki_api/changes.py ai/src/agent_runtime/reply_sanitizer.py \
        ai/tests/mcp/test_federated_vaultfs.py ai/tests/api/test_changes.py
git commit -m "$(cat <<'EOF'
refactor(wiki-api): 목차를 하이드레이션하지 않고 요약을 페이지에서 조립한다

백엔드가 목차를 DB 로 그리게 됐으므로 에이전트가 목차를 손댈 것이 없다. 올리면 그것을
고치려 들다 dangling-link 로 잡이 죽는다 — 그것이 그 버그의 경로였다.

indexEntries 는 계약이 요구하는 동안 계속 보내되 역할이 바뀐다: 목차 제안이 아니라
요약 제안 채널이다. 목차 마크다운을 파싱하지 않고 손댄 페이지의 frontmatter description
에서 가져온다. description 이 없는 페이지는 건너뛴다 — 빈 값은 기존 요약을 지운다.

rewrite_index_links 는 메울 간극이 없어져 삭제한다.
EOF
)"
```

---

## Task 2: 에이전트 지침·지시문에서 목차 관리를 걷어낸다

**Files:**
- Modify: `ai/src/wiki_mcp/tools/guide.py` — 「`index.md` — 허브」 절, 구조 설명 3번, 작업 순서 8번, 금지 항목
- Modify: `ai/src/agent_runtime/base.py` — 지시문 네 곳
- Modify: 위 문구를 단정하는 테스트가 있으면 함께

**Interfaces:**
- Consumes: 없음 (프롬프트 텍스트만)
- Produces: 없음

**왜 중요한가.** 지침이 「원본문서를 처리할 때마다 목차를 갱신한다. 한 페이지만 고칠 수 있다면 이 페이지다」라고 시킨다. 에이전트는 매번 목차에 소개 문단·「최근 변경」·각주·frontmatter 를 쓰는데 **Spring 이 전부 버린다.** 순수 낭비이고, 목차를 고치는 순간 그 파일이 작업 층으로 올라와 lint 전체 검사를 받는다 — 그것이 잡을 죽이던 경로다.

- [ ] **Step 1: 목차를 언급하는 곳을 전부 찾는다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup/ai
grep -n "index.md\|목차\|허브" src/wiki_mcp/tools/guide.py src/agent_runtime/base.py
```

- [ ] **Step 2: `guide.py` 를 고친다**

- 「### `index.md` — 허브」 절 전체를 지운다
- 구조 설명의 「3. **목차** — `index.md`. 범위마다 하나. 항상 있다.」를 지운다
- 작업 순서에서 「`edit` 으로 `index.md` 갱신」 단계를 지우고 뒤 번호를 당긴다
- 금지 항목의 「`index.md` 를 지우지 않는다」를 지운다
- **공간을 파악하는 수단을 대신 안내한다.** 지금도 있는 도구다:

```
`search(mode="list")` 가 이 범위에 무엇이 있는지 카테고리별로 보여준다 — 주소·제목·한 줄 요약.
새로 만들 페이지가 이미 있는지 먼저 확인한다.
```

- **`description` 이 왜 중요한지 한 줄 추가한다.** 이제 그것이 요약의 유일한 출처다:

```
frontmatter 의 `description` 은 사원이 위키를 열 때 **제목 바로 아래**에 뜬다. 그 페이지가
무슨 내용인지 한 줄로 적는다 — 원본문서 이름이나 내부 식별자를 쓰지 않는다.
```

- 이식 기록 헤더의 「`overview.md` -> `index.md`」 줄에 목차가 더 이상 에이전트 소관이 아님을 덧붙인다. **원본과 갈라진 지점을 기록으로 남긴다** (`NOTICE` 의 요구다).

- [ ] **Step 3: `base.py` 의 지시문을 고친다**

네 곳을 고친다. 「`index.md` 를 갱신하고」·「`index.md` 에서 사라진 페이지 줄을 빼고」·「본문이 바뀌면 `index.md` 의 요약도 맞춘다」를 지우고, 요약이 필요한 곳은 **frontmatter `description`** 으로 바꾼다. 나쁜 예 문장에 든 `index.md` 언급도 정리한다.

- [ ] **Step 4: 테스트를 돌린다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup/ai
env -u ANTHROPIC_BASE_URL uv run pytest -m "not ocr" -q 2>&1 | tail -5
```

프롬프트 문구를 단정하는 테스트가 있으면 새 문구로 고친다.

- [ ] **Step 5: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup
git add ai/src/wiki_mcp/tools/guide.py ai/src/agent_runtime/base.py
git commit -m "$(cat <<'EOF'
refactor(agent-runtime): 지침에서 목차 관리를 걷어낸다

Spring 이 목차를 DB 로 그리므로 에이전트가 쓴 목차는 전부 버려진다. 그런데 지침은
「한 페이지만 고칠 수 있다면 목차」라고 시켰고, 에이전트는 매번 소개 문단·최근 변경·각주를
써서 턴과 토큰을 들였다.

공간 파악은 search(mode="list") 가 카테고리별로 더 낫게 보여준다. 요약은 페이지
frontmatter 의 description 이 유일한 출처가 됐으므로 그것을 왜 쓰는지 한 줄 남긴다.
EOF
)"
```

---

## Task 3: `orphan-page` 문구를 고치고 증가폭을 잰다

**Files:**
- Modify: `ai/src/wiki_mcp/tools/lint.py` — `_lint_orphan` 메시지
- Modify: `ai/tests/mcp/test_lint*.py` 중 그 문구를 단정하는 것

**Interfaces:**
- Consumes: `fs.get_backlinks(scope_id, address)`
- Produces: 없음

**왜.** 목차가 그래프에서 빠지므로 「목차에서 닿을 수 없다」가 거짓이 된다. 그리고 목차로만 닿던 페이지가 새로 경고에 잡힌다.

- [ ] **Step 1: 문구를 고친다**

`_lint_orphan` 의 메시지를 바꾼다. 심각도는 `warn` 그대로 둔다.

```python
        return [LintIssue("warn", "orphan-page", doc["address"],
                          "다른 페이지 중 아무도 이 페이지를 링크하지 않는다 — "
                          "관련 페이지가 있으면 본문에서 링크한다")]
```

주석으로 근거를 남긴다:

```python
        # 「목차에서 닿을 수 없다」로 판정하던 것을 바꿨다. 목차는 이제 하이드레이션되지 않고
        # (S15P11B106-280 이후 Spring 이 DB 로 그린다) 항상 공간 전체를 담으므로 목차 도달성은
        # 늘 참이다. 남는 질문은 「다른 페이지가 나를 링크하나」이고, 그것은 여전히 중요하다 —
        # 본문 링크가 「관련 위키」 관계의 유일한 입력원이다 (`guide.py`).
```

- [ ] **Step 2: 테스트를 돌려 문구 단정을 고친다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup/ai
env -u ANTHROPIC_BASE_URL uv run pytest -m "not ocr" -q 2>&1 | tail -5
```

- [ ] **Step 3: 증가폭을 하네스로 잰다**

설계가 요구한 측정이다. 하네스로 페이지 두 장을 서로 링크 없이 만들고 `lint` 를 불러 `orphan-page` 건수를 센다. 목차를 올리지 않으므로 이전보다 늘어나는 것이 정상이다 — **얼마나 늘어나는지**가 별건 판단의 근거다.

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup/ai
grep -rn "harness" docs/superpowers/specs/2026-08-02-wiki-mcp-tool-lint-harness-design.md | head -5
```

하네스 사용법은 그 설계 문서가 정본이다. **측정 결과를 `experiments/INDEX.md` 에 한 줄로 남긴다** — 그 파일이 수치의 정본이다.

- [ ] **Step 4: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup
git add ai/src/wiki_mcp/tools/lint.py ai/tests/mcp/ ai/experiments/INDEX.md
git commit -m "$(cat <<'EOF'
fix(wiki-mcp): orphan-page 판정 근거를 본문 링크로 바꾼다

「목차에서 닿을 수 없다」가 거짓이 됐다. 목차는 이제 하이드레이션되지 않고 Spring 이
공간 전체로 그리므로 목차 도달성은 늘 참이다.

남는 질문은 「다른 페이지가 나를 링크하나」이고 그것은 여전히 중요하다 — 본문 링크가
「관련 위키」 관계의 유일한 입력원이다. 심각도는 warn 그대로 두고 문구만 고친다.
EOF
)"
```

---

## Task 4: MR

- [ ] **Step 1: 담당 범위를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/.worktrees/ai-index-cleanup
git status && git diff --name-only origin/develop
```

Expected: `ai/` 만. 백엔드·프론트 변경이 섞이면 빼낸다.

- [ ] **Step 2: develop 최신을 반영하고 전체 테스트를 돌린다**

```bash
git fetch origin && git merge origin/develop
cd ai && env -u ANTHROPIC_BASE_URL uv run pytest -m "not ocr" -q 2>&1 | tail -5
```

- [ ] **Step 3: MR — 사용자 지시가 있을 때만**

제목: `refactor(wiki-api): 에이전트가 목차를 관리하지 않는다 [S15P11B106-<티켓번호>]`

본문에 담을 것:
- 왜: Spring 이 목차를 DB 로 그리게 됨(S15P11B106-280). 에이전트가 쓴 목차는 전부 버려지고, 목차를 고치는 순간 lint 전체 검사를 받아 잡이 죽던 경로가 됐다
- 무엇이 없어지나: 목차 하이드레이션, `rewrite_index_links`, 지침의 목차 관리
- `indexEntries` 의 역할 변경 — 목차 제안 → 요약 제안 채널. **계약 무변경**
- **`description` 이 요약의 유일한 출처가 됐다.** 없는 페이지는 항목을 안 보낸다(기존 요약 보존)
- `orphan-page` 문구 변경과 **측정한 증가폭**
- 원본 프로젝트(lucas-llmwiki)와 갈라진 지점 — 그쪽은 DB 가 없어 허브 문서가 유일한 메타데이터 저장소였다. 이식 기록에 남겼다
- **실기동은 아직**이라는 사실과, 왜 지금 묶어서 하는지

---

## 이 계획에서 하지 않는 것

- **백엔드 변경.** S15P11B106-280 이 이미 했다
- **계약에서 `indexEntries` 제거.** 계약 변경 절차가 필요하고, 지금은 요약 채널로 계속 쓴다. 별건
- **`lint` 의 목차 분기 제거.** 개발 도구 세션(`LocalVaultFS`)에서 여전히 유효하다
- **목차 파일 제거.** 챗봇이 아직 공간 개요로 읽는다. 별건
- **에이전트가 목차를 쓰는 것이 페이지 품질에 기여했나.** 이 변경이 그 지시를 걷어내므로 이제 측정할 수 있게 된다 — 측정은 별건이다
