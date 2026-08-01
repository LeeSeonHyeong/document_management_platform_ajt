# 위키 변환을 에이전트 하나로 — 설계

작성 2026-08-01. S15P11B106-175. 백엔드 몫은 S15P11B106-174.

앞선 설계 `2026-07-31-agent-endpoint-merge-design.md` 의 §4.1·§4.3·§5 가 이 작업의 밑그림이었다.
그 문서는 챗봇을 먼저 하기로 하고 위키 부분을 「다음 단계」로 미뤘다. 이 문서가 그 다음
단계이며, 밑그림과 **달라진 판단 두 가지**를 명시한다 (§1.1·§3).

## 1. 무엇을 하는 작업인가

`experiments/` 에서 LocalVaultFS 로 재고 검증한 위키 변환을, **툴 뒤의 데이터만 Spring Wiki
조회 API 로 갈아끼운다.** 에이전트가 보는 것과 하는 것은 그대로다.

지금 위키 변환은 두 번 호출된다. 1단계(`wiki-context-selections`)에서 목차만 보고 볼 위키를
고르고, 백엔드가 그 본문을 읽어 2단계(`wiki-transformations`)에 실어 보낸다. 1단계도 결국
LLM 이 고르는 것이라 나눌 이유가 없다. 한 번으로 합치고 무엇을 볼지는 에이전트가 도구로
정한다.

### 1.1 기준선은 push 경로가 아니다

**이 판단이 나머지를 다 좌우한다.**

지금 돌아가는 경로(요청에 `selectedWikis` 를 싣는 것, 이하 push 경로)는 성능 기준선이
아니다. 그 경로 자체가 부분 가시성이다 — 백엔드가 고른 몇 장만 에이전트에게 존재하고
나머지는 보이지 않는다. `experiments/` 의 측정은 전부 LocalVaultFS 로 **범위 전체가 보이는
상태**에서 잰 것이고, 조회 API 는 그 상태를 되돌린다.

그래서 조회 API 전환은 퇴화가 아니라 복원이다. 「토큰이 늘어난다」·「왕복이 늘어난다」는 비교를
push 경로에 대고 할 때만 나오는 결론이며, 그 경로는 마이그레이션 대상이 아니다.

지켜야 하는 불변은 하나다 — **툴 10개가 LocalVaultFS 때와 같은 답을 내야 한다.** 뒤에 있는
것이 SQLite 든 Spring 이든 에이전트는 몰라야 한다.

| 툴 | 뒤의 데이터 | 조회 API 상태 |
| --- | --- | --- |
| `guide` | 없음 | 무관 |
| `list_scopes` | 범위 | 됨 |
| `search` | 라이브 본문 검색 | 됨 (S15P11B106-154) |
| `read` | 본문 1장 | 됨 (S15P11B106-151 지연 적재) |
| `create`·`edit`·`append` | 작업 층 | 로컬 — 무관 |
| `merge`·`delete` | 작업 층 + 위키→위키 역링크 | 됨 |
| `lint` | 전 본문 대조 | 됨 |

**안 맞는 곳은 툴이 아니라 지시문 하나다** (§3.1).

## 2. 계약

```
POST /internal/v1/wiki-context-selections     삭제 — 남겨두지 않는다

POST /internal/v1/wiki-transformations
  남음   jobId · documentId · scopeKey · changeType
         parsedMarkdown · removedParsedMarkdown
  필수화 wikiCapability · scopeVersion   (1.6.0 부터 선택 필드로 있었다)
  빠짐   currentIndex · currentCategories · selectedWikis
  응답   그대로 — summary · categoryChanges · wikiChanges · relationChanges · indexEntries

POST /internal/v1/wiki-edits
  남음   wikiId · scopeKey · instruction · chatHistory
  필수화 wikiCapability · scopeVersion
  빠짐   currentWiki · currentCategories · evidenceDocuments
  응답   그대로
```

AI 엔드포인트 6 → 5. 내부 API 전체 16 → 15.

**계약 재생성은 백엔드(174)가 한다.** `docs/api/generate-postman-collections.mjs` 는 AI 관할이
아니고, 버전 번호는 머지 직전에 정한다 — 미리 잡아두면 다른 티켓과 충돌한다
(S15P11B106-101 이 1.7.0 을 먼저 가져간 전례).

### 2.1 `wiki-edits` 도 이번에 함께 바꾼다

