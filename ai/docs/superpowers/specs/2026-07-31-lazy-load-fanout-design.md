# 지연 적재 fan-out 제거 — 설계

- 티켓: S15P11B106-151
- 선행: S15P11B106-150 (Wiki 조회 API 계약·어댑터, MR !89)
- 후행: S15P11B106-152 (조회 API 경로 배선)
- 범위: `ai/` 안에서 끝난다. 계약·백엔드 무변경

> **용어** — 이 문서는 S15P11B106-161 의 용어를 쓴다. FastAPI 가 Spring Boot 에
> 호출하는 조회 엔드포인트 묶음을 **Wiki 조회 API** 라 부른다. 기존 문서의
> 「창구」·「게이트웨이」와 같은 대상이다.

## 1. 문제

Wiki 조회 API 경로에서 본문은 첫 접근에 1장씩 받는다(지연 적재). 그런데 **본문 1장을
받는 데 조회 호출이 위키 장수만큼 터진다.**

측정 (2026-07-31, develop `69556f5`, 가짜 조회 API, 사슬 링크 100장):

```
catalog_pages              100
first_read_calls           100     ← read 한 번에 조회 100회
first_read_body_fetches    100     ← 본문 100장을 다 당겼다
```

재현:

```bash
mkdir -p /tmp/ajt-corpus/wiki/ALL
ln -sfn "$(pwd)/experiments/corpus-ko/pages" /tmp/ajt-corpus/wiki/ALL/pages
uv run python experiments/measure_federated.py --corpus /tmp/ajt-corpus \
    --scope ALL --link-density 1 --pages 100 --phases de
```

약 60초. LLM·네트워크를 쓰지 않는다.

## 2. 원인 — 서로 독립인 두 가지

```
_ensure_body(A)
 ├─ ① _sync_page_references(scope_id)   공간의 라이브 문서 전체를 다시 훑는다
 └─ ② build_edges → 링크마다 fs.get     FederatedVaultFS.get 이 _ensure_body 를 탄다
                                          = 링크 대상의 본문을 조회 API 로 당긴다
```

**② 가 조회 100회를 만든다.** A 를 읽으면 A 가 링크한 B 의 본문을 당기고, B 의 링크가
C 를 당긴다. 사슬 끝까지 간다. ① 을 좁혀도 이 사슬은 그대로 돈다 — 좁힌 훑기가 여전히
`build_edges` 를 부르고, `build_edges` 가 여전히 `fs.get` 으로 본문을 당기기 때문이다.

**① 은 로컬 O(n²) 다.** 조회 호출은 0이지만 본문 n건 적재 × 전체 재훑기 n. 위키 1,000장
이면 100만 번 단위다.

`build_edges` 가 링크마다 `fs.get` 을 부르는 것은 Lucas 이식분 그대로다. **로컬 전용
전제에서는 문제가 아니다** — 본문이 이미 다 있어 `fs.get` 이 공짜다. 문제는 그 전제를
네트워크 경로에 그대로 가져다 쓴 것이다.

`references.py:113-118` 을 보면 `build_edges` 는 반환값의 `address` 만 읽는다. **본문을
쓰지 않는다.** 필요한 것은 「이 주소가 이 공간에 있나」뿐이고, 그 답은 하이드레이션이
이미 SQLite 에 넣어 뒀다(제목·주소·카테고리, 본문만 빈 문자열).

즉 **「존재 확인」과 「본문 적재」가 한 메서드에 묶여 있는 것**이 근본 원인이다.

## 3. 고침 ① — 포트에 주소 해석을 낸다

`vaultfs/base.py` 에 순수 추가:

```python
@abstractmethod
async def resolve_address(self, scope_id: str, address: str) -> dict | None:
    """이 주소가 공간에 있으면 그 행을, 없으면 None.

    존재 확인 전용이다. 본문 적재를 유발하지 않는다 — 반환된 dict 의 content 는
    비어 있을 수 있다. 본문이 필요하면 `get` 을 쓴다.
    """
```

| 구현체 | 동작 |
| --- | --- |
| `LocalVaultFS` | `get` 위임 — 로컬은 본문이 이미 있다 |
| `SpringVaultFS` | 상속 — `selectedWikis` 경로도 본문이 이미 있다 |
| `FederatedVaultFS` | `super().get` — `_ensure_body` 를 안 탄다 |

포트 구현체는 `VaultFS ← LocalVaultFS ← SpringVaultFS ← FederatedVaultFS` 한 줄기뿐이라
(테스트 대역 포함) `@abstractmethod` 로 내도 실제로 구현할 곳은 두 곳이다.

호출부는 `tools/references.py:113` 한 곳:

```python
target = await fs.resolve_address(scope_id, address)   # was fs.get
```

`get` 의 의미는 바뀌지 않는다. 본문이 필요한 기존 호출자는 그대로다.

### 왜 다른 안을 안 골랐나

- **`get(..., load_body=False)` 플래그** — 구현체 3곳 시그니처가 다 바뀌어 순수 추가가
  아니다. 플래그가 반환값이 아니라 **부작용**을 끄는 것이라 이름이 거짓말한다
  (로컬에서는 본문이 그대로 온다).
- **`federated.py` 안에서 재진입 감지** — `hydrated_bodies` 라는 재귀 차단 위에 또
  상태를 얹는다. 원인을 그대로 두고 증상만 가린다.

## 4. 고침 ② — 재동기화 범위를 좁힌다

