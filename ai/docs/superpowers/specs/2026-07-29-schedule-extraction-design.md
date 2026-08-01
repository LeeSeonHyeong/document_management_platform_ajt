# 일정 추출 설계 — 모델은 판단하고 코드는 계산한다

- 작성일: 2026-07-29
- 대상: `POST /internal/v1/schedule-extractions` 신설, `src/schedule_extractor/` 신설
- Jira: `S15P11B106-79` `[AI] 일정 추출 에이전트`
- 근거: 요구사항 `FR-SCH-001`·`FR-SCH-002`·`FR-SCH-003`·`FR-AI-001`·`NFR-AI-002`·`DR-022` · `../../../../docs/api/AJT-FastAPI-Internal-API.postman_collection.json` (계약 v1.3.1)
- 선행 없음. `ai/` 안에서 끝난다
- 검토: Codex 1회. 12건을 정정했다 (§8)
- 상태: 설계 확정. 구현 계획 작성 대기

## 1. 배경

**기능 전체가 AI 없이 죽어 있다.**

```
ScheduleSourceService.upload()                      관리자 일정 문서 업로드, 동기
  ├ storeOriginal()                                 원본 저장
  ├ aiClient.parseSource(sourceType=SCHEDULE)       → /source-parses          있다
  ├ aiClient.extractSchedules(...)                  → /schedule-extractions   없다
  ├ 비었으면 no_schedule + 원본·파싱 파일 삭제
  └ scheduleRepository.saveAll(drafts)              DRAFT 로 일괄 저장
```

`RestClientAiClient:74` 가 실제로 그 경로를 부른다. AI 라우터에 없으니 FastAPI 404 → `throwMappedHttpError` → `UNEXPECTED_STATUS`. 백엔드 티켓 `S15P11B106-121` 본문이 "AI 서버: S15P11B106-79" 라고 명시로 이 문서를 가리킨다.

백엔드 쪽 구조는 이미 옳다. 읽기 제한이 위키와 분리돼 있고(`application.yml:37` `schedule-extraction-read-timeout: 180s`, 별 `RestClient` 빈), AI 호출이 트랜잭션 밖이다. 위키 변환의 D3(트랜잭션 경계) 같은 문제가 없다.

### 1.1 위키 변환과 성격이 다르다

| | 위키 변환 | 일정 추출 |
| --- | --- | --- |
| 입력 | 원본문서 + 위키 본문·목차·관계 | `parsedMarkdown` 하나 |
| 상태 | 작업층 · 임시 루트 · SQLite 색인 | 없다 |
| 툴 | MCP 10개 | 없다 |
| 쓰기 | 작업 공간에 여러 번 | 없다 |
| 시간 | 126~894초 | 실측 26초 |
| 반환 | 변경안 JSON | 추출 결과 |

**상태가 없다.** `wiki_mcp` 의 VaultFS·MCP·SQLite·lint 를 하나도 쓰지 않는다. 에이전트 루프도 필요 없다 — 읽을 것이 요청 본문 하나고 쓸 곳이 없다.

## 2. 측정

`ollama` 로 로컬 모델을 재봤다. 코퍼스는 한국어 일정 문서 1건(일정 3건 · 일정 아닌 항목 1건 · 종일 일정 1건 · 종료 시각 누락 1건).

### 2.1 모델에게 시간 산술을 시키면 틀린다

| 모델 | 찾은 일정 | UTC 변환 | `status` | 시간 |
| --- | --- | --- | --- | --- |
| `gemma4:e2b` | **0/3** | — | — | 25.5s |
| `qwen2.5:7b-instruct` (모델이 변환) | 2/3 | **틀림** — `09:00 KST` 를 `09:00Z` 로 냈다 | **자기모순** — 일정 2건에 `no_schedule` | 41.6s |
| `qwen2.5:7b-instruct` (코드가 변환) | **3/3** | **맞음** | 코드가 정한다 | 25.9s |

세 번째 줄이 이 설계다. 모델은 문서에 적힌 지역 시각을 `YYYY-MM-DDTHH:MM` 으로 그대로 옮기고, `Asia/Seoul` → UTC 변환·`order` 부여·`status` 판정을 코드가 한다.

```
09:00 KST → 2026-08-12T00:00:00Z
14:00 KST → 2026-08-20T05:00:00Z
8월 25일 종일 → 2026-08-24T15:00:00Z ~ 2026-08-25T14:59:00Z
```

`gemma4:e2b` 는 유효 2B 급이라 출력 67토큰으로 시도조차 안 했다. **7B 급이 하한이다.**

### 2.2 시간은 변수가 아니다

26초다. 계약 상한 180초의 14%. `NFR-PERF-002` 와 무관한 경로이며, Spring 읽기 제한도 이 경로만 180초로 분리돼 있다.

