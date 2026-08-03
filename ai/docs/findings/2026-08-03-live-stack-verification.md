# 2026-08-03 실기동 검증 — 위키 변환·챗봇·권한 격리

> [`2026-08-02-wiki-e2e-stability-test.md`](2026-08-02-wiki-e2e-stability-test.md) 의 후속이다.
> 그 문서가 **미검증으로 남긴 것**(lint 종료 신호가 실제로 도움이 되는지)을 이 문서가 확인한다.
> `experiments/backend_sim.py` 가 아니라 **Spring Boot + MySQL + AI 서버 3계층을 모두 띄운 상태**
> 로 검증했다 (`ai/CLAUDE.md` 작업 수칙).

## 환경

- MySQL: docker `ajt-mysql`(mysql:8.4), DB `ajt`. **데이터 초기화하지 않음** — 기존 문서·위키를
  그대로 두고 그 위에 쌓았다.
- Spring: `SPRING_PROFILES_ACTIVE=local` + `SPRING_DATASOURCE_URL` 을 환경변수로 덮어써 실제
  MySQL 연결. ⚠️ `application-local.yml` 의 기본값은 **H2 인메모리**이고 `ddl-auto: create-drop`
  이라, **재시작하면 MySQL 스키마가 드롭·재생성된다** — 이번 검증 동안 재시작하지 않았다.
  (AI 서버 재시작은 안전하다. 지침 수정 검증에 여러 번 재시작했다.)
- AI 서버: `AI_RUNTIME=deepagents`, **`AI_MODEL=anthropic:claude-sonnet-4-6`**(프로덕션 모델
  그대로), GMS 게이트웨이 경유, `VISION_OCR_PROVIDER=gemini`.
- 코드: 브랜치 `fix/S15P11B106-143-harness-fidelity` — 하네스로 고친 결함 9건이 들어간 상태.

## 1. 위키 변환 — 5회 실패했던 문서가 성공했다

`08-compensation.md`(16,846자)는 8/2 문서에 기록된 대로 **네 번 연속 실패**했고(job 16·17·18·21,
합계 약 $11), MySQL 에 문서 31·34·35 가 `FAILED` 로 남아 있었다. 같은 문서를 이번에 다시 올렸다.

| 항목 | 결과 |
| --- | --- |
| job 23 / 문서 36 | **completed** (13:52:04 → 13:55:58, **3분 54초**) |
| 같은 문서 이력 | 문서 34·35 `FAILED` ↔ **문서 36 `COMPLETED`** (MySQL 에 나란히 남음) |
| 산출물 | 위키 3건 신규(wikiId 10 주식옵션 · 11 수습/퇴직금 · 12 보상체계) + 카테고리 신규 생성 |
| 사용량 | 12턴, 입력 258,825 · 출력 11,442 토큰, 도구 14회, 231.9초 |

**8/2 문서가 미검증으로 남긴 것이 검증됐다.** 그 문서 §2-2 는 `lint._report()` 에 "error 가
없으니 이걸로 끝이다" 종료 신호를 넣고 *"이 수정이 실제로 도움이 되는지는 다음에 사용자 확인
받고 나서 재측정할 것"* 이라고 적었다. 이번 실행에서 **40턴 → 12턴**으로 줄고 성공했다.
warn 완화(§2-1) + 따옴표·강조 정규화(§2-3) + 종료 신호(§2-2)의 조합이 실제로 들었다.

### 산출물 품질 — 사실은 정확하고, 인용문 14%는 이어붙인 것

wikiId 12 본문 6,762자 · 각주 23개. 세 위키의 각주 인용문 50개를 원본과 프로그램으로 대조했다
(링크·따옴표·강조·백틱을 정규화한 뒤 부분문자열 검사).

- **43/50(86%) 문자 그대로 일치**
- 불일치 7건은 전부 **원문에서 떨어져 있는 문장·불릿을 하나의 인용문으로 이어붙인 것**이다.
  예: wiki10 `^2` 는 원문 113·114·115행의 세 불릿(`Standard 4-year vesting with a 1-year
  cliff` / `10 years to exercise...` / `Double trigger acceleration...`)을 `/` 로 연결했다.
  **위치(절 제목)는 정확하고 사실도 원문에 실재한다 — 날조가 아니다.** 설계상
  `citation-quote-not-found` 는 `warn` 이라 통과시키는 수준이다(§2-1 의 의도).
- **상대경로 링크 0개 · 백틱 포함 각주 0개** — 하네스에서 고친 것(인용문 링크 지침, 백틱
  정규화)이 프로덕션 모델에서도 유효함이 확인됐다.
- 표·mermaid 포함, 각주 형식(`document-36, ## 절 — "원문"`) 정확.