티켓 175 는 `wiki-edits` 를 범위 밖으로 적었고 앞 설계 §4.3 은 함께 바꾸자고 했다. **함께
바꾼다.** 이유는 코드량이 아니라 사고 위험이다.

`wiki-edits` 만 push 경로로 남기면 `session.py` 의 `_federated()` 분기가 계속 살아 있고, 그
분기가 false 로 떨어지는 길에는 이제 하이드레이션 재료가 없다 — 에이전트가 빈 위키를 보고
라이브를 덮는다. 기동 검사(§4.3)로 한 구멍은 막지만 분기가 남는 한 다음 구멍이 또 난다.

근거 문서는 대상 위키의 `documentRefs` 로 찾아 `GET /documents/{documentId}/parsed` 로 읽는다
(§3). 백엔드가 `evidenceDocuments` 로 보내던 것과 같은 집합이다 — 출처가 같은 JSON 이다.

## 3. 구현 — 하이드레이션에서 범위 관계를 한 번 받는다

`GET /internal/v1/wiki-spaces/{scopeKey}/relations` 를 하이드레이션에서 **1회** 부르고,
`items[].wikiRefs` 와 `items[].documentRefs` 를 카탈로그에 넣어 **양방향으로 뒤집는다.**

계약이 이 사용을 명시하고 있다:

> 역방향(`backlinks`)은 싣지 않습니다. 범위 전체 간선이 있으면 소비자가 뒤집어 구합니다.

이 한 번의 호출로 세 가지가 풀린다.

| 무엇 | 어떻게 |
| --- | --- |
| 삭제·교체 인용 위키 (§3.1) | `documentRefs` 뒤집기 → 해당 위키 본문만 당겨 각주 추출 |
| `merge`·`delete` 역링크 | `wikiRefs` 뒤집기 → 페이지별 `/wikis/{id}/relations` 호출 소멸 |
| `wiki-edits` 근거 문서 | 대상 위키의 `documentRefs` → `/documents/{documentId}/parsed` |

**조회 API 호출은 순감이다.** 하이드레이션에 1회 늘고, 실행 중 페이지당 1회가 사라진다. 지금
`get_backlinks` 의 원격 보강은 캐시가 없어 **호출할 때마다** `/wikis/{id}/relations` 를
부른다 (`query_client.py` 의 예산 주석). 그 항이 통째로 없어진다.

`WikiQueryClient` 에 `scope_relations()` 를 새로 만든다 — 조회 API 8개 중 유일하게 쓰지
않던 것이다.

### 3.1 유일한 실질 구멍 — 삭제·교체 재조정

`document_removed`·`document_replaced` 는 라우터가 「사라진 원본문서를 인용한 위키」를 미리
뽑아 재조정 지시문에 싣는다 (`routers/wiki.py` → `get_citation_backlinks`). 각주 라벨·위치·
인용문까지 실어야 에이전트가 어디를 어떻게 고칠지 안다.

각주는 **위키 마크다운 안에 있다.** 원본문서 쪽에는 아무 표시도 없고 DB 에도 없다.

```
[^1]: 인사규정.pdf, 3장 휴가 — "입사일을 기준으로 산정한다"
       └ 파일명       └ 위치      └ 원문 인용
```

LocalVaultFS 때는 범위 전 본문이 로컬에 있어서 `document_references` 표가 다 차 있었다.
조회 API 는 본문을 지연 적재하므로(S15P11B106-151) 하이드레이션 직후 그 표가 비어 있고,
`get_citation_backlinks` 가 빈 목록을 낸다. 그러면 재조정 지시문이 비고, **사라진 문서를
인용한 각주가 위키에 그대로 남는다.**

`get_backlinks` 에는 원격 보강이 있지만 `get_citation_backlinks` 에는 없다. 그리고
`/wikis/{id}/relations` 는 위키 기준 역링크라 「문서를 인용한 위키」를 직접 주지 못한다.

**해법:**

```
1. 범위 관계 1회        → documentRefs 를 뒤집어 「문서 15 를 인용한 것은 101, 102」
2. 101, 102 본문만 당김 → 인용한 위키 수만큼. 범위 전체가 아니다
3. 그 본문에서 각주 추출 → sync_references 가 이미 하는 일
```