### 2.3 이 측정의 한계

문서 1건이다. 재현율·정밀도라 부를 수 없다. 일정 20건짜리 큰 문서에서 뒤쪽을 흘리는지, `location` 누락이 재현되는지 모른다. §7 에 남긴다.

## 3. 설계

### 3.1 원칙

**모델은 판단하고 코드는 계산한다.**

모델에게 맡기는 것은 "이것이 일정인가", "제목이 무엇인가", "문서가 적은 시각이 무엇인가" 다.
코드가 하는 것은 시간대 변환·연도 추론·순서 부여·상태 판정·탈락 판정이다.

계산을 모델에 맡기면 틀리고(§2.1), 틀렸는지 검증할 방법이 없다. 코드가 하면 단위 테스트가 전부 잡는다.

### 3.2 구조

```
src/schedule_extractor/            새 패키지. wiki_mcp·agent_runtime 을 임포트하지 않는다
  __init__.py       공개 함수 하나 — extract_schedules(markdown, *, now, provider) -> Extraction
  prompt.py         프롬프트와 JSON Schema. 시각 규칙·종일 규칙·대상 규칙
  normalize.py      순수 함수. KST→UTC · order · 빈값 · 탈락 판정. LLM 안 부른다
  provider.py       LLM 경계 포트 — complete_json(prompt, schema) -> dict
  providers/
    ollama.py       로컬. base_url·model 설정
    anthropic.py    배포. 구조화 출력 강제

src/wiki_api/routers/schedule.py   POST /internal/v1/schedule-extractions. 경계만
src/wiki_api/schemas.py            요청·응답 스키마 (파일 끝에 추가)
src/wiki_api/errors.py             경로별 코드 2줄 추가
src/wiki_api/app.py                include_router 1줄
```

의존 방향은 기존 규칙을 잇는다: `wiki_api → schedule_extractor`. 역방향 없다.
`schedule_extractor` 는 FastAPI 를 모르고 계약 스키마도 모른다 — 자기 자료형만 낸다.
계약 모양을 아는 곳은 `wiki_api` 뿐이라는 기존 경계를 깨지 않는다.

`document_parser` 와 같은 층이다. 둘 다 상태 없는 변환기다.

**`wiki_api` 안에 두지 않는 이유** — `wiki_api` 는 HTTP 경계다. 프롬프트·정규화·LLM 어댑터가 거기 들어가면 계약 지식과 추출 로직이 섞인다. 별 패키지면 `pytest tests/schedule/` 이 FastAPI 없이 돈다.

### 3.3 자료형

추출기는 공개 범위를 모른다.

```python
@dataclass(frozen=True)
class ExtractedSchedule:
    order: int
    title: str
    content: str | None     # 없으면 warning. FR-SCH-001 이 추출을 요구한다
    target_text: str | None # 같음
    location: str | None    # 요구 대상 아니다. 없어도 조용히 넘어간다
    start_at: datetime      # tz-aware UTC
    end_at: datetime

@dataclass(frozen=True)
class Extraction:
    schedules: tuple[ExtractedSchedule, ...]
    warnings: tuple[str, ...]
```

`content`·`target_text` 는 `FR-SCH-001` 이 "각 일정마다 일정명, 내용, 시작·종료 일시와 대상을 추출" 하라고 요구한다. 그런데 계약 응답과 백엔드 record 는 `null` 을 허용한다. **누락을 탈락 사유로 삼지 않고 `warnings` 에 남긴다** — 문서에 대상이 안 적힌 일정을 통째로 버리는 것이 요구사항에 더 어긋나고, 관리자가 초안에서 채울 수 있다(`FR-SCH-002`).

`visibilityType`·`departmentIds` 는 라우터가 요청값으로 채운다. 백엔드가 응답의 그 값을 안 믿고 요청값을 쓰지만(`ScheduleSourceService.toDrafts` 주석) 검증은 비어 있으면 거부하므로(`RestClientAiClient:196`) 반드시 채운다. 추출기가 공개 범위를 아예 모르게 두면 모델이 그것을 바꿀 여지가 없다.

**응답을 내기 전에 교차 검증한다.** 백엔드 검증(`RestClientAiClient:173`·`:192`)은 필드 형식만 본다 — `status` 허용값, `status` 와 목록의 일관성, `order` 중복·양수를 확인하지 않는다. AI 가 스스로 본다: `order` 는 1..N 연속, `status` 는 `schedules` 길이와 일치, `departmentIds` 는 요청과 동일. 어긋나면 조립 버그이므로 500 이다.

`status` 도 라우터가 정한다. **모델 출력 스키마에 `status` 필드를 두지 않는다** — §2.1 의 자기모순을 구조적으로 없앤다.

