> **이관 주석 (2026-07-28)** — 개인 검증 워크스페이스의 `ai-server/` 설계 기록을 그대로
> 옮겼다. 본문 경로는 당시 기준이며 지금은 다음에 대응한다: `ai-server/mcp/` →
> `ai/src/wiki_mcp/`, `ai-server/runtime/` → `ai/src/agent_runtime/`, `ai-server/api/` →
> `ai/src/wiki_api/`, `docs/FastAPI명세서.json` → `docs/api/AJT-FastAPI-Internal-API.postman_collection.json`
> (현행 v1.3.0). `backend_sim.py`·`experiments/` 는 이관하지 않았다.

# AI 서버 계약 정합 설계 — 위키 변환 계열

> ⚠ 계약 v1.1.0(2026-07-28)으로 4.1(읽기 4개)·4.2(재조정 엔드포인트)·경계 그림의 역호출이
> 대체됐다. 현행은 `2026-07-28-ai-server-v1.1-adaptation.md`. 나머지 절(4.4 응답 필드·4.5
> 오류·6 시간 상한·7 검증)은 유효.

- 작성일: 2026-07-28
- 대상: `ai-server/` (FastAPI AI 서버)
- 범위: `wiki-transformations` · `wiki-edits` · 재조정. 나머지 4개 엔드포인트는 별도 설계
- 근거 문서: `docs/요구사항정의서.md`(2.5) · `docs/erdTable.sql` · `docs/API_컨벤션.md` ·
  `docs/FastAPI명세서.json`(v1.0.0) · `docs/BackendAPI명세서.json`(v1.0.0) ·
  `docs/AJT 파일 디렉터리 구조 설계.md`

## 1. 배경

`FastAPI명세서.json`이 AI 서버 계약 v1.0.0을 확정했다. 6개 엔드포인트 전부
`Spring → FastAPI` 단방향이고, 위키 컨텍스트를 요청 본문(`currentIndex` ·
`currentCategories` · `currentWikis`)으로 받는다.

`ai-server/`는 다른 방식으로 지어져 있다. 에이전트가 MCP 툴로 검색·조회하고 작업 공간에만
쓴다. 그 차이가 중요한 이유는 **측정 결과가 그 방식에서 나왔기** 때문이다.

12건 측정(`ai-server/experiments/2026-07-27-opus46-12docs`):

```
페이지 20 · 카테고리 5 · 페이지간 링크 69 · 각주 616(613 원문 인용) · 조각 0
lint error 0 / warn 16 · 94분 47초 · $25.37
시간 = 1.9분 + 5.0분 × (크기/1만자)      R² 0.853   ← 위키 크기 항이 없다
```

이전 spike는 위키 전체를 프롬프트에 주입했고 거기서 무너졌다(`spikes/wiki-convert-loop/FINDINGS.md`
§9): 조각 13/26, 카테고리 1개로 붕괴, `merge` 미발동. 검색·조회로 바꾸니 셋이 함께 사라졌다.

**따라서 계약의 필드는 지키되 위키 컨텍스트를 얻는 방식은 검색·조회를 유지한다.** 팀 논의도
같은 방향이었다 — 목록을 받고 필요한 것만 되물어 읽는다. 다만 그 역방향 엔드포인트가 계약
v1.0.0에 들어가지 않았다(두 Postman 파일에 `AI → Spring`이 0건).

## 2. 결정 요약

| 항목 | 결정 |
|---|---|
| 계약 준수 | 최대한 그대로. 문제 지점만 Spring 담당자와 협의 |
| 설계 범위 | 위키 변환 계열 — `wiki-transformations` · `wiki-edits` · 재조정 |
| 위키 컨텍스트 | 목록 1콜 + 필요한 본문만 되물어 읽는다 (역호출) |
| `currentWikis` | 제거. 되물을 수 있으니 중복 |
| 재처리 | 재조정 엔드포인트 신설 (`POST /internal/v1/wiki-reconciliations`) |
| AI→Spring 명세 | 별 파일로 신설. 기존과 같은 Postman v2.1 형식 |
| 저장 계층 | AI 서버가 요청마다 임시 색인을 만들고 폐기 — 무상태 |
| `work/{jobId}` | Spring 소유. AI 서버는 `/data/ajt`를 만지지 않는다 |
| 1주 안 런타임 | `claude-code`(구독, 비용 0, 품질 측정됨). `deepagents`는 어댑터만 검증 |
| 시간 상한 | 고정 10분 → 크기 비례 + 절대 천장. `NFR-PERF-002` 문구 조정 |
| 측정 | 계속 유지. `backend_sim`을 HTTP 서버로 승격해 Spring 자리를 흉내낸다 |

