# 2026-08-07 — 압축의 상태보존 요약이 한국어 장문 모양에서 실행되지 않는다

> 가짜 모델 하네스 실측 (LLM 비용 0). 스크립트: 세션 스크래치패드 `exp_summary_starvation.py`.
> memory `gms-korean-longdoc-limitation` ③ (job 43 트레이스에 요약 LLM 호출이 없었다)의 확정이다.

## 결론

S15P11B106-273 이 넣은 상태보존 요약 프롬프트(`wiki_work_state`)는 **프로덕션에서 가장
흔한 압축 유발 모양(한국어 장문 read 반복)에서 한 번도 실행되지 않는다.** 이력은 요약
없이 `"Previous conversation was too long to summarize."` 한 줄로 대체된다.

## 실측

`DeepAgentsRuntime` + `ScriptedModel` (게이트웨이 크레덴셜 → 트리거 34K 근사):

| 모양 | 압축 발동 | 상태보존 요약 호출 |
| --- | --- | --- |
| A. 중간 AI 메시지 다수 (기존 `test_compaction_state_preservation` 모양) | ✅ | **3회** |
| B. 영문 120K자 read 반복 | ❌ (read 결과가 파일로 치워져 +733토큰 스텁만 남음) | — |
| C. **한국어 32K자 read 반복** (job 43·사내규정 통합본 모양) | ✅ (근사 37,972에서) | **0회** |

## 메커니즘 (소스 확인)

`langchain.agents.middleware.summarization._create_summary`:

```python
trimmed_messages = self._trim_messages_for_summary(messages_to_summarize)
if not trimmed_messages:
    return "Previous conversation was too long to summarize."   # ← LLM 호출 없음
```

trim 은 `max_tokens=trim_tokens_to_summarize(기본 4000), start_on="human",
strategy="last"` 다. C 모양에서 read 결과 하나가 **근사 4,630토큰**이라 4,000 예산 안에
human 시작점을 만들 수 없어 빈 목록이 나온다 → 폴백. A 모양은 요약 대상 조각이 4,000
안에 들어 정상 호출된다 — **기존 테스트가 A 모양만 검증해서 이 구멍이 보이지 않았다.**

## 부수 발견 두 가지

1. **영문 거대 read 는 문제 자체가 안 된다** — deepagents 가 큰 도구 결과를 파일로
   치우고 스텁만 남긴다(B 실측: read 하나가 +733토큰). 그런데 **한국어는 근사 카운터의
   4배 과소평가 때문에 그 치우기 문턱까지 피해 간다** (32K자 = 근사 8K로 보임).
   job 51·52 가 GMS 요청 크기벽을 맨몸으로 맞은 메커니즘이 이것이다 — 치우기도, 압축도,
   전부 근사 카운터 뒤에 숨어서 못 왔다.
2. 압축 회복(!269 이력 되읽기 도구)이 그나마 동작해 온 이유: 이력 파일 안내는 폴백과
   무관하게 나간다. 즉 「무엇을 했는지」는 파일로 살아 있는데 「무엇이 끝났는지」요약만
   침묵으로 사라져 왔다 (job 41 의 guide 4회·lint 6회 반복 재시작이 그 증상).

## 수정 (S15P11B106-313)

`_CompactionSummarizationMiddleware`(우리 서브클래스, `agent_runtime/deep_agents.py`)에서:

1. `trim_tokens_to_summarize` 4,000 → **8,000 (근사)**. 근거: 한국어 근사 8K ≈ 실 32K
   토큰으로 GMS 요청 크기벽(~42K 실토큰) 안에 드는 최대 규모. 요약 모델(FAST haiku)의
   컨텍스트로도 충분.
2. `_trim_messages_for_summary` 오버라이드 — trim 이 비면 폴백 문자열 대신 **꼬리
   메시지를 글자수로 잘라서라도 요약 모델에 준다.** 단일 메시지가 예산보다 커도 요약이
   침묵 소멸하지 않는다.

검증은 C 모양 가짜 모델 테스트로 고정한다 (`summary_requests >= 1`).

## 남는 것 (별건 유지)

- 근사 카운터의 한국어 4배 과소평가 자체 — 보류된 CJK 카운터 건
  (memory `gms-korean-longdoc-limitation`). 이 수정은 요약 침묵만 없앤다.
  → **2026-08-07 해소** (S15P11B106-321): `_count_tokens_cjk` 로 압축 트리거·trim 의
  눈금을 실토큰에 맞췄다. 경위는 압축 설계 spec 의 2026-08-07 절.
