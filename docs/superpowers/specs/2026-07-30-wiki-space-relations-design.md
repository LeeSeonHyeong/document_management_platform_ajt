# Wiki 범위 링크 그래프 조회 창구 — 설계

**티켓** S15P11B106-153
**브랜치** `feature/S15P11B106-153-wiki-space-relations`
**선행** S15P11B106-150 (Wiki 조회 창구 7개 · 계약 v1.6.0). 머지 완료.
**계약** v1.6.1. 이 설계와 함께 작성했다 (커밋 `18a09e3`).

## 1. 문제

에이전트가 Wiki 를 병합·삭제할 때 **남의 링크를 깨뜨리면 안 된다.** A 가 B 를 가리키는데 B 를
지우면 A 의 링크가 죽는다. 그것을 막으려면 "누가 B 를 가리키는가" 를 알아야 하고, 그 답은
**범위 전체의 참조 그래프**에서만 나온다. 일부만 알면 안 읽은 페이지가 거는 링크를 놓친다.

AI 서버는 그 그래프를 지금 **직접 만든다.** Wiki 본문을 한 장씩 창구에서 받아 본문 안의
링크를 파싱해 로컬 SQLite 에 쌓는다.

그 방식의 비용이 S15P11B106-151 에 적힌 결함이다. Wiki 100장 범위에서 `read` 한 번에 창구
호출 100회가 나간다. 151 이 그 회수를 줄이지만, **본문을 받아 파싱한다는 구조 자체는 남는다.**

**백엔드는 같은 그래프를 이미 갖고 있다.** `wiki.wiki_refs`·`wiki.document_refs` JSON 컬럼이
그것이고 (DR-002), 파싱이 필요 없다. `WikiTransformationApplier` 가 반영 시점에 갱신한다.

데이터를 소유한 쪽이 답하게 한다.

## 2. 지금 있는 것과 부족한 것

계약 v1.6.0 의 `GET /internal/v1/wikis/{wikiId}/relations` 는 **Wiki 1건**의 관계를 준다.
범위 전체 그래프를 얻으려면 장수만큼 불러야 한다.

범위 단위 엔드포인트 하나를 **추가**한다. 기존 API·엔티티·ERD 는 건드리지 않는다.

## 3. 계약

```
GET /internal/v1/wiki-spaces/{scopeKey}/relations
```

```json
{
  "scopeVersion": 47,
  "items": [
    { "wikiId": "101", "wikiRefs": ["102", "115"], "documentRefs": ["15"] },
    { "wikiId": "102", "wikiRefs": [], "documentRefs": ["15", "16"] },
    { "wikiId": "115", "wikiRefs": ["101"], "documentRefs": [] }
  ]
}
```

| 필드 | 뜻 |
| --- | --- |
| `scopeVersion` | 이 응답을 만든 시점의 범위 버전 (DR-030) |
| `items[].wikiId` | 간선의 출발 Wiki |
| `items[].wikiRefs` | 이 Wiki 가 가리키는 Wiki ID |
| `items[].documentRefs` | 각주 근거로 쓰인 원본문서 ID |

ID 는 JSON 문자열, 빈 목록은 `[]`, 필드명은 `camelCase` — `docs/api/README.md` 의 공통
데이터 규칙 그대로다.

인증은 기존 창구와 동일하다. `X-Internal-API-Key` + 요청 단위 `X-Wiki-Capability`.
capability 의 `scopeKey` 와 불일치하면 존재를 노출하지 않고 404.

계약 버전은 1.6.0 → **1.6.1**. 엔드포인트 추가이고 기존 필드·경로·의미가 그대로라 patch 다.

### 3.1 역링크를 싣지 않는 이유

범위 전체 간선이 있으면 소비자가 뒤집어서 역링크를 구한다. 응답에 넣으면 같은 정보를 두 번
싣고, 백엔드가 Wiki 마다 전체를 훑는 O(n²) 계산을 떠안는다. 기존 1건 조회 API 가 역링크를
계산하는 것은 그 API 에는 다른 방법이 없기 때문이다.

### 3.2 페이징을 두지 않는 이유

소비자는 그래프 **전체**가 필요하다. 일부만 주면 "누가 이것을 가리키는가" 에 답이 안 된다 —
잘려 나간 쪽에 그 링크가 있을 수 있다. 페이징은 이 API 의 목적을 무너뜨린다.

크기는 문제가 아니다. Wiki 1,000장이어도 ID 배열이라 수백 KB 이고, 작업 1건에 1회 호출이다.

### 3.3 기존 `backlinks` 필드는 그대로 둔다

기존 1건 조회의 `backlinks` 는 wikiId 배열뿐이라 그 역링크가 인용인지 단순 링크인지
구분되지 않는다. AI 쪽(`vaultfs/federated.py`)이 원격 역링크를 전부 `links_to` 로 찍고
**"계약에 없는 값이라 MR 협의 항목으로 올린다"** 는 주석을 남겨 둔 상태다.

이 API 가 `wikiRefs`(Wiki→Wiki 링크)와 `documentRefs`(각주 근거)를 나눠 주므로 소비자가
종류를 추정할 필요가 없어진다. **이 티켓이 머지되면 그 협의 항목을 닫는다.**