## 3. 경계와 데이터 흐름

```
프론트 ──► Spring ──POST /internal/v1/wiki-transformations──► FastAPI (AI 서버)
                │                                                  │
                │◄──── GET /internal/v1/… 읽기 4개 ────────────────┤
                │      X-Internal-API-Key · requestId 범위 강제      │
                │                                                  │
                │◄──── 200 { wikiChanges, categoryChanges, … } ────┘
                │
                └─► work/{jobId} 작성 · 검증 · 원자적 이동 · DB 트랜잭션
```

| 것 | 소유 |
|---|---|
| DB · 파일 · 권한 · 트랜잭션 · `work/{jobId}` | Spring |
| 판단 · 초안 · 각주 검증 | AI 서버 |

`API_컨벤션.md`는 "AI 서버는 DB를 직접 조회하거나 수정하지 않습니다"만 규정한다. 이 설계는 더
좁게 잡는다 — **파일도 만지지 않는다.** DR-007·008의 작업 공간 절차(작성 → 검증 → 원자적 이동
→ DB 커밋)는 Spring이 수행한다. AI 서버는 자기 임시 공간에서 초안을 쓰고 JSON으로 돌려준다.

### 한 요청의 생애

```
1  요청 도착        jobId · documentId · scopeKey · parsedMarkdown · currentIndex · currentCategories
2  라이브 층 채움    GET /internal/v1/wiki-pages?includeContent=true  1콜
                   → 임시 SQLite 라이브 층 + 청크 FTS 색인
3  에이전트 시작     guide 읽고 → search 로 후보 좁히고 → read 로 자기가 고른 것만 본다
4  쓰기             작업 층에 쌓인다. Spring 안 부른다. 쓰고 다시 읽고 고칠 수 있다
5  lint             각주가 가리킨 원본문서만 당겨 원문 대조. error 남으면 실패 반환
6  응답 조립         작업 층 diff → wikiChanges · categoryChanges · relationChanges · indexEntries
7  폐기             임시 SQLite 버린다
```

3번이 이 설계의 핵심이다. 위키를 전부 받아 색인하지만 **에이전트가 프롬프트로 읽는 양은 자기가
고른 3~5개**다. 12건 측정에서 시간이 위키 크기와 무관했던 이유이고, 이 구조를 유지하는 한 그
결과가 계속 유효하다.

### 두 층 겹쳐 읽기

`read`는 작업 층을 먼저 보고 없으면 라이브 층을 본다. 방금 만든 페이지를 바로 다시 읽고 고칠
수 있다. 라이브 층은 에이전트에게 **읽기 전용**이다 — 검증 안 된 것이 서비스 파일에 닿는 경로가
없다(DR-007).

### Spring 호출 횟수

| 단계 | 콜 |
|---|---|
| 페이지 목록 + 본문 (`includeContent=true`) | 1 |
| 원본문서 파싱 본문 (lint 원문 대조) | 1~3 |
| **문서 1건당** | **2~4** |

왕복이 적은 대신 첫 콜의 페이로드가 크다. 100페이지면 30만자 정도이며 HTTP로는 문제가 없다.
10분 상한 안에서 무시할 만하다. 실제 수치는 검증 계층 3에서 기록한다.

## 4. 계약

### 4.1 Spring이 열어줄 읽기 4개 (신설)

전부 `X-Internal-API-Key`. **모두 `requestId`와 `scopeKey`를 받는다** — Spring이 "이 요청은
자기 `scopeKey`만 읽을 수 있다"를 강제해야 한다. 없으면 AI 서버가 임의 범위를 읽어 권한 우회가
된다(`FR-ACL` · 권한 비노출).

`jobId`가 아니라 `requestId`인 이유: `wiki-edits`는 `ai_job`을 만들지 않는다
(`POST /api/v1/wikis/{id}/chat-messages`가 동기 처리이고 응답에 `jobId`가 없다). `jobId`로
제한하면 채팅 수정 중에는 되물을 수 없다.

**`requestId`는 기존 규약을 그대로 쓴다.** `API_컨벤션.md` 6.4가 "백엔드 서버가 AI 서버를
호출하는 경우 동일한 `requestId`를 AI 서버에 전달합니다"를 이미 규정하고 있다. AI 서버는
`X-Request-Id` 헤더로 받은 값을 읽기 4개에 그대로 되돌려 붙인다. **세 엔드포인트의 요청 본문에
새 필드를 넣지 않는다.**

Spring은 진행 중인 요청의 `requestId`에 그 요청의 `scopeKey`만 허용한다. 요청이 끝나면 그
`requestId`의 열람 허가도 사라진다. 요청 단위 열람 허가여서 작업이 끝난 뒤 남는 권한이 없다.

