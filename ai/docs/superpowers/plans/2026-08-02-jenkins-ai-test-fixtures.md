# Jenkins AI Test Fixtures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jenkins AI 테스트의 공통 계약 파일과 로컬 CLI 테스트 의존성을 올바르게 제공한다.

**Architecture:** 테스트 컨테이너에만 루트 `docs/`를 읽기 전용 마운트하고, CLI 배선 단위 테스트에만 실행 파일 탐색 결과를 모킹한다. 운영 이미지와 운영 기동 가드는 변경하지 않는다.

**Tech Stack:** Jenkins Pipeline, Docker, Bash, pytest

## Global Constraints

- 운영 AI runtime 이미지에 `docs/`를 복사하지 않는다.
- 실제 `claude` CLI 부재 시 기동 실패 동작을 유지한다.
- API 키와 환경파일을 수정하지 않는다.

---

### Task 1: Jenkins 테스트 컨테이너에 계약 문서 제공

**Files:**
- Modify: `scripts/tests/jenkins-ai-test.sh`
- Modify: `Jenkinsfile`

- [ ] 정적 검사에 `${WORKSPACE}/docs:/docs:ro` 요구 조건을 추가한다.
- [ ] 현재 Jenkinsfile에서 검사가 실패하는지 확인한다.
- [ ] AI 테스트 `docker run`에 읽기 전용 문서 마운트를 추가한다.
- [ ] 정적 검사를 다시 실행해 통과를 확인한다.

### Task 2: claude-code 배선 단위 테스트 격리

**Files:**
- Modify: `ai/tests/api/test_serve.py`

- [ ] Jenkins #8의 세 실패 테스트에서 `serve.shutil.which`를 모킹한다.
- [ ] CLI 부재를 검증하는 전용 테스트는 수정하지 않는다.
- [ ] `ai/tests/api/test_serve.py`를 실행해 통과를 확인한다.

### Task 3: 통합 검증과 전달

- [ ] 관련 정적 검사와 AI 테스트를 실행한다.
- [ ] 변경 파일을 명시적으로 stage하고 커밋한다.
- [ ] 브랜치를 push하고 develop 대상 MR 링크를 전달한다.

