# 지연 적재 fan-out 제거 — 설계

**티켓** S15P11B106-151
**브랜치** `feature/S15P11B106-151-lazy-load-fanout`
**선행** S15P11B106-150 (창구 계약 1.6.0 · federated 어댑터). 머지 완료.
**후행** S15P11B106-152 (`local_server` 창구 모드 배선). **이 티켓이 먼저다** — 이유는 1.3.

## 1. 문제

### 1.1 증상

창구 모드에서 에이전트가 위키 한 장을 `read` 하면 창구 호출이 위키 장수만큼 간다.

실측 (`experiments/measure_federated.py`, 사슬 링크 코퍼스):

```
위키 100장    read 1회 → 창구 호출 100회 · 본문 100건
위키 150장    read 1회 → 창구 호출 150회
```

실제 백엔드에서는 순차 네트워크 왕복이다. `NFR-PERF-002` 의 10분 목표에 직접 위협이다.

### 1.2 원인 — 두 가지가 겹쳐 있다

**(A) 링크 대상 확인이 본문을 당긴다.**

```
_ensure_body(A)
 → page_content(A)                        창구 호출 1
 → _sync_page_references(scope)
 → build_edges(A 본문)
 → parse_wiki_links 마다 fs.get(B)         references.py:112
 → FederatedVaultFS.get → _ensure_body(B)  창구 호출 1  ← 여기
 → B 도 같은 일을 한다                      연결 성분 전체
```

`build_edges` 가 링크 대상에서 실제로 쓰는 것은 `target["address"]` **하나뿐이다.**
본문은 받아놓고 버린다.

**(B) 본문 1건마다 라이브 전체를 다시 훑는다.**

`_ensure_body` 가 끝에서 `_sync_page_references(scope_id)` 를 부른다. 이 메서드는
스코프의 모든 라이브 문서를 `list_documents(with_content=True)` 로 읽어 전부
`sync_references` 를 다시 돌린다 (`spring.py:106-120`). 본문을 n 번 당기면 O(n²) 이다.

(A) 가 왕복 수를, (B) 가 그 위에 제곱을 얹는다. **둘 다 고쳐야 한다.**

### 1.3 왜 배선(-152)보다 먼저인가

지금 `BACKEND_BASE_URL` 이 비어 있어 창구가 한 번도 호출되지 않는다. 배선을 먼저
끝내면 **창구의 첫 실경로 실행이 곧바로 왕복 100회**가 된다.

150 티켓은 조회 예산을 카탈로그 크기에 연동해(`105 + 2 × 장수`) 작업이 죽지 않게만
막았다. 증상만 막은 것이고 왕복은 그대로다.

## 2. 이미 확인한 사실

**하이드레이션이 모든 페이지의 라이브 행을 이미 만든다.** 본문만 빈 문자열이다.

```python
# federated.py:147-150
await self._insert_live(scope_id, address, "", wiki_id=wiki_id,
                        title=page.get("title"), category=page.get("categoryName"))
```

따라서 링크 대상의 존재 여부와 정규 주소는 **로컬 SQLite 한 번의 조회로 답이 나온다.**
카탈로그도, 창구 호출도 필요 없다.

> 정정: 앞선 논의에서 "카탈로그로 확인한다" 고 적었다. 실제 근거는 카탈로그가 아니라
> 하이드레이션이 만들어 둔 `visible_documents` 행이다. 어느 쪽이든 창구 호출은 0회지만,
> 후자면 `FederatedVaultFS` 에 오버라이드를 추가할 필요가 없다 — 부모의 SQL 이 그대로 답한다.

`fs.get` 이 본문을 당기는 이유는 단 하나다 — `FederatedVaultFS.get` 이 `_ensure_body` 를
타도록 오버라이드돼 있다. **`get` 을 쓰지 않으면 지연 적재가 걸리지 않는다.**

## 3. 수정 방향

### 3.1 포트에 주소 해석 메서드를 추가한다 — (A) 제거

`base.py` 에 추가:

```python
@abstractmethod
async def resolve_address(self, scope_id: str, address: str) -> str | None:
    """이 주소의 문서가 보이면 정규 주소를, 없으면 None 을 돌려준다.

    본문을 적재하지 않는다. `get` 과 갈라 둔 이유가 그것이다 — 링크 간선을 만드는
    데 필요한 것은 존재 여부와 정규 주소뿐이고, 창구 모드의 `get` 은 본문을
    당긴다 (federated.py 의 `_ensure_body`).
    """
```

구현체:

| 구현체 | 방식 | 창구 호출 |
| --- | --- | --- |
| `LocalVaultFS` | `SELECT address FROM visible_documents WHERE scope_id=? AND address=?` | — |
| `SpringVaultFS` | 상속 (push 모드는 본문이 이미 로컬에 있다) | — |
| `FederatedVaultFS` | **상속. 오버라이드하지 않는다** — 2절의 라이브 행이 답한다 | 0 |

`build_edges` 를 바꾼다 (`tools/references.py:111-118`):

```python
    for address in parse_wiki_links(content):
        target = await fs.resolve_address(scope_id, address)
        if not target or target == source_address or target in seen:
            continue
        seen.add(target)
        edges.append({"targetAddress": target, "type": "links_to",
                      "footnote": None, "location": None, "quote": None, "page": None})
```

각주 경로(`find_source`)는 손대지 않는다 — 원본문서는 `stage_source` 가 본문째 넣고,
창구 지연 적재의 대상이 아니다.

### 3.2 재스캔을 주소 단위로 좁힌다 — (B) 제거

`_sync_page_references` 에 선택 인자를 준다 (`spring.py:106`):

```python
async def _sync_page_references(self, scope_id: str,
                                only_address: str | None = None) -> None:
```