기존 필드를 지우거나 의미를 바꾸는 것은 minor 이상이다. 이 티켓의 범위가 아니다.

## 4. 끊어진 참조를 걸러내지 않는다

`wiki_refs` 는 JSON 컬럼이라 FK 가 없다. 그래서 "존재하지 않는 Wiki 를 가리키는 ID" 가
이론상 남을 수 있다.

**확인한 결과 그 경로가 사실상 없다.**

* 삭제 시 `WikiTransformationApplier.removeDanglingWikiRefs` 가 같은 범위의 다른 Wiki 에서
  그 ID 를 지운다
* 그 정리는 `@Transactional(propagation = REQUIRES_NEW)` 안에서 돈다
  (`DocumentWikiTransformationTransactionService`). 실패하면 삭제와 함께 롤백된다
* 관계 추가는 `requireWikiInScope` 로 범위를 검증한다

남는 경로는 이 정리 로직 이전의 과거 데이터, DB 직접 수정, 그리고 **아직 없는 기능**(Wiki 를
다른 범위로 옮기기)뿐이다.

그래서 **`wiki_refs` 를 원본 그대로 싣는다.** 필터를 넣으면 없는 문제에 코드를 쓰고, 더 나쁘게는
데이터가 이미 깨져 있다는 사실을 숨긴다. 깨진 데이터는 소비자 쪽에서 오류로 드러나는 편이
낫다 — 조용히 사라지면 원인 추적이 훨씬 비싸다.

범위 이동 기능이 생기면 그때 다시 판단한다.

## 5. 구현

### 5.1 위치

창구 8번째 엔드포인트다. 기존 창구와 같은 계층에 붙인다. **새 클래스를 만들지 않는다.**

기존 파일 2곳에 각각 한 덩이씩 **추가**한다. 삭제·수정은 없다. 백엔드 담당과 이 조건으로
합의했다.

### 5.2 컨트롤러 — `InternalWikiQueryController`

```java
@GetMapping("/internal/v1/wiki-spaces/{scopeKey}/relations")
public InternalWikiQueryService.WikiSpaceRelations spaceRelations(
        @PathVariable String scopeKey,
        @RequestHeader(value = "X-Wiki-Capability", required = false) String capability) {
    authorize(capability, scopeKey);
    return queryService.spaceRelations(scopeKey);
}
```

`index`·`categories` 와 형태가 같다 — `scopeKey` 는 경로 변수, capability 헤더로 인가.

### 5.3 서비스 — `InternalWikiQueryService`

```java
@Transactional(readOnly = true)
public WikiSpaceRelations spaceRelations(String scopeKey) {
    WikiScope scope = requireScope(scopeKey);
    return new WikiSpaceRelations(
            scope.scopeVersion(),
            wikiRepository.findAllByScopeKey(scopeKey).stream()
                    .map(wiki -> new WikiRelationItem(
                            String.valueOf(wiki.id()),
                            wiki.wikiRefs().stream().map(String::valueOf).toList(),
                            wiki.documentRefs().stream().map(String::valueOf).toList()))
                    .toList());
}
```

`requireScope` 가 범위 없음·capability 불일치를 404 로 낸다. 기존 `relations` 는 건드리지
않는다.

`scopeVersion` 과 Wiki 목록을 같은 읽기 트랜잭션에서 읽는다. 버전이 가리키는 시점과 간선이
어긋나지 않는다 (DR-030 의 반영 직전 확인이 그 버전을 쓴다).

### 5.4 응답 레코드

기존 창구 응답은 전부 `InternalWikiQueryService` 안의 nested record 다. 같은 자리에 **추가**
한다.

```java
public record WikiSpaceRelations(long scopeVersion, List<WikiRelationItem> items) {}
public record WikiRelationItem(String wikiId, List<String> wikiRefs, List<String> documentRefs) {}
```

### 5.5 조회 방식 — 엔티티 로드

`wikiRepository.findAllByScopeKey(scopeKey)` 를 그대로 쓴다. 기존 `relations` 가 역링크
계산에 이미 같은 조회를 한다. 새 리포지토리 메서드가 없다.

프로젝션 쿼리로 세 컬럼만 뽑는 방법도 있지만 **쓰지 않는다.**

* `wiki` 표에 본문 컬럼이 없다. 파일이 정본이고 표에는 `content_hash` 만 있다 (DR-001).
  아끼는 양이 행당 수백 바이트다
* `scope_key` 에 인덱스가 있다 (`idx_wiki_scope_updated`). 범위 조회가 풀스캔하지 않는다
* JSON 컬럼을 엔티티 밖에서 받으면 문자열로 와서 `List<Long>` 변환 코드를 새로 만들어야
  한다. 같은 컬럼을 읽는 방법이 두 개로 갈린다

측정 근거 없이 최적화하지 않는다. 필요해지면 그때 프로젝션으로 바꾼다 — 응답 모양은 같으므로
소비자에 영향이 없다.

`JSON_TABLE` 네이티브 쿼리는 MySQL 전용이고 결국 자바에서 다시 묶어야 해서 얻는 것이 없다.

