# AI Docker Test and Schedule Haiku Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jenkins AI 테스트가 실험 모듈을 정상 수집하고 배포 일정 추출이 Haiku 모델을 사용하게 한다.

**Architecture:** `experiments/`는 Docker 테스트 스테이지에만 복사하고 런타임 스테이지에서는 제외한다. Compose 일정 추출 모델은 정확한 GMS 모델 이름으로 고정하고 정적 회귀 검사로 보호한다.

**Tech Stack:** Docker multi-stage build, Docker Compose, Bash regression tests, pytest

## Global Constraints

- 일정 추출 모델은 정확히 `claude-haiku-4-5-20251001`이다.
- `AI_MODEL`, `AI_MODEL_FAST`, `AI_MODEL_QUALITY`은 변경하지 않는다.
- 최종 `runtime` 이미지에는 `experiments/`를 포함하지 않는다.
- API 키와 실제 환경파일은 커밋하지 않는다.

---

### Task 1: Docker 테스트 스테이지에 실험 모듈 포함

**Files:**
- Modify: `ai/.dockerignore`
- Modify: `ai/Dockerfile`
- Test: `scripts/tests/jenkins-ai-test.sh`

**Interfaces:**
- Consumes: `ai/tests/**`가 `/app/experiments`를 `sys.path`에 추가하는 현재 테스트 구조
- Produces: 테스트 이미지의 `/app/experiments/query_gateway.py`와 `/app/experiments/experiment.py`

- [ ] **Step 1: 실패하는 Dockerfile 회귀 검사 추가**

```bash
grep -q 'COPY experiments ./experiments' ai/Dockerfile
! grep -q '^experiments/$' ai/.dockerignore
```

- [ ] **Step 2: 검사가 현재 설정에서 실패하는지 확인**

Run: `bash scripts/tests/jenkins-ai-test.sh`

Expected: `AI test image does not copy experiments` 오류로 실패한다.

- [ ] **Step 3: 테스트 스테이지에만 experiments 복사**

`ai/.dockerignore`에서 `experiments/` 제외 규칙을 제거하고 `ai/Dockerfile`의 test 스테이지에 다음을 추가한다.

```dockerfile
COPY experiments ./experiments
```

- [ ] **Step 4: 정적 검사 통과 확인**

Run: `bash scripts/tests/jenkins-ai-test.sh`

Expected: `PASS: Jenkins AI pipeline wiring`

### Task 2: 일정 추출 모델을 Haiku로 고정

**Files:**
- Modify: `docker-compose.yml`
- Test: `scripts/tests/compose-ai-test.sh`

**Interfaces:**
- Consumes: `schedule_extractor`의 Anthropic 모델 이름 문자열
- Produces: `SCHEDULE_EXTRACTOR_MODEL=claude-haiku-4-5-20251001`

- [ ] **Step 1: Compose 회귀 검사의 기대 모델 변경**

```bash
grep -q 'SCHEDULE_EXTRACTOR_MODEL: claude-haiku-4-5-20251001' "$rendered"
```

- [ ] **Step 2: 검사가 현재 Opus 설정에서 실패하는지 확인**

Run: `bash scripts/tests/compose-ai-test.sh`

Expected: Haiku 모델 grep 검사에서 실패한다.

- [ ] **Step 3: Compose 모델 변경**

```yaml
SCHEDULE_EXTRACTOR_MODEL: "claude-haiku-4-5-20251001"
```

- [ ] **Step 4: 서버 Docker 환경에서 최종 검증**

Run: `bash scripts/tests/compose-ai-test.sh`

Expected: `compose AI integration test passed`

### Task 3: 커밋과 Jenkins 재검증

**Files:**
- Modify: 없음
- Test: Jenkins `AI Test` stage

**Interfaces:**
- Consumes: Task 1과 Task 2의 Docker/Compose 변경
- Produces: develop에 병합 가능한 fix 브랜치

- [ ] **Step 1: 변경 파일 검사**

Run: `git diff --check && git status --short`

Expected: 공백 오류가 없고 계획된 파일만 표시된다.

- [ ] **Step 2: 변경 커밋**

```bash
git add ai/.dockerignore ai/Dockerfile docker-compose.yml scripts/tests/jenkins-ai-test.sh scripts/tests/compose-ai-test.sh
git commit -m "fix(ci): AI 테스트 컨텍스트와 일정 모델 수정"
```

- [ ] **Step 3: 브랜치 push 후 Jenkins 재실행**

MR을 develop에 병합한 후 `S15P11B106-develop` Job을 실행한다. Backend Test, Frontend Check, AI Test, Docker Build가 모두 성공하고 `Deployment Approval`에서 멈추는지 확인한다.
