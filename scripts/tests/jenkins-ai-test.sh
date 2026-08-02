#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JENKINSFILE="$ROOT_DIR/Jenkinsfile"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

grep -q "AI_IMAGE = 'ajt-ai'" "$JENKINSFILE" \
  || fail 'AI image name is not declared'
grep -q "stage('AI Test')" "$JENKINSFILE" \
  || fail 'AI test stage is missing'
grep -q 'docker build --target test --tag "ajt-ai-test:${IMAGE_TAG}" ai' "$JENKINSFILE" \
  || fail 'AI test image is not built from the test target'
grep -q 'docker run --rm "ajt-ai-test:${IMAGE_TAG}"' "$JENKINSFILE" \
  || fail 'AI test image is not executed'
grep -q 'docker build --target runtime --tag "${AI_IMAGE}:${IMAGE_TAG}" ai' "$JENKINSFILE" \
  || fail 'AI runtime image is not built with the commit SHA'
grep -q 'AI_IMAGE="${AI_IMAGE}"' "$JENKINSFILE" \
  || fail 'AI image name is not passed to deploy.sh'

echo 'PASS: Jenkins AI pipeline wiring'
