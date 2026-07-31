# AI 작업 인수인계

> 다음 작업자가 정본 문서(`docs/requirements/`·`docs/api/`·`docs/db/`)를 읽기 전에 **지금 무엇을 하는 중인지** 빠르게 확인하는 문서다. 정본은 아니다 — 여기 적힌 것과 정본이 어긋나면 정본이 맞다.
>
> 마지막 갱신 2026-07-31.

## 지금 하는 일 — 챗봇을 에이전트 하나로 합친다

챗봇이 AI 를 두 번 부르던 것을 **한 번으로 합치고, 무엇을 볼지는 에이전트가 도구로 직접
정하게** 한다. 백엔드 제의로 시작했다.

| 티켓 | 무엇 | 담당 | 상태 |
| --- | --- | --- | --- |
| **S15P11B106-169** | 백엔드 자바 구현 | **백엔드 (최민서)** | 계약·문서 완료, 자바 구현 남음 |
| (미생성) | AI 서버 구현 | AI | 착수 가능 |

**두 쪽이 동시에 진행한다.** 계약(**v1.8.0**)이 이미 서 있으므로 서로 기다리지 않는다. 실연동
확인만 양쪽이 끝난 뒤에 한다.

브랜치: `feature/S15P11B106-169-chat-agent-backend` (develop 기준) — 계약·문서가 여기 있다.

**읽을 문서 세 개.**

- 설계 (정본): `ai/docs/superpowers/specs/2026-07-31-agent-endpoint-merge-design.md`
- 백엔드 계획: `docs/backend-chat-single-call-plan.md` — **백엔드 담당자에게 넘기는 문서다.**
  그래서 `ai/` 밖에 둔다
- AI 계획: `ai/docs/superpowers/plans/2026-07-31-chat-single-agent-endpoint.md`

## 지금까지 한 것

| 무엇 | 상태 |
| --- | --- |
| 설계 문서 | 완료 |
| 백엔드 구현 계획 (7 태스크) | 완료 |
| AI 구현 계획 (10 태스크) | 완료 |
| 계약 생성기·예시 수정 + **재생성·검증** | 완료. 세 명령 다 통과 |
| `docs/api/README.md` 버전·개수 | 완료 |
| `docs/requirements/요구사항정의서.md` FR-QNA-012 | 완료 |
| `docs/conventions/rest-api-convention.md` | 완료 |
| `scripts/validate-artifact-consistency.mjs` | 완료 |
| 백엔드 자바 구현 | **아직 시작 안 함** |
| AI 서버 구현 | 아직 시작 안 함 |

## 다음에 할 것

**계약은 서 있다.** 1.7.0 으로 재생성했고 세 검증이 다 통과한다.

- **AI 담당**: `ai/docs/superpowers/plans/2026-07-31-chat-single-agent-endpoint.md` Task 1 부터
- **백엔드 담당**: `docs/backend-chat-single-call-plan.md` Task 1 부터 (계약·문서 태스크인 5·6 은 완료)

계약을 다시 만질 일이 생기면 이 세 명령을 다 통과시켜야 한다. **JSON 을 손으로 고치지
않는다** — 생성기를 고쳐 재생성한다.

```bash
node docs/api/generate-postman-collections.mjs
node docs/api/validate-postman-collections.mjs
node scripts/validate-artifact-consistency.mjs
```

### 정합성 검사가 낡아 있던 것을 두 줄 고쳤다

이 브랜치와 무관하게 원래부터 실패하는 상태였다. **계약을 올릴 때 이 파일도 같이 올려야
한다.**

| 기대값 | 낡은 값 | 고친 값 |
| --- | --- | --- |
| 계약 버전 | `1.6.2` (계약은 1.6.9 까지 가 있었다) | `1.8.0` |
| 공개 API 수 | `56` (README 는 58 로 맞았다) | `58` |
| 내부 API 수 | `15` | `16` (1단계 삭제, 일정 조회 2개 추가) |

## 계약이 어떻게 바뀌었나

| 대상 | 변경 |
| --- | --- |
| `POST /internal/v1/answer-context-selections` | **삭제** (남겨두지 않는다) |
| `POST /internal/v1/answers` 요청 | `questionType`·`selectedWikis`·`selectedSchedules`·`scheduleSummaries` 제거. `wikiIndexes[].wikiCapability` 추가 |
| `POST /internal/v1/answers` 응답 | `questionType` 추가. `sources[].title` 필수 |
| `GET /internal/v1/schedules` | **신설** — 기간·키워드로 일정 목록 |
| `GET /internal/v1/schedules/{scheduleId}` | **신설** — 일정 상세 (본문 포함) |
| 오류 코드 | 8개로 나뉜다 (아래) |
| `contractVersion` | `1.6.9` → **`1.8.0`** |

