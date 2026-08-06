# 2026-08-06 실기동 — 같은 파일 재업로드가 근거를 부풀리고 삭제를 막는다

> [`2026-08-03-live-stack-verification.md`](2026-08-03-live-stack-verification.md) 의 후속이다.
> Spring Boot + MySQL(docker `ajt-mysql`) + AI 서버 3계층을 띄운 상태에서 사용자가 직접
> 문서를 올리고 지우며 확인했다. **MySQL 데이터는 초기화하지 않았다** — 아래 수치는 8/1 부터
> 쌓인 실데이터를 그대로 조회한 것이다.
>
> 이 문서가 다루는 것은 **아직 고치지 않은 결함 세 가지**다. 같은 날 함께 확인한
> 관계 단방향 저장(S15P11B106-279)과 목차 링크 깨짐(S15P11B106-280·281)은 고쳐서 머지했고
> 여기서 다루지 않는다.

## 요약

| # | 결함 | 심각도 | 담당 | 상태 |
| --- | --- | --- | --- | --- |
| ① | 같은 파일을 다시 올리면 별개 문서로 쌓여 **한 위키의 근거가 중복**된다 | 그래프뷰 오독 | 백엔드 | 미해결 |
| ② | **삭제 작업이 끝나지 않는다**(`GraphRecursionError`) | 작업 실패 | AI | **고침** (MR !266) |
| ③ | 교체 시 **낡은 인용문이 `warn` 이라 통과**해 위키에 남는다 | 조용한 사실 오류 | AI | 미해결 |

> ⚠️ **2026-08-06 정정.** 이 표의 앞 판본은 「②는 ①이 근본 원인」이라고 적었다. **틀렸다.**
> ②는 중복이 하나도 없어도 일어난다 — 삭제 재조정이 지울 문서의 옛 본문을 스스로
> 작업 공간에 얹기 때문이다. 자세한 것은 ② 절. **①과 ②는 독립이고, ①을 고쳐도 ②는
> 안 죽는다.**

③은 별개지만 같은 「원본이 바뀌었을 때」 경로에 있다.

---

## ① 같은 파일을 다시 올리면 근거가 중복된다

### 증상

그래프뷰에서 **한 위키에 같은 원본문서가 여러 번 매달려 보인다.**

### 실데이터

`document` 테이블 전체를 파일명으로 묶고, 디스크의 `original_path` 를 SHA-256 으로 대조했다.

| document_id | 파일명 | 크기 | 내용 해시(앞 12) | status |
| --- | --- | --- | --- | --- |
| 15 | `03-service-rules-amendment.md` | 1,033 | `daff8b09dfca` | COMPLETED |
| 24 | **`03-amendment.md`** | 1,033 | `daff8b09dfca` | COMPLETED |
| 42 | `03-service-rules-amendment.md` | **3,440** | `cfe71aad6aaa` | COMPLETED |
| 23 | `01-service-rules-v1.docx` | 37,755 | `ed873e6ed687` | COMPLETED |
| 25 | `01-service-rules-v1.docx` | 37,755 | `ed873e6ed687` | FAILED |
| 28 | `02-side-gigs.md` | 3,381 | `895223df3e2d` | COMPLETED |
| 32 | `02-side-gigs.md` | 3,381 | `895223df3e2d` | COMPLETED |

**15 와 24 는 내용이 완전히 같은데 파일명이 다르다.** 이것이 뒤에서 중요하다.
42 만 진짜 개정본이다(같은 이름, 다른 내용).

그 결과 위키의 `document_refs` 가 이렇게 됐다:

| wiki_id | 제목 | document_refs | 실제 서로 다른 원본 |
| --- | --- | --- | --- |
| 5 | 연차유급휴가 규정 | `[15, 23, 24, 25, 42]` — **5건** | **3건** (`daff…`·`ed87…`·`cfe7…`) |
| 4 | 출장비(여비) 정산 안내 | `[13, 23, 25]` — 3건 | 2건 |
| 8 | 부업(Side Gig) 정책 | `[28, 32]` — 2건 | **1건** |

