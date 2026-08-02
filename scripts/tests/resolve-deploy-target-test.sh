#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESOLVER="${REPO_ROOT}/scripts/resolve-deploy-target.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local output="$1"
  local expected="$2"
  grep -Fxq "$expected" <<<"$output" || fail "missing: $expected"
}

develop="$(bash "$RESOLVER" develop)"
assert_contains "$develop" 'DEPLOY_ENV_FILE=/var/lib/jenkins/ajt-secrets/develop.env'
assert_contains "$develop" 'DEPLOY_STATE_DIR=/var/lib/jenkins/ajt-deploy/develop'
assert_contains "$develop" 'DEPLOY_HEALTHCHECK_URL=https://127.0.0.1:8090/api/v1/health'
assert_contains "$develop" 'COMPOSE_PROJECT_NAME=ajt-develop'
assert_contains "$develop" 'DEPLOY_TARGET_LABEL=develop 8090'

origin_develop="$(bash "$RESOLVER" origin/develop)"
[[ "$origin_develop" == "$develop" ]] || fail 'origin/develop was not normalized to develop'

master="$(bash "$RESOLVER" master)"
assert_contains "$master" 'DEPLOY_ENV_FILE=/var/lib/jenkins/ajt-secrets/prod.env'
assert_contains "$master" 'DEPLOY_STATE_DIR=/var/lib/jenkins/ajt-deploy/prod'
assert_contains "$master" 'DEPLOY_HEALTHCHECK_URL=https://127.0.0.1/api/v1/health'
assert_contains "$master" 'COMPOSE_PROJECT_NAME=ajt-prod'
assert_contains "$master" 'DEPLOY_TARGET_LABEL=master 443'

wildcard_master="$(bash "$RESOLVER" '*/master')"
[[ "$wildcard_master" == "$master" ]] || fail '*/master was not normalized to master'

if bash "$RESOLVER" feature/example >/dev/null 2>&1; then
  fail 'unsupported branch accepted'
fi

if bash "$RESOLVER" feature/develop >/dev/null 2>&1; then
  fail 'feature/develop was incorrectly accepted as develop'
fi

if bash "$RESOLVER" feature/master >/dev/null 2>&1; then
  fail 'feature/master was incorrectly accepted as master'
fi

printf 'PASS: deploy target resolver\n'
