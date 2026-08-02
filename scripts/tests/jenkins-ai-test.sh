#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JENKINSFILE="$ROOT_DIR/Jenkinsfile"
AI_DOCKERFILE="$ROOT_DIR/ai/Dockerfile"
AI_DOCKERIGNORE="$ROOT_DIR/ai/.dockerignore"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

grep -q 'def scmVars = checkout scm' "$JENKINSFILE" \
  || fail 'checkout result is not captured for branch detection'
grep -q 'scmVars.GIT_BRANCH' "$JENKINSFILE" \
  || fail 'checkout-provided GIT_BRANCH is not used'
if grep -Fq 'env[pair[0]]' "$JENKINSFILE"; then
  fail 'dynamic env assignment is blocked by the Jenkins Groovy sandbox'
fi
for key in DEPLOY_ENV_FILE DEPLOY_STATE_DIR DEPLOY_HEALTHCHECK_URL COMPOSE_PROJECT_NAME DEPLOY_TARGET_LABEL; do
  grep -q "env\.${key} = pair\[1\]" "$JENKINSFILE" \
    || fail "explicit Jenkins env assignment is missing: ${key}"
done

grep -q "AI_IMAGE = 'ajt-ai'" "$JENKINSFILE" \
  || fail 'AI image name is not declared'
grep -q "stage('AI Test')" "$JENKINSFILE" \
  || fail 'AI test stage is missing'
grep -q 'docker build --target test --tag "ajt-ai-test:${IMAGE_TAG}" ai' "$JENKINSFILE" \
  || fail 'AI test image is not built from the test target'
grep -Fq 'docker run --rm --volume "${WORKSPACE}/docs:/docs:ro" "ajt-ai-test:${IMAGE_TAG}"' \
  "$JENKINSFILE" \
  || fail 'AI test image is not executed with read-only contract docs'
grep -q 'docker build --target runtime --tag "${AI_IMAGE}:${IMAGE_TAG}" ai' "$JENKINSFILE" \
  || fail 'AI runtime image is not built with the commit SHA'
grep -q 'AI_IMAGE="${AI_IMAGE}"' "$JENKINSFILE" \
  || fail 'AI image name is not passed to deploy.sh'

if grep -q '^experiments/$' "$AI_DOCKERIGNORE"; then
  fail 'AI Docker build context excludes experiments required by tests'
fi

if ! awk '
  /^FROM base AS test$/ { stage = "test"; next }
  /^FROM / { stage = "other"; next }
  stage == "test" && $0 == "COPY experiments ./experiments" { found = 1 }
  END { exit !found }
' "$AI_DOCKERFILE"; then
  fail 'AI test image does not copy experiments'
fi

if [[ "$(grep -c '^COPY experiments ./experiments$' "$AI_DOCKERFILE")" != "1" ]]; then
  fail 'experiments must be copied exactly once in the AI test stage'
fi

echo 'PASS: Jenkins AI pipeline wiring'
