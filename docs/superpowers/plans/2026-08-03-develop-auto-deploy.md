# develop Automatic Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make successful `develop` Jenkins builds deploy automatically to 8090 while preserving the manual approval gate for `master` deployments to 443.

**Architecture:** `scripts/resolve-deploy-target.sh` remains the single source of branch-specific deployment policy and emits `DEPLOY_APPROVAL_REQUIRED`. `Jenkinsfile` validates and consumes that value, using Declarative Pipeline `when { beforeInput true }` so only master enters the existing approval input. The develop Job's one-time `Poll SCM` UI configuration provides automatic triggering and is documented in the runbook.

**Tech Stack:** Jenkins Declarative Pipeline, Groovy, Bash, Docker Compose, GitLab SCM, shell-based contract tests

## Global Constraints

- `develop` must deploy to `ajt-develop` on port 8090 without human approval only after all existing tests and Docker image builds succeed.
- `master` must continue to require human approval before deploying to `ajt-prod` on port 443.
- Build and deploy commit SHA equality checks, deploy locking, healthcheck, and rollback behavior must remain unchanged.
- DB volume deletion, database reset, super-admin creation, and `ALL` Wiki bootstrap must never run as part of ordinary automatic deployment.
- Unsupported branches and invalid approval policy values must fail before deployment.
- Work only in `feature/S15P11B106-162-develop-auto-deploy`; do not modify the user's dirty primary checkout.

---

### Task 1: Define and consume branch-specific approval policy

**Files:**
- Modify: `scripts/tests/resolve-deploy-target-test.sh`
- Modify: `scripts/tests/jenkins-ai-test.sh`
- Modify: `scripts/resolve-deploy-target.sh`
- Modify: `Jenkinsfile`

**Interfaces:**
- Consumes: normalized SCM branch values already accepted by `scripts/resolve-deploy-target.sh`
- Produces: `DEPLOY_APPROVAL_REQUIRED=false` for develop variants and `DEPLOY_APPROVAL_REQUIRED=true` for master variants
- Produces: validated Jenkins environment variable `env.DEPLOY_APPROVAL_REQUIRED`

- [ ] **Step 1: Add failing resolver policy assertions**

Add these assertions to `scripts/tests/resolve-deploy-target-test.sh` beside the existing environment assertions:

```bash
assert_contains "$develop" 'DEPLOY_APPROVAL_REQUIRED=false'
assert_contains "$master" 'DEPLOY_APPROVAL_REQUIRED=true'
```

The existing equality checks for `origin/develop` and `*/master` must continue to prove that normalized variants return the same complete policy.

- [ ] **Step 2: Add failing Jenkins approval wiring assertions**

Extend the explicit environment assignment loop in `scripts/tests/jenkins-ai-test.sh` to include `DEPLOY_APPROVAL_REQUIRED`:

```bash
for key in DEPLOY_ENV_FILE DEPLOY_STATE_DIR DEPLOY_HEALTHCHECK_URL COMPOSE_PROJECT_NAME DEPLOY_TARGET_LABEL DEPLOY_APPROVAL_REQUIRED; do
```

Add assertions that require validation, pre-input condition ordering, and the existing manual input:

```bash
grep -Fq "env.DEPLOY_APPROVAL_REQUIRED != 'true' && env.DEPLOY_APPROVAL_REQUIRED != 'false'" "$JENKINSFILE" \
  || fail 'deploy approval policy is not validated'
grep -Fq 'beforeInput true' "$JENKINSFILE" \
  || fail 'approval condition is not evaluated before input'
grep -Fq "env.DEPLOY_APPROVAL_REQUIRED == 'true'" "$JENKINSFILE" \
  || fail 'manual approval is not limited by deployment policy'
grep -q '^            input {' "$JENKINSFILE" \
  || fail 'master manual approval input is missing'
```

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```bash
bash scripts/tests/resolve-deploy-target-test.sh
bash scripts/tests/jenkins-ai-test.sh
```

Expected: both tests fail because `DEPLOY_APPROVAL_REQUIRED` is not emitted or consumed yet. The failures must name the missing approval policy rather than a syntax or path error.

- [ ] **Step 4: Emit approval policy from the resolver**

Add one line to each resolver output block in `scripts/resolve-deploy-target.sh`:

```bash
# develop branch output
'DEPLOY_APPROVAL_REQUIRED=false'

# master branch output
'DEPLOY_APPROVAL_REQUIRED=true'
```

Keep every existing target, environment file, state directory, healthcheck URL, project name, and label unchanged.

- [ ] **Step 5: Capture and validate policy in Jenkinsfile**

Add an explicit resolver output case beside the existing assignments:

```groovy
case 'DEPLOY_APPROVAL_REQUIRED':
    env.DEPLOY_APPROVAL_REQUIRED = pair[1]
    break
```

After the resolver output loop, reject missing or unsupported values:

```groovy
if (env.DEPLOY_APPROVAL_REQUIRED != 'true' && env.DEPLOY_APPROVAL_REQUIRED != 'false') {
    error("지원하지 않는 배포 승인 정책입니다: ${env.DEPLOY_APPROVAL_REQUIRED ?: '<empty>'}")
}
```

- [ ] **Step 6: Skip approval input only for develop**

Keep the existing stage-level approval input and add a `when` block before `options`:

```groovy
stage('Deployment Approval') {
    agent none

    when {
        beforeInput true
        expression {
            env.DEPLOY_APPROVAL_REQUIRED == 'true'
        }
    }

    options {
        timeout(time: 24, unit: 'HOURS')
    }

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

Do not add an alternate input or approval API call for develop. A false policy skips the stage and proceeds to the existing `Deploy` stage.

- [ ] **Step 7: Run focused tests and verify GREEN**

Run:

```bash
bash -n scripts/resolve-deploy-target.sh
bash scripts/tests/resolve-deploy-target-test.sh
bash scripts/tests/jenkins-ai-test.sh
```

Expected:

```text
PASS: deploy target resolver
PASS: Jenkins AI pipeline wiring
```

- [ ] **Step 8: Commit the tested behavior**

```bash
git add Jenkinsfile \
  scripts/resolve-deploy-target.sh \
  scripts/tests/resolve-deploy-target-test.sh \
  scripts/tests/jenkins-ai-test.sh
git commit -m "feat(infra): develop 자동 배포 승인 분기 추가 [S15P11B106-162]"
```

### Task 2: Document the one-time develop Job trigger configuration

**Files:**
- Modify: `docs/infra/deployment-runbook.md`
- Modify: `docs/superpowers/specs/2026-08-03-develop-auto-deploy-design.md`

**Interfaces:**
- Consumes: Jenkins Job `S15P11B106-develop` configured with branch specifier `*/develop`
- Produces: one-time Poll SCM schedule `H/2 * * * *` for develop only
- Preserves: existing master Job trigger configuration and master manual approval

- [ ] **Step 1: Update the Jenkins Job setup section**

Document the develop Job trigger exactly as:

```text
develop Job Build Trigger
  Poll SCM: enabled
  Schedule: H/2 * * * *
  GitHub hook trigger for GITScm polling: disabled
```

State that this polls only the configured `*/develop` branch and normally starts a build within approximately two minutes of a merge. Remove instructions that require a GitLab webhook for the develop Job. Do not alter the master Job's existing trigger configuration.

- [ ] **Step 2: Document branch-specific approval behavior**

Add an operational flow matching the implementation:

```text
develop merge -> automatic checkout/test/build -> approval stage skipped -> 8090 deploy
master Job start -> checkout/test/build -> manual approval -> 443 deploy
```

Explicitly state that automatic develop deployment does not initialize or delete data.

- [ ] **Step 3: Align the approved design document with Jenkins syntax**

Ensure `docs/superpowers/specs/2026-08-03-develop-auto-deploy-design.md` states that `beforeInput true` evaluates the approval policy before the stage-level input and that the master Job's trigger configuration is unchanged.

- [ ] **Step 4: Verify documentation integrity**

Run:

```bash
rg -n "H/2|DEPLOY_APPROVAL_REQUIRED|beforeInput|develop.*자동|master.*승인" \
  docs/infra/deployment-runbook.md \
  docs/superpowers/specs/2026-08-03-develop-auto-deploy-design.md
git diff --check
```

Expected: the runbook contains the one-time develop Poll SCM setting, the spec matches the implemented condition ordering, and `git diff --check` exits successfully.

- [ ] **Step 5: Commit the runbook update**

```bash
git add docs/infra/deployment-runbook.md \
  docs/superpowers/specs/2026-08-03-develop-auto-deploy-design.md
git commit -m "docs(infra): develop 자동 배포 운영 절차 추가 [S15P11B106-162]"
```

### Task 3: Run full infrastructure regression verification

**Files:**
- Verify: `Jenkinsfile`
- Verify: `scripts/resolve-deploy-target.sh`
- Verify: `scripts/tests/*.sh`
- Verify: `docs/infra/deployment-runbook.md`

**Interfaces:**
- Consumes: completed Tasks 1 and 2
- Produces: merge-ready verification evidence for the branch

- [ ] **Step 1: Run all shell contract tests relevant to deployment**

```bash
bash scripts/tests/jenkins-ai-test.sh
bash scripts/tests/resolve-deploy-target-test.sh
bash scripts/tests/deploy-test.sh
bash scripts/tests/compose-ai-test.sh
bash scripts/tests/bootstrap-admin-test.sh
```

Expected: every runnable test prints `PASS`. If Docker is unavailable locally, record `compose-ai-test.sh` as not runnable and rely on Jenkins for that integration check; do not report it as passed.

- [ ] **Step 2: Run shell syntax and diff checks**

```bash
bash -n scripts/resolve-deploy-target.sh
bash -n scripts/deploy.sh
bash -n scripts/bootstrap-admin.sh
git diff --check origin/develop...HEAD
git status --short
```

Expected: shell syntax checks and diff check exit zero. `git status --short` is empty after the planned commits.

- [ ] **Step 3: Review the final branch diff**

```bash
git log --oneline origin/develop..HEAD
git diff --stat origin/develop...HEAD
git diff origin/develop...HEAD -- \
  Jenkinsfile \
  scripts/resolve-deploy-target.sh \
  scripts/tests/resolve-deploy-target-test.sh \
  scripts/tests/jenkins-ai-test.sh \
  docs/infra/deployment-runbook.md
```

Confirm that develop skips only the approval stage, master retains the approval input, and no DB/bootstrap/deploy rollback logic changed.

- [ ] **Step 4: Hand off for push and Merge Request**

Report the exact verification results and ask whether to push `feature/S15P11B106-162-develop-auto-deploy` and create a Merge Request targeting `develop`. Do not push or create the MR without that explicit choice.