wiki 8 은 **근거가 하나뿐인데 그래프에 둘로 보인다.** wiki 5 는 다섯 개 중 둘이 군더더기다.

**FAILED 문서도 근거로 남는다** — 문서 25 는 `FAILED` 인데 `document_wiki_refs` 가 `[5, 4]` 이고
위키 쪽 `document_refs` 에도 들어 있다. 실패한 작업이 관계를 남긴 것인지 이전 성공 작업의
잔재인지는 이 문서에서 가리지 못했다.

### 원인

업로드 경로에 **중복 판정이 없다.** 파일을 올릴 때마다 `document` 행이 새로 생기고 새 작업이
돈다. 에이전트는 매번 같은 내용을 읽으므로 같은 위키에 도달하고, 관계가 하나 더 붙는다.

### 고치는 방향 — ERD 무변경으로 가능하다

해시 컬럼을 새로 만들 필요가 없다. 이미 있는 것으로 판정할 수 있다:

1. **후보 좁히기**: 같은 `scope_key` + 같은 `file_size`
2. **확정**: 후보들의 `original_path` 파일을 실제로 해시해 비교

> ⚠️ **파일명으로 먼저 좁히면 안 된다.** 위 표의 15·24 가 반례다 — 내용이 같은데 이름이
> 달라서 이름 기준 후보 집합에서 빠진다. `file_size` 는 이름이 바뀌어도 살아남는다.

판정 뒤 갈래:

| 판정 | 처리 |
| --- | --- |
| 크기·해시 모두 같음 | **거부.** "이미 올라와 있는 문서입니다 (document N)" |
| 이름 같고 내용 다름 | 기존 `DOCUMENT_REPLACED` 흐름으로 태운다 (문서 42 가 이 경우다) |
| 이름·내용 모두 다름 | 지금처럼 신규 |

담당은 백엔드다. 티켓 없음.

---

## ② 삭제가 끝나지 않는다 — `GraphRecursionError`

### 증상

문서 25(`01-service-rules-v1.docx`)를 UI 에서 삭제했다. UI 표시:

> 이 문서를 인용하던 페이지가 14건 있는데 에이전트가 아무 위키도 고치지 않았습니다.
> 실패 단계 · AI 처리 오류

### 실데이터

```
ai_job job_id=35  status=FAILED  document_ids=[25]
started 2026-08-06 08:57:17 → finished 08:59:27  (2분 09초)
failure_reason: 위키 작업이 완료되지 못했습니다 — GraphRecursionError
                (마지막 도달 단계: search): Recursion limit of 120 reached
                without hitting a stop condition.
```

위키는 하나도 바뀌지 않았다. 실행 중 AI 서버 콘솔에서 **`wiki-search` 호출 22회**를 셌다
— 이 수치만은 DB 가 아니라 그때의 표준출력이 근거이고, 서버를 내린 뒤라 다시 세지 못한다.
`failure_reason` 의 `마지막 도달 단계: search` 가 같은 사실을 DB 에도 남기고 있다.

### 처음 진단은 틀렸다

첫 가설은 "에이전트가 어느 페이지를 고쳐야 하는지 몰라서 전수 탐색했다" 였다. **아니다.**
`agent_runtime/base.py::reconcile_instruction` 은 각주 단위 목록을 이미 지시문에 적어 준다:

```python
lines = [
    f"  - `{item['address']}` 각주 [^{item.get('footnote')}] "
    f"— {item.get('location') or '위치 미기재'} — \"{(item.get('quote') or '')[:60]}\""
    for item in affected
]
```

### 에이전트가 마주친 상태

- 지시문: "`01-service-rules-v1.docx` 가 **삭제**됐다. 이 문서를 근거로 쓴 문단과 각주를 걷어내라"
- 도구: 그 이름을 `read` 하면 **문서가 멀쩡히 나온다**
- `lint`: `unresolved-citation`(error)이 **안 뜬다**

