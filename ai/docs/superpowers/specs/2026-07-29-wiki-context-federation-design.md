# 위키 문맥 범위 설계 — 사전 선택에서 조회 창구로

- 작성일: 2026-07-29
- 대상: `POST /internal/v1/wiki-transformations`, `POST /internal/v1/wiki-edits`, `wiki_mcp/vaultfs/*`
- 근거: 요구사항 `FR-WIKI-002`·`FR-WIKI-005`·`FR-WIKI-014`·`FR-AI-003`·`NFR-PERF-002`·`DR-002`·`DR-003`·`DR-006`~`DR-011` · `../../../docs/api/AJT-FastAPI-Internal-API.postman_collection.json` (계약 v1.3.0)
- 선행: `2026-07-28-ai-server-v1.1-adaptation.md` §7 리스크 — "5개 밖 병합 기회 상실 — 감수하기로 확정. **측정으로 크기 확인**"
- 상태: 설계 제안. 요구사항 개정 합의 전
- 검토: Codex 3회. 3차 검토에서 관계 그래프·캐시 상태·lint 분리 논거를 정정했다 (§10)
- develop 반영: 2026-07-29 21시. Wiki 도메인·변환 반영·작업 종료 처리가 올라와 §8.4 를 갱신했다

## 1. 배경

에이전트가 위키 5장만 본다.

```
1단계  Spring → AI: 목차만 준다. "변환에 필요한 위키 5개 골라"
       AI → Spring: wikiIds ≤ 5      ← 별도 LLM 단발 호출. 에이전트가 아니다
2단계  Spring → AI: 그 5장 본문을 selectedWikis[] 로 싣는다
       AI: 에이전트가 그 5장 안에서만 검색·수정
```

목차는 전체가 온다. 그래서 에이전트는 6번째 위키의 **존재**는 안다. 본문을 못 읽으니 **고치지 못한다.**

관리자 채팅(`wiki-edits`)은 더 좁다. 대상 위키 **1장**만 온다 (`routers/wiki.py:175`). "이거 휴가 규정하고 합쳐줘" 를 수행할 방법이 없다.

### 1.1 요구사항이 서로 모순된다

```
FR-WIKI-005 (필수)  에이전트는 중복되거나 분산된 Wiki를 하나로 통합하는 변경안을
                    작성할 수 있어야 한다
FR-WIKI-014 (필수)  에이전트는 scope_key 내 지식 구조를 최적화하기 위해 Wiki
                    카테고리를 생성·수정·병합·삭제하고 …
FR-WIKI-002 (필수)  변환에 필요한 Wiki를 최대 5개 선택하고, 백엔드가 재검증해
                    전달한 선택 Wiki 본문·관계만 고려해 변환해야 한다
```

병합은 최소 2장을 읽어야 한다. 카테고리 재편은 전체 구조를 봐야 한다. `FR-WIKI-002` 가 그것을 막는다.

## 2. 측정

`2026-07-28-ai-server-v1.1-adaptation.md` 가 "측정으로 크기 확인" 이라 적어둔 그 측정이다.
코퍼스: 한국어 위키 100장, 평균 7,946자 (실측 위키 크기와 동일). 질의 40개 = 정확어 20 + 자연어 20.

### 2.1 한국어 검색이 절반 이상 실패한다

현재 구현은 SQLite FTS5 `tokenize='unicode61'` — 공백으로만 자른다.

| 색인 · 질의 | R@5 | MRR | 정확어 R@5 | 자연어 R@5 | 0건 |
| --- | --- | --- | --- | --- | --- |
| `unicode61` AND — **현행** | 0.33 | 0.33 | 0.65 | 0.00 | 26/40 |
| `unicode61` OR | 0.38 | 0.38 | 0.65 | 0.10 | 19/40 |
| `trigram` AND | 0.30 | 0.30 | 0.60 | 0.00 | 28/40 |
| `trigram` OR | 0.35 | 0.35 | 0.60 | 0.10 | 26/40 |
| 2글자 분해 OR (= MySQL `ngram`) | 0.60 | 0.59 | **0.95** | 0.25 | 7/40 |
| 2글자 분해 AND / phrase | 0.47 | 0.48 | 0.95 | 0.00 | 20/40 |

원인은 조사다. `"연차를 사용하려면"` → 토큰 `[연차를] [사용하려면]`. 질의 `"연차"` 가 맞지 않는다.
`trigram` 은 3글자 이상만 되니 한국어 핵심어(연차·이월·승인·부여)가 전부 빠진다. **`ngram_token_size` 는 2로 고정해야 한다.**

현행이 AND 인 이유는 `vaultfs/local.py` 의 `search_chunks` 가 에이전트 질의를 `MATCH ?` 에
그대로 넘기고 FTS5 가 맨 term 을 AND 로 읽기 때문이다.

> **정정 (2026-07-30).** 앞 판본은 `unicode61` 을 OR 로, `trigram` 을 폐기한 48개 질의셋으로,
> 2글자를 40개 세트로 재 한 표에 나란히 두고 있었다. 색인 방식의 차이로 보고한 것에 질의
> 방식과 질의셋의 차이가 섞여 있었다. 위 표는 `experiments/corpus-ko/evaluate_sqlite.py`
> 로 한 조건에서 다시 측정한 것이다. 결론은 그대로지만 차이가 보고했던 것보다 크고
> (현행 0.33 → 0.60), `trigram` 은 0.50 이 아니라 0.30~0.35 다.

### 2.2 문맥 범위를 넓히는 비용은 무의미하다

| 항목 | 규모 | 시간 |
| --- | --- | --- |
| 하이드레이션 `SpringVaultFS.open()` | 100장 · 795K자 | 712ms |
| 원본문서 적재 | 1건 | 88ms |
| FTS 검색 | 청크 180 | 2.4ms |
| **에이전트 루프** | 문서 1건 | **126~894초** |

문맥 준비가 실행 시간의 0.1~0.7% 다. **속도는 이 결정의 변수가 아니다.**

### 2.3 위키 변환에는 의미 검색이 필요 없다

| 단계 | 정확어 R@5 | 자연어 R@5 |
| --- | --- | --- |
| 2글자 분해 BM25 | **0.95** | 0.25 |
| + 임베딩 | 0.75 | 0.60 |
| + RRF 융합 · 보너스 | 0.95 | 0.70 |
| + 재정렬 모델 | 0.95 | 0.80 |