```
GET /internal/v1/wiki-pages?requestId=req-42&scopeKey=D1-D2&includeContent=true
→ { "items": [ { "wikiId": "101", "title": "휴가 규정",
                 "wikiPath": "wiki/D1-D2/pages/a3f2c1d4.md",
                 "summary": "연차와 반차 사용 기준",
                 "wikiCategoryId": "9", "categoryName": "휴가 및 근태",
                 "contentMarkdown": "---\ntitle: 휴가 규정\n…",   // includeContent=true 일 때만
                 "updatedAt": "2026-07-27T09:00:00Z" } ] }

GET /internal/v1/wikis/{wikiId}/content?requestId=req-42&scopeKey=D1-D2
→ { "wikiId": "101", "title": "휴가 규정",
    "contentMarkdown": "---\ntitle: 휴가 규정\n…" }        프론트매터 포함 원문

GET /internal/v1/documents/{documentId}/parsed?requestId=req-42&scopeKey=D1-D2
→ { "documentId": "15", "originalFileName": "취업규칙.pdf",
    "parsedMarkdown": "# 취업 규칙\n…" }                    lint 원문 대조용

GET /internal/v1/wiki-spaces/{scopeKey}/index?requestId=req-42
→ { "scopeKey": "D1-D2", "indexMarkdown": "# 목차\n…" }
```

`summary`는 `GET /api/v1/wikis` 응답과 같은 값이다. AI 서버 프론트매터의 `description`이 여기
매핑된다.

**`wikiPath`가 반드시 필요하다.** AI 서버의 주소는 `pages/{pageKey}.md`이고 `pageKey`는 에이전트가
발급한 값이라 `wikiId`에서 유도할 수 없다. `wiki.wiki_path`가 저장 컬럼이므로(DR-016) 그 값을
그대로 받아 `wiki/{scopeKey}/` 접두사를 떼면 주소가 복원된다. 이것이 없으면 기존 페이지를 고칠 수
없고 전부 새로 만들게 된다.

**검색 엔드포인트는 요구하지 않는다.** `search`는 받은 목록 안에서 AI 서버가 수행한다.
검색 구현 기술은 요구사항 9절에서 아직 미정이므로 여기에 의존하면 막힌다.

허가된 `scopeKey` 밖 요청은 `404`로 거부한다(`403`이 아니다 — 존재 여부를 노출하지 않는다).
`requestId`가 진행 중인 요청이 아니어도 `404`다.

#### `includeContent=true` 가 필요한 이유 — 지연 적재는 성립하지 않는다

`search`는 `document_chunks` + SQLite FTS5로 돈다. 청크는 **본문을 저장할 때** 생성된다
(`store_chunks(db, doc_id, chunk_text(content))`). 라이브 페이지 본문을 비워두고 필요할 때 당기는
설계는 다음을 만든다:

```
search("회의")  →  청크가 없다  →  0건
              →  에이전트가 기존 페이지를 못 찾는다
              →  전부 새로 만든다   ← 옛 spike의 조각화(13/26)가 재발
```

**이 설계가 막으려는 실패를 설계가 만든다.** 그리고 12건 측정은 FTS에 본문이 전부 들어간 상태에서
나왔으므로, 본문을 빼면 그 수치의 근거도 사라진다.

따라서 **본문을 한 번에 전부 받는다.** 대신 프롬프트에는 넣지 않는다 — 임시 SQLite에 색인하고
에이전트는 `search`·`read`로 자기가 고른 것만 본다.

```
HTTP 페이로드  100페이지 30만자 — 문제 없다. 전송만 조금 느려진다
프롬프트       에이전트가 읽은 3~5개분만 — 12건 측정과 같다
```

받는 것과 프롬프트에 넣는 것은 별개다. 계약이 `currentWikis`로 하려던 것을 HTTP 본문으로 하되
프롬프트 폭증만 피한다.

`GET /wikis/{wikiId}/content` 단건 엔드포인트는 `wiki-edits`(페이지 1건)와 재읽기에 쓴다.

### 4.2 재조정 (신설)

```
POST /internal/v1/wiki-reconciliations
{
  "jobId": "43",
  "scopeKey": "D1-D2",
  "reason": "document_removed",          // document_removed | document_replaced
  "documentId": "15",
  "removedParsedMarkdown": "# 취업 규칙\n…",
  "newParsedMarkdown": null,             // document_replaced 일 때만
  "currentIndex": "# 목차\n…",
  "currentCategories": [ … ]
}
→ 200  wiki-transformations 와 같은 응답 스키마
```

