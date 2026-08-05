# 게이트가 용서할 오류를 에이전트가 고치려 드는 것 — lint 보고와 반영 게이트의 판정 일치

- 상태: 설계
- 날짜: 2026-08-05
- 범위: `ai/` (`wiki_mcp/tools/lint.py`, `wiki_mcp/vaultfs/`, `wiki_api/session.py`)

## 문제

`lint`가 에이전트에게 `error`로 보고하는 것 중, **반영 게이트가 애초에 무시하는 것**이 있다.
에이전트는 "error가 0이 될 때까지 끝내지 않는다"(`base.py::ingest_instruction`)는 지시를
받으므로, 고쳐도 없어지지 않고 막지도 않을 오류를 붙들고 턴을 다 쓴다.

### 실측 (job 32 / document 43, 2026-08-05 01:03~01:07)

`02-hr-committee-minutes-2024-03.pdf` 반영이 `GraphRecursionError`(recursion_limit 120)로
실패. LangSmith 트레이스(`019fcd84-4371-7222-9599-9a2bc0019ee4`) 기준:

- 도구 호출 **46라운드**, 누적 **1,370,707 토큰** (같은 잡의 성공한 문서 42는 377,715)
- `lint`를 6번 불렀고 `pages/8300a3625b5f.md`를 대상으로 한 4번(20·28·32·39라운드)은
  **글자 하나까지 같은 4건**을 냈다:

  ```
  [unresolved-citation] pages/8300a3625b5f.md
    — 각주 ^1가 01-service-rules-v1.docx을 가리키는데 그런 원본문서가 없다
  (^2·^3·^4 동일)
  ```

- 그 페이지를 **4번 고쳤는데(16·17·44·46라운드) 4건이 한 번도 줄지 않았다.**
- 그 각주 4개는 에이전트가 쓴 것이 아니다 — 라이브 본문에 원래 있던 것이다.
- 에이전트는 없는 문서를 찾아 헤맸다: 33라운드 `service-rules 복무규정`,
  35라운드 `01-service-rules`, 36라운드 `sources/*` → 모두 "없다".
- 그러다 오류가 안 나오는 lint 조합을 찾기 시작했다: 30·41라운드 `check_scope=sources`,
  40라운드 `pages/*` + `check_scope=sources`(대상 없음). **회피 행동이다.**

> `lint` 자체는 일관적이다. 같은 대상을 부르면 항상 같은 결과다. 중간에 "통과"가 섞인 것은
> 다른 파일을 검사한 결과다(21라운드는 새로 만든 페이지, 38라운드는 다른 페이지). 앞선
> 조사에서 "통과와 오류가 번갈아 난다"고 본 것은 인자를 확인하지 않은 오독이었다.

### 왜 구조적으로 못 고치나

하이드레이션은 **범위의 위키 페이지 전부**를 올리지만 **원본문서는 이번 요청의 것만** 올린다
(`session.py:56-67` 주석이 이미 이 사실을 적어두고 있다). 그래서 다른 문서를 인용하는 기존
페이지의 각주는 세션 안에서 `find_source`로 풀리지 않는다. 에이전트는 원본문서를 만들 수
없으므로(읽기 전용) 고칠 수단이 없다.

트레이스 3라운드의 `search`가 이를 그대로 보여준다 — 범위 전체의 원본문서가 1건뿐이다:

```
**원본문서 (1):**
  sources/43/parsed/content.md
```

### 게이트는 이미 알고 있다

`session.py::assert_lint_clean`은 정확히 이 경우를 통과시킨다:

```python
if issue.code == "unresolved-citation" and \
        await self._is_legacy_footnote(issue.address, issue.footnote):
    continue
```

`_is_legacy_footnote`는 **나이로 판정한다** — 각주 정의 줄이 라이브 층 본문에 그대로 있으면
실행 전부터 있던 것이다. 이름으로 걸러내려던 앞 판본이 지어낸 인용까지 통과시켜(NFR-AI-002
상실) 나이 판정으로 바꾼 이력이 그 docstring에 남아 있다.

**즉 document 43은 반영 게이트에서 막히지 않았을 것이다.** 게이트에 도달하지도 못했다 —
에이전트가 그 4건을 고치려다 턴 상한에 걸려 죽었다.

### 진짜 결함

같은 판정("이 각주가 이번 작업 전부터 있었나")이 **필요한 두 곳 중 한 곳에만 있다.**
게이트에는 있고, 에이전트가 읽는 lint 보고서에는 없다.

같은 결함이 하나 더 있다. `[missing-frontmatter] index.md`가 31·42라운드에 나오는데,
게이트는 `_INDEX_FRONTMATTER_CODES`로 이미 무시한다 — Spring이 주는 목차 본문에는
frontmatter가 없고 에이전트가 목차를 고쳐도 그 사실은 변하지 않는다. 에이전트는 index.md를
두 번 고쳤고(19·43라운드) 그래도 남아 있었다.

## 설계

판정을 **아래로 내려** 두 소비자가 하나의 규칙을 공유한다. `wiki_mcp`는 `wiki_api`를
임포트할 수 없으므로 반대 방향(게이트가 `wiki_mcp`의 판정을 쓴다)이 유일하게 성립하는 방향이고,
마침 판정에 필요한 것이 전부 VaultFS 원시 연산이라 그대로 내려간다.

### 1. `VaultFS.live_content`를 규약으로 올린다

지금 `live_content`는 `spring.py`에만 있다. `session.py`가 그것을 무조건 부르므로 **게이트가
구체 클래스에 조용히 의존하고 있다** — `LocalVaultFS` 기반 세션이면 `AttributeError`다.
지금은 세션이 항상 `FederatedVaultFS`라 잠재 결함이지만, 판정을 `lint`로 내리면 하네스·개발
도구·테스트가 쓰는 `LocalVaultFS`도 이 경로를 타므로 더는 미룰 수 없다.

