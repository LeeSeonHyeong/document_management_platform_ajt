# Jenkins Dual Deploy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 일반 Pipeline Job 두 개가 같은 Jenkinsfile을 사용해 `develop`은 승인 후 8090 `ajt-develop`에, `master`는 승인 후 443 `ajt-prod`에 안전하게 배포하도록 만든다.

**Architecture:** 브랜치별 배포 값을 작은 Bash resolver가 단일 정본으로 제공하고 Jenkinsfile은 Checkout 후 그 값을 읽는다. 두 Job은 SCM 브랜치를 각각 고정하며, 공통 파일 잠금을 사용하는 배포 스크립트가 서로 다른 Job의 Compose 변경을 직렬화한다. AI 빌드·배포는 일정 추출 API 계약이 확정된 후 별도 후속 작업으로 추가한다.

**Tech Stack:** Jenkins Declarative Pipeline, Groovy, Bash, Docker 29, Docker Compose v5, GitLab Webhook

## Global Constraints

- Job은 `S15P11B106-develop`과 `S15P11B106-pipeline` 두 개의 일반 Pipeline으로 유지한다.
- `develop`은 `/var/lib/jenkins/ajt-secrets/develop.env`, `ajt-develop`, 8090을 사용한다.
- `master`는 `/var/lib/jenkins/ajt-secrets/prod.env`, `ajt-prod`, 443을 사용한다.
- 두 브랜치 모두 테스트·이미지 빌드 뒤 24시간 이내 사람 승인을 받아야 배포한다.
- 다른 브랜치 값은 배포하지 않고 Checkout 준비 단계에서 오류로 중단한다.
- 실제 env 파일과 API 키·비밀번호는 Git 또는 Jenkins 로그에 출력하지 않는다.
- 기존 MySQL·파일 volume은 삭제하거나 초기화하지 않는다.
- AI Docker 이미지와 AI 테스트는 이번 계획 범위에 포함하지 않는다.

---

### Task 1: 브랜치별 배포 대상 resolver

**Files:**
- Create: `scripts/resolve-deploy-target.sh`
- Create: `scripts/tests/resolve-deploy-target-test.sh`

**Interfaces:**
- Consumes: `bash scripts/resolve-deploy-target.sh <develop|master>`
- Produces: `KEY=value` 형식의 `DEPLOY_ENV_FILE`, `DEPLOY_STATE_DIR`, `DEPLOY_HEALTHCHECK_URL`, `COMPOSE_PROJECT_NAME`, `DEPLOY_TARGET_LABEL`

- [ ] **Step 1: 실패하는 resolver 계약 테스트 작성**

`scripts/tests/resolve-deploy-target-test.sh`에 다음 동작을 검증한다.

```bash
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESOLVER="${REPO_ROOT}/scripts/resolve-deploy-target.sh"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_contains() {
  local output="$1" expected="$2"
  grep -Fxq "$expected" <<<"$output" || fail "missing: $expected"
}

develop="$(bash "$RESOLVER" develop)"
assert_contains "$develop" 'DEPLOY_ENV_FILE=/var/lib/jenkins/ajt-secrets/develop.env'
assert_contains "$develop" 'DEPLOY_STATE_DIR=/var/lib/jenkins/ajt-deploy/develop'
assert_contains "$develop" 'DEPLOY_HEALTHCHECK_URL=https://127.0.0.1:8090/api/v1/health'
assert_contains "$develop" 'COMPOSE_PROJECT_NAME=ajt-develop'
assert_contains "$develop" 'DEPLOY_TARGET_LABEL=develop 8090'

master="$(bash "$RESOLVER" master)"
assert_contains "$master" 'DEPLOY_ENV_FILE=/var/lib/jenkins/ajt-secrets/prod.env'
assert_contains "$master" 'DEPLOY_STATE_DIR=/var/lib/jenkins/ajt-deploy/prod'
assert_contains "$master" 'DEPLOY_HEALTHCHECK_URL=https://127.0.0.1/api/v1/health'
assert_contains "$master" 'COMPOSE_PROJECT_NAME=ajt-prod'
assert_contains "$master" 'DEPLOY_TARGET_LABEL=master 443'

if bash "$RESOLVER" feature/example >/dev/null 2>&1; then
  fail 'unsupported branch accepted'
fi

printf 'PASS: deploy target resolver\n'
```

- [ ] **Step 2: 테스트가 올바른 이유로 실패하는지 확인**

Run:

```bash
bash scripts/tests/resolve-deploy-target-test.sh
```

Expected: FAIL because `scripts/resolve-deploy-target.sh` does not exist.

- [ ] **Step 3: 최소 resolver 구현**

`scripts/resolve-deploy-target.sh`를 다음 계약으로 작성한다.