판정은 모델이 낸 건수와 정규화가 살린 건수를 함께 본다.

| 모델 출력 | 문서에 날짜 패턴 | 정규화 후 | 응답 |
| --- | --- | --- | --- |
| 0건 | 없다 | 0건 | 200 `no_schedule` — 일정이 없는 문서다 |
| 0건 | **있다** | 0건 | **500** — 모델이 흘렸을 가능성이 있다 |
| N건 | — | 1건 이상 | 200 `extracted` — 탈락분은 `warnings` 로 |
| N건 (N ≥ 1) | — | **0건** | **500** — 추출이 다 깨졌다 |

**두 번째 줄이 이 표의 핵심이다.** `gemma4:e2b` 가 일정 3건짜리 문서에 0건을 냈다(§2.1). 모델 출력만 믿으면 그것이 `no_schedule` 로 나가고, 관리자는 "이 문서에 일정이 없다" 는 답을 받아 **재시도할 이유를 못 얻는다.** 그래서 코드가 문서에서 날짜 패턴(`N월 N일`·`YYYY-MM-DD`·`N/N` 등)을 독립적으로 찾고, 날짜가 있는데 모델이 0건이면 추출 실패로 본다.

이 검사는 **거짓 500 을 만들 수 있다** — 날짜가 있지만 일정이 아닌 문서(회의록의 작성일, 규정의 시행일)가 그렇다. 감수한다. 일정이 든 문서를 "없다" 고 답하는 쪽이 더 나쁘다. 관리자가 500 을 받으면 다시 올리거나 문서를 손보고, `no_schedule` 을 받으면 아무것도 안 한다.

**파일 삭제는 이 판정과 무관하다.** 백엔드는 `no_schedule` 이든 예외든 원본·파싱 파일을 지운다(`ScheduleSourceService.java:93`). 일정 경로에는 재처리 구조가 없어서 그것이 옳다 — 일정 원본문서는 `document` 테이블에 저장하지 않고(`FR-DOC-003`), `schedule.status` 는 `DRAFT`·`APPROVED` 뿐이고, `FR-DOC-012` 의 `FAILED` 재처리는 위키 문서 전용이다. 동기 요청이라 재시도는 관리자가 화면에서 다시 올리는 것이고 원본은 관리자 로컬에 있다. 서버에 남긴 파일은 아무도 참조하지 않는 고아다.

즉 `no_schedule` 과 500 을 가르는 값은 파일 보존이 아니라 **관리자에게 무엇을 말하나** 다.

### 3.4 흐름

```
POST /internal/v1/schedule-extractions
  sourceGroupKey · parsedMarkdown · visibilityType · departmentIds[]
    │
    ├ 요청 검증        parsedMarkdown 바이트 상한 · visibilityType 은 all · department 만
    │
    ├ extract_schedules(markdown, now=…, provider=app.state.schedule_provider)
    │    prompt.py    기준 연도·시간대를 프롬프트에 박는다. now 를 주입받는다
    │    provider     JSON Schema 강제 호출 1회. 자체 타임아웃
    │    normalize.py RawItem[] → ExtractedSchedule[]
    │
    ├ 날짜 패턴 검사    모델 0건일 때만 돈다 (§3.3)
    │
    └ 계약 응답 조립   status · schedules[] · warnings[] + 교차 검증
                     visibilityType·departmentIds 는 요청값을 그대로 되돌린다
```

**provider 배선.** `create_app()` 이 설정을 읽어 어댑터를 만들고 `app.state.schedule_provider` 에 둔다. 라우터는 `app.state` 에서 꺼내 `extract_schedules` 에 넘긴다. 테스트는 `create_app()` 뒤에 그 속성을 가짜로 바꾼다 — `app.state.api_key` 가 이미 쓰는 방식(`app.py:20`)과 같다. `serve.py` 는 설정 이름만 더한다.

`complete_json` 은 `async` 다. 라우터가 `async def` 이므로 그대로 `await` 한다. `ollama`·`anthropic` 둘 다 HTTP 이고 `httpx` 가 이미 의존성에 있다.

### 3.5 시각 규칙

문서는 KST 로 쓰이고 DB·API 는 UTC 다 (`DR-022` · `rest-api-convention.md:487`).
**계약 요청에 기준 시각도 시간대도 없다.**

```
시간대     Asia/Seoul 가정
기준 연도   now 를 KST 로 본 연도. now 는 tz-aware UTC 로 주입받는다 (테스트가 고정한다)
```