> ⚠️ **1.7.0 이 아니라 1.8.0 이다.** 처음 1.7.0 으로 올렸는데, 169 가 develop 에서 갈라진 뒤
> S15P11B106-101(AI 작업 수동 시작 API, 커밋 `5e2ad9a`)이 먼저 머지되면서 그 번호를 가져갔다.
> 같은 번호가 두 뜻을 가지면 「1.7.0 기준으로 구현했다」가 어느 쪽인지 알 수 없어져 챗봇이
> 1.8.0 으로 물러섰다. 요구사항정의서도 2.18 로 올렸다 (169 가 FR-QNA-012 를 고치면서 변경
> 요약·문서 버전을 안 올린 것을 함께 정정했다).

**오류 이름 8개.** 이름만 읽고 무슨 일인지 알 수 있게 지었다. 계약에 없던 이름이므로
**MR 본문에 협의 항목으로 적어야 한다.**

`INVALID_ANSWER_REQUEST`(400) · `NO_WIKI_OR_SCHEDULE_WAS_READ` ·
`WIKI_QUERY_FAILED` · `SCHEDULE_QUERY_FAILED` · `AGENT_TURN_LIMIT_REACHED` ·
`AGENT_TIMED_OUT` · `MODEL_CALL_FAILED` · `ANSWER_WAS_EMPTY`

사라지는 이름: `ANSWER_CONTEXT_SELECTION_FAILED` · `INVALID_ANSWER_CONTEXT_REQUEST` ·
`ANSWER_GENERATION_FAILED`

## 알아둘 결정

**1. 챗봇은 MCP 를 쓰지 않는다.** 위키 에이전트가 MCP 서버를 별도 프로세스로 띄우는 이유는
쓰기(작업 공간·참조 그래프·색인·범위 격리) 때문이고 챗봇은 그중 아무것도 필요 없다. 파이썬
함수 다섯 개를 런타임에 직접 붙인다. **그래서 S15P11B106-151·152 가 선행에서 빠진다.**

**2. 권한 판정 방식이 둘로 갈린다.** Wiki 는 이미 돌아가는 허가값(`wikiCapability`)을 그대로
쓰고, 일정은 새로 만드는 것이라 질문 번호(`questionId`)로 판정한다. 나중에 Wiki 도 번호
기준으로 옮기면 하나가 된다 — 지금 갈아치우면 백엔드 작업이 커진다.

### 2026-07-31 저녁에 여섯 개를 다시 정했다

첫 설계가 **확정 요구사항 두 개와 어긋나 있었다.** 그것을 되돌리는 과정에서 함께 정한 것이다.

| 무엇 | 정한 것 | 왜 |
| --- | --- | --- |
| 근거를 못 찾았을 때 | 찾아봤으면 빈 출처로 정상 답변, 조회를 아예 안 했으면 실패 | 첫 설계는 무조건 500 이었다. **FR-QNA-007**(근거를 못 찾으면 정보 부족을 안내한다)을 어긴다. 사용자에게 「처리 중 문제가 생겼습니다」가 나갔다 |
| `questionType` | 에이전트가 질문 맥락을 보고 판단한다 | 첫 설계는 「읽은 자료의 종류」였다. **FR-QNA-002**(질문 맥락을 보고 판단)를 어기고, 일정 질문을 Wiki 로 답하면 유형이 뒤집힌다. 지난 측정(2026-07-29 분류 정확도 8/8)과도 비교가 끊긴다 |
| 출처 | 에이전트가 신고하고, 읽은 기록과 대조해 걸러낸다 | 「읽은 것 전부」로 하면 읽어보고 안 쓴 페이지가 섞인다. 그 대책이 지침 한 줄뿐이었다. 교집합을 취하면 지어내기는 그대로 막고 과다 포함만 사라진다 |
| 대기 시간 | 20~25초, 턴 4~5 | 첫 설계는 8턴·60초인데 구현대로면 65초에서 잘린다. 채팅에서 65초 침묵은 사용자가 창을 닫는다 |
| 재시도 | 고칠 수 있는 실패만 (시간 초과·모델 실패·조회 실패) | 요청 오류나 「근거 없음」은 다시 불러도 같다. 대기만 두 배가 된다 |
| 동작 확인 | 가짜 모델로 루프를 고정하고, 진짜 모델은 한 번만 | 예산 3달러에 확인 항목이 여섯이다. `GenericFakeChatModel`(설치돼 있다)에 대사를 넣으면 진짜 루프·진짜 도구·진짜 HTTP 로 돌면서 0원이다. 진짜 모델은 「지침을 따르나」에만 쓴다 |