```bash
#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  develop)
    printf '%s\n' \
      'DEPLOY_ENV_FILE=/var/lib/jenkins/ajt-secrets/develop.env' \
      'DEPLOY_STATE_DIR=/var/lib/jenkins/ajt-deploy/develop' \
      'DEPLOY_HEALTHCHECK_URL=https://127.0.0.1:8090/api/v1/health' \
      'COMPOSE_PROJECT_NAME=ajt-develop' \
      'DEPLOY_TARGET_LABEL=develop 8090'
    ;;
  master)
    printf '%s\n' \
      'DEPLOY_ENV_FILE=/var/lib/jenkins/ajt-secrets/prod.env' \
      'DEPLOY_STATE_DIR=/var/lib/jenkins/ajt-deploy/prod' \
      'DEPLOY_HEALTHCHECK_URL=https://127.0.0.1/api/v1/health' \
      'COMPOSE_PROJECT_NAME=ajt-prod' \
      'DEPLOY_TARGET_LABEL=master 443'
    ;;
  *)
    printf 'ERROR: unsupported deploy branch: %s\n' "${1:-<empty>}" >&2
    exit 2
    ;;
esac
```

- [ ] **Step 4: resolver 테스트와 문법 검사**

Run:

```bash
bash -n scripts/resolve-deploy-target.sh
bash scripts/tests/resolve-deploy-target-test.sh
```

Expected: `PASS: deploy target resolver`.

- [ ] **Step 5: resolver 커밋**

```bash
git add scripts/resolve-deploy-target.sh scripts/tests/resolve-deploy-target-test.sh
git commit -m "ci: 브랜치별 Jenkins 배포 대상 분리"
```

### Task 2: 두 Job 사이 배포 잠금

**Files:**
- Modify: `scripts/deploy.sh`
- Modify: `scripts/tests/deploy-test.sh`

**Interfaces:**
- Consumes: 선택 환경변수 `DEPLOY_LOCK_FILE`, 기본값 `/var/lib/jenkins/ajt-deploy/deploy.lock`
- Produces: 같은 잠금 파일을 사용하는 배포 프로세스 중 한 개만 Compose를 변경하는 실행 보장

- [ ] **Step 1: 실패하는 잠금 호출 테스트 추가**

`scripts/tests/deploy-test.sh`의 fake bin에 `flock`을 추가한다.

```bash
FAKE_FLOCK_LOG="${TEST_ROOT}/flock.log"

cat > "${FAKE_BIN}/flock" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_FLOCK_LOG"
[[ "${1:-}" == "--exclusive" ]] || exit 64
shift 2
exec "$@"
EOF

chmod +x "${FAKE_BIN}/flock"
export FAKE_FLOCK_LOG
export DEPLOY_LOCK_FILE="${TEST_ROOT}/deploy.lock"
```

성공 배포 테스트 뒤 다음 검증을 추가한다.

```bash
grep -q -- "--exclusive ${DEPLOY_LOCK_FILE}" "$FAKE_FLOCK_LOG" \
  || fail "deploy lock was not acquired"
```

- [ ] **Step 2: 기존 구현에서 테스트 실패 확인**

Run:

```bash
bash scripts/tests/deploy-test.sh
```

Expected: FAIL with `deploy lock was not acquired`.

- [ ] **Step 3: 배포 스크립트 재진입 잠금 구현**

`scripts/deploy.sh`에서 배포 설정을 읽은 직후 다음 잠금을 적용한다.

```bash
DEPLOY_LOCK_FILE="${DEPLOY_LOCK_FILE:-/var/lib/jenkins/ajt-deploy/deploy.lock}"

if [[ "${DEPLOY_LOCK_HELD:-0}" != "1" ]]; then
  command -v flock >/dev/null 2>&1 || die_config "flock command not found"
  mkdir -p "$(dirname "$DEPLOY_LOCK_FILE")"
  exec env DEPLOY_LOCK_HELD=1 \
    flock --exclusive "$DEPLOY_LOCK_FILE" "$0" "$@"
fi
```

`die_config` 정의보다 잠금 코드가 먼저 실행되지 않도록 잠금 진입을 함수 정의 다음, 입력 검증 전에 둔다. 재진입한 프로세스는 `DEPLOY_LOCK_HELD=1`로 잠금 획득 단계를 건너뛴다.

- [ ] **Step 4: 배포 테스트 전체 실행**

Run:

```bash
bash -n scripts/deploy.sh
bash scripts/tests/deploy-test.sh
```

Expected: `PASS: deploy.sh behavior` and fake flock log contains the common lock path.

- [ ] **Step 5: 잠금 커밋**

```bash
git add scripts/deploy.sh scripts/tests/deploy-test.sh
git commit -m "ci: Jenkins 환경 간 배포 동시 실행 차단"
```

### Task 3: 공통 Jenkinsfile의 develop·master 배포