**이 가정에 계약 근거가 없다.** 계약 예시의 `parsedMarkdown` 은 `"# 8월 일정\n..."` 이라 본문이 없고, 응답의 `01:00:00Z` 가 KST 10시라는 것은 역산한 추측이다. 사내 시스템이고 Jira 계정이 전부 `Asia/Seoul` 이라 가정 자체는 안전하지만, **근거는 관행이지 계약이 아니다.**

그리고 기준 시각을 서버 시각으로 잡으면 **같은 문서를 다시 올린 결과가 시점에 따라 달라진다.** 12월에 올린 문서와 1월에 올린 같은 문서가 다른 연도를 낸다. 결정적이지 않다.

따라서 요청에 `timezone`·`referenceDate` 추가를 계약 변경으로 제기한다 (§7.1). 그것이 들어오면 이 절의 가정 전체가 없어지고 규칙이 결정적이 된다. **그때까지는 가정으로 진행하고 코드 주석·MR 본문에 명시한다.**

기준 연도를 **KST 로 본다.** UTC 자정 전후 9시간은 두 시간대의 날짜가 다르고, 12월 31일 밤에는 연도까지 다르다. 문서를 쓴 사람의 연도가 기준이다.

`now` 는 **연도만** 채운다. 날짜를 만들어내지 않는다.

| 문서 | 결과 |
| --- | --- |
| `8월 12일 09:00` | 연도 없음 → `now` 의 연도 + 문서의 8월 12일 09:00 |
| `2025년 8월 12일` | 연도 있음 → `now` 안 쓴다 |
| `8월 25일 급여 지급` | 날짜 있고 시각 없음 → 그날 종일 (`00:00:00`~`23:59:59.999999` KST) |
| `8월 5일까지 제출` | 마감일도 날짜다 → 8월 5일 종일 |
| `문의: 인사팀 내선 1234` | 날짜 없음 → **일정으로 만들지 않는다** |

날짜가 없는 항목에 오늘을 끼워 넣지 않는다. 근거 없는 생성이고 `NFR-AI-002` 위반이다.

**연말·연초 경계.** 12월 문서의 `1월 5일` 에 현재 연도를 넣으면 11개월 과거가 된다.

```
규칙      추론한 연도로 계산한 날짜가 now 보다 3개월 이상 과거면 다음 해로 본다
경고      연도를 추론한 항목은 전부 warnings 에 남긴다
```

**종일 일정의 종료 시각.** `23:59:59.999999` KST 로 둔다. `23:59:00` 은 하루의 마지막 60초를 잘라내고, DB 가 `DATETIME(6)` 이므로(`DR-022`) 마이크로초를 담을 수 있다. 다음 날 `00:00` 배타적 종료를 쓰지 않는 이유는 달력이 이틀에 걸친 일정으로 그리기 때문이다(`FR-SCH-008`).

**종료 시각만 없는 경우** — 시작 시각은 있고 종료가 없다. 종일로 만들지 않는다. 시작 시각 + 1시간으로 두고 `warnings` 에 추정을 남긴다. 측정에서 모델이 스스로 `14:00~16:00` 을 추정하고 경고를 남기기도 했다(§2.1) — 모델이 문서 문맥으로 더 잘 추정하면 그 값을 쓰고, 안 내면 코드가 1시간을 채운다.

**`warnings` 가 지금 아무에게도 도달하지 않는다.** 백엔드가 `response.schedules()` 만 꺼내고(`ScheduleSourceService.java:118`) 공개 API 응답에도 `warnings` 필드가 없다. 연도 추론·부분 탈락·종료 추정 경고가 관리자에게 갈 경로가 계약 차원에서 없다. 그래도 `warnings` 를 채운다 — 내부 계약이 요구하고(`RestClientAiClient` 가 `null` 을 거부한다), AI 로그와 측정에 근거로 남는다. 관리자 노출은 §7.1 협의 항목이다.

### 3.6 오류 처리

계약이 이 경로에 허용한 상태는 400·401·500 셋뿐이다. 그 밖을 내면 Spring 이 `UNEXPECTED_STATUS` 로 뭉갠다.

| 상황 | 응답 |
| --- | --- |
| `parsedMarkdown` 빈 값·상한 초과, `visibilityType` 이 계약 값 아님 | 400 `INVALID_SCHEDULE_EXTRACTION_REQUEST` |
| 내부 API 키 없음·불일치 | 401 (기존 `deps.py` 그대로) |
| LLM 호출 실패·타임아웃, JSON 스키마 위반, 전부 탈락 | 500 `SCHEDULE_EXTRACTION_FAILED` |
| **일정이 정말 없는 문서** | **200 `no_schedule`** — 오류 아니다 (`FR-SCH-001`) |

