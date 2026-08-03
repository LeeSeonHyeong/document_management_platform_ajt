# wiki_mcp 도구·lint 반복 검증 하네스

- 상태: 구현 완료, 사용 중 (corpus 5개 문서로 검증, 결함 9건 발견·수정)
- 날짜: 2026-08-02 (갱신: 2026-08-03)
- 범위: `ai/` (`wiki_mcp`, `agent_runtime`), backend·MySQL 관여 없음

## 결과 (2026-08-03)

corpus 5개 문서(`08-compensation`·`07-time-off`·`04-benefits`·`12-communication`·
`11-offsites`)로 실제 위키화·보강하며 **결함 9건을 발견·수정하고 회귀테스트를 추가**했다
(유닛테스트 849건 통과, MCP 경로로도 재확인). 상세는 [[wiki-mcp-tool-lint-harness]] 메모리와
커밋 로그.

- **1~5** (Opus, MR !157 로 develop 머지): `rebuild_index()` 가 work 레이어 documents row 를
  레이어 구분 없이 지워 **이미 만든 페이지가 파일은 남은 채 색인에서 증발**하던 것(가장 심각),
  인용문 리터럴 검증을 error→warn 완화 + 따옴표·강조·마크다운 링크 정규화, 각주 정의 오탐,
  인용 파싱 그리디, 삭제된 페이지의 인용 엣지가 `uncited-source` 검사를 가리던 것.
- **6** (Opus): `parse_citation` 이 파일명·위치의 공백 하이픈에서 잘리던 것. 사용자 판단으로
  이번 반영에서는 제외(브랜치에 보존).
- **7~9** (**Sonnet 으로 돌려 발견**): 인용문 링크 지침이 `dangling-link`(error)를 유발하는
  모순, 백틱(인라인 코드) 정규화 누락, `create` 의 `category`·`tags` 가 조건부 필수인데
  스키마엔 생략 가능처럼 보이던 것. 「페이지 2~5건」 지침이 빈 위키 첫 반영과 어긋난 것도 정정.