에이전트가 하는 일: `removedParsedMarkdown`의 각주를 파싱해 어느 페이지 어느 문단이 이 문서
근거였는지 찾고 그 부분만 걷어낸다. 근거가 전부 사라진 페이지는 `delete`, 일부만이면 `edit`.

AI 서버의 참조 그래프가 이 자료를 각주 단위로 들고 있다:

```
get_backlinks("sources/15/parsed/content.md")
  → pages/a3f2c1d4.md  [^2]  "3장 휴가"  "연차는 15일…"
  → pages/7b91e0c2.md  [^1]  "5장 급여"  "급여는 매월…"
```

**호출 순서 제약**: `DELETE /api/v1/documents/15`가 파싱 파일을 먼저 지우면
`removedParsedMarkdown`을 만들 수 없다. **재조정을 먼저 부르고 그 다음에 지운다.**

`reason` 두 값으로 프론트 API 4개를 덮는다:

| 프론트 API | Spring이 부르는 것 |
|---|---|
| `DELETE /documents/15` | 재조정 `document_removed` → 성공 후 삭제 |
| `PUT /documents/15/file` | 재조정 `document_replaced` (옛·새 파싱 둘 다) |
| `PATCH /documents/15` 범위 변경 | 옛 범위 재조정 `document_removed` + 새 범위 `wiki-transformations` — 2콜 분해 |
| `POST /documents/15/retry` | `wiki-transformations` 재호출. 새 것 없음 |

`PUT /documents/{id}/file`은 1주 안에 구현되지 않을 수 있다. 그래도 `reason`을 열거형으로
두어 나중에 값 하나만 추가하면 되게 한다 — 계약이 깨지지 않는다.

### 4.3 기존 2개 정합

`wiki-transformations` — 필드 하나 제거, 하나 확정.

```diff
  {
    "jobId", "documentId", "scopeKey", "parsedMarkdown", "currentIndex",
-   "currentWikis": []
+   "currentCategories": [ { "wikiCategoryId", "name", "description" } ]
  }
```

`currentWikis`는 되물을 수 있으니 중복이다. `currentIndex`는 싸고 에이전트의 출발 지도라
남긴다. `currentCategories`는 작고 카테고리 수렴에 직접 쓰이니 남긴다.

`wiki-edits` — **요청 스키마 그대로.** `wikiId` · `instruction` · `currentWiki` ·
`evidenceDocuments` · `chatHistory` 모두 필요하고 범위가 좁아 되물을 것이 적다. 관련 페이지를
고쳐야 할 수 있으므로 읽기 4개는 여기서도 쓴다.

`POST /api/v1/wikis/{wikiId}/chat-messages` 정책상 "같은 `scopeKey`에서 문서 변환 중이면 409"다
— 채팅 수정도 전역 직렬 큐에 들어간다. AI 서버는 직렬화를 강제하지 않는다(Spring 몫).

응답은 계약 그대로. `tempWikiId`/`tempCategoryId` 유지.

### 4.4 계약에 비어 있는 필드 채우기

#### `relationChanges`

계약에 예시가 빈 배열뿐이다. DR-002·003의 무방향 관계를 채우는 필드다.

```json
"relationChanges": [
  { "action": "link",   "type": "wiki_document", "wikiRef": "wiki-temp-1", "documentId": "15" },
  { "action": "link",   "type": "wiki_wiki",     "wikiRef": "wiki-temp-1", "targetWikiRef": "101" },
  { "action": "unlink", "type": "wiki_document", "wikiRef": "101",         "documentId": "15" }
]
```

`wikiRef`는 `tempWikiId`(신규) 또는 실제 `wikiId`(기존) 둘 다 받는다. Spring이 양쪽 JSON에
한 트랜잭션으로 쓴다 — `wiki.wiki_refs` · `wiki.document_refs` · `document.document_wiki_refs`.
무방향이라 한쪽만 쓰면 안 된다.

`unlink`가 재조정에 필수다.

#### `wikiChanges[].evidence` — FR-AI-009 충족에 필요

FR-AI-009는 "요약에는 …각 변경의 **근거 문서 위치**를 포함한다"를 요구한다. 계약의 `summary`는
문자열 하나여서 담을 자리가 없다.

```json
{ "action": "create", "tempWikiId": "wiki-temp-1", "title": "휴가 규정",
  "contentMarkdown": "…",
  "evidence": [
    { "documentId": "15", "footnote": "1", "location": "3장 휴가",
      "quote": "연차는 15일을 부여한다" }
  ] }
```

이 필드로 셋이 해결된다:

- FR-AI-009 근거 위치 — 관리자 요약 화면에 표시
- NFR-AI-002 근거 기반 생성 — Spring이 반영 전에 원문 재대조 가능
- 프론트가 각주를 눌렀을 때 원본 위치 표시