## 6. 오류

계약이 정한 것만 낸다. 새 상태·새 코드를 만들지 않는다.

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 파라미터 오류 |
| 401 | — | 내부 API 키 오류 (`InternalApiKeyFilter`) |
| 404 | `WIKI_CAPABILITY_EXPIRED` | 허가 만료·철회 |
| 404 | `WIKI_SCOPE_NOT_FOUND` | 허가 범위 밖 또는 범위 없음 |

404 두 가지는 HTTP 상태가 같고 `code` 로 구분한다 — 기존 창구와 동일하다.

## 7. 검증

테스트는 **기존 테스트 클래스에 추가**한다. 창구 7개의 테스트가 이미
`InternalWikiQueryServiceTest`·`InternalWikiQueryControllerTest`·`InternalWikiQuerySecurityTest`
세 곳에 모여 있다. 새 클래스를 만들면 같은 창구의 테스트가 흩어진다.

`InternalWikiQuerySecurityTest` 의 「7개 조회 endpoint」 목록과 그 `@DisplayName` 문자열은
8개로 고친다 — 이 파일에서만 기존 줄을 수정한다.

### 7.1 서비스 테스트 (`InternalWikiQueryServiceTest` 에 추가)

| 무엇 | 확인 |
| --- | --- |
| 간선 매핑 | `wiki_refs`·`document_refs` 가 문자열 ID 배열로 나온다 |
| 참조 없는 Wiki | `wikiRefs`·`documentRefs` 가 `[]` |
| Wiki 0장인 범위 | `items` 가 `[]`, `scopeVersion` 은 정상 |
| 다른 범위 Wiki | 응답에 섞이지 않는다 |
| 없는 범위 | `WIKI_SCOPE_NOT_FOUND` |
| 끊어진 참조 | **걸러내지 않고 그대로 나온다** (4절의 결정을 고정한다) |

### 7.2 웹 계층 테스트 (`InternalWikiQueryControllerTest`·`InternalWikiQuerySecurityTest` 에 추가)

| 무엇 | 확인 |
| --- | --- |
| 계약 일치 | 응답 JSON 이 계약 Example 과 같은 모양 (ID 문자열, `items` 키) |
| capability 없음 | 404 |
| capability 범위 불일치 | 404, 존재를 노출하지 않는다 |
| 내부 API 키 없음 | 401 |

### 7.3 회귀

* 기존 `GET /internal/v1/wikis/{wikiId}/relations` 응답 무변경
* `./gradlew test` 기존 실패 0
* `node scripts/validate-artifact-consistency.mjs` 통과 — 이미 갱신했다
  (내부 API 15개, contractVersion 1.6.1)

## 8. 순서와 영향

**백엔드 구현이 AI 쪽 전환보다 먼저다.** 엔드포인트를 추가해도 아무것도 깨지지 않는다 —
AI 가 아직 부르지 않는다.

AI 쪽 전환은 **별건**이다. 로컬 그래프 재계산 코드를 제거하는 변경이라 순수 추가가 아니다.

관련 티켓과의 관계:

| 티켓 | 관계 |
| --- | --- |
| S15P11B106-151 (AI fan-out 제거) | 독립. 151 은 AI 안에서 끝나고 이 티켓은 계약 합의가 필요하다. 이 API 가 들어오면 151 이 고친 문제가 애초에 안 생기지만, 151 없이 창구를 열면 첫 실행부터 왕복 100회다 |
| S15P11B106-152 (창구 모드 배선) | **이 티켓 결론이 152 전에 나면 좋다.** 백엔드가 그래프를 주면 AI 쪽 로컬 그래프 배선을 유지할 이유가 줄어든다 |
| S15P11B106-154 (AI 라이브 중복 색인 제거) | 같은 뿌리 — 데이터 소유자가 답하게 하는 정리 |

소비자는 AI 서버뿐이다. 프론트엔드 영향 없음.

## 9. 범위 밖

| 항목 | 이유 |
| --- | --- |
| AI 서버의 로컬 그래프 제거 | 별건. 삭제를 동반해 순수 추가가 아니다 |
| ERD 변경 | 없음. 기존 컬럼을 읽기만 한다 |
| `wiki_refs` 갱신 로직 | 반영 경로(`WikiTransformationApplier`)는 건드리지 않는다 |
| 기존 `backlinks` 필드 정리 | 삭제·의미 변경은 minor 이상 (3.3) |
| 끊어진 참조 필터 | 방어할 실제 경로가 없다 (4절) |

## 10. 관련

* 계약: `docs/api/AJT-FastAPI-Internal-API.postman_collection.json` — "Wiki 범위 관계"
* 계약 생성기: `docs/api/generate-postman-collections.mjs`, `postman-contract-examples.mjs`
* ERD: `docs/db/erd.sql` 의 `wiki` 표 (`wiki_refs`·`document_refs`·`idx_wiki_scope_updated`)
* 기존 구현: `InternalWikiQueryService.relations`, `InternalWikiQueryController`
* 근거 주석: `ai/src/wiki_mcp/vaultfs/federated.py` 의 `get_backlinks`