## 2. 사원 챗봇 — 정상, 다만 버그 하나를 찾아 고쳤다

질문: "주식 옵션 베스팅 조건이 어떻게 되나요?" → **15초, 정확한 답변**. 4년 베스팅·1년 클리프·
입사일 기준·10년 행사·이중 트리거·리프레시 18~25% 전부 원문과 일치. 출처 체인도 정확했다:
`wikiId 10` → `evidenceDocuments: documentId 36` → `downloadUrl: /api/v1/documents/36/file`.

**챗봇이 쓰는 도구는 위키 편집 도구와 별개다.** `wiki_api/answer_tools.py` 의 읽기 전용 5개
(`search_wiki`·`read_wiki`·`read_wiki_index`·`list_schedules`·`read_schedule`)이고, `wiki_mcp`
저장 계층을 타지 않고 **Spring 조회 API 만** 부른다 — 그래서 **하네스로는 이 경로를 검증할 수
없다.** 실기동만이 수단이다.

### 발견한 버그 — 위키에 없는 것을 물으면 사용자에게 장애 메시지가 나갔다

| 질문 | 수정 전 | 수정 후 |
| --- | --- | --- |
| "사내 헬스장 이용 시간이 어떻게 되나요?" | **503** `AI_SERVER_UNAVAILABLE` (5.3초) | **200** "정보가 없습니다" (9.6초) |
| "회사 셔틀버스 노선을 알려주세요" | **503** (5.5초) | **200** "찾을 수 없습니다" (7.6초) |
| (대조군) "수습 기간은 몇 개월인가요?" | 200 | 200, **6/6 성공** (9~16초) |

`FR-QNA-007`("근거를 못 찾은 것은 실패가 아니다")과 `answer.py` 주석이 명시적으로 경고한 상황이
실제로 벌어지고 있었다. 임시 로깅으로 원인을 확정했다:

```
tool_calls={}   ← 도구를 한 번도 부르지 않음
answer="죄송합니다, 사내 헬스장 이용 시간에 대한 정보는 현재 위키에 등록되어 있지 않습니다..."
```

**원인은 500 조건이 아니라 모델이 검색을 건너뛴 것이다.** `build_response` 가 도구 미호출을
근거 없는 답변으로 보고 500 을 내는 것은 의도된 안전장치이므로 **그대로 뒀다** — 위키를 읽지
않았으면 답할 자격이 없다.

지침(`answer_guide.py`)이 빠져나갈 길을 주고 있었다:
- 순서 2번이 *"위키 질문이면 `search_wiki` 로..."* 라는 **조건절**이라, 스스로 "이건 위키에
  없는 질문" 이라고 판단하면 건너뛸 수 있었다.
- *"찾아봤지만 없으면 그렇게 답하는 것이 맞다"* 가 **"찾아보지 않아도 된다"** 로 읽혔다.

고침: (1) **"어떤 질문이든 도구를 최소 한 번은 부른다"** 를 순서 2번으로 올리고, 목차는 요약이라
본문 내용이 안 보일 수 있음을 근거로 달았다. (2) **"「모른다」도 찾아본 뒤에만 할 수 있다"** 로
바꾸고, 도구를 안 부르면 서버가 요청을 실패로 처리해 사용자에게 장애 메시지가 나간다는 결과까지
적었다. 수정 후 응답 시간이 5초 → 8~10초로 늘어난 것이 **실제로 검색을 부른 증거**다.

## 3. 권한 격리 — 3겹 방어, 실측으로 확인

`01-training.md`(2,423자)를 **개발부(D1) 한정**으로 업로드(job 24 / 문서 37, **1분 40초** 완료)해
`D1` 위키(wikiId 13 "교육 및 도서 지원")를 만든 뒤, 같은 질문을 두 부서 사원에게 던졌다.

| 사원 | D1 권한 | 결과 | 출처 |
| --- | --- | --- | --- |
| `employee@ajt.com` (개발부) | 있음 | **정확한 답변** (도서 월 $50, 교육 연 $1,000, BookHog) | wikiId **13** (5회 반복 전부) |
| `planning.emp@ajt.com` (기획부) | 없음 | **"찾을 수 없습니다"** | 없음 |

AI 서버 로그가 결정적 증거다 — 기획부 요청은 **`scopeKey=D2` 로만 조회**됐다
(`GET /internal/v1/wiki-search?scopeKey=D2&...`, `GET /internal/v1/wiki-spaces/D2/index`).
D1 을 아예 건드릴 수 없다.

방어 3겹을 각각 확인했다:

| 층 | 방어 | 실측 |
| --- | --- | --- |
| 내부 API 인증 | 내부 API 키 필수 | 키 없음 → **401** |
| capability(허가값) | 요청마다 발급, scope·만료 검사 | 없음 → **404**, 위조 → **404** |
| | `!capability.scopeKey().equals(scopeKey)` → 거부 | scope 불일치 차단 (코드) |
| 출처 재검증 | `accessibleScopeKeys.contains(wiki.scopeKey())` 필터 | AI 가 권한 밖 ID 를 내밀어도 제거 (코드) |

허용 scope 계산은 `ALL` + **사용자 부서가 포함된 scope 만**
(`QuestionAskService.accessibleScopeKeys`, `departmentRefs().contains(departmentId)`).

## 미해결 — 간헐적 503

이번 세션에서 **3회 관측**했다(대조군 질문 1회, 개발부 질문 1회, 그 외 1회). 직후 반복은 5~6회
연속 성공해 **재현율이 낮다(대략 10~15%)**. 8/2 문서 §1 의 간헐적 실패와 같은 성격으로 보이며
원인은 미확인이다. **사용자에게는 장애로 보이는 실제 문제**이므로 별도 조사가 필요하다.

## 관리자 채팅 수정 — 이번 검증에서 제외

`POST /api/v1/wikis/{wikiId}/chat-messages` 는 500(`WIKI_EDIT_FAILED`)으로 실패했다. 원인은
확인했다: 실행 중인 Spring 이 **8/1 빌드**라 `wiki-edits` 요청에 `currentWiki`·
`evidenceDocuments` 를 아직 보내는데, AI 의 `EditRequest` 는 `Strict`(추가 필드 금지)라 400 을
낸다. 소스는 이미 그 필드를 걷어낸 상태다(`f0a23ac` [S15P11B106-176]). **Spring 재빌드·재시작이
필요한데 그러면 MySQL 스키마가 드롭되므로 이번엔 하지 않았다.** 해당 수정은 별건으로 진행 중.

## 실기동 절차와 함정 (다음 사람 참고)

1. **Spring 을 재시작하지 않는다** — `ddl-auto: create-drop`. 떠 있는 Spring 이 H2 가 아니라
   MySQL 을 보는지 `ps eww -p <pid>` 의 `SPRING_DATASOURCE_URL` 로 확인한다(같은 방법으로 DB
   비밀번호도 얻어 `docker exec ajt-mysql mysql` 조회에 쓸 수 있다).
2. 로그인은 CSRF 흐름 — `GET /api/v1/auth/csrf` → `XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더.
   **토큰은 1시간 뒤 만료**되니 장시간 세션에서는 재로그인한다.
3. 업로드 필드는 `files`·`documentCategoryId`·**`visibilityType`**(`all`|`department`, **소문자**)
   ·`departmentIds` 다. `scopeKey` 를 직접 보내는 게 아니다.
4. **카테고리는 그 scope 소속이어야 한다**(`category.belongsToScope`) — D1 업로드에 ALL
   카테고리를 쓰면 400 이고 `fieldErrors` 가 비어 원인이 안 보인다.
5. **업로드만으로는 안 돈다** — `POST /api/v1/ai-jobs/{jobId}/start` 를 반드시 부른다.
6. 문서 목록 API 는 `page` 가 **1부터**다.
7. **내부 조회 API 를 직접 부르면 404/0건이 정상이다** — capability 헤더가 없기 때문이다.
   그것으로 "검색이 안 된다"고 오판하지 말 것(이번에 한 번 오판했고, MySQL 에서 FULLTEXT
   `MATCH ... AGAINST` 로 4건이 나오는 것을 확인해 바로잡았다).
8. AI 쪽 검증 실패(400) 원인은 응답 `fieldErrors` 에 담기는데 Spring 이 그것을 버린다 —
   AI 서버에 임시 로깅을 넣어야 보인다.

## 이번 검증으로 고친 것

- `fix(chatbot)`: 검색 없이 「없습니다」라고 답해 503 이 나가던 것 (`answer_guide.py`)

## 데이터 상태 (검증 후, 초기화하지 않음)

| 테이블 | 건수 |
| --- | --- |
| document | 31 (COMPLETED 11 / FAILED 20) |
| wiki | 9 (ALL 8 + **D1 1**) |
| wiki_category | 7 (D1 카테고리 1건 신규) |
| ai_job | 24 |
| wiki_search_chunk | 45 |

`FAILED` 20건은 대부분 **이번 수정 이전의 재시도 흔적**이다(같은 파일이 여러 번:
`01-service-rules-v1.docx` 7건, `08-compensation.md` 6건 — 그중 마지막이 이번 성공분).
이번 검증에서 새로 실패한 문서는 없다.