- `base.py`: 추상 메서드로 선언 (docstring은 spring.py의 것을 정본으로 옮긴다)
- `local.py`: `_row(scope_id, address, "live")` 로 구현 — 이미 있는 헬퍼다
- `spring.py`: 그대로 (상속으로 `FederatedVaultFS`까지 내려간다)

**비용 0.** `SpringVaultFS(LocalVaultFS)`이라 `_row`는 로컬 SQLite 조회다. Spring 재호출이
아니다 — 라이브 층은 하이드레이션이 이미 채워뒀다.

### 2. 나이 판정을 `wiki_mcp`로 옮긴다

`wiki_mcp/services/footnotes.py` (신규):

```python
async def legacy_footnote_labels(fs, scope_id: str, address: str) -> set[str]:
    """정의 줄이 라이브 층에 그대로 있는 각주 라벨. = 이번 작업 전부터 있던 것."""
```

라벨이 아니라 **정의 줄 전체**로 비교한다 — 게이트가 쓰는 규칙 그대로다. 라벨만 보면 같은
번호로 내용을 바꿔 쓴 각주가 통과한다.

### 3. `lint`가 강등한다

`LintHandler._lint_citations`에서 `find_source`가 실패했을 때:

| 조건 | 결과 |
| --- | --- |
| 편집 세션(`self._editing`) **이고** 그 각주가 legacy | `warn` / 새 코드 `unresolved-citation-legacy` |
| 그 밖 | 지금대로 `error` / `unresolved-citation` |

메시지는 고칠 수 없다는 것을 명시한다 — 「이번 작업 전부터 있던 각주이고 그 원본문서는 이
작업에 올라오지 않았다. **고칠 수 없으니 그대로 둔다.**」

**새 코드로 나눈다.** `unresolved-citation`을 재사용해 심각도만 바꾸면 게이트가 둘을 구분할
수 없고, 강등 조건이 나중에 넓어져도 조용히 통과한다.

**`_editing` 가드가 필요하다.** 작업 층이 없는 개발 도구 세션에서는 모든 각주가 legacy로
보여 미해결 인용이 전부 강등된다. 그쪽은 지금대로 `error`를 낸다.

`index.md`의 frontmatter 계열도 같이 처리한다 — 편집 세션에서 `index.md`에 대한
`missing-frontmatter`·`missing-title`·`missing-tags`·`missing-category`·
`footnote-in-frontmatter`는 `warn`으로 내린다. 게이트가 이미 무시하는 목록과 같다.

### 4. 게이트가 그 판정을 쓴다

`assert_lint_clean`은 `_is_legacy_footnote` 호출을 버리고 `unresolved-citation-legacy`와
강등된 index frontmatter 코드를 무시한다. `_is_legacy_footnote`·`_definitions`·
`_definition_line`은 삭제한다 — 판정이 한 곳에만 있어야 한다.

`_INDEX_FRONTMATTER_CODES`도 지운다. 같은 이유다.

### 왜 `warn`이고 침묵이 아닌가

풀리지 않는 기존 각주는 실재하는 부채다. 그 범위에 그 원본문서가 다시 올라오면 풀린다.
숨기면 아무도 모른다. 그리고 2026-08-02에 넣은 종료 신호(`lint._report()`: "error가 없으니
이걸로 끝이다. warning은 참고만 한다 — 고치려고 다시 edit·lint를 부르지 않는다")가 이미
에이전트에게 warn을 쫓지 말라고 말한다.

## 이 설계가 고치지 않는 것

- **부분 하이드레이션 자체.** legacy 각주는 여전히 검증 불가 상태로 남는다. 손댄 페이지가
  인용하는 문서까지 끌어오는 것이 근본 수정이고 별도 과제다 — 조회 호출이 문서 수만큼 늘어
  예산 영향을 먼저 재야 한다.
- **에이전트가 고칠 수 있는 다른 루프.** 이 변경은 「고칠 수 없는 오류」 한 갈래만 없앤다.
- **턴 상한.** `MAX_TURNS=60` 은 그대로 둔다 (사용자 지시로 보류).

## 검증

1. **단위 테스트 (크레딧 0)**
   - `local.py::live_content` 가 작업 층 수정을 무시하고 실행 전 본문을 준다
   - legacy 각주 → `warn`/`unresolved-citation-legacy`, 이번 작업이 쓴 각주 → `error`
   - 같은 라벨로 정의 줄을 고친 각주는 legacy 가 아니다 (지어낸 인용이 통과하지 않는다)
   - `_editing=False` 인 세션은 강등하지 않는다
   - `assert_lint_clean` 이 `unresolved-citation-legacy` 를 막지 않고
     `unresolved-citation` 은 막는다
   - 기존 928건이 그대로 통과한다
2. **실기동 1회 — document 43 재투입.** 같은 문서, 같은 페이지(`8300a3625b5f`)가 대상이므로
   조건이 재현된다. 성공 판정은 「완주」와 「턴 수」다 (실패 실행 46라운드 대비).
   **유료이므로 사용자 확인 후에만 돌린다.**

## 리스크

- 강등 조건이 넓으면 지어낸 인용이 통과한다(NFR-AI-002). 정의 줄 전체 비교와 새 코드 분리가
  그 방어다. `_is_legacy_footnote` docstring에 같은 사고 이력이 남아 있다.
- `live_content` 를 ABC로 올리면 VaultFS 구현체를 만드는 외부 코드가 깨진다. 저장소 안에는
  세 구현체뿐이고 전부 이 변경에 포함된다.