### 함께 잡은 버그 넷 (문서·구현 양쪽)

- **지침에 오늘 날짜가 없다.** 「다음 워크샵」을 물으면 모델이 오늘을 몰라 엉뚱한 기간을 조회한다
- **출처에 제목이 빠진다.** `answer_source.source_title` 은 `NOT NULL` 인데 응답 조립이 제목을
  버린다 (장부에 있는데 안 쓴다)
- **지시문이 두 번 실린다.** `system_prompt` 와 첫 user 메시지에 같은 문자열을 넣어 목차가 두 번
  들어간다
- **턴 상한 오류 이름이 절대 안 나온다.** `recursion_limit` 으로 걸면 `GraphRecursionError` 가
  나고 그것이 `MODEL_CALL_FAILED` 로 잡힌다. `ModelCallLimitMiddleware` 를 쓰면 구분된다

## 미결 사항 (2026-07-31 종료 시점)

**AI 티켓이 아직 없다.** Jira 응답이 두 번 시간초과로 실패했다. 백엔드와 동시에 진행하는
것이므로 AI 작업을 시작할 때 만들면 된다. 내용은 계획서
(`ai/docs/superpowers/plans/2026-07-31-chat-single-agent-endpoint.md`)를 그대로 요약하면
된다. 제목 후보: `[AI] 챗봇을 에이전트 하나로 — 도구로 직접 조회하고 읽은 것만 출처로 낸다`

**멀티턴 측정은 보류 상태다.** 브랜치 `feature/S15P11B106-80-chat-multiturn-memory` 에
확인용 스크립트(`ai/experiments/chat_memory_sim.py`)가 있고 푸시돼 있다. 아직 돌리지 않았다.
지금 구조가 이전 대화를 통째로 넘기는데 그것이 대명사를 실제로 푸는지 확인하는 것이다.

**이 브랜치는 MR 을 아직 올리지 않았다.** 백엔드 자바 구현이 같은 브랜치에 들어올 예정이라
그것까지 끝난 뒤 올리는 것이 자연스럽다. 계약만 먼저 머지하고 싶으면 지금 올려도 된다.

## 다른 컴퓨터에서 이어받을 때 준비물

```bash
cd ai && uv sync --extra deepagents
```

**`src/.env` 는 커밋되지 않는다.** 그 컴퓨터에서 다시 채운다.

```
INTERNAL_API_KEY=<아무 값>
OPENAI_API_KEY=<키>
AI_RUNTIME=deepagents
AI_MODEL=openai:gpt-4o-mini
AI_MODEL_FAST=openai:gpt-4o-mini
AI_MODEL_QUALITY=openai:gpt-4o-mini
```

**티어 모델 셋을 다 지정해야 한다.** 하나만 지정하면 단발 호출이 Anthropic 기본값으로 가서
「인증 방법을 못 찾았다」로 죽는다. 2026-07-31 실측으로 확인했고, AI 계획 Task 1 이 그것을
고치는 일이다.

**Node 도 필요하다** (계약 재생성·검증). 이 컴퓨터에는 없어서 직접 받아 깔았다 — 같은 방법을
쓰면 된다 (sudo 없이 된다).

```bash
V=v24.18.1
curl -sSO "https://nodejs.org/dist/$V/node-$V-linux-x64.tar.xz"
mkdir -p ~/.local/opt ~/.local/bin
tar -xJf "node-$V-linux-x64.tar.xz" -C ~/.local/opt
ln -sfn ~/.local/opt/node-$V-linux-x64/bin/node ~/.local/bin/node
ln -sfn ~/.local/opt/node-$V-linux-x64/bin/npm ~/.local/bin/npm
export PATH="$HOME/.local/bin:$PATH"
```

## 예산 제약

**모델 예산이 3달러다.** 동작 확인까지만 한다 — 질문 하나가 끝까지 도는지 보고 멈춘다.
정확도·응답 시간 분포는 예산이 생긴 뒤로 미룬다.

그래서 이 값들은 **추정으로 남아 있다.** 실측하면 조정한다.

- 에이전트 턴 상한 4~5
- 시간 상한 25초
- 챗봇을 동기로 둘지 비동기로 바꿀지 (지금은 동기, 동시 10명 규모 가정)

## 쓸 수 있는 모델

| 제공자 | 상태 |
| --- | --- |
| OpenAI | **쓸 수 있다** |
| GMS 게이트웨이 | 토큰 부족 |
| Anthropic 직결 | 키 없음, 결제 안 됨 |

`claude-code` 명령줄 도구는 **로컬 시험용**이고 배포에 쓰지 않는다. 그쪽 특성(도구 지연
로딩 등)을 설계 근거로 삼지 않는다. 기준 런타임은 `deepagents` 다.