Spring 부담은 없다. 저장하지 않고 요약 표시에만 써도 요구사항은 충족된다. 저장할 경우
`ai_job.document_results` JSON에 들어간다.

### 4.5 오류 구조

`API_컨벤션.md` 6.2의 공통 구조를 따른다. FastAPI 기본 `422`를 `400`으로 변환하고
(컨벤션 361행), `requestId`는 `X-Request-Id` 헤더로만 내보낸다(컨벤션 6.4).

내부 API에만 `failureStage`를 하나 더한다 — FR-AI-001·NFR-AI-003이 실패 단계 저장을 요구하고,
`GET /api/v1/ai-jobs/{jobId}` 응답에 `currentStage`가 있으나 AI 서버가 그것을 알려줄 필드가
계약에 없다.

```json
{ "timestamp": "2026-07-28T09:00:00Z", "status": 500,
  "error": "Internal Server Error",
  "code": "WIKI_TRANSFORMATION_FAILED",
  "message": "문서 분석이 제한 시간을 초과했습니다.",
  "path": "/internal/v1/wiki-transformations",
  "fieldErrors": [],
  "failureStage": "agent_timeout" }
```

| `failureStage` | 뜻 |
|---|---|
| `context_load` | Spring 읽기 실패 |
| `agent_start` | 런타임 기동 실패 (MCP 서버 안 뜸 · 툴 0개) |
| `agent_timeout` | 제한 시간 초과 |
| `agent_error` | 모델·툴 오류 |
| `lint_failed` | 검증 error 잔존 — 이 문서분은 반영하지 않는다 |
| `assemble` | 응답 조립 실패 |

`lint_failed`가 「승인 게이트 없음, 단 기계 검증은 거친다」의 구현이다. error가 남으면 `200`이
아니라 실패로 돌린다. **부분 반영을 하지 않는다.**

프론트 공개 API는 `failureStage`를 노출하지 않는다. Spring이 받아 `document_results`에 저장하고
사람이 읽을 문장으로 바꿔 내보낸다.

## 5. 내부 구조

### 유지 (손대지 않는다)

```
mcp/tools/*            툴 10개 — search · browse · references · read · create · edit ·
                       append · merge · delete · lint · guide
mcp/tools/guide.py     GUIDE_TEXT 4,549자. 12건 측정이 이 판본으로 나왔다
mcp/tools/lint.py      검사 12종. 각주 원문 대조 포함
mcp/shared/schema.sql  2단 저장 · visible_documents 뷰 · 주소 기반 참조 그래프
mcp/telemetry.py       툴 호출 계수
tests/                 122건
```

측정 결과가 유효하게 남는 이유가 이것이다. **에이전트가 보는 면이 바뀌지 않는다.**

### 바뀌는 것 하나 — 라이브 층을 무엇으로 채우나

2단 SQLite는 그대로 쓴다. 라이브 층의 출처만 갈린다.

```
mcp/vaultfs/local.py    2단 SQLite · 겹쳐 읽기 · 작업 층 쓰기 · diff        유지
mcp/vaultfs/rebuild.py  파일 트리에서 채운다                                유지 (backend_sim 내부용)
mcp/vaultfs/spring.py   Spring 읽기 4개에서 채운다                          신설
```

`spring.py`:

```
open()          GET /internal/v1/wiki-pages?includeContent=true 1콜
                → 라이브 층 행 + 청크 FTS 색인. 이후 search 가 돈다
get()           이미 색인된 것을 읽는다. 없는 주소면 GET /wikis/{id}/content 로 보충
find_source()   GET /documents/{id}/parsed  (요청당 1~3회, lint 가 부른다)
search_chunks() 색인 안에서 수행 — Spring 호출 없음
```

`mcp/vaultfs/base.py`의 18개 메서드가 툴이 아는 전부다. `spring.py`는 그중 읽기 쪽만 다르게
채운다. 툴·프롬프트·`lint`는 `spring.py`의 존재를 모른다.

### 신설

```
api/app.py              FastAPI 앱
api/deps.py             X-Internal-API-Key 검증
api/errors.py           공통 오류 구조 · 422→400 · X-Request-Id · failureStage
api/routers/wiki.py     POST /internal/v1/wiki-transformations
                             /internal/v1/wiki-edits
                             /internal/v1/wiki-reconciliations
api/changes.py          작업 층 diff → 계약 응답 조립
mcp/vaultfs/spring.py   위
```

`api/changes.py`가 계약 모양을 아는 유일한 곳이다. `mcp/`는 계약을 모르고 `api/`는 SQLite를
모른다 — `pending_changes()` 반환값이 둘 사이의 유일한 접점이다.