정확어 열이 0.95 로 평평하다. 에이전트는 원본문서 문구를 들고 검색하므로 정확어 성격이다.
**의미 검색은 챗봇용이다** (사용자가 자기 말로 묻는다). 별개 안건으로 둔다.

이 결론은 **취약하다** — 실제 검색어를 수집하지 않았다. §8 참조.

## 3. 설계

### 3.1 원칙

**데이터·권한·검색 색인은 Spring 이 소유한다. AI 는 질의해서 에이전트를 돌린다.**

전체를 넘기지 않는다. 사본을 갖지 않는다. 필요한 것을 물어본다.

### 3.2 저장 계층 18개 기능의 배치

`vaultfs/base.py` 포트가 툴이 아는 전부다. 성격별로 갈린다.

```
Spring 질의로      get · find_source · list_documents · get_source_pages
                  search_chunks · get_backlinks · get_forward_references
                  find_uncited_sources · find_stale_pages
                  resolve_scope · list_scopes
                  ← 단, 라이브 층에 한해서다

AI 로컬 유지        allocate_page · write · remove · pending_changes
                  replace_references · propagate_staleness
                  ← 작업층에 한해서다.  §3.2.1
```

#### 3.2.1 관계 그래프는 없어지지 않는다 — 라이브만 옮긴다

**초기 판단을 정정한다.** "`DR-002` 가 Spring JSON 컬럼을 정본으로 정했으므로 AI 그래프는
불필요하다" 고 썼는데 틀렸다. `DR-002` 의 projection 은 원본보다 정보가 적다.

| | AI 로컬 `document_references` | `DR-002` JSON |
| --- | --- | --- |
| 방향 | `source_address` → `target_address` | 없음 (무방향) |
| 유형 | `cites` / `links_to` 구분 | 없음 |
| 각주 라벨 | `footnote_label` | 없음 |
| 위치·인용문 | `location` · `quote` · `page` | 없음 |
| 단위 | **각주 1개 = 행 1개** | 위키 ID 목록 |

그리고 `changes.py:178` `build_response()` 가 `get_forward_references()` 로
`relationChanges` 를 만든다 — `reference_type` 과 `source_id` 를 행 단위로 읽는다.
`_evidence()` 는 각주별 `location`·`quote` 를 읽는다 (`FR-AI-009` 가 요구하는 근거).

**무방향 ID 목록으로는 이것들을 만들 수 없다.** 그래서:

```
라이브 관계     Spring 이 파생 directed reference index 를 소유하고 질의로 제공한다
              (DR-002 JSON 이 아니라 별도 파생 색인. 방향·유형·각주 단위)
작업층 관계     AI 가 계속 본문을 파싱해 work reference overlay 를 유지한다
              replace_references · propagate_staleness 를 작업층에 대해 유지
```

`_sync_page_references()` 의 **라이브 전수 순회**는 없어진다. 작업층 파싱은 남는다.
없어지는 것은 "매 요청 라이브 100장을 다시 파싱" 이고, 남는 것은 "이번에 만진 3~5장 파싱" 이다.

"관계 재구축 2개가 없어진다" 는 서술은 폐기한다.

### 3.3 라이브 층은 사라지지 않는다 — 카탈로그 + 본문 캐시로 갈린다

**초기 판단을 정정한다.** "읽은 것만 캐시하면 `pending_changes` 가 무변경으로 유지된다" 고
썼는데 틀렸다. `local.py:480` 이 이렇게 판정한다.

```python
change_type = "update" if live else "create"
```

**캐시에 라이브 행이 없으면 조용히 `create` 가 된다.** "존재하지 않는다" 와 "아직 받지 않았다" 를
구분하지 못하면, 실재하는 페이지를 새 페이지로 신고해 Spring 이 중복을 만든다.

그래서 두 층으로 나눈다.

```
메타데이터 카탈로그    scope 전체를 보유한다
                    wikiId · 주소 · 제목 · 카테고리 · contentHash · 존재 여부
                    질의 1회.  본문 없음.  100장에 약 5KB

본문 캐시            읽은 것만 보유한다
                    read 로 받은 것을 적재
```

그리고 캐시 행에 **3상태**를 둔다.

| 상태 | 뜻 | `pending_changes` 판정 |
| --- | --- | --- |
| `present` | 본문을 받았다 | 카탈로그에 있으면 `update` |
| `missing` | Spring 이 "없다" 고 답했다 | `create` |
| `unloaded` | 아직 안 받았다 | **판정하지 않는다 — 오류** |

**첫 쓰기 시점에 존재 확인과 관계 스냅샷을 강제한다.** `write`·`remove` 가 대상 주소에 대해
카탈로그를 확인하고, 수정이면 수정 전 관계를 찍어둔다 (`changes.py` 의 `before` 계산에 필요).

따라서 이것들은 **수정이 필요하다.**

```
write · remove       첫 mutation 시 존재 확인 + 관계 스냅샷 강제
pending_changes      unloaded 를 create 로 판정하지 않는다
allocate_page        카탈로그와 주소 충돌을 확인한다
```

무변경으로 유지되는 것은 **2층 오버레이 개념과 겹쳐 읽기 규칙**이다. 구현은 손댄다.

### 3.4 쓰기는 손대지 않는다

`DR-007` — "문서 1건의 변환 결과는 **서비스 중인 파일을 직접 수정하지 않고** 작업 공간에
먼저 전체 작성하고 검증한다."

에이전트 쓰기는 AI 작업층에만 간다. 변경안 JSON 만 반환한다. 저장·백업·원자적 교체는
Spring 이 한다 (`DR-008`). 실패하면 서비스 파일을 건드리지 않는다 (`DR-009`).

**작업층을 Spring 으로 옮기는 안을 검토했고 채택하지 않았다** — §6.2.

### 3.5 `lint` — 둘로 나눈다

**초기 판단을 정정한다.** "라이브 페이지는 전수 검사하지 않는다" 만으로는 부족하다.
전역 불변식 하나를 놓쳤다.

`lint.py:279` `_uncited()`

> A document cited by no page is knowledge that never landed — **an error, not a warning.**
> It is the failure a prompt variant produced when it dropped a whole document silently.

**`uncited-source` 는 오류다.** 새 원본문서가 어느 위키에도 반영되지 않았다는 뜻이고,
문서를 조용히 떨어뜨린 실패를 잡으려고 만든 검사다. 그런데 서버 게이트는 이것을 버리고
(`session.py:64` 에 없다) 사실상 **에이전트의 자기 교정에 의존한다.**

