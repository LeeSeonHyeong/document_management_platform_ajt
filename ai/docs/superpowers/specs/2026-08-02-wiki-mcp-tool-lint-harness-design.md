# wiki_mcp 도구·lint 반복 검증 하네스

- 상태: 구현 완료, 사용 중 (3개 corpus 문서로 검증, 버그 4건 발견·수정)
- 날짜: 2026-08-02 (갱신: 2026-08-03)
- 범위: `ai/` (`wiki_mcp`, `agent_runtime`), backend·MySQL 관여 없음

## 결과 (2026-08-03)

`08-compensation.md`·`07-time-off.md`·`04-benefits.md` 3개 문서를 이 하네스로 실제
위키화(10페이지)하며 실제 도구 버그 4건을 발견·수정·회귀테스트 추가함(유닛테스트 794건
통과, MCP 경로로도 재확인 완료). 상세는 [[wiki-mcp-tool-lint-harness]] 메모리와 커밋 로그.
가장 심각했던 것은 `rebuild_index()`가 work 레이어 documents row를 레이어 구분 없이
지워버려 이미 만든 위키 페이지가 파일은 남은 채 색인에서 증발하던 버그. 네 번째는
인용문 안 마크다운 링크 문법 — 처음엔 guide.py 지침만 추가했으나 반복 관찰되어
따옴표·강조와 같은 코드 레벨 정규화로 최종 해소.

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

## 구조적 동일성 확인 (근거)

이 하네스가 프로덕션과 "얼마나 같은 구조인지"를 코드로 직접 대조했다.

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
- **남는 유일한 차이**(capability 토큰·HTTP 하이드레이션)는 프로덕션 **자동 실행**
  전용 개념이다(`X-Wiki-Capability`가 프로세스 경계를 못 넘어 in-process로 간 이유,
  `wiki_tools.py` 헤더). "사람이 MCP로 쓰는 경로"엔 애초에 이 개념이 없으므로
  메꿔야 할 갭이 아니다.

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

## 완료 조건

- `ai/tools/harness/{seed.py,reset.sh}` 동작, `ai/.mcp.json` 등록
- `08-compensation.md`로 시드 → 이 세션에서 MCP 도구로 write+lint 실행 →
  기존에 실패했던 5개 인용문이 이번엔 `lint`를 통과하는지 확인