400 코드 이름과 메시지는 **계약이 이미 정했다.** Saved Example 에 `INVALID_SCHEDULE_EXTRACTION_REQUEST` · `"일정 추출 요청 구조가 올바르지 않습니다."` 가 있다. `errors.py` 의 `_VALIDATION_CODES` 와 `_FAILURE_CODES` 에 그 문장 그대로 등록한다. 협의 항목이 아니다.

**`parsedMarkdown` 상한은 계약에 없다.** 임의로 좁히면 계약상 유효한 요청을 400 으로 막는다. 그래서 자원 한계에서 유도한다 — 업로드 파일은 20MB 이하이고(`FR-DOC-003`) 파싱 결과가 원본보다 크는 경우는 드물다. 상한을 그보다 넉넉히 잡고 근거를 주석에 남기고 **MR 본문에 협의 항목으로 올린다.** 위키 쪽 요청 상한(`schemas.py`)과 같은 처지다.

**`failureStage`.** `NFR-AI-003` 첫 문장은 "AI 처리와 문서 파싱 실패 시 실패 단계와 사유를 제공해야 한다" 이므로 일정 추출도 대상이다. 그런데 두 번째 문장이 정한 단계 어휘(`context_load`·`agent_timeout`·`agent_error`·`lint_failed`·`assemble`)는 Wiki 변환·문맥 선택용이고, 계약의 일정 추출 500 응답에 `failureStage` 필드가 없다. **지금은 붙이지 않고 협의 항목으로 올린다** (§7.1) — 일정용 단계 어휘(`extract`·`normalize` 등)와 계약 필드가 함께 정해져야 의미가 있다.

**provider 예외를 그대로 흘리지 않는다.** `errors.py:141` 의 마지막 그물이 `f"요청을 처리하지 못했습니다 — {exc}"` 로 예외 문자열을 응답에 싣는다. LLM 어댑터 예외에는 URL·모델명·응답 본문 조각이 들어갈 수 있다. 라우터가 provider 예외를 전부 잡아 고정 메시지의 `InternalError(SCHEDULE_EXTRACTION_FAILED)` 로 바꾸고 내부 상세는 `logger.exception` 으로만 남긴다.

**부분 실패는 살릴 수 있는 것을 살린다.**

```
모델이 5건 냈고 그중 1건이 endAt < startAt
  → 그 1건만 탈락. warnings 에 이유. 나머지 4건으로 200
  → 전체를 500 으로 만들지 않는다
```

`FR-SCH-002`·`FR-SCH-003` 이 초안을 건별로 검토·수정·승인하게 한다. 4건을 살려 보내면 관리자가 그것으로 일을 진행하고, 500 을 내면 관리자가 얻는 것이 없다.

**단 모두 탈락하면 `no_schedule` 이 아니라 500 이다** (§3.3 표).

**타임아웃 — 180초는 파싱과 추출을 합친 값이다.**

```
요구사항 FR-AI-001   "일정 문서 파싱·추출은 최대 180초의 동기 요청으로 처리한다"
공개 계약 정책        "파싱과 추출을 최대 180초 동안 동기로 처리합니다"
```

그런데 백엔드는 파싱에 공용 `read-timeout: 10m` 를 쓰고(`application.yml:35`) 추출만 `180s` 로 분리했다(`:37`). 파싱 4분 + 추출 3분이면 백엔드는 안 끊고 계약은 깨진다. **`ai/` 밖이라 §7.1 에 전달 항목으로 남긴다.**

AI 쪽은 추출 자체에 180초보다 짧은 자체 상한을 둔다. Spring 이 먼저 끊으면 코드도 없는 전송 오류가 되어 실패 사유가 사라진다. 실측이 26초라 여유가 크다(§2.2).

### 3.7 백엔드 응답 검증에 맞춘다

`RestClientAiClient:173`·`:192` 가 하나만 어긋나도 `INVALID_RESPONSE` 로 던진다.

| 필드 | 요구 |
| --- | --- |
| `status` | 비어 있으면 실패 |
| `schedules` · `warnings` | **`null` 금지.** 빈 배열이어야 한다 |
| `order` | `Integer`, `null` 금지 |
| `title` | 비어 있으면 실패 |
| `visibilityType` · `departmentIds` | 비어 있으면/`null` 이면 실패 |
| `startAt` · `endAt` | `Instant.parse` 가능. **RFC 3339 UTC `Z`** |
| 기간 | `endAt >= startAt` |
| `content` · `targetText` · `location` | `null` 허용 |

정규화가 빈 문자열을 `None` 으로 바꾼다. 모델이 `targetText: ""` 를 낸 것을 측정에서 봤다.

**이 표는 형식만 보장한다.** 백엔드는 `status` 허용값, `status` 와 목록의 일관성, `order` 중복·양수, `departmentIds` 내용을 확인하지 않는다. 그래서 AI 가 자기 응답을 교차 검증한다 (§3.3 마지막). 백엔드 검증을 강화하는 것은 `backend/` 쪽 안건으로 §7.1 에 남긴다.