즉 에이전트가 부르는 `lint` 를 작업층으로 좁히면 **문서 내용을 하나도 반영하지 않고 성공할 수 있다.**

그래서 목적이 다른 둘로 나눈다.

```
lint_for_agent      에이전트가 보고 스스로 고치는 것.  넓게 본다
  작업 페이지 전부      frontmatter · 각주 형식 · 각주가 원문과 맞나 · 링크 유효성
  이번 입력의 반영 여부  추가한 원본문서가 최소 하나의 변경 위키에 인용됐나  ← uncited-source
  변경 영향            삭제·병합한 위키를 라이브 페이지와 index.md 가 가리키나
  해결 가능한 경고       고아 페이지 등

assert_apply_safe   반영을 막는 것.  결정적 불변식만.  구조화된 결과를 낸다
  이번 요청이 만든 dangling-link
  삭제한 원본문서를 기존 위키가 계속 인용하나
  삭제·병합할 카테고리가 반영 후 비어 있나
  index 가 노출 대상 위키를 전부 포함하나
```

지금은 `session.py` 가 `lint` 의 **문자열 보고서를 정규식으로 다시 파싱한다**
(`_ISSUE_LINE_RE`·`_FOOTNOTE_LABEL_RE`·`_LINK_TARGET_RE`). federation 전환 때 이것도 없앤다 —
`assert_apply_safe` 가 구조화된 결과를 돌려주게 한다.

#### 3.5.1 역조회가 필요한 것은 삭제·병합이다

**초기 서술을 정정한다.** "내가 링크를 없앤 페이지 → `get_backlinks`" 라고 썼는데 틀렸다.
**나가는 링크를 지워도 남의 링크가 깨지지 않는다.**

역조회가 필요한 것은 **대상 페이지를 삭제·병합할 때**다.

```
내가 pages/108.md 를 지운다
  → get_backlinks(108)  →  [101, 105]
  → 101 · 105 의 링크가 깨진다.  읽어서 고친다
```

#### 3.5.2 반영 전에 계산할 수 있다