변환:

```
pending_changes()  { type: "create", address: "pages/a3f2c1d4.md", content: "…", evidence: [ … ] }
                       ↓
                   { action: "create", tempWikiId: "wiki-temp-1", title: "휴가 규정",
                     contentMarkdown: "…", evidence: [ … ] }

pageKey a3f2c1d4  →  tempWikiId wiki-temp-1
본문의 페이지 링크도 함께 치환한다 (Spring이 실제 wikiId 발급 후 최종 치환)
```

`index.md`는 에이전트가 쓴 마크다운을 파싱해 `indexEntries` 배열
(`{ wikiRef, order, title, summary }`)로 뽑는다 — 계약이 구조를 요구한다.

### 승격 — `backend_sim.py`

측정을 계속 유지하므로 버리지 않는다. **HTTP 서버로 승격한다.**

```
backend_sim (Spring 자리)
  ├─ GET  /internal/v1/wiki-pages              읽기 4개를 실제로 서비스
  ├─ GET  /internal/v1/wikis/{id}/content
  ├─ GET  /internal/v1/documents/{id}/parsed
  ├─ GET  /internal/v1/wiki-spaces/{k}/index
  ├─ 업로드 흉내 → AI 서버 호출 → 응답 검증 → 파일 반영 → 관계 JSON 갱신
  └─ 내부 저장은 local.py + 로컬 파일 트리
```

이로써 측정 경로와 프로덕션 경로가 같아진다:

```
측정      에이전트 → 툴 → spring.py → HTTP → backend_sim
프로덕션  에이전트 → 툴 → spring.py → HTTP → Spring
                          ↑ 같은 코드
```

지금은 측정이 `local.py`, 프로덕션이 `spring.py`로 갈려 **측정이 프로덕션을 보장하지 못한다.**
이 변경이 그 구멍을 막는다.

경계가 HTTP로 강제되므로 "`mcp/`·`runtime/`은 `backend_sim`을 임포트하지 않는다"는 원칙이
grep이 아니라 프로세스 분리로 지켜진다.

새 CLI 플래그:

```
--serve              backend_sim 을 HTTP 서버로 띄운다
--dry-run            AI 서버 응답을 기록하되 파일에 반영하지 않는다
--from-experiment    기존 실험의 data/ 를 복사해 시작점으로 쓴다 (원본 보존)
```

`--dry-run`과 `--from-experiment`가 없으면 약한 모델 실험이 12건 측정 데이터를 오염시킬 수 있다.

## 6. 시간 상한

`NFR-PERF-002`는 문서 1건당 10분이며 등급이 `권장`이다. 크기를 보지 않는다. 12건 중 3건이
초과했고, 그 3건은 고장이 아니라 문서가 컸다.

```
예상 = 1.9분 + 5.0분 × (글자수 / 1만)          ← 12건 회귀, R² 0.853
상한 = min(예상 × 1.5, 절대천장)
절대천장 = 30분 (설정)
천장을 넘길 크기는 업로드 단계에서 거부한다     ← 약 3만6천자
```

업로드 거부는 **Spring이 한다** — `POST /api/v1/documents`가 이미 파일 형식·개수·용량을 검증하니
(`FR-DOC-001`) 거기에 조건 하나가 붙는 것이다. AI 서버는 파싱 결과 글자수를 모르는 상태로는
판정할 수 없으므로 파싱 후 판정이 필요하면 `source-parses` 응답의 `warnings`로 알린다.

| 문서 | 예상 | 상한 | 실제 | 지금 규칙 | 새 규칙 |
|---|---|---|---|---|---|
| 2.4KB | 3.1분 | 4.7분 | 2.1분 | 통과 | 통과 |
| 10KB | 6.9분 | 10.4분 | 5.0분 | 통과 | 통과 |
| 26KB | 15.0분 | 22.5분 | 14.9분 | **실패** | 통과 |

상한은 여전히 진짜 멈춘 작업을 잡는다. 26KB 문서가 22분을 넘으면 이상한 것이다.

업로드 단계 거부가 핵심이다. 30분 기다린 뒤 실패를 알리는 것보다 올릴 때 막는 편이 낫다.

회귀식은 과대 예측하는 방향이라 상한으로 안전하다(31.5KB 문서는 예상 17.7분, 실제 9.4분).

`runtime/base.py`의 `CALL_TIMEOUT_SECONDS`를 고정값에서 크기 함수로 바꾼다. 요구사항 문구
조정이 필요하다 — 팀에서 조정 가능하다고 확인됐다.

## 7. 검증 계획