지우라는 근거가 실재하니 지울 수도, 끝낼 수도 없다. 에이전트는 무엇이 잘못됐는지 찾으려
검색을 반복했고 상한에서 죽었다.

### 원인 — ①이 아니다 (2026-08-06 정정)

> ⚠️ **이 절의 앞 판본은 원인을 잘못 짚었다.** "문서 23 과 25 는 파일명이 같아서, 25 를
> 지워도 그 이름을 부르는 각주가 **살아 있는 23 으로 해석된다**" 고 적었는데 **틀렸다.**
> 위 관측 세 줄은 맞다 — 귀속된 원인만 틀렸다.

**하이드레이션은 위키 페이지만 올린다.** `FederatedVaultFS._hydrate_catalog` 가
`list_pages()` 만 부른다 — **문서 23 은 애초에 vault 에 없다.** 그러니 각주가 23 으로
해석될 수 없다.

진짜 원인은 **삭제 경로 자신**이다. 삭제 재조정은 **지울 문서의 옛 본문을 작업 공간에
얹는다** (`wiki_api/routers/wiki.py` 의 `load_source(payload.documentId, removed, …)`).
그래야 각주가 풀려 backlink 가 잡히므로 **필연적 동작**이고, 코드 주석이 이유까지 적어
뒀다. 그 스테이징본은 `visible_documents` 에 들어가므로 `find_source` 가 그것을 찾는다.

**중복이 하나도 없어도 똑같이 일어난다.** `tests/mcp/test_removal_self_contradiction.py`
가 중복 0 인 범위에서 재현한다:

| | 스테이징 전 | 스테이징 후 |
| --- | --- | --- |
| `find_source("복무규정.docx")` | `None` | **문서를 찾는다** |
| `lint` | `unresolved-citation` **잡음** | **안 잡음** |

따라오는 것: **①(중복 업로드)을 고쳐도 이 교착은 안 죽는다.** 두 결함은 독립이다.
①은 그래프 오독 문제로만 남는다.

### 고친 방법 (MR !266)

`document_replaced` 분기는 **이미 이 문제를 알고 고쳐놨다** — "그 주소를 `read` 하면 이미
새 내용이 들어 있다" 를 적고 주석에 이유까지 달아 뒀다. 같은 교훈이 삭제 분기에만
반영되지 않았다. 그래서 삭제 분기에도 넣었다:

1. 그 주소가 **참고용 사본**이며 문서가 아직 있다는 뜻이 아님을 명시
2. **`lint` 로 완료를 판단하지 말 것** — 걷어낼 각주가 미해결로 안 잡히므로 `error 0` 은
   완료의 근거가 아니다
3. **고칠 곳 목록이 전부**임을 명시

참고로 「각주가 파일명으로 문서를 가리키니 주소로 지목하면 된다」는 안도 검토했다가 버렸다
— 지시문은 **이미** `sources/{id}/parsed/content.md` 를 주소로 지목하고 있고, 그 주소가
읽히는 것이 문제이기 때문이다.

아래 항목은 위 정정 전에 적은 것이다. 여전히 유효하고 MR !266 에 함께 들어갔다:

`reconcile_instruction` 의 지시문에 **목록이 전부라는 명시가 없다.** "고칠 곳은 다음과 같다"
까지만 말하고 "이 목록 밖은 찾지 않는다"를 말하지 않아, 에이전트가 탐색으로 새는 문을
열어 둔다. 한 문장 추가로 이 경로의 폭주를 줄일 수 있다 — 다만 **모순 자체는 남는다.**

---

## ③ 교체 시 낡은 인용문이 조용히 통과한다

### 상황

문서를 **일부만 고쳐** 다시 올리는 경우다. 실데이터의 문서 42 가 그렇다
(`03-service-rules-amendment.md`, 1,033 → 3,440 바이트).

내용의 70% 가 그대로면 그 부분을 인용한 각주는 계속 맞다. 나머지 30% 를 인용한 각주는
**틀린 말을 하게 된다.**