`only_address` 가 있으면 그 문서 하나만 돌린다.

**호출부별로 전체/단건이 갈린다.**

| 호출부 | 인자 | 이유 |
| --- | --- | --- |
| `SpringVaultFS._hydrate_from_pages` 끝 | 전체 | 그래프를 처음 만든다 |
| `SpringVaultFS.stage_source` | 전체 | 원본문서가 새로 들어와야 **기존 페이지의 각주가 비로소 풀린다.** 좁히면 인용 간선이 안 생긴다 |
| `FederatedVaultFS._ensure_body` | 단건 | 방금 당긴 페이지의 간선만 새로 생긴다 |

단건으로 좁혀도 되는 근거: 링크 A→B 의 간선은 **A 의 본문이 적재될 때** 만들어진다.
그 시점에 B 의 라이브 행은 하이드레이션이 이미 만들어 뒀으므로(2절) `resolve_address`
가 답한다. B 를 뒤늦게 적재해도 A 의 간선을 다시 계산할 이유가 없다.

`_clear_staleness` 는 지금처럼 짝으로 남긴다. 빼면 페이지 하나 읽은 직후 `index.md` 가
거짓 stale 이 된다 (`spring.py:122-136` 의 이유 그대로).

### 3.3 재귀 차단 가드의 처지

`hydrated_bodies` 의 현재 본래 역할은 캐시가 아니라 **재귀 차단**이다. 고리가
`_ensure_body → _sync_page_references → build_edges → get → _ensure_body` 였다.

3.1 이 그 고리의 `get` 을 끊으므로 **재귀는 구조적으로 사라진다.**

**그래도 걷어내지 않는다.** 같은 페이지를 두 번 `read` 할 때 본문을 두 번 받는 것을
막는 역할이 남는다. 대신 주석의 근거를 바꿔야 한다 — 지금 주석은 "걷어내면 무한 재귀가
난다" 이고, 3.1 이후로는 사실이 아니다. **주석을 그대로 두면 다음 사람이 틀린 근거로
판단한다.**

## 4. 검증

### 4.1 자동 테스트

| 무엇 | 확인 |
| --- | --- |
| `resolve_address` — 라이브·작업층·없는 주소·index.md | 정규 주소 또는 None |
| `resolve_address` 가 본문을 적재하지 않는다 | 호출 후 `hydrated_bodies` 가 그대로 |
| `build_edges` 의 간선 결과가 이전과 동일 | 링크·자기참조·중복 제거 동작 불변 |
| `_ensure_body` 1회의 창구 호출 수 | 링크 n 개 페이지에서 **1회** |
| `only_address` 단건 재스캔 | 그 문서의 간선만 교체, 다른 문서 간선 불변 |
| `stage_source` 는 전체 재스캔을 유지 | 기존 페이지의 각주가 풀린다 |
| 거짓 stale 없음 | 지연 적재 직후 `find_stale_pages` 가 빈 목록 |

### 4.2 실측 재현

`experiments/measure_federated.py` 를 **링크가 있는 코퍼스로** 돌린다.

기존 측정이 이 결함을 놓친 이유가 `corpus-ko` 에 내부 링크가 0개라는 것이다.
사슬 링크 코퍼스로 100장 · 150장에서 `read` 1회의 창구 호출 수를 다시 잰다.

목표:

```
현재    100장 → 100회 · 150장 → 150회
수정 후  장수와 무관하게 1회
```

`INDEX.md` 를 갱신한다. **수치의 정본은 그 파일이다.**

### 4.3 조회 예산 재검토

150 티켓이 fan-out 때문에 예산을 `105 + 2 × 장수` 로 만들었다. fan-out 이 사라지면
그 스케일링의 근거가 없어진다.

**이번에 상수를 바꾸지 않는다.** 예산을 줄이는 것은 죽는 경로를 만드는 변경이고,
근거가 될 실측이 4.2 뿐이다. `query_client.py` 주석에 "fan-out 제거로 스케일링 근거가
약해졌다 — 재산정은 실기동 측정 후" 를 남기고 별건으로 둔다.

## 5. 범위 밖

- **작업층 검색 행이 밀리는 문제** (별 티켓). 부모 `search_chunks` 의 SQL 이 층을 모른다.
  포트 표면 변경이 필요해 이 티켓과 섞지 않는다.
- **`local_server` 창구 모드 배선** — S15P11B106-152.
- **`get_backlinks` 의 원격 병합** — 손대지 않는다. 지연 적재의 사각지대를 메우는
  곳이고, 이 수정으로 적재되는 페이지가 줄어들면 그 역할이 오히려 더 중요해진다.

## 6. 위험

**역링크가 로컬 그래프에서 줄어든다.** fan-out 제거 이후 라이브 페이지의 간선은 그 페이지를
읽었을 때만 만들어진다. 안 읽은 페이지가 거는 링크는 로컬 `document_references` 에 없다.

**이미 대비돼 있다.** `FederatedVaultFS.get_backlinks` 가 창구 `relations` 로 원격
역링크를 병합한다(150 티켓, 설계 9.6). 로컬 그래프가 아니라 창구가 범위 전체의 역링크를
답한다.

**확인 항목으로 4.1 에 넣는다** — 링크를 거는 페이지를 읽지 않은 상태에서 대상의
`get_backlinks` 가 그 링크를 여전히 보고하는지.

## 7. 미결

`resolve_address` 라는 이름. 포트에 새 추상 메서드를 하나 늘리는 변경이라 이름이
남는다. 대안은 `get` 에 `load_body: bool = True` 를 붙이는 것인데, 기본값이 무거운
동작인 플래그 인자는 호출부에서 놓치기 쉽다 — 실제로 이 결함이 그 형태로 생겼다.
별 메서드로 간다.