100장 중 2장이 인용했으면 조회 3회다. 전 본문을 당기는 안(100회)과 백엔드에 문서별 조회를
신설하는 안(계약 변경 + 174 의존)을 검토했고, 뒤집기가 둘 다의 상위 호환이다 — 호출 1회,
백엔드 작업 0, 계약 변경 0.

**대조를 함께 한다.** 뒤집어 나온 위키 본문에 실제로 그 문서를 가리키는 각주가 있는지
확인한다. `documentRefs` 는 백엔드가 관리하는 JSON 이라 낡을 수 있고, 대조는
`sync_references` 가 이미 하므로 공짜다. 목록이 넓게 틀리는 것은 못 잡지만 없는 항목은
걸러진다.

## 4. 안전장치

조회 API 전환이 새로 들여온 실패 방식들을 막는다. 넷 다 계약 변경이 없다.

### 4.1 삭제·교체·수정인데 카탈로그가 0장 → 실패

조회 API 가 오류 없이 **빈 목록**을 주는 경우가 남는다. HTTP 실패·404·버전 불일치는 이미 예외로
갈리지만(`ScopeChangedError`·`QueryNotFound`) 200 에 빈 목록은 안 갈린다.

지울 문서가 있다는 것은 그 문서로 만든 위키가 있었다는 뜻이다. 0장은 모순이므로 실패로
끊는다 — `FailureStage.CONTEXT_LOAD`. 에이전트를 돌리기 전이다.

`document_added` 는 0장이 정상이다 (신규 범위).

`wiki-edits`(수정 요청) 도 같은 게이트를 탄다 — `WikiSession(requires_existing_wiki=True)`
는 삭제·교체와 수정 모두에 서고, 둘 다 「고칠 위키가 이미 있어야 한다」는 같은 전제를
공유한다: 카탈로그가 0장이면 수정할 대상 자체가 없으므로 삭제·교체와 똑같이 모순이다.
구현이 이 확장을 이미 반영했고(§4.1 이 삭제·교체만 말하던 원안보다 범위가 넓다), 이 문서를
그에 맞춰 넓힌다.

이것이 지금 `TransformRequest._the_request_must_be_actionable` 의 I3 검증(「삭제에 인용 위키가
없다」)이 하던 일이다. `selectedWikis` 가 없어지면 접수 시점에 못 막으므로 하이드레이션
시점으로 옮긴다.

**「인용 위키 0」은 막지 않는다.** 카탈로그는 있는데 그 문서를 아무도 안 쓴 경우는 합법이다.
경고만 남기고 진행한다.

### 4.2 읽기 툴을 한 번도 안 불렀으면 실패

에이전트가 `read`·`search` 를 0회 부르고 쓰기만 하고 끝냈다면, 현재 위키를 못 본 채
라이브를 덮은 것이다. `RunResult.tool_calls` 로 판정한다.

챗봇 §6.4.1 의 「찾아봤는데 없음」과 「찾아보지도 않음」을 가르는 장치와 같은 것이다. 위키
쪽은 추정이 아니라 기록이라 더 확실하다.

**예외 하나** — 카탈로그 0장 + `document_added` 는 읽을 것이 없는 것이 정상이다.

### 4.3 `BACKEND_BASE_URL` 이 없으면 기동을 거부한다

`wikiCapability` 만 필수화하고 백엔드 주소가 안 잡힌 채 뜨면, `_federated()` 가 false 로
떨어지는데 하이드레이션 재료는 이미 없다 — 빈 위키로 라이브를 덮는다. 설정 하나로 나는
사고다.

`serve.py` 기동 시점에 막는다. 선례가 있다 (`claude-code` CLI 부재 검사). `src/.env.example`
에도 넣는다.

### 4.4 각주 대조

§3.1 마지막 문단.

## 5. 지우는 것

남겨두지 않는다.

- `src/wiki_api/selection.py`
- `SelectionRequest` · `SelectionResponse` · `SelectedWiki` · `WikiBody` · `EvidenceDocument`
- `agent_runtime.base.selection_instruction`
- push 하이드레이션 — `SpringVaultFS.open(pages=, index_markdown=)`
- `routers/wiki.py` 의 `_hydration_pages` · `_edit_pages` · `_category_map` 의 요청 입력
- `session.py` 의 `_federated()` 분기와 `pages`·`index_markdown` 인자
- 위 대응 테스트