**Files:**
- Modify: `Jenkinsfile`

**Interfaces:**
- Consumes: Checkout 후 `BRANCH_NAME` 또는 `GIT_BRANCH`, Task 1의 resolver 출력
- Produces: 두 브랜치 모두 build/test/image build → 승인 → 해당 Compose 프로젝트 배포

- [ ] **Step 1: Jenkinsfile 검증 경계 확인**

Jenkinsfile은 실행 프레임워크가 해석하는 설정 파일이므로 소스 문자열을 `grep`하는 변화 감지 테스트를 만들지 않는다. 브랜치별 값과 잘못된 브랜치 거부는 Task 1의 실제 resolver 실행 테스트가 보호한다. Jenkins DSL 연결은 Task 5에서 실제 Jenkins feature Job으로 문법과 승인 화면을 검증한다.

- [ ] **Step 2: Checkout에서 실제 브랜치와 대상 설정**

기존 `IS_MASTER` 계산과 정적 prod 환경값을 제거한다. Checkout의 `script`에서 다음 순서로 값을 설정한다.

```groovy
env.IMAGE_TAG = sh(
    script: 'git rev-parse --short=12 HEAD',
    returnStdout: true
).trim()

env.DEPLOY_BRANCH = sh(
    script: '''
        branch="${BRANCH_NAME:-${GIT_BRANCH#origin/}}"
        printf '%s' "$branch"
    ''',
    returnStdout: true
).trim()

def targetOutput = withEnv(["RESOLVED_BRANCH=${env.DEPLOY_BRANCH}"]) {
    sh(
        script: 'bash scripts/resolve-deploy-target.sh "$RESOLVED_BRANCH"',
        returnStdout: true
    ).trim()
}

targetOutput.readLines().each { line ->
    def pair = line.split('=', 2)
    env[pair[0]] = pair[1]
}

env.DEPLOY_LOCK_FILE = '/var/lib/jenkins/ajt-deploy/deploy.lock'
```

resolver가 다른 브랜치를 오류로 거부하므로 잘못 구성된 일반 Pipeline Job은 이미지 빌드나 승인 단계까지 가지 않는다.

- [ ] **Step 3: 공통 승인과 배포 단계 구현**

기존 `Production Approval`과 `Production Deploy`의 master 조건을 제거하고 이름을 바꾼다.

```groovy
stage('Deployment Approval') {
    agent none
    options { timeout(time: 24, unit: 'HOURS') }
    input {
        message "${env.IMAGE_TAG} 이미지를 ${env.DEPLOY_TARGET_LABEL} 환경에 배포하시겠습니까?"
        ok '배포 승인'
        submitterParameter 'APPROVED_BY'
    }
    steps {
        echo "배포 승인자: ${env.APPROVED_BY}"
    }
}
```

`Deploy` 단계는 다시 Checkout하여 SHA 일치를 확인하고 다음 값을 `scripts/deploy.sh`에 전달한다.

```bash
DEPLOY_ENV_FILE="${DEPLOY_ENV_FILE}" \
DEPLOY_STATE_DIR="${DEPLOY_STATE_DIR}" \
DEPLOY_HEALTHCHECK_URL="${DEPLOY_HEALTHCHECK_URL}" \
DEPLOY_LOCK_FILE="${DEPLOY_LOCK_FILE}" \
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME}" \
BACKEND_IMAGE="${BACKEND_IMAGE}" \
FRONTEND_IMAGE="${FRONTEND_IMAGE}" \
bash scripts/deploy.sh "${IMAGE_TAG}"
```

- [ ] **Step 4: Jenkinsfile이 소비하는 실행 계약 검증**

Run:

```bash
bash scripts/tests/resolve-deploy-target-test.sh
bash scripts/tests/deploy-test.sh
```

Expected: both scripts print `PASS`. Jenkins DSL 자체는 Task 5의 실제 Jenkins 실행에서 검증한다.

- [ ] **Step 5: Jenkinsfile 커밋**

```bash
git add Jenkinsfile
git commit -m "ci: develop과 master 승인 배포 파이프라인 분리"
```

### Task 4: Runbook과 Jenkins Job 생성 절차

**Files:**
- Modify: `docs/infra/deployment-runbook.md`

**Interfaces:**
- Consumes: Task 1~3의 스크립트와 Jenkinsfile 계약
- Produces: Jenkins 운영자가 비밀값 노출 없이 두 Job을 재현할 수 있는 명령과 UI 설정

- [ ] **Step 1: 기존 master 단일 Job 설명 위치 확인**

Run:

```bash
rg -n 'master|Pipeline job|webhook|prod.env|ajt-deploy' docs/infra/deployment-runbook.md
```

Expected: master 단일 Job과 단일 상태 파일을 전제로 한 항목이 출력된다.

- [ ] **Step 2: 두 env·상태 디렉터리 준비 명령으로 교체**