돈이 드는 부분을 최소로 깎는다. `deepagents` 검증에 두 질문이 섞여 있었고 값이 완전히 다르다.

### 계층 1 — LLM 없이. $0

`pytest`로 끝난다. 툴을 직접 부르는 테스트로 잡힌다.

```
spring.py 가 backend_sim 에 정확히 HTTP 를 쏜다 (읽기 4개, requestId 범위 강제)
라이브 층 적재 후 search 가 기존 페이지를 찾는다 (청크 FTS 색인이 채워졌다)
작업 층 diff → wikiChanges 변환
pageKey → tempWikiId 매핑, 본문 링크 치환
index.md 마크다운 → indexEntries 구조 추출
응답이 계약 스키마를 통과
오류 구조 (422→400, X-Request-Id, fieldErrors, failureStage)
X-Internal-API-Key 없으면 401 / 허가된 scopeKey 밖은 404
재조정 호출 순서 제약 (파싱 파일 삭제 전에 호출)
--dry-run 이 파일을 건드리지 않는다
```

### 계층 2 — `deepagents` 어댑터만. $0.04

LLM이 필요한 건 하나다: deepagents가 우리 MCP 서버와 툴을 주고받나. 3턴으로 확인된다.
**강한 모델을 쓴다** — 약한 모델은 실패 원인을 갈라낼 수 없게 만든다.

```
프롬프트  "guide 를 부르고, search 로 '회의' 를 찾고, 결과를 요약하고 멈춰라."
턴 3 · 누적 입력 ~15k · 출력 ~1k
```

위키 변환을 시키지 않는다. 실패하면 어댑터 문제가 확실하다.

### 계층 3 — 새 경로 전체. $0

`claude-code`는 구독이라 비용이 없다. 새 경로(`spring.py` + HTTP + 계약 조립)는 런타임과
무관하다. **강한 모델로, 대조군과 함께, 공짜로 돌린다.**

| 실험 | 문서 | 대조군 | 볼 것 |
|---|---|---|---|
| A 재투입 | `01-training.md` (2.4KB) | `2026-07-27-reingest` (create 0, 1.3분) | 멱등 유지 · 툴 순서 |
| B 신규 | `handbook/getting-started/meetings.md` (5.9KB) | 12건의 update 32 > create 20 | **중복 회피** |
| C 재조정 | `01-training.md` 삭제 | 없음 — 첫 측정 | 걷어내기 품질 |
| D 12건 재측정 | 코퍼스 전체 | `2026-07-27-opus46-12docs` | 새 경로에서 수치 유지 |

B가 진짜 시험이다. 기존 위키의 「커뮤니케이션 가이드」가 회의를 18번 언급한다 — 에이전트가
중복을 만들지 않고 찾아 고쳐야 하는 상황이 자연히 만들어진다. 옛 spike에서 무너진 판단이다.

C는 대조군이 없다. 삭제 재조정은 한 번도 측정하지 않았고, 걷어내기가 추가보다 어려울 것으로
본다(여러 문서가 같은 페이지에 각주를 걸면 어느 문단이 누구 근거인지 갈라야 한다).

### 계층 4 — `deepagents` 품질. 예산이 생기면

```
문서 1건 (누적 위키 위)   ~$0.4
12건 전체 재측정          ~$5
```

설계는 이것을 전제하지 않는다. 계층 3이 공짜로 대조군까지 준다. 다만 **배포 런타임의 품질은
배포 전까지 미확인으로 남는다** — 정직하게 남겨두는 위험이다. 프롬프트·툴·`lint`가 동일하니
배관이 붙는 한 크게 갈릴 이유는 없다.

### 1주 안 런타임

`--runtime` 설정으로 둘 다 살려두고 기본값을 `claude-code`로 둔다 — 비용 0이고 품질이 측정된
유일한 조합이다. GMS Anthropic 키는 사실상 사용 불가로 확인됐다.

## 8. 협의 목록 (Spring 담당자)

```
신설  읽기 4개                      GET /internal/v1/wiki-pages?includeContent=true
      → docs/AI호출명세서.json 「현재 Wiki 목록 조회」·「Wiki 본문 조회」·
        「원본문서 파싱 본문 조회」·「Wiki 목차 조회」. 요청·응답 예시와
        X-Request-Id 헤더까지 이 파일이 정본이고 테스트가 구현과 묶는다
        (ai-server/tests/test_ai_call_spec.py)
                                        /internal/v1/wikis/{id}/content
                                        /internal/v1/documents/{id}/parsed
                                        /internal/v1/wiki-spaces/{scopeKey}/index
신설  재조정                        POST /internal/v1/wiki-reconciliations
수정  wiki-transformations          currentWikis 제거 · currentCategories 형태 확정
추가  relationChanges 스키마        무방향 link/unlink, wiki_document · wiki_wiki
추가  wikiChanges[].evidence        FR-AI-009 를 계약으로 만족시키는 데 필요
추가  오류 본문 failureStage        내부 API 한정
제약  삭제·교체는 재조정 먼저 호출한 뒤 파일을 지운다
제약  읽기 4개는 X-Request-Id 에 허가된 scopeKey 밖을 404 로 거부한다
      (요청 단위 열람 허가. 요청이 끝나면 허가도 사라진다)
조정  NFR-PERF-002 고정 10분 → 크기 비례 + 절대 천장
추가  POST /api/v1/documents 업로드 검증에 파싱 글자수 상한 추가
```

