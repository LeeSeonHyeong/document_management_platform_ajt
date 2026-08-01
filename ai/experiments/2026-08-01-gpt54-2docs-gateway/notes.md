# 2026-08-01-gpt54-2docs-gateway

- 런타임: deepagents · 모델: openai:gpt-5.4
- 범위: ALL · 코퍼스: 01-training.md, 02-side-gigs.md
- guide: b5444b72e5a4 (4549자)

## 무엇을 재려 했나

S15P11B106-175 Wiki 조회 API 단일 호출 — LocalVaultFS 기준선(2026-08-01-gpt54-2docs-inprocess) 대조

## 어떻게 돌렸나

`report.json` 의 `runtime` 은 `via-api` 다 — **계약 층을 실제로 통과한 측정**이다
(HTTP → `wiki_api` 세션 → 조회 API 하이드레이션 → 에이전트 → 계약 응답 → 반영).
`manifest.json` 의 `runtime: deepagents` 는 AI 서버가 쓴 런타임을 적어 둔 것이다.

```bash
# Wiki 조회 API (가짜 Spring)
uv run python -m experiments.query_gateway \
  --corpus experiments/2026-08-01-gpt54-2docs-gateway/data --scope ALL \
  --api-key local-dev-key --capability cap-local --port 8901

# AI 서버 — 계측을 끼워 띄운다 (`experiments/serve_instrumented.py`)
INTERNAL_API_KEY=local-dev-key BACKEND_BASE_URL=http://127.0.0.1:8901 \
AI_RUNTIME=deepagents AI_MEASURE_LOG=<경로> \
  uv run python -m experiments.serve_instrumented --port 8010

# 백엔드 시뮬레이터
INTERNAL_API_KEY=local-dev-key uv run python experiments/backend_sim.py \
  experiments/corpus/01-training.md experiments/corpus/02-side-gigs.md \
  --experiment 2026-08-01-gpt54-2docs-gateway --scope ALL \
  --via-api http://127.0.0.1:8010 --wiki-capability cap-local --scope-version 47 \
  --runtime deepagents --model openai:gpt-5.4 --purpose "..."
```

`measure.jsonl` 이 툴 호출·토큰·조회 API 호출의 출처다. 계약 응답에는 그런 필드가 없어서
(있어서도 안 된다) `report.json` 만으로는 기준선과 대조할 수 없다 — 서버 쪽에서 감싸
파일로 남겼다. 자세한 것은 `experiments/serve_instrumented.py` 헤더.

## 결과

| | 문서1 (01-training) | 문서2 (02-side-gigs) | 합 |
| --- | --- | --- | --- |
| 소요 시간(왕복) | 21.9초 | 25.1초 | **47.0초** |
| 소요 시간(에이전트) | 21.8초 | 24.9초 | 46.7초 |
| 툴 호출 | 10회 | 12회 | 22회 |
| 조회 API 호출 | 5회 | 7회 | 12회 |
| 조회 예산 | 105 | 107 | — |
| 입력 토큰 | 65,046 | 74,406 | 139,452 |
| 출력 토큰 | 2,213 | 2,562 | 4,775 |
| 턴 | 9 | 9 | 18 |

- 하이드레이션이 본 라이브 위키: 문서1 0장, 문서2 1장 (`catalogPages`).
  문서2 는 앞 문서가 만든 페이지를 실제로 당겨 읽었다 (`bodyFetches: 1`) —
  그래서 `relationChanges` 1건이 나왔다.
- 조회 예산 소진율 **최대 6.5%** (7/107). 상한을 건드리지 않는다.
- 비용은 `0.0` 이다 — DeepAgents 런타임이 비용을 보고하지 않는다 (기준선도 같다).
  대조 가능한 값은 토큰이다.

## 판정

기준선 `2026-08-01-gpt54-2docs-inprocess`(및 `-r2`·`-r3`) 대비 **저하 없음**.
2문서 합계 47.0초 대 기준선 55.9초(중앙) / 52.6~67.1초(3회 범위) — 범위 아래다.
출력 토큰 4,775 대 기준선 5,474~6,818 도 아래다.

**분포가 아니다.** 문서 2건·실행 1회다. 기준선 3회의 범위(52.6~67.1초, 최대·최소가
1.28배)를 보면 이 차이는 잡음 안에서도 설명된다 — 주장할 수 있는 것은 「빨라졌다」가
아니라 「느려지지 않았다」까지다.

하드 게이트는 전부 통과했다: `lint` error 0(2건 다 「lint 통과」), 문서당 페이지 1장
(조각나지 않았다), 각주 원문 인용 16·15건이 `lint` 의 원문 대조를 통과했다
(NFR-AI-002).