Runbook에 다음 서버 명령을 기록한다.

```bash
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-secrets
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-deploy
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-deploy/develop
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-deploy/prod
sudo install -m 600 -o jenkins -g jenkins \
  /home/ubuntu/S15P11B106/.env.develop \
  /var/lib/jenkins/ajt-secrets/develop.env
```

기존 `prod.env`는 덮어쓰지 않고 권한과 필수값만 검사하도록 명시한다.

- [ ] **Step 3: 두 일반 Pipeline Job의 정확한 UI 값 기록**

Runbook에 다음 표를 추가한다.

```text
S15P11B106-develop: */develop, develop push만 트리거
S15P11B106-pipeline: */master, master push만 트리거
SCM URL: https://lab.ssafy.com/s15-webmobile2-sub1/S15P11B106.git
Credential: gitlab-token
Script Path: Jenkinsfile
```

도입 중에는 master Job을 Disable하고 develop 검증 후 master 병합 시 다시 Enable한다고 기록한다.

- [ ] **Step 4: 기존 배포 상태 인수 명령 기록**

develop 컨테이너에서 이미지 태그를 읽고 로컬 이미지가 존재할 때만 상태 파일을 쓰는 명령과, 기존 prod 상태 파일이 유효할 때 prod 하위 디렉터리로 복사하는 명령을 기록한다. 상태 파일에는 7~40자리 소문자 SHA만 허용하며 env 내용은 출력하지 않는다.

- [ ] **Step 5: 문서 일관성 검사와 커밋**

Run:

```bash
rg -n 'S15P11B106-develop|S15P11B106-pipeline|ajt-deploy/develop|ajt-deploy/prod|develop.env|prod.env' docs/infra/deployment-runbook.md
git diff --check
```

Expected: 두 Job과 두 환경의 설정이 모두 검색되고 whitespace 오류가 없다.

```bash
git add docs/infra/deployment-runbook.md
git commit -m "docs(infra): Jenkins develop과 master Job 운영 절차 추가"
```

### Task 5: 전체 검증과 서버 적용 준비

**Files:**
- Verify: `Jenkinsfile`
- Verify: `scripts/resolve-deploy-target.sh`
- Verify: `scripts/deploy.sh`
- Verify: `docs/infra/deployment-runbook.md`

**Interfaces:**
- Consumes: Task 1~4 전체 결과
- Produces: feature 브랜치 Jenkins 수동 빌드와 develop Job 설정에 사용할 검증된 변경 묶음

- [ ] **Step 1: 모든 인프라 테스트와 문법 검사**

Run:

```bash
bash -n scripts/resolve-deploy-target.sh
bash -n scripts/deploy.sh
bash scripts/tests/resolve-deploy-target-test.sh
bash scripts/tests/deploy-test.sh
bash scripts/tests/bootstrap-admin-test.sh
git diff --check origin/develop...HEAD
```

Expected: three test scripts print `PASS`, both Bash syntax checks and `git diff --check` exit 0.

- [ ] **Step 2: 변경 파일과 비밀값 유입 검사**

Run:

```bash
git status --short
git diff --stat origin/develop...HEAD
git grep -n -E '(ANTHROPIC_API_KEY|AI_INTERNAL_API_KEY|MYSQL_ROOT_PASSWORD)=[^<[:space:]]' HEAD -- ':!*.example' || true
```

Expected: 계획된 파일만 변경되고 실제 비밀값이 검색되지 않는다.

- [ ] **Step 3: 브랜치 push 후 임시 feature 빌드**

브랜치를 push하고 `S15P11B106-develop` Job을 처음 만들 때만 SCM Branch Specifier를 `*/codex/jenkins-dual-deploy`로 설정한다. 자동 GitLab 트리거는 아직 켜지 않는다. `Build Now`에서 Jenkinsfile 문법을 통과한 뒤 resolver가 지원하지 않는 feature 브랜치를 오류로 거부하는지 확인한다. feature 브랜치를 develop으로 위장해 테스트·이미지 빌드·배포하지 않는다.

- [ ] **Step 4: MR 병합 후 develop Job 고정**

변경이 develop에 병합되면 Job의 Branch Specifier를 `*/develop`으로 바꾸고 GitLab 브랜치 필터를 `develop`으로 제한한다. 수동 빌드를 실행해 승인 후 8090 배포와 volume 보존을 확인한 다음 develop Webhook을 활성화한다.

- [ ] **Step 5: master Job 유지 조건 확인**

`S15P11B106-pipeline`은 변경이 master에 병합될 때까지 Disable 상태로 둔다. master 병합 뒤 SCM `*/master`, 브랜치 필터 `master`, `prod.env`, `ajt-prod`, 443 대상이 승인 화면에 표시되는 것을 확인한 후 Enable한다.
