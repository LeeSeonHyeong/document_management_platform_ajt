# 측정 원본문서 (12건)

`experiments/` 의 모든 위키 변환 측정이 쓰는 입력이다.

## ⚠️ 전부 영어다

[PostHog 오픈소스 핸드북](https://posthog.com/handbook)의 사내 규정 문서다. 프론트매터에
`sidebar: Handbook` 이 남아 있다. **12건 130,048글자에 한글이 0자다.**

실서비스는 **한국어 원본 → 한국어 위키**다. 이 코퍼스로 낸 수치는 그 조건이 아니다.

| 이 코퍼스로 잰 것 | 실서비스 |
| --- | --- |
| 영어 원본 → 한국어 위키 | 한국어 원본 → 한국어 위키 |
| 각주가 **영어 문장**을 인용 | 각주가 **한국어 문장**을 인용 |
| 영어 기준 토큰/바이트 | 한국어는 같은 토큰에 바이트가 3배 |

### 영향받는 수치

`ai/CLAUDE.md` 가 이미 "한국어 실문서는 같은 토큰에 바이트가 3배라 한계가 더 빨리 온다 —
미측정"이라 적어뒀다. 아래 값이 전부 그 미측정 구간 밖이다.

- **시간 회귀식** `1.9분 + 5.0분 × (크기/1만자)` — 크기가 영어 기준이다
- **토큰/바이트 0.488(목차)·0.364(산문)** — 영어·한국어 혼재 문서에서 나온 값
- **챗봇 1단계 천장 120페이지** (`tests/api/test_answer_scale_ceiling.py`) — 목차
  343바이트/페이지가 이 코퍼스 산출물 기준이다. 한국어 제목·요약이면 페이지당 바이트가
  커져 천장이 더 낮다
- **입력 상한** `MAX_INPUT_BYTES` 40,000 / 120,000

### 가장 위험한 미검증

**한국어 각주 인용 정확도.** `lint` 가 인용문을 원문과 **문자열 대조**한다
(`citation-quote-not-found`). 영어는 문장 경계가 명확해 그대로 잘라 붙이면 맞는다. 한국어는
조사·어미가 붙어 부분 인용이 어긋나기 쉽다. sonnet 12건 측정에서 이 오류가 한 페이지에
15건 났다가 에이전트가 고쳤는데(`2026-07-29-sonnet46-12docs`), **한국어에서는 더 자주 날
수 있고 고치기도 어려울 수 있다.**

한국어 코퍼스로 최소 2건을 재기 전까지 이 항목은 열려 있다.

## 구성

한 건이 2.4KB~31KB, 합계 168KB. `documentId` 는 파일 순서다 — `01` → 101 부터 12번까지.

| 파일 | 크기 | documentId |
| --- | --- | --- |
| `01-training.md` | 2.4KB | 101 |
| `02-side-gigs.md` | 3.4KB | 102 |
| `03-career-progression.md` | 3.9KB | 103 |
| `04-benefits.md` | 7.0KB | 104 |
| `05-offboarding.md` | 8.9KB | 105 |
| `06-security.md` | 10.0KB | 106 |
| `07-time-off.md` | 10.8KB | 107 |
| `08-compensation.md` | 16.9KB | 108 |
| `09-spending-money.md` | 22.4KB | 109 |
| `10-onboarding.md` | 24.8KB | 110 |
| `11-offsites.md` | 26.1KB | 111 |
| `12-communication.md` | 31.5KB | 112 |

## 이관 주석 (2026-07-29)

옛 작업 공간 `spikes/wiki-convert-loop/corpus/` 에만 있어서 저장소에서 측정을 재현할 수
없었다. 내용은 이미 각 실험의 `data/wiki/ALL/sources/{documentId}/parsed/content.md` 에
그대로 들어 있다 — 파싱 전후가 동일함을 확인하고 가져왔다.

## 사용

```bash
uv run python experiments/backend_sim.py \
  --experiment <slug> --purpose "..." --runtime claude-code \
  --model claude-sonnet-4-6 \
  experiments/corpus/01-training.md experiments/corpus/02-side-gigs.md
```