`federated.py::_ensure_body` 가 부르던 전체 훑기를 방금 받은 주소 하나로 바꾼다.

```python
# 지금
await self._sync_page_references(scope_id)      # 라이브 문서 전부

# 바꾼 뒤
await sync_references(self, scope_id, address, body_markdown)   # 방금 그 페이지만
await self._clear_staleness(scope_id)
```

`sync_references` 는 `wiki_mcp.tools.references` 에 있고 `vaultfs` 는 `tools` 를 모듈
상단에서 임포트하지 않는다(순환 임포트). `spring.py:115` 가 메서드 안에서 지연
임포트하는 것과 같은 방식을 쓴다.

**전체 훑기는 두 곳에서 유지한다** — `SpringVaultFS._hydrate_from_pages` 와
`stage_source`. 이유는 `_sync_page_references` docstring 에 있다. 하이드레이션은 위키
페이지만 넣고 각주가 가리키는 원본문서는 `stage_source` 가 나중에 넣으므로, 원본문서가
들어온 뒤 다시 훑어야 이미 넣은 페이지의 각주가 풀린다.

`_clear_staleness` 짝은 유지한다. `sync_references` 가 `propagate_staleness` 를 태우고,
지연 적재는 컨텍스트를 처음 채우는 것일 뿐 페이지가 실제로 바뀐 게 아니다
(`spring.py:122-130`).

### 4.1 목차 참조 그래프 — 이번에 드러난 구멍

`FederatedVaultFS._hydrate_catalog` 는 `_sync_page_references` 를 **부르지 않는다**
(`federated.py:151-158`). 그래서 조회 API 경로는 `index.md` 의 참조 그래프를 아무도 만들지
않는다. 지금은 첫 `_ensure_body` 의 전체 훑기가 **우연히** 대신 해주고 있다. 범위를
좁히면 그 우연이 사라진다.

하이드레이션 끝에 목차 1건만 명시적으로 동기화한다:

```python
await sync_references(self, scope_id, INDEX_ADDRESS, index_markdown)
await self._clear_staleness(scope_id)
```

**3절이 없으면 이것은 비쌌다** — 목차는 모든 페이지를 링크하므로 페이지 수만큼 본문을
당겼을 것이다. `resolve_address` 가 있으니 조회 호출 0으로 끝난다. 두 변경이 서로를
가능하게 한다.

### 4.2 `hydrated_bodies` 는 남긴다

지금 주석은 이 집합을 **무한 재귀 차단** 장치로 설명한다(뮤턴트 근거 포함, 설계 9.2.1).
3절이 재귀 고리 자체를 없애므로 그 역할은 끝난다. 그러나 집합은 **유지한다** — 「이
본문을 이미 받았나」를 아는 유일한 곳이고, 없으면 같은 페이지를 읽을 때마다 조회를 다시
부른다. **주석을 새 역할로 고쳐 쓴다.** 근거를 지우지 않고 이력으로 남긴다.

## 5. 검증

### 5.1 측정

| 항목 | 지금 | 통과 기준 |
| --- | --- | --- |
| 100장, `read` 1회의 조회 호출 | 100 | **1~2** |
| 150장, 같은 조건 | 150 | **1~2** (장수에 비례하지 않는다) |

150장은 `--pages 150` 으로 같은 명령을 돌린다.

### 5.2 회귀

- `uv run pytest -m "not ocr"` 기존 실패 0
- **`selectedWikis` 경로가 안 깨졌는지** — 지금 실제로 도는 방식이다.
  `tests/api/test_api_wiki.py`·`tests/api/test_federated_session.py` 의 고정 테스트가 지킨다

### 5.3 새로 붙이는 테스트

- 조회 API 경로에서 `read` 1회의 조회 호출 수를 고정한다 (가짜 클라이언트로 센다)
- 순환 링크(A→B, B→A)에서 `RecursionError` 가 나지 않는다. 지금은 `hydrated_bodies`
  가드가 막고 있고, 고친 뒤에는 구조적으로 없어야 한다
- `resolve_address` 가 본문 적재를 유발하지 않는다 — 구현체별로

### 5.4 실연동은 이번에 못 한다

Wiki 조회 API 경로는 `session.py::_assert_runtime_can_use_the_gateway` 가 fail-closed 로
막고 있다. 그 가드는 S15P11B106-152 가 걷는다. **이 티켓은 가짜 조회 API 로만 재고,
실연동 확인은 152 에서 붙는다.** 가짜 API 가 증명하는 것과 못 하는 것은
`experiments/query_gateway.py` 헤더에 적혀 있다.

## 6. 이번에 다시 볼 것

`query_client.py` 의 `QUERY_CALLS_PER_PAGE = 2`. 조회 예산을 카탈로그 크기에 연동한
상수인데, fan-out 이 사라지면 과할 수 있다. **실측한 뒤 판단하고, 안 바꿔도 된다.**
숫자는 `experiments/INDEX.md` 에 기록한다 — 측정하지 않은 숫자를 넣지 않는다.

## 7. 범위 밖

- Wiki 조회 API 경로 배선 — S15P11B106-152
- 라이브 본문 적재가 작업층 검색 결과를 밀어내는 결함 — S15P11B106-154.
  이번 측정에서도 재현됐다(`index_work_rows` 5행 → 3행). 부모 `search_chunks` 의 SQL 이
  층을 몰라서 생기는 별건이다
- 「창구」·「게이트웨이」 용어 통일 — S15P11B106-161
- 계약·백엔드 변경 없음