## 4. LLM 어댑터

포트는 함수 하나다.

```python
class JsonCompletionProvider(Protocol):
    async def complete_json(self, prompt: str, schema: dict) -> dict: ...
```

| 어댑터 | 쓰임 | 과금 |
| --- | --- | --- |
| `ollama` | 개발 확인 · 품질 측정 · `@pytest.mark.llm` | 없다 |
| `anthropic` | 배포 | API 크레딧 |

**설정은 `wiki_mcp/config.py` 에 두지 않는다.** 그 파일은 `WORKSPACE_PATH`·`APP_URL` 만 읽는 위키 저장 계층 설정이고, `schedule_extractor` 는 `wiki_mcp` 를 임포트하지 않는다. 자기 설정을 자기 패키지에서 읽고 `src/.env.example` 에 항목을 더한다 — 어댑터 선택·모델 이름·`base_url`·자체 타임아웃.

**배포 모델은 이 문서에서 정하지 않는다.** GMS 게이트웨이 Anthropic 키가 사실상 사용 불가로 확인됐고(`2026-07-28-ai-server-contract-design.md:547`), 배포 서버 GPU 유무도 미정이다. 어댑터가 교체 가능하므로 로컬 모델 품질 측정이 "로컬 모델로 배포" 의 근거가 될 수 있다. 그 판단은 §7.

`claude-code` CLI 는 후보가 아니다 — FastAPI 안에서 `claude -p` 를 subprocess 로 띄우는 것이 성립하지 않는다 (`ai/README.md`).

## 5. 테스트

```
tests/schedule/test_normalize.py        LLM 없음. 이 파일이 제일 크다
  KST→UTC 경계        자정 앞뒤 · 일자 넘김 · 종일 일정
  종일 종료           23:59:59.999999 KST 로 나가나
  종료만 없음         시작 + 1시간 · warning
  연도 추론           연도 없음 → now 의 연도 / 3개월 초과 과거 → 다음 해 + warning
  기준 연도 시간대     now 가 UTC 12월 31일 15:30 일 때 KST 로는 다음 해 1월 1일
  탈락 판정           endAt < startAt · title 빈 값 · 날짜 파싱 실패 → 그 건만 탈락
  누락 경고           content · targetText 없으면 탈락이 아니라 warning
  전부 탈락           → 500 대상임을 알리는 신호 (no_schedule 과 구분)
  정규화              "" → None · order 재부여 1..N · 공백 정리

tests/schedule/test_date_probe.py       날짜 패턴 검사 (§3.3 두 번째 줄)
  N월 N일 · YYYY-MM-DD · N/N · 요일 표기를 찾나
  날짜 없는 문서에서 0건이어야 한다 — 거짓 500 을 만들지 않는다

tests/schedule/test_prompt.py           스키마·프롬프트가 규칙을 담고 있나
  출력 스키마에 status 필드가 없다 · startLocal 이 naive 형식이다

tests/api/test_api_schedule.py          라우터 경계. app.state.schedule_provider 를 가짜로 교체
  200 계약 형태 · no_schedule · 400 · 401 · 500
  모델 0건 + 문서에 날짜 있음 → 500
  모델 0건 + 문서에 날짜 없음 → 200 no_schedule
  visibilityType·departmentIds 되돌리기
  교차 검증 — order 1..N · status 와 목록 일관성
  provider 예외에 내부 상세가 응답에 안 실린다
  백엔드 검증 통과 형태 — §3.7 표를 그대로 검사한다

tests/schedule/test_local_model.py      @pytest.mark.llm  기본 deselect
  ollama 없으면 skip. 고정 코퍼스로 실호출
```

`pyproject.toml` 의 `markers` 에 `llm` 을 더한다. 기존 `ocr` 선례와 같은 구조다.
기본 실행은 `uv run pytest -m "not ocr and not llm"`. 추후 CI 에 모델이 붙으면 `llm` 을 켠다.

가짜 provider 는 `complete_json` 하나만 흉내낸다. 포트가 함수 하나라 가짜도 한 줄이다.

**코퍼스** — `experiments/corpus-schedule/` 에 문서 5~8건과 정답 JSON. `@pytest.mark.llm` 테스트와 모델 대조(`qwen2.5:7b-instruct` vs `exaone3.5:7.8b`)가 같은 셋을 쓴다. 합성 문서임을 README 에 적는다.

측정 항목은 재현율·정밀도다 — 일정 몇 건 중 몇 건을 찾았나, 없는 일정을 만들었나, 문서에 적힌 시각을 그대로 옮겼나. 시각 변환이 코드로 빠져서 **모델 채점이 결정적이 된다.**