`FederatedVaultFS` 가 `SpringVaultFS` 를 상속하므로 클래스 자체는 남는다. 하이드레이션
인자만 없어진다.

`currentCategories` 의 대체는 이미 있다 — `FederatedVaultFS._hydrate_catalog` 가
`GET /wiki-spaces/{scopeKey}/categories` 를 이미 부르고 `fs.categories` 로 노출한다.
`build_response` 의 `current_categories` 를 거기서 받는다.

## 6. 오류 이름은 늘리지 않는다

`WIKI_TRANSFORMATION_FAILED` · `WIKI_EDIT_FAILED` 와 `failureStage` 로 지금처럼 간다. 계약에
없는 이름을 임의로 정하지 않는다는 규칙(`ai/CLAUDE.md`)에 걸리고, 단계 구분은 이미
`FailureStage` 가 한다.

사라지는 이름은 1단계 전용 두 개다 — `WIKI_CONTEXT_SELECTION_FAILED` 와
`INVALID_WIKI_CONTEXT_SELECTION_REQUEST` (`errors.py:79`·`:94`).

## 7. 검증

| 무엇 | 어떻게 | 비용 |
| --- | --- | --- |
| 툴 동작 | 가짜 조회 API(`experiments/query_gateway.py`) | 0 |
| 배관 회귀 | **가짜 모델**로 도구 호출 순서·조회 API 호출 수·예산 소진을 고정 | 0 |
| 저하 없음 | **실모델 1~2건**을 `experiments/` 기준선과 대조, `INDEX.md` 에 기록 | 수십 센트 |
| 실기동 | Spring 연동 1건 | **S15P11B106-172 대기** |

가짜 모델(`GenericFakeChatModel`)로 고정하는 것이 핵심이다. 실모델 측정은 한 번 찍는
숫자고, 회귀를 계속 지키는 것은 가짜 모델 쪽이다.

### 7.1 실기동은 172 를 기다린다

`WikiCapabilityService.require` 가 `remove` → 검사 → `put` 이라 같은 허가값으로 동시 조회하면
일부가 404 를 받는다. 챗봇에서 4회 중 3회 났고, 위키 변환은 조회 API 를 훨씬 많이 부르므로 지금
상태로는 거의 확실히 실패한다.

우리 쪽 처리는 맞다 — `query_client._get` 이 `code` 로 `WIKI_CAPABILITY_EXPIRED` 만 중단
신호로 잡고 `WIKI_NOT_FOUND` 는 「없음」으로 흘린다. 백엔드가 고치면 그대로 풀린다.

### 7.2 조회 예산

`QueryBudgetExceeded` 는 **조회 API 에만 있는 제약**이다. LocalVaultFS 때는 없었다. 예산이
모자라면 전에 되던 작업이 죽으므로 「저하 없음」에 직접 걸린다.

지금 `105 + 2 × 장수` 다. §3 이 페이지당 역링크 호출을 없애므로 소비가 줄지만, 조회 API 전량
가시성으로 도는 실측이 없다. 7절의 측정에서 실제 소비를 기록하고 필요하면 그때 올린다.

## 8. 배포 순서

**AI 가 먼저 나간다.**

백엔드가 먼저 `selectedWikis`·`currentIndex`·`currentCategories` 를 끊으면 에이전트가 빈
문맥으로 돌아 라이브를 덮는다. AI 가 먼저 나가면 조회 API 로 읽으므로 동작하고, 백엔드가 아직
본문을 실어 보내는 동안 요청이 무거울 뿐이다.

`Strict` 모델이 `extra="forbid"` 라 필드가 남아 있으면 400 이 난다. 그러므로 실제로는
**필드 제거를 백엔드와 같은 시점에 맞춰야 한다.** 계약 머지 시점을 174 와 맞춘다.

백엔드가 조회 엔드포인트를 새로 낼 필요는 없다 — 8개 전부 이미 있다 (S15P11B106-150).

## 9. 범위 밖

- S15P11B106-172 (허가값 경합) · 173 (MySQL 재색인 중복 키) — 백엔드
- 계약 재생성 — 174
- 작업 번호 기반 권한 — 앞 설계 §3.1
- OpenAI 로 지침 재조율 — 앞 설계 §6.1.1. 배포 모델이 정해진 뒤
- CI 를 MySQL 로 — `wiki-search` 와 173 이 H2 에서 재현되지 않는다. 백엔드·인프라 판단