기존 세 엔드포인트의 **요청 본문 변경은 `currentWikis` 제거 하나뿐이다.** 나머지는 응답 필드
추가와 신설이라 Spring 쪽 기존 구현을 깨지 않는다.

## 9. 문서 정합 필요 목록

읽는 과정에서 확인된 어긋남이다. **이 설계의 범위가 아니며 별도 승인을 받아 고친다.**

| 위치 | 문제 |
|---|---|
| `CLAUDE.md` | "MySQL DDL 17테이블" — 실제 16개 |
| `CLAUDE.md` | "Swagger를 공식 명세로 쓴다" — Postman Collection v2.1로 바뀌었다 |
| `CLAUDE.md` | "DB 엔진 미정, Postgres가 더 맞지만" — MySQL 8.4 LTS 확정 |
| `CLAUDE.md` · `experiments/INDEX.md` · `12docs/notes.md` | **"문서당 2만 토큰 상한"은 요구사항에 없다.** 작성자 착오. 실제 상한은 파일당 20MB |
| `CLAUDE.md` | `answer_source` "정확히 하나" — 생성 시 정확히 하나, 삭제 후 NULL 허용 |
| `AJT 파일 디렉터리 구조 설계.md` | `schedules/{id}/attachments/` — DR-016이 일정 첨부 없음으로 확정 |
| `API_컨벤션.md` 파일 형식 표 | "일정 첨부파일" 행 — 같은 이유로 낡았다 |
| `DR-015` vs `ai-server` | `pages/{wikiId}.md` vs `pages/{pageKey}.md`. 계약이 `tempWikiId`를 쓰므로 계약 쪽이 이 발상을 부분 수용했다 |

## 10. 미해결과 리스크

| | 상태 |
|---|---|
| 배포 런타임(`deepagents`) 품질 | 미확인. 계층 4로 미룬다 |
| 삭제 재조정 품질 | 미측정. 계층 3-C가 첫 측정 |
| 한국어 실문서 | 미확인. 코퍼스가 전부 영문. 같은 토큰에 바이트 3배 |
| 큰 문서 (20MB 상한) | 6절이 완화하지만 해결은 아니다. 문서 내부 분할은 미해결 |
| 복수 원본문서의 Wiki 공개 범위 계산 | 교집합이면 병합 Wiki가 아무에게도 안 보인다. 각주 문서명 노출 문제가 딸려 있다 |
| `index.md`가 챗봇의 유일한 검색면 | `POST /questions` 정책이 그렇게 못 박았다. **`index.md` 품질을 측정 지표로 삼은 적이 없다** — 페이지 수·조각·링크·각주만 셌다 |

마지막 항목은 이 설계에서 새로 드러난 것이다. 벡터 DB도 전문검색도 쓰지 않고 `index.md`
하나로 위키 100페이지 중 5개를 골라야 한다. 챗봇 재현율이 거기 걸려 있으므로 다음 측정에
`index.md` 품질 지표를 추가해야 한다.

## 11. 범위 제외

```
source-parses            문서 파싱. document-parsing-posthog spike 진행 중
schedule-extractions     일정 추출
answer-context-selections · answers   챗봇 2단계
```

넷은 별도 설계로 다룬다. 다만 4.5의 오류 구조와 `api/` 골격은 넷이 공유한다.

## 12. 산출물

```
1  docs/AI호출명세서.json         AI → Spring. Postman v2.1, 기존 두 파일과 같은 형식
                                  읽기 4개 + 재조정(Spring이 부르는 쪽)
2  ai-server/api/                 FastAPI 앱. 계약대로
3  ai-server/mcp/vaultfs/spring.py
4  ai-server/changes.py           작업 층 diff → 계약 응답
5  ai-server/backend_sim.py       HTTP 서버로 승격 + --serve · --dry-run · --from-experiment
6  ai-server/tests/               계층 1 통합 테스트
7  실험 A~D                       experiments/2026-07-28-*
```