## 6. 검토하고 채택하지 않은 안

### 6.1 2단 파이프라인 — 블록 분할 후 블록별 추출

1단이 문서를 일정 후보 블록으로 쪼개고 2단이 블록별로 추출한다. 일정 30건짜리 큰 문서에서 뒤쪽을 흘리는 것을 막는다.

**채택하지 않은 이유** — 호출이 N+1 번이라 180초를 위협하고, 블록 분할이 틀리면 일정이 통째로 사라진다. 누락이 아직 관측되지 않았다. §7 의 큰 문서 측정에서 실제로 보이면 그때 온다.

### 6.2 에이전트 루프

위키 변환이 쓰는 방식이다. 툴을 주고 스스로 검증·재시도하게 한다.

**채택하지 않은 이유** — 읽을 것이 요청 본문 하나고 쓸 곳이 없다. MCP·작업층·lint 가 전부 무의미하다. 위키 변환이 126~894초 걸리는 이유가 그 루프이고, 이 작업은 26초다.

### 6.3 `status` 를 모델이 정한다

측정에서 모델이 일정 2건을 내면서 `no_schedule` 이라 답했다(§2.1). 코드가 `schedules` 길이로 정하면 그 모순이 불가능해진다.

### 6.4 `pytest` 가 실제 모델을 호출한다

출력이 비결정적이고 CI 에 GPU 도 모델도 없다. 기본 테스트가 로컬 모델에 의존하면 그 순간 CI 에서 깨진다. `@pytest.mark.llm` 으로 분리한다.

### 6.5 실패 시 원본 파일을 보존하도록 백엔드에 요청한다

한때 이쪽이 필요하다고 판단했다. 철회한다.

백엔드는 `no_schedule` 이든 예외든 원본·파싱 파일을 지운다(`ScheduleSourceService.java:93`). 처음에는 "500 은 재처리 대상이니 남겨야 한다" 고 봤는데, **일정 경로에 재처리 구조가 없다.** 일정 원본문서는 `document` 테이블에 저장하지 않고(`FR-DOC-003`), `schedule.status` 는 `DRAFT`·`APPROVED` 뿐이고, `ai_job` 에 일정 작업 타입이 없고, `FR-DOC-012` 의 `FAILED` 재처리는 위키 문서 전용이다.

동기 요청이므로 재시도는 관리자가 화면에서 다시 올리는 것이고 원본은 관리자 로컬에 있다. 서버에 남긴 파일은 아무도 참조하지 않는 고아다. 지우는 것이 옳다.

### 6.6 종일 일정의 종료를 다음 날 `00:00` 배타적으로 둔다

경계가 깔끔하다. 그러나 달력이 이틀에 걸친 일정으로 그린다(`FR-SCH-008` 은 일정 유형 구분만 요구하고 배타적 종료 규약이 없다). `23:59:59.999999` 로 둔다.

## 7. 미해결

### 7.1 계약·팀 협의

| 항목 | 내용 |
| --- | --- |
| 기준 시각 필드 | 요청에 `referenceDate`·`timezone` 이 없다. `Asia/Seoul` + 서버 시각 가정으로 진행하되 계약 필드 추가를 제기한다. 들어오면 §3.5 의 가정이 전부 없어지고 결과가 재현 가능해진다 |
| `warnings` 노출 경로 | 내부 계약은 `warnings` 를 요구하는데 백엔드가 버리고(`ScheduleSourceService.java:118`) 공개 API 응답에 필드가 없다. 연도 추론·부분 탈락·종료 추정이 관리자에게 갈 길이 없다. `FR-SCH-002` 검수의 실질을 위해 공개 노출을 제기한다 — 계약·요구사항·프론트 3곳 변경 |
| 파싱 read-timeout | `FR-AI-001` 과 공개 계약이 "파싱+추출 180초" 인데 파싱은 공용 `read-timeout: 10m` 을 쓴다(`application.yml:35`). 일정 경로용 짧은 제한 또는 남은 시간 전달이 필요하다. `S15P11B106-121` 에 전달 |
| `failureStage` | `NFR-AI-003` 첫 문장이 AI 처리 전반의 실패 단계를 요구하는데 일정용 단계 어휘와 계약 필드가 둘 다 없다. 함께 정해야 한다 |
| `parsedMarkdown` 상한 | 계약에 없다. 파일 20MB(`FR-DOC-003`)에서 유도한 값으로 두고 MR 본문에 협의 항목으로 올린다 |
| 백엔드 응답 검증 | `RestClientAiClient` 가 `status` 허용값·목록 일관성·`order` 중복을 안 본다. AI 가 자기검증으로 막지만 백엔드 검증 강화가 정본 처방이다 |
| CSV·XLSX 파싱 | 계약과 백엔드는 일정에 CSV·XLSX 를 허용하는데 `source_parse.py:34` 는 받지 않고 파서도 없다. `S15P11B106-118` 이 명시로 범위 제외했고 **어느 티켓도 맡고 있지 않다.** 별 티켓이 필요하다 |