**같은 조건에서 divergence 감사도 했다** — 하네스가 프로덕션과 어디까지 같은지 `VaultFS`
전 메서드를 대조해, 초판의 "남는 차이는 capability 뿐" 이라는 과장을 정정했다(아래
「정직한 경계」). 짧은 본문 색인 divergence(#3)는 코드로 메웠고, 검색 랭킹·참조 타이밍은
못 메우는 갭으로 기록했다.

## 문제

`lint.py`(따옴표/강조 정규화 등)나 `guide.py`(에이전트 지침) 문구 하나를 검증하려고
매번 실제 Spring+MySQL+AI 풀 파이프라인을 돌리면 회당 $2.5~3, 수 분이 든다
([[wiki-agent-e2e-timeout-bug]] 참고 — `08-compensation.md` 하나에 실패 4회로 $11+
소진). 순수 `wiki_mcp` 도구·lint 로직 문제를 이 비용으로 반복 확인하는 건 비효율적.

## 목표와 스코프

**목표**: Claude Code(사람)가 실제 `wiki_mcp` 도구(read/search/write/lint/guide)를
MCP로 직접 호출해, Spring/MySQL 없이 값싸게 반복 검증한다.

**MySQL 반영은 스코프 밖이다.** 프로덕션에서도 AI 서버는 MySQL을 건드리지 않는다 —
위키 본문의 정본은 파일이고(`docs/db/erd.sql`의 `wiki.content_hash` 주석: "파일이
정본이므로(DR-001) 이 값은 파일에서 파생된다"), `wiki_search_chunk`는 Spring 쪽이
자체 검색에 쓰는 별개의 파생 캐시로 AI 쪽 sqlite 색인과 무관하다. 하네스가 생성한
내용을 실제 MySQL에 반영하고 싶다면 Spring의 커밋 로직(`WikiTransformationService`)을
타야 하는데, 그건 새로 만들 도구가 아니라 **이미 있는 실기동 파이프라인**
(`POST /api/v1/documents` → `/ai-jobs/{id}/start` → 폴링)을 사용자 확인 후 돌리는
것이다. 하네스는 그 실기동 실패 확률을 낮추는 사전 검증 단계이지, 그것을 대체하지 않는다.

## 구조적 동일성 확인 (근거) — **도구·lint 로직 층에 한정**

이 하네스가 프로덕션과 "얼마나 같은 구조인지"를 코드로 직접 대조했다. **아래 "같은
코드" 대조는 도구 동작과 lint 로직 층에 대해서만 참이다** — 검색·참조 타이밍 층에는
실재하는 divergence가 있다(2026-08-03 감사, 이 절 끝 「정직한 경계」 참조).

- **도구 구현은 완전히 같은 코드.** `wiki_mcp/local_server.py:83`과
  `agent_runtime/wiki_tools.py:54`가 둘 다 `wiki_mcp/tools/__init__.py`의 같은
  `register(mcp, get_scope_key, fs_factory)`를 호출한다. 차이는 전송(stdio MCP vs
  in-process `call_tool`)과 `fs_factory`가 주는 것(`LocalVaultFS` vs 세션이 연
  `FederatedVaultFS`)뿐 — `wiki_tools.py` 헤더 주석의 주장을 실제로 두 파일을 읽고
  검증함.
- **색인 스키마도 같다.** `FederatedVaultFS.open()`(federated.py:126)이 내부적으로
  `LocalVaultFS.open()`을 그대로 호출해 같은 `.llmwiki/index.db`(`schema.sql`)를
  쓴다. 차이는 카탈로그/본문을 파일에서 읽느냐 Spring API로 당기느냐뿐.
- **guide.py**는 시스템 프롬프트 주입이 아니라 MCP 도구로 등록돼(`guide` 도구 호출)
  전달된다 — 하네스에서도 동일하게 재현됨.
- **lint 호출 시점**: `changes.py`에는 lint 호출이 없다. lint는 에이전트가 자발적으로
  부르는 도구일 뿐 세션 종료 시 자동 실행되지 않는다 — job 21에서 본 "반복 호출
  습성"과 일치.
- capability 토큰·HTTP 하이드레이션은 프로덕션 **자동 실행** 전용 개념이다
  (`X-Wiki-Capability`가 프로세스 경계를 못 넘어 in-process로 간 이유, `wiki_tools.py`
  헤더). "사람이 MCP로 쓰는 경로"엔 이 개념이 없다.

### 정직한 경계 (2026-08-03 divergence 감사로 정정)

**초판이 "남는 유일한 차이는 capability·하이드레이션뿐, 메꿀 갭 아님"이라고 쓴 것은
과장이었다.** `LocalVaultFS`(하네스)와 프로덕션 `FederatedVaultFS`/`SpringVaultFS`를
`VaultFS` 인터페이스 전 메서드에 걸쳐 대조하니, **검색·참조 타이밍 층에 실재하는
divergence가 있다.** 하네스가 믿을 수 있는 것과 아닌 것을 분리한다.

**믿을 수 있다 (LocalVaultFS ≡ 프로덕션, 같은 코드):** lint 규칙 자체(중복각주·
dangling-link·인용문/위치 대조·각주 파싱), 도구 동작(create/edit/append/merge/delete·
각주 재번호·edit 오류), 인용 파싱. — 지금까지 하네스가 잡은 실제 버그가 전부 이 층이다.

**믿을 수 없다 (divergence):**
- **검색 (#1, 최악).** 프로덕션 `FederatedVaultFS.search_chunks`는 **work 층=로컬 SQLite,
  live 층=Spring 조회 API(→MySQL FULLTEXT ngram)**로 나눠 조회하고 `origin`(초안/기존)을
  붙이며 층별 몫으로 자른다(`_allocate`, S15P11B106-154). 하네스 `LocalVaultFS`는 **전부
  로컬 SQLite 한 번에** 검색하고 `origin`도 층 할당량도 없으며 live 페이지까지 색인한다
  (154에서 고친 결함 재현). 토크나이저(2글자 분해)를 ngram과 맞춰 recall은 근사하지만,
  live 검색의 랭킹은 엔진(MySQL vs SQLite)이 달라 **동일 보장 불가 — 못 메우는 구조적 갭.**
  origin·층 할당량 같은 *모양*은 코드로 메울 수 있다.
- **참조 타이밍 (#2).** 프로덕션엔 페이지 존재 후 참조를 다시 푸는 장치가 셋 있다
  (`_sync_page_references`·`_ensure_body`의 read-time sync·`_hydrate_catalog`). 하네스엔
  없다(`rebuild_index`의 1회 시드뿐). **늦게 온 소스·기존 위키 편집·다중 문서 재조정**을
  하네스는 구조적으로 재현 못 한다 — 그 경우 정상 인용이 uncited-source/orphan-page로
  잘못 뜬다. `stage_source` 자체가 Spring 전용이라 완전 재현 불가.
- **짧은 본문 색인 (#3, 수정 완료).** 프로덕션은 `_chunks_for_index`(청크 0개면 본문
  전체를 한 청크로) 폴백을 `SpringVaultFS`에 두는데 `LocalVaultFS`엔 없어 색인 동작이
  계층 간에 갈렸다. 폴백을 `LocalVaultFS`로 내리고 `bootstrap_scope`·`register_source`도
  쓰게 해 통일했다(그에 따라 중복이 된 `SpringVaultFS.write` override 제거).
  **도달 경로를 실측으로 가렸다** — 처음엔 "짧은 위키 페이지가 검색 안 됨"으로 적었으나
  하네스 도구 경로로 확인하니 `create`가 frontmatter를 붙여 임계값을 넘겨(126자 본문 →
  frontmatter 포함 235자·청크 1개) **페이지 경로로는 거의 재현되지 않는다.** 실제로
  재현된 것은 **시드 경로**다: 하네스에 짧은 원본문서("12월 24일은 휴무다.")를
  `register_source`로 넣으면 청크 0개로 등록돼 `search 휴무`가 **0건**이었고, 프로덕션
  하이드레이션(`_insert_live`)은 top-up해서 검색됐다. 회귀 테스트
  `test_a_short_source_stays_searchable`이 이 실측 경로를 고정한다.
- **낮음**: `list_documents` 본문(프로덕션 지연 로딩 vs 하네스 즉시), `get`의 부작용
  (프로덕션 read가 그래프 변경 vs 하네스 순수 읽기). 지연 로딩 구조라 로컬로 메우기 어려움.
- **양쪽 다 고장**: `get_source_pages`는 `document_pages`에 INSERT하는 코드가 없어
  하네스·프로덕션 모두 빈 결과(갈라지진 않음).

**따라서 하네스는 "도구·lint 로직 단위 테스트대"로는 유효하고, "검색 품질·에이전트
실동작 검증대"로는 아니다.** 후자는 `ai/CLAUDE.md` 수칙대로 Spring+MySQL+AI 실기동이
맡는다 — 하네스가 그걸 대체하려는 게 아니다.

## 설계

```
ai/tools/harness/
  vault/                 # 독립 로컬 vault, git-ignore (실서빙 스냅샷 아님)
  seed.py                # experiments/corpus/*.md 를 원본문서로 등록 + 색인 재생성
  reset.sh               # vault/ 삭제 후 seed.py 재실행 (반복 실험용 초기화)

ai/.mcp.json             # 프로젝트 고정 등록
  → uv run python -m wiki_mcp.local_server --root ./tools/harness/vault --scope ALL --job-id harness
```

- **시드 데이터**: `experiments/corpus/*.md` 재사용 — 실패 이력이 있는 문서
  (`08-compensation.md`)로 바로 회귀 테스트 가능. Spring이 안 떠 있어도 됨.
- **job-id 고정(`harness`)**: 항상 쓰기 모드로 열어 `write` 도구가
  `vault/work/harness/output/`에만 쓰고 라이브 `pages/`는 안 건드림 — 프로덕션과
  같은 쓰기 모델(work layer → lint 통과 후에만 승격, 이 하네스에서는 승격 자체를
  안 함).
- **`reset.sh`**: guide.py/lint.py 문구를 바꿔가며 같은 문서로 반복 실험할 때
  깨끗한 상태로 되돌리는 용도.
- **재사용 코드**: `wiki_mcp/vaultfs/local.py`의 `bootstrap_scope`·`register_source`,
  `wiki_mcp/vaultfs/rebuild.py`의 `rebuild_index` — `experiments/backend_sim.py`가
  쓰던 것과 같은 헬퍼. `seed.py`는 이 헬퍼들을 그대로 부를 뿐 새 로직을 만들지 않는다.

## 사용 흐름

1. `./tools/harness/reset.sh 08-compensation.md`로 vault 초기화
2. Claude Code 재시작(MCP 서버 재연결) 후 `guide` 도구부터 호출해 지침 확인
3. `search`/`read`로 원본문서 확인 → `write`로 위키 페이지 작성 → `lint`로 검증
4. `lint.py`가 실제로 통과시키는지, `guide.py` 지침(반복 warn 무시 등)이 실제로
   지켜지는지 직접 확인·기록

## 하네스로 답 못 하는 것 (알고 진행)

- deepagents가 실제로 쓰는 모델·프롬프트·`MAX_TURNS`/타임아웃 압박 조합에서의
  루프 습성(예: job 21의 "error 0인데도 계속 고치려 드는" 행동)은 이 하네스로
  검증 불가 — 다른 모델(제 자신)이 같은 도구를 써서 안 헤맨다고 해서 deepagents가
  안 헤맨다는 근거가 안 됨. 이건 실제 파이프라인 1회 실행으로만 확인 가능
  ([[confirm-before-rerunning-expensive-live-tests]] — 실행 전 항상 먼저 확인).
- capability/하이드레이션 계층(Spring Wiki 조회 API 자체의 버그·지연)은 검증 대상 아님.

### 모델을 바꿔 돌리면 다른 결함이 나온다 (2026-08-03 실측)

위 첫 항목의 한계를 **부분적으로** 우회하는 방법이 있다: 하네스를 **다른 모델**로 돌린다.
Opus 로 8회 가까이 검증하며 못 본 결함 4건이, 프로덕션 모델(`claude-sonnet-4-6`)에 가까운
Sonnet 서브에이전트로 같은 작업을 시키자 3라운드 만에 드러났다.

| 라운드 | 조건 | 드러난 결함 |
| --- | --- | --- |
| 1 | 기존 위키에 `08-compensation.md`(17k) 보강 | 인용문 링크 지침이 `dangling-link`(error)를 유발하는 모순 |
| 2 | **빈 위키**에 `12-communication.md`(31k, 최대) 첫 반영 | 백틱(인라인 코드) 정규화 누락 / 「페이지 2~5건」 지침이 첫 반영과 어긋남 |
| 3 | 기존 7페이지 위키에 `11-offsites.md`(26k) 반영 — 겹침 판단 강제 | `create` 의 `category`·`tags` 가 조건부 필수인데 스키마엔 생략 가능처럼 보임 |

**한계**: 서브에이전트 `model: sonnet` 은 **별칭이라 4-6 으로 고정되지 않는다** — `ai/CLAUDE.md`
가 경고하는 "별칭은 시점에 따라 다른 모델로 해석돼 측정 비교를 조용히 깨뜨린다"의 대상이다.
정확한 4-6 검증은 여전히 deepagents 실기동이 필요하다. 그래도 **"내가 안 헤맸다"를 근거로
삼지 않게 해 준다**는 점에서 값이 있다.

**Sonnet 안정성 관측**: 3라운드 모두 `lint` 재시도 0~1회로 error 0 에서 깔끔히 종료했다 —
job 21 의 "error 0 인데도 warn 을 계속 고치려 드는" 루프는 재현되지 않았다. 다만 이것도
위 별칭 한계와 실기동 압박(턴 상한·타임아웃) 부재 때문에 "deepagents 도 안 헤맨다"의
근거는 못 된다.

### 하네스로는 만들 수 없는 개선 (기록만)

라운드 3 에서 Sonnet 이 제안했다: 위키 페이지가 많아지면 `search` 키워드 검색만으로 겹침
판단이 어렵다(청크 단위라 무관한 문맥에서도 걸린다). **"이 원본문서 전체와 관련된 기존 페이지
후보를 랭킹으로"** 뽑아주는 기능이 있으면 반복 검색 없이 판단할 수 있다.

이것은 `wiki_mcp` 안에서 기존 `search_chunks` 를 여러 번 불러 합치는 조합이라 **계약 변경 없이
구현 자체는 가능하다.** 그런데 **하네스로는 검증할 수 없다** — 이 기능의 본질은 랭킹 품질이고,
live 검색 랭킹은 위 「정직한 경계」 #1 에 적은 못 메우는 divergence(MySQL FULLTEXT vs SQLite
FTS)다. 제대로 하려면 의미 검색이 필요한데 그것은 S15P11B106-143 에서 명시적으로 범위 밖이다
(「챗봇 의미 검색 — 배포 GPU 유무가 전제」). **검증 수단이 생긴 뒤에 한다.** 지금은 페이지가
적어 `read(path="pages/*")` 전체 읽기로 대체된다(라운드 3 에서 실제로 그렇게 판단했다).

## 완료 조건

- `ai/tools/harness/{seed.py,reset.sh}` 동작, `ai/.mcp.json` 등록
- `08-compensation.md`로 시드 → 이 세션에서 MCP 도구로 write+lint 실행 →
  기존에 실패했던 5개 인용문이 이번엔 `lint`를 통과하는지 확인