`lint` 은 지금도 겹쳐 읽기로 **반영 후 상태**를 본다 (`local.py:18` — "Reads overlay work over
live, so the agent sees its own edits immediately"). 새 개념이 아니다. 라이브 쪽이 원격이 될 뿐이다.

```
반영 후 주소 집합 = (메타데이터 카탈로그 − 내가 지운 것) ∪ (내가 만든 것)
깨질 링크          = get_backlinks(지운·병합한 주소)
```

지금은 이것이 양방향으로 틀린다. 색인에 5장뿐이라 (1) 5장 밖의 깨진 링크를 못 잡고,
(2) 실재하는 페이지로 가는 링크를 "깨졌다" 고 오판한다. 후자는 에이전트가 멀쩡한 링크를 지우게 만든다.

### 3.6 3중 게이트는 그대로

```
1  에이전트가 작업 끝내고 lint 를 부르고 스스로 고친다      guide 가 지시
2  AI 서버가 반환 전에 다시 부른다                        session.py — 에이전트 말을 믿지 않는다
3  Spring 이 반영 전에 검증한다                          DR-003 — 최종 게이트
```

3번에서 걸리면 작업 실패로 기록하고 관리자가 재처리한다 (`DR-009`). 에이전트로 자동 되돌아가지 않는다.

## 4. 흐름

### 4.1 문서 업로드

```
관리자 업로드
  → Spring: 파일 저장 · ai_job 생성
  → POST /source-parses → parsedMarkdown
  → Spring: 열람 허가 발급 (requestId → scopeKey + scopeVersion)
  → POST /wiki-transformations  (parsedMarkdown + currentIndex + requestId)
       에이전트 루프:
          search → Spring 검색 질의
          read   → Spring 본문 질의
          create · edit · merge → 작업층
          lint   → 작업층 전수 + 역조회
       lint 재검증 → 변경안 조립 → tempdir 폐기
  → 변경안 JSON
  → Spring: 검증 · 백업 · 원자적 교체 · 목차 재구성 · 검색색인 갱신
  → 열람 허가 소멸
```

**없어지는 단계**: `POST /wiki-context-selections`.

### 4.2 관리자 채팅

같은 창구를 쓴다. 흐름 2를 위해 따로 만들 것이 없다.

```
관리자: "이거 휴가 규정하고 합쳐줘"
  → Spring: 열람 허가 발급
  → POST /wiki-edits  (wikiId + instruction + chatHistory + requestId)
       read(대상)      → 본문 질의
       search("휴가 규정") → 검색 질의
       read(찾은 것)    → 본문 질의
       get_backlinks(사라질 위키) → 역조회
       merge → 작업층
       lint
  → agentMessage + 변경안 JSON
```

`currentWiki` 를 요청에 실을 필요가 없어진다 — 창구로 읽는다.

## 5. 무엇이 바뀌나

### 5.1 없어지는 것

| 무엇 | 위치 |
| --- | --- |
| `wiki-context-selections` 엔드포인트 | `routers/wiki.py:72` |
| `selection.py` 전체 | AI |
| `selectedWikis` 필드 | 계약 |
| `_hydrate_from_pages()` | `vaultfs/spring.py:76` |
| `_sync_page_references()` | `vaultfs/spring.py:106` |
| `replace_references` · `propagate_staleness` | 포트 |
| 라이브 페이지 청킹 + FTS 색인 | `chunker.py` 호출부 |
| `lint` 의 라이브 전수 검사 | `lint.py:75` |

### 5.2 유지되는 것과 바뀌는 것

**초기 서술("툴 10개 무변경")을 정정한다.** 검색 응답 형식과 `lint` 의미가 바뀐다.

```
이름·호출 방식 유지    툴 10개 전부
동작 유지            create · edit · append · merge · delete 의 쓰기 대상 (작업층)
                    겹쳐 읽기 규칙 · lint 3중 게이트 구조
                    변경안 JSON 반환 형식 (DR-025) · 프롬프트 · guide
                    임시 폴더 생성·삭제

동작 바뀜            search   응답이 두 섹션으로 나뉜다 (§7.1)
                    lint     둘로 나뉜다 (§3.5)
                    write · remove · pending_changes · allocate_page
                             첫 mutation 시 존재 확인·관계 스냅샷 (§3.3)
```

### 5.3 강해지는 것

| 항목 | 지금 | 바뀐 후 |
| --- | --- | --- |
| 위키 검색 대상 | 5장 | 부서 전체 |
| 관리자 채팅 시야 | 1장 | 부서 전체 |
| `dangling-link` | 5장 안에서만 · 양방향 오판 | 정본으로 정확 |
| `orphan-page` | 5장 안에서만 | 부서 전체 |
| `find_uncited_sources` | 이번 문서만 | 부서 전체 |
| `find_stale_pages` | 5장 안에서만 | 부서 전체 |
| 각주 원문 대조 | 이번 문서만 | 인용한 문서 전부 |
| 관계 그래프 | AI 가 매 요청 재구축 | Spring 정본 |
| 병합 · 삭제 | 5장 밖 불가 | 가능 |
| 한국어 검색 | R@5 0.33 | `ngram` 으로 0.60 · 정확어 0.95 |

**약화가 아니다.** 지금 "전체 검사" 는 이름만 그렇고 임시 색인에 5장뿐이라 실제로는 5장만 검사한다.

### 5.4 추가로 필요한 것 — 측정 하네스

`experiments/backend_sim.py` 가 로컬 파일 트리로 AI 를 돌린다. 그 헤더가 `--via-api URL`
경로를 **「측정 경로 = 프로덕션 경로」** 라고 적었다. 창구 방식으로 바꾸면 `backend_sim` 도
질의 API 를 서비스해야 한다. 안 하면 측정이 프로덕션과 다른 경로로 돈다.

`2026-07-28-ai-server-contract-design.md` 에 이미 그 계획이 있다 ("`backend_sim.py` 를 HTTP 서버로 승격").

## 6. 검토하고 채택하지 않은 안

### 6.1 요청 본문에 스코프 전체를 싣는다

한때 이쪽이 낫다고 판단했다. 철회한다.

**철회 근거** — 비용이 아니다. 소유권이다.

> 데이터 소유권·ACL 집행·장기 확장성 때문에 federation 을 선택하며,
> 그 대가로 스냅샷 원자성·재현성·장애 격리의 복잡성을 감수한다.

비용을 근거로 들었던 것은 정정한다. 712ms 는 이 문서가 스스로 "성능 변수가 아니다"(§2.2)
라고 결론냈고, 로그 노출은 redaction 으로 완화할 수 있다. 둘 다 결정적 근거가 아니다.

반대 근거로 들었던 "라이브·작업층 검색 결과 병합 규칙이 Java·Python 양쪽에 생긴다" 는
성립하지 않는다 — 점수를 섞지 않고 두 섹션으로 나누면 된다 (§7.1).

**남는 장점 (기록)** — 요청 하나가 완결된 원자적 스냅샷이고, AI 실행 중 Spring 장애에
영향받지 않고, 기존 `read`·역참조·`lint`·인용 스냅샷이 무변경으로 동작하고, 요청 payload 만
보관하면 재현된다. 규모가 수천 장을 넘거나 창구 구현이 지연되면 재검토 대상이다.

### 6.2 작업층을 Spring 이 소유한다

`create`·`edit` 툴이 Spring 창구를 부르고, Spring 이 초안을 `work/{jobId}` 에 쓴다.

**장점** — AI 완전 무상태. 작업 공간이 하나. `DR-004` 의 재시작 감지를 자연히 만족.

**채택하지 않은 이유**
- 겹쳐 읽기를 Spring 이 구현해야 한다. 에이전트가 쓰고 다시 읽으면 초안이 나와야 한다
- `pending_changes` diff 계산도 Spring 으로 옮겨야 한다
- 즉 2층 오버레이 엔진을 Java 로 다시 만드는 것이고, 검증된 메커니즘을 옮기는 위험이 있다
- 작업층은 아무도 조회하지 않는 스크래치다. 성공하면 삭제된다 (`DR-010`)
- `DR-004` 의 영속 작업 공간은 **Spring 의 반영 단계**용이고 그것은 이미 별개로 존재한다.
  AI 스크래치가 재시작에 사라져도 `DR-011` 이 처리한다

**단 `DR-011` 단독 논거는 부족하다.** `DR-011` 은 `PROCESSING` 작업을 실패로 바꿀 뿐
idempotency 를 보장하지 않는다. AI 스크래치 비영속이 허용되려면 다음이 함께 명시돼야 한다.

```
Spring 이 immutable 입력과 attempt 상태를 영속 보관한다
AI 재시작 후 같은 attempt 를 이어가지 않고 새 attempt 로 처음부터 실행한다
AI 는 Spring 상태를 중간에 변경하지 않는다
응답 유실·재시도 시 반영은 scopeVersion CAS 로 한 번만 수행된다
timeout·cancel 시 permit·lease 가 만료되고 고아 AI 프로세스가 종료된다
DR-004 의 "변환 중 출력" 이 AI 임시 스크래치를 포함하지 않는다는 해석 또는 개정
```

**C 가 맞아지는 시점** — 작업을 중간에 이어받아야 할 때, 관리자가 반영 전 초안을 봐야 할 때,
여러 AI 인스턴스가 한 작업을 나눠야 할 때. 셋 다 현재 요구사항에 없다.

### 6.3 서비스 파일을 백업 후 직접 수정한다

`DR-007` 이 명시로 금지한다. 그리고 에이전트가 2~15분 도는 동안 부분 반영 상태가 노출되어
`FR-WIKI-008`("반영이 완료된 Wiki만 노출")을 깬다. 에이전트는 같은 페이지를 여러 번 고치므로
중간 버전이 전부 노출된다.

### 6.4 AI 가 영속 검색 색인을 보유한다

동기화·백업·스코프 격리·stale 탐지를 떠안는다. AI 가 사실상 복제 DB 가 된다.
사원 위키 검색이 본문 검색이므로 Spring 이 색인을 어차피 만든다 — 두 개를 두는 것이 중복이다.

### 6.5 의미 검색(임베딩·재정렬)을 지금 도입한다 — 기각이 아니라 **보류**

정확어 질의에서 2글자 분해 BM25 가 0.95 로 전체 파이프라인과 동점이다. 위키 변환은 정확어 성격이다.
챗봇에는 필요하다 (자연어 0.25 → 0.80). 별개 안건.

**기각이 아니라 보류다.** 실제 에이전트 검색어와 MySQL `ngram` 품질을 아직 측정하지 않았다.
정확어 성격이라는 전제가 실측으로 뒤집히면 재검토한다.

참고로 QMD 는 벡터 전용 DB 를 쓰지 않는다 — `better-sqlite3` + `sqlite-vec` + `node-llama-cpp`.
색인 파일 하나에 FTS5 와 벡터가 동거한다. **의미 검색 도입이 별도 DB 도입을 뜻하지 않는다.**

## 7. 구현 시 규칙

### 7.1 검색 툴 응답은 두 섹션으로 나눈다

점수를 섞지 않는다.

```
**기존 위키 3건**  (source=live · scopeVersion=4711)
  pages/101.md   휴가 규정      §연차 > 부여 기준   "연차는 입사일로부터 1년이…"
  pages/108.md   근태 관리      §출퇴근 기록        "기록 누락은 다음 영업일까지…"

**이번 작업에서 수정한 문서 2건**  (source=work · 현재 상태)
  pages/101.md   휴가 규정 (수정됨)
  pages/temp-1.md 교육 훈련 예산 (새로 만듦)
```

- 같은 주소가 양쪽에 있으면 **work 만 반환한다** — work 가 authoritative overlay
- work 결과는 자르지 않는다
- Spring 결과는 관련도 순위를 유지하되 **통합 순위를 만들지 않는다**
- 각 항목에 `source`·`scopeVersion`·`contentHash` 를 표시한다
- 툴 설명에 "두 섹션 모두 후보, work 는 이미 수정한 현재 상태" 를 명시한다

에이전트가 이 두 목록을 제대로 합치는지는 **아직 가정이다.** §8.

### 7.2 열람 허가 — `requestId` 를 권한 토큰으로 쓰지 않는다

**초기 판단을 정정한다.** `requestId` 는 correlation ID 다. 로그에 남고 비밀값으로 취급되지
않는다. 그것으로 열람 범위를 인가하면 로그를 본 사람이 다른 부서 위키를 조회할 수 있다.

```
발급     Spring 이 변환 요청 시작에 요청 단위 capability 를 발급한다
         내부 API 키와 별개.  단일 요청·단일 scopeKey·단일 scopeVersion 에 묶인다
         짧은 만료.  로그에 남기지 않는다
전달     AI 가 창구 호출에 그 capability 를 싣는다
확인     Spring 이 서버 측 permit 으로 검증한다
거부     범위 밖은 404 — 403 이 아니다 (NFR-SEC-003 · FR-ACL-006: 존재 여부 비노출)
소멸     요청 종료·timeout·cancel 시 만료된다
```

`requestId` 는 관측·상관관계용으로 그대로 쓴다 (`API_컨벤션` 6.4). **인가에는 쓰지 않는다.**

### 7.3 스냅샷 버전 고정

에이전트가 최대 30분 돈다. 그 사이 다른 관리자가 채팅으로 같은 위키를 고칠 수 있다.

```
요청 시작에 scopeVersion 을 발급하고 허가에 고정한다
검색·본문 조회가 같은 버전을 근거로 응답한다
반영 직전에 버전이 그대로인지 확인한다 (CAS)
다르면 반영하지 않고 실패·재처리 대상으로 기록한다
버전 유지를 위해 DB 트랜잭션을 실행 시간 동안 열어두지 않는다
```

지금은 전역 직렬 큐가 우연히 이 문제를 막는다. **직렬 범위를 부서로 좁히면 구멍이 드러난다.**
따라서 `FR-AI-003` 개정과 이 조항은 함께 가야 한다.

## 8. 미해결

### 8.1 요구사항 개정 (선행 조건)

| 조항 | 개정 내용 |
| --- | --- |
| `FR-WIKI-002` | "최대 5개 선택" · "선택 Wiki 본문·관계만 고려" 삭제 → 범위 전체 + 요청 단위 열람 허가 |
| `FR-AI-003` · 8절 | 전역 직렬 → 같은 `scope_key` 직렬, 다른 `scope_key` 병렬, 전체 동시 실행 수 상한 |
| `NFR-PERF-002` | 10분 고정 → 문서 크기 비례 + 절대 천장, 백엔드 읽기 제한이 천장보다 길어야 함 |
| DR 신설 | Wiki 본문 검색 색인 (파생·재생성 가능) · 변환 스냅샷 버전 고정 |

`docs/db/erd.sql` 은 루트 `CLAUDE.md` 의 DB 스키마 변경 금지 원칙에 따라 직접 수정하지 않고
`TODO(DB)` 주석과 합의를 거친다.

### 8.2 결정 대기

| # | 결정 | 영향 |
| --- | --- | --- |
| 1 | 질의 API 를 몇 개 엔드포인트로 쪼갤지 | 관계 조회를 본문 응답에 합칠 수 있다 |
| 2 | 전체 동시 실행 수 상한 값 | 메모리·DB 커넥션 산정 필요 |
| 3 | `wiki-context-selections` 삭제 여부 | 삭제·교체 때 Spring 이 `document_wiki_refs` 로 인용 위키를 정확히 아므로 힌트로 유용할 수 있다 |
| 4 | 배포 서버 GPU | 챗봇 재정렬 모델 도입 가능성이 갈린다 |

### 8.3 측정으로 확인이 남은 것

| 항목 | 상태 |
| --- | --- |
| 실제 에이전트 검색어의 정확어/자연어 비율 | 미수집. `wiki_mcp/telemetry.py` 에 질의 문자열 기록 추가 필요. §2.3 결론이 여기 달려 있다 |
| MySQL `ngram` 의 순위 품질 | 미측정. SQLite FTS5 `bm25()` 기준 수치만 있다 |
| 에이전트가 두 섹션 검색 결과를 제대로 합치나 | 미측정. `live-only`·`work-only`·같은 문서 shadow·양쪽에 정답이 있는 질의로 평가 필요 |
| 위키 1,000장 이상 규모 | 미측정. 500장을 1차 목표로 두고 이후 재검토 |
| 측정 신뢰도 | 코퍼스·질의·정답 라벨을 한 사람이 작성했다. 블라인드 재라벨과 dev/test 분리 필요 |

### 8.4 결함 현황 (2026-07-30 갱신)

`develop` 이 이 문서 작성 중에 크게 움직였다. **D2·D4 는 해결됐고 D1·D3 은 잠재에서 실제로 바뀌었다.**

```
42e3993  feat(wiki): Wiki 변환 결과 반영과 Wiki 영속화 계층 추가 [S15P11B106-134]
         Wiki · WikiCategory 엔티티 · 리포지토리 2개
         WikiIndex · WikiTransformationApplier(391줄) · WikiTransformationService(150줄)
         WikiFileStorage · LocalWikiFileStorage · 테스트 5개.  총 2,132줄
f8835aa  feat(ai): AI 작업 종료 상태 전이와 문서별 결과 기록 추가 [S15P11B106-137]
         AiJob 상태 전이 · AiClientException.failureStage · 문서별 결과 JSON
47b7ca8  feat(wiki): Wiki 관리자 대화 API와 수정 지시 연동 추가 [S15P11B106-140]
         WikiChatMessageService · editWiki 배선 · 대화 이력 저장
         D3 의 두 번째 인스턴스가 여기서 생겼다 (아래)
fbb5492  docs: Wiki 변환 문맥 범위 확대와 본문 검색 색인 신설 [S15P11B106-139]
         요구사항 v2.9 · 계약 v1.3.1 · ERD (MR !63 병합)
```

#### 해결됨

| # | 결함 | 해결 |
| --- | --- | --- |
| D2 | 변환 worker 부재 | `DocumentParseWorker:129` 가 `transformWiki` 를 부른다. `WikiTransformationService` 가 선택→변환→반영을 잇는다 |
| D4 | Wiki 도메인 미구현 | 엔티티·리포지토리·서비스·파일 저장소·테스트가 올라왔다 |

#### 잠재 → 실제로 전환됨

변환 호출부가 없던 동안 이 둘은 발현하지 않았다. 이제 발현한다.

| # | 결함 | 내용 | 근거 |
| --- | --- | --- | --- |
| D1 | 읽기 제한 | Spring 이 응답을 10분만 기다린다. AI 상한은 30분. 실측 최장 894.6초(14.9분). **AI 가 성공해도 Spring 은 실패로 기록한다.** 끊긴 뒤에도 AI 는 계속 돌며 전역 락을 점유해 다음 문서를 막는다 | `application.yml:35` · `limits.py:20` |
| D3 | 트랜잭션 경계 | `@Transactional` 이 문서 20건 루프 전체를 감싼다. 그 안에 AI 호출 3개(`parseSource`·`selectWikiContext`·`transformWiki`)가 있다 | `DocumentParseWorker.java:53` |
| D3b | 트랜잭션 경계 — 관리자 대화 | `@Transactional` 안에서 `editWiki` 를 부르고 같은 트랜잭션에서 `applier.apply` 가 파일을 쓴다 | `WikiChatMessageService.java:95,124` |

**D1 은 "그대로 두고 문제가 생기면 조정한다" 로 결정했다.** 근거: 배포는 `claude-code` CLI 가
아니라 `deepagents` 런타임으로 도는데 그 조합의 실행 시간을 아직 측정하지 않았다.
30분 상한은 CLI 측정에서 나온 값이므로 배포 조건에 그대로 적용된다고 볼 수 없다.

**단, `47b7ca8` 로 조건이 하나 늘었다.** `read-timeout: 10m` 은 클라이언트 공통 설정이라
`editWiki` 에도 걸린다. 문서 업로드는 비동기 worker 라 사람이 기다리지 않지만 **관리자 대화는
동기 요청이라 사람이 화면 앞에서 10분을 기다린다.** Task 7(`deepagents` 측정) 결과로 D1 을
재판단할 때 이 경로를 같이 봐야 한다 — 변환용과 수정용 제한 시간을 따로 걸어야 할 수 있다
(`schedule-extraction-read-timeout` 이 이미 그 선례다).

**D3 은 실제 위험이 셋이다.**

```
① 커넥션 점유    문서 20건 × 최대 30분 = DB 커넥션 1개를 최대 10시간
                실측으로도 12건에 93분.  커넥션 풀이 마르면 다른 API 도 막힌다

② 파일·DB 불일치  WikiTransformationApplier 가 트랜잭션 안에서 파일을 쓰고 지운다
                  storeWikiMarkdown(:332) · deleteWikiMarkdown(:145) · wikiRepository.delete(:146)
                파일은 트랜잭션을 타지 않는다
                → 문서 15건째에서 잡히지 않은 오류가 나면
                  DB 행 14개는 롤백되고 파일 14개는 디스크에 남는다
                  지운 파일은 이미 사라져 복구 불가
                DR-008 의 "실패 시 백업 복원" 단위가 무너진다

③ 반영 단위      FR-AI-008 은 문서 1건씩 계속 진행을 요구하고
                DR-008 은 "문서 1건의 변환 결과" 를 반영 단위로 정했다
                지금은 20건이 한 트랜잭션이다
```

②가 가장 위험하다. 예외를 문서별로 잡고 있어 정상 흐름에서는 안 터지지만,
**잡히지 않는 오류 하나가 파일과 DB 를 어긋나게 만든다.**

권장 형태:

```java
// 바깥: 트랜잭션 없음
public void parse(AiJob job) {
    for (Long documentId : job.documentIds()) {
        parseAndApplyOne(documentId);        // 문서 1건 = 트랜잭션 1개
    }
}

private void parseAndApplyOne(Long documentId) {
    var parsed    = aiClient.parseSource(...);          // 트랜잭션 밖
    var selection = aiClient.selectWikiContext(...);    // 트랜잭션 밖
    var result    = aiClient.transformWiki(...);        // 트랜잭션 밖
    applier.apply(result);                              // @Transactional — 여기만
}
```

federation 으로 가면 이것이 **선행 조건**이 된다. AI → Spring 콜백이 생기므로,
트랜잭션을 잡은 채로 콜백을 받으면 커넥션 고갈 또는 교착이 난다 (§11.6).

**D3b — 관리자 대화도 같은 구조다.** `47b7ca8` 이 올린 경로다.

```java
WikiChatMessageService.java:95
  @Transactional
  public WikiChatReplyResponse sendChatMessage(long wikiId, String content) {
      ...
      WikiEditResponse response = requestEdit(...);   // :124  aiClient.editWiki
      wikiChatMessageRepository.save(adminMessage);
      applier.apply(wiki.scopeKey(), response);       // 파일 쓰기
      wikiChatMessageRepository.save(agentMessage);   // 여기서 실패하면?
```

`DocumentParseWorker` 보다 가볍다 — AI 호출이 1개고 커넥션 점유가 `read-timeout` 10분으로
끊긴다. **그러나 파일·DB 불일치 위험은 동일하다.** `applier.apply` 가 파일을 쓴 뒤
`agentMessage` 저장이나 응답 조립에서 실패하면 DB 는 롤백되고 파일은 남는다. 병합·삭제가
포함된 수정 지시였다면 지운 파일은 복구되지 않는다.

같은 처방이다 — AI 호출을 트랜잭션 밖으로 빼고 `applier.apply` 만 `@Transactional` 로 둔다.
`requireNoUnfinishedJob` 검사는 AI 호출 전에 있어야 하므로 트랜잭션을 두 조각으로 나눠야 한다.

`ai/` 밖이라 직접 고치지 않는다. `S15P11B106-73` 에 D3 과 함께 전달한다.

#### 신규 — 백엔드가 명시로 요청한 스키마 변경

| # | 결함 | 근거 |
| --- | --- | --- |
| D10 | `wiki` 테이블에 `summary` 컬럼이 없다. 계약이 `selectedWikis[].summary` 를 요구하므로 현재 **`index.md` 마크다운에서 요약을 되읽고, 없으면 제목으로 대체한다.** 목차 파일 형식에 의존하는 우회다 | `Wiki.java:23` `TODO(DB)` · `WikiTransformationService:132` |

`Wiki.java:23` 원문 — "`wiki.summary VARCHAR(500)` 추가가 정본 해결책이다.
erd.sql 변경 필요 — 팀원 합의 후 진행."

#### AI 쪽 남은 결함

| # | 결함 | 근거 |
| --- | --- | --- |
| D5 | 에이전트 `lint` 기본값이 `pattern="*"`·`check_scope="all"`. 원본문서는 이번 것만 오므로 기존 페이지 각주가 전부 `unresolved-citation` 후보가 된다. 에이전트 지시가 "마지막에 `lint`" 라서 남의 각주를 고치려 들 수 있다 | `lint.py:73` · `base.py:117` |
| D6 | `chunker.py:26` 주석이 반대로 적혀 있다 — "과소계산하면 청크가 작아진다" → 실제로는 커진다. 한국어 실토큰 3,454 vs 목표 512. **검색 품질에는 영향 없음이 측정으로 확인됐다** (§2). 주석만 고친다 | `chunker.py:26` |
| D7 | `selectedWikis` 에 개수·바이트 상한이 없다 | `schemas.py:80` |
| **D8** | **출력 토큰이 과하다.** 시간 ≈ 출력토큰 ÷ 55 로 거의 일정하다(12건 중 11건이 45~59 tok/s). 6.9KB 문서에 출력 30,224 토큰, 21.9KB 에 50,620 토큰. 위키 2~3장이면 5,000 토큰 수준이어야 한다. `--effort` 를 지정하지 않아 CLI 기본값으로 측정됐고 `manifest.json` 에도 기록이 없다 | `claude_code.py:144` · `experiments/2026-07-27-opus46-12docs/report.json` |
| D9 | `10-onboarding` 이 724초 동안 출력 589 토큰. 턴 2개에 툴 77회. 다른 문서의 1/60 속도. 별개 버그 | 같음 |

**D8 은 배포 런타임에서 다시 재야 한다.** 지금 수치는 `claude-code` CLI 것이고
배포는 `deepagents` 다. `--output-format json` 이라 턴별·툴별 내역이 남지 않으므로
`stream-json` 전환과 `--effort` 대조가 선행한다. 문서 12건 순차가 93분,
계약 상한 20건이면 약 4시간이라는 수치도 CLI 기준이다.

## 9. 순서

**D2·D4 가 해결되어 기초가 생겼다.** 순서를 그에 맞춰 다시 놓는다.

```
0  이미 완료 (develop)
   Wiki 도메인 · 변환 반영 · 작업 종료 처리                    42e3993 · f8835aa

1  D3 트랜잭션 경계                                          ← 지금 가장 위험
   AI 호출을 @Transactional 밖으로
   반영 단위를 문서 1건으로
   파일·DB 불일치를 막고, federation 의 선행 조건이 된다

2  D10 wiki.summary 컬럼                                     ← 백엔드가 요청했다
   목차 마크다운 되읽기 우회를 제거한다
   지금 ERD 를 고치는 중이라 같이 넣는 것이 싸다

3  병렬
   3a  D5 에이전트 lint 범위 · D6 chunker 주석 · D7 요청 상한   ai/ 안에서 끝난다
   3b  D8 배포 런타임(deepagents) 실행 시간 측정               지금 수치는 CLI 것이다
       stream-json 전환 · --effort 대조
   3c  요구사항 · 계약 · DR 개정 제기 (§8.1)

4  검색 색인 채우기 + ngram 질의                              사원 위키 검색이 P0
   측정 완료: MySQL 8.4 ngram_token_size=2
     정확어 R@5 0.95 (SQLite bigram 과 동일)
     질의 방식은 용도별로 — 에이전트는 boolean phrase, 사원은 natural
     boolean +req(AND)는 자연어 질의를 전멸시킨다 (para 0.00)

5  capability 인증 · scopeVersion · lease · CAS

6  Spring 질의 API

7  AI federated 어댑터 + lint 분리 (§3.5)

8  계약 · 장애 · 한국어 검색 회귀 테스트

9  CAS 배포 후 전역 락을 scope 별로 축소                        CAS 가 선행 조건

10 챗봇용 의미 검색                                           별개 안건
```

**D1(읽기 제한)은 목록에서 빠졌다.** "그대로 두고 문제가 생기면 조정한다" 로 결정했다.
배포 런타임 측정(3b) 결과에 따라 다시 판단한다.

### 9.1 MySQL 실측 (2026-07-29)

`erd.sql` 을 MySQL 8.4.11 컨테이너에 실제로 적용하고 한국어 코퍼스 100장으로 측정했다.
Codex 가 지적한 "SQLite 측정만으로 `ngram_token_size=2` 를 확정하면 안 된다" 에 대한 답이다.

| 질의 방식 | R@5 | R@20 | MRR | 0건 | 정확어 R@5 | 정확어 MRR | 자연어 R@5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `natural` | 0.55 | 0.68 | 0.57 | 7 | 0.90 | 0.91 | 0.20 |
| `boolean` bare | 0.55 | 0.62 | 0.56 | 10 | **0.95** | **0.96** | 0.15 |
| `boolean` phrase | 0.55 | 0.62 | 0.56 | 10 | **0.95** | **0.96** | 0.15 |
| `boolean +req` | 0.47 | 0.50 | 0.48 | 20 | 0.95 | 0.96 | **0.00** |
| *(대조) SQLite bigram* | *0.60* | *0.68* | *0.59* | *7* | *0.95* | *0.96* | *0.25* |

**정확어 0.95 / 0.96 이 SQLite 측정과 완전히 일치한다.** 위키 변환에 중요한 값이 전이됐다.

- 정밀도 확인: 없는 단어(`싸이버펑크`·`블록체인`) 0건, 있는 단어(`연차`) 15건. 오탐 폭증 없음
- 색인 비용: 원본 2.0MB → 청크 데이터 3.6MB → FULLTEXT 색인 10.1MB (토큰 378,738개).
  **원본의 약 5배.** 위키 1,000장이면 100MB 수준
- 운영 주의 1: 삽입 직후 색인이 메모리 캐시에 있어 `innodb_ft_index_table` 에 보이지 않는다.
  `innodb_optimize_fulltext_only=ON` + `OPTIMIZE TABLE` 로 flush 한다.
  FULLTEXT 가 있는 InnoDB 의 `OPTIMIZE TABLE` 은 online DDL 이 아니다
- 운영 주의 2: 클라이언트 charset 이 utf8mb4 가 아니면 한국어가 이중 인코딩된다
  (측정 중 실제로 겪었다 — `C3AC E28094` mojibake). **JDBC 연결 설정을 확인해야 한다**

## 10. 이 문서가 정정한 것

| # | 초기 서술 | 정정 |
| --- | --- | --- |
| 1 | 요청 본문에 스코프 전체를 싣는 것이 낫다 | 철회 (§6.1). 단 근거는 비용이 아니라 소유권·ACL·확장성 |
| 2 | 하이드레이션 100장 93ms | 실제 712ms. 청킹+FTS 만 잰 것이었다 (8배 오차) |
| 3 | 관계 재구축 2개가 없어진다 | 틀렸다. 작업층 파싱은 남는다. 라이브만 Spring 파생 색인으로 (§3.2.1) |
| 4 | 읽은 것만 캐시하면 `pending_changes` 무변경 | 틀렸다. 카탈로그/본문 2층 + 3상태 필요 (§3.3) |
| 5 | 툴 10개 무변경 | 틀렸다. `search` 응답 형식과 `lint` 의미가 바뀐다 (§5.2) |
| 6 | 라이브 페이지는 전수 검사하지 않는다로 충분 | 부족하다. `uncited-source` 전역 불변식을 놓쳤다 (§3.5) |
| 7 | 내가 링크를 없앤 페이지도 역조회 대상 | 틀렸다. 나가는 링크 제거는 남의 링크를 깨지 않는다 (§3.5.1) |
| 8 | `requestId` 로 열람 허가를 판정한다 | 틀렸다. correlation ID 를 권한 토큰으로 쓸 수 없다 (§7.2) |
| 9 | AI 스크래치 비영속은 `DR-011` 로 충분 | 부족하다. attempt·idempotency·CAS·lease 조건 필요 (§6.2) |
| 10 | 의미 검색 기각 | 보류. 실측 전이다 (§6.5) |
| 11 | 재정렬 상한 +0.08 이라 나중 일 | 상한 예측은 맞았고 판단이 틀렸다. MRR 이 +0.19 로 최대 이득이었다 |

## 11. 구현 전 확정 필요

**이 문서만으로는 구현할 수 없다.** 방향은 정해졌고 아래가 비어 있다.

### 11.1 질의 API 계약

```
각 엔드포인트의 request·response 스키마
페이지네이션 · 배치 조회 · 결과 상한
검색 결과에 실을 필드 (source · scopeVersion · contentHash · breadcrumb · snippet)
관계 조회의 방향·유형 표현 (cites / links_to, 각주 단위)
```

### 11.2 상태와 실패

```
scopeVersion 불일치 시 AI 의 중단 동작
retry · timeout · cancellation · lease 만료
Spring 장애·응답 유실 시 상태 전이
permit·lease 가 AI timeout 과 프로세스 고아화 후에도 남는 실패 모드
과거 scopeVersion 보존 여부 — 보존하지 않으면 "같은 버전 조회" 를 제공할 수 없다
```

### 11.3 캐시와 그래프

```
메타데이터 카탈로그와 본문 캐시의 경계
negative cache (missing) 와 first-mutation hydration
라이브 그래프 + 작업층 그래프 오버레이 알고리즘
관계 추가뿐 아니라 제거를 계산하는 방식 (changes.py 의 before 계산)
```

### 11.4 검색

```
work 가 shadow 한 라이브 결과를 제거하면 실제 K 번째 후보를 잃는다
  → exclude-address 파라미터 또는 over-fetch 필요
work 검색 결과의 개수·문자·토큰 상한
  → "work 결과는 자르지 않는다"(§7.1) 는 D8 과 충돌한다. 상한을 둔다
```

### 11.5 카테고리·목차

```
카테고리 목록·사용량·생성·병합·삭제 계약 (FR-WIKI-014)
index 완전성 불변식 — 반영 후 노출 대상 위키를 전부 포함해야 하나
```

### 11.6 재진입

```
Spring → AI 호출 중에 AI → Spring 콜백이 생긴다
D3 가 남아 있거나 scope lease 가 비재진입 락이면 DB 커넥션 고갈 또는 교착이 난다
  → lease 는 재진입 가능해야 하고, AI 호출은 트랜잭션 밖이어야 한다
```

### 11.7 계약 테스트

```
live-only · work-only · 같은 문서 shadow · 양쪽에 정답이 있는 질의
scopeVersion 충돌 시나리오
Spring 장애 주입
한국어 검색 회귀 (코퍼스 100장 + 질의 40개)
```

**11.6 이 가장 위험하다.** 지금 구조에는 AI → Spring 방향 호출이 없어서 재진입 문제가
존재하지 않는다. federation 은 그것을 만든다. `DocumentParseWorker.java:47` 의
`@Transactional` 이 남아 있으면 커넥션을 잡은 채로 콜백을 받게 된다.