`400` 코드 이름은 협의 항목이 아니다 — 계약 Saved Example 이 `INVALID_SCHEDULE_EXTRACTION_REQUEST` 와 메시지까지 확정했다.

### 7.2 측정으로 확인이 남은 것

| 항목 | 상태 |
| --- | --- |
| 일정 20건 이상 큰 문서의 누락 | 미측정. §6.1 재검토 조건 |
| `location` 누락 재현성 | 1회 관측. 문서에 있는 장소를 빠뜨렸다 |
| `qwen2.5` vs `exaone3.5` | 미대조. 둘 다 받아뒀다 |
| 배포 모델 | 미정. 로컬 모델 품질과 배포 서버 GPU 유무에 달렸다 |
| 코퍼스 신뢰도 | 문서 1건으로는 재현율·정밀도라 부를 수 없다 |

### 7.3 범위 밖

| 항목 | 이유 |
| --- | --- |
| 일정 초안 승인·거부 | 백엔드. `S15P11B106-91` 완료 |
| CSV·XLSX 파서 확장 | 별 티켓 (§7.1) |
| 챗봇 답변 API 2개 | 계약에 있고 AI 에 없다. 별 안건 |
| 배포 모델 확정 | 측정 뒤 (§7.2) |

### 7.4 구현과 함께 갱신할 문서

새 패키지가 생기므로 `ai/CLAUDE.md` 의 패키지 표와 의존 그래프에 `schedule_extractor` 를 넣는다. `src/.env.example` 에 어댑터 설정 항목을 더한다. `ai/README.md` 의 내부 API 표에도 새 엔드포인트를 넣는다 (그 표는 계약 v1.1.0 시점이라 이미 낡았다).

## 8. 이 문서가 정정한 것

Codex 리뷰(1회)에서 나온 지적과 확인 결과다.

| # | 초기 서술 | 정정 |
| --- | --- | --- |
| 1 | 계약 예시가 KST 10시를 `01:00:00Z` 로 적은 것이 근거 | 근거가 아니다. 예시 `parsedMarkdown` 은 `"# 8월 일정\n..."` 이라 본문이 없다. 관행에 기댄 가정이다 (§3.5) |
| 2 | 연도 추론 경고를 관리자가 화면에서 본다 | 틀렸다. 백엔드가 `warnings` 를 버리고 공개 API 에 필드가 없다 (§3.5 · §7.1) |
| 3 | 400 코드 이름은 계약에 없어 협의 항목이다 | 틀렸다. Saved Example 에 코드와 메시지가 확정돼 있다 (§3.6) |
| 4 | 모델 0건은 일정이 없는 문서다 | 부족하다. `gemma4:e2b` 가 일정 3건에 0건을 냈다. 날짜 패턴 검사를 덧댄다 (§3.3) |
| 5 | 500 을 내면 재처리 대상으로 남는다 | 틀렸다. 일정 경로에 재처리 구조가 없고 파일은 어느 쪽이든 지워진다. 구분의 값은 관리자에게 전할 메시지다 (§3.3 · §6.5) |
| 6 | 종일 종료는 `23:59` | `DATETIME(6)` 이라 마지막 60초가 빈다. `23:59:59.999999` (§3.5) |
| 7 | `content`·`targetText` 는 `null` 허용 | `FR-SCH-001` 이 추출을 요구한다. 탈락시키지 않고 warning 을 남긴다 (§3.3) |
| 8 | 180초는 추출 시간 | 파싱+추출 총량이다. 백엔드 파싱이 공용 10분을 쓴다 (§3.6 · §7.1) |
| 9 | provider 를 주입받는다 | 주입 지점을 안 정했다. `app.state.schedule_provider` (§3.4) |
| 10 | `failureStage` 는 대상 아니다 | `NFR-AI-003` 첫 문장은 대상으로 본다. 협의 항목 (§3.6 · §7.1) |
| 11 | 예외는 공통 핸들러가 처리한다 | `errors.py:141` 이 예외 문자열을 응답에 싣는다. 고정 메시지로 변환한다 (§3.6) |
| 12 | `experiments/INDEX.md:169` | 이 브랜치 파일은 83행이다. 169행은 `feature/…-143` 것이었다 — `ai/CLAUDE.md` 가 경고한 브랜치 혼동 (§4) |