### 무엇이 걸리고 무엇이 안 걸리나

`tools/lint.py::_lint_citations` 기준:

| 상황 | 판정 | 심각도 |
| --- | --- | --- |
| 원본문서가 없다 | `unresolved-citation` | **error** |
| 절 제목을 못 찾는다 | `citation-location-not-found` | **error** |
| 절은 있는데 **인용문이 원문에 없다** | `citation-quote-not-found` | **warn** |

반영 게이트(`assert_lint_clean`)는 **`error` 에서만 막는다.** 그래서:

- ~~**삭제**는 안전하다 — 원본이 통째로 사라지니 `unresolved-citation`(error)이 잡는다~~
  **(2026-08-06 정정: 틀렸다.)** 재조정 중에는 그 원본이 작업 공간에 **올라와 있어**
  각주가 풀린다. `unresolved-citation` 이 뜨지 않는다 —
  `tests/mcp/test_removal_self_contradiction.py` 가 실측으로 고정한다. 그래서 삭제 지시문은
  **`lint` 로 완료를 판단하지 말라고 명시한다** (MR !266)
- **교체**는 새는 구멍이 있다 — 원본도 절 제목도 살아 있고 **문장만 바뀐** 각주는
  `warn` 하나로 통과한다. 위키에는 새 원문에 없는 인용문이 그대로 남는다

### warn 인 것은 의도된 결정이었다

`lint.py` 의 주석이 근거를 남겨 두고 있다:

> warn, not error: 위치(`citation-location-not-found`)가 이미 날조를 막는 최소선이다.
> 인용문 리터럴 일치까지 강제하면 에이전트가 정당한 주장에 딱 맞는 문장을 못 찾고 계속
> 다른 표현을 시도하다 턴 상한(GraphRecursionError)에 걸려 작업 전체가 실패하는 사례가
> 실측으로 확인됐다 (2026-08-02).

**되돌리자는 게 아니다.** 그 결정은 신규 변환에서 옳았다 — 8/2 문서가 40턴 실패를 기록했고,
8/3 문서가 완화 뒤 12턴 성공을 기록했다.

### 고치는 방향 — 범위를 좁혀서만

신규 변환에서는 지금대로 `warn` 을 유지하고, **`document_replaced` 요청의 `affected` 목록에
실린 각주에 한해서만** `error` 로 올리는 안이 있다. 근거:

- 그 각주들은 **원문이 바뀌었다고 이미 알려진 것**이다. "못 찾겠다"가 아니라 "고치라고
  지목한 것을 안 고쳤다"이므로 막을 근거가 다르다
- 대상이 목록으로 한정돼 있어 8/2 의 폭주(위키 전체의 모든 각주를 리터럴 대조)와 규모가 다르다

**결정하지 않았다.** 실측 없이 `error` 를 늘리는 것은 8/2 의 실패를 되부를 수 있어,
넣는다면 실기동 재측정이 함께 가야 한다.

---

## 검증에 쓴 명령

```bash
docker exec ajt-mysql mysql -uroot -prootpw ajt -e \
  "SELECT document_id, original_file_name, file_size, status, document_wiki_refs FROM document ORDER BY document_id;"

docker exec ajt-mysql mysql -uroot -prootpw ajt -e \
  "SELECT wiki_id, title, document_refs, wiki_refs FROM wiki ORDER BY wiki_id;"

docker exec ajt-mysql mysql -uroot -prootpw ajt -e \
  "SELECT job_id, status, document_ids, failure_reason FROM ai_job WHERE JSON_CONTAINS(document_ids,'25');"

cd backend/build/ajt-documents
for d in 15 23 24 25 28 32 42; do shasum -a 256 wiki/ALL/sources/$d/original.*; done
```

MySQL 클라이언트가 한글을 `?` 로 뱉으므로 제목 확인에는 `--default-character-set=utf8mb4` 를
붙인다. 위 표의 제목은 위키 본문 파일에서 읽었다.
