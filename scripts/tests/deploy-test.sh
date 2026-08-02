#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_SCRIPT="${REPO_ROOT}/scripts/deploy.sh"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT

FAKE_BIN="${TEST_ROOT}/bin"
FAKE_DOCKER_LOG="${TEST_ROOT}/docker.log"
FAKE_ACTIVE_TAG_FILE="${TEST_ROOT}/active-tag"
FAKE_FAIL_TAG_FILE="${TEST_ROOT}/fail-tag"
FAKE_BLOCK_TAG_FILE="${TEST_ROOT}/block-tag"
FAKE_BLOCK_ENTERED_FILE="${TEST_ROOT}/block-entered"
FAKE_BLOCK_RELEASE_FILE="${TEST_ROOT}/block-release"
FAKE_COMPOSE_ENTERED_DIR="${TEST_ROOT}/compose-entered"
ENV_FILE="${TEST_ROOT}/deploy.env"
COMPOSE_FILE="${TEST_ROOT}/compose.yml"
mkdir -p "$FAKE_BIN"
mkdir -p "$FAKE_COMPOSE_ENTERED_DIR"
touch "$ENV_FILE"
printf 'services: {}\n' > "$COMPOSE_FILE"

cat > "${FAKE_BIN}/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'IMAGE_TAG=%s DEPLOY_ENV_FILE=%s :: %s\n' \
  "${IMAGE_TAG:-}" "${DEPLOY_ENV_FILE:-}" "$*" >> "$FAKE_DOCKER_LOG"

if [[ "${1:-}" == "compose" && "$*" == *" up "* ]]; then
  : > "${FAKE_COMPOSE_ENTERED_DIR}/${IMAGE_TAG:?}"
  block_tag="$(cat "$FAKE_BLOCK_TAG_FILE" 2>/dev/null || true)"
  if [[ "${IMAGE_TAG:-}" == "$block_tag" ]]; then
    : > "$FAKE_BLOCK_ENTERED_FILE"
    while [[ ! -e "$FAKE_BLOCK_RELEASE_FILE" ]]; do
      /usr/bin/sleep 0.05
    done
  fi
  printf '%s\n' "${IMAGE_TAG:?}" > "$FAKE_ACTIVE_TAG_FILE"
fi
EOF

cat > "${FAKE_BIN}/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
active_tag="$(cat "$FAKE_ACTIVE_TAG_FILE")"
fail_tag="$(cat "$FAKE_FAIL_TAG_FILE" 2>/dev/null || true)"
[[ "$active_tag" != "$fail_tag" ]]
EOF

cat > "${FAKE_BIN}/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF

cat > "${FAKE_BIN}/flock" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

[[ "${1:-}" == "--exclusive" ]] || exit 64
lock_dir="${2:?}.test-lock"
shift 2

while ! mkdir "$lock_dir" 2>/dev/null; do
  /usr/bin/sleep 0.05
done
trap 'rmdir "$lock_dir"' EXIT
"$@"
EOF

chmod +x \
  "${FAKE_BIN}/docker" \
  "${FAKE_BIN}/curl" \
  "${FAKE_BIN}/sleep" \
  "${FAKE_BIN}/flock"

export PATH="${FAKE_BIN}:${PATH}"
export FAKE_DOCKER_LOG
export FAKE_ACTIVE_TAG_FILE
export FAKE_FAIL_TAG_FILE
export FAKE_BLOCK_TAG_FILE
export FAKE_BLOCK_ENTERED_FILE
export FAKE_BLOCK_RELEASE_FILE
export FAKE_COMPOSE_ENTERED_DIR
export DEPLOY_ENV_FILE="$ENV_FILE"
export DEPLOY_COMPOSE_FILE="$COMPOSE_FILE"
export DEPLOY_LOCK_FILE="${TEST_ROOT}/deploy.lock"
export DEPLOY_HEALTHCHECK_ATTEMPTS=3
export DEPLOY_HEALTHCHECK_INTERVAL=0

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_equals() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  [[ "$actual" == "$expected" ]] || fail "${message}: expected=${expected}, actual=${actual}"
}

test_rejects_non_sha_before_docker_call() {
  local state_dir="${TEST_ROOT}/invalid-state"
  : > "$FAKE_DOCKER_LOG"

  set +e
  DEPLOY_STATE_DIR="$state_dir" bash "$DEPLOY_SCRIPT" latest >/dev/null 2>&1
  local status=$?
  set -e

  assert_equals "2" "$status" "SHA가 아닌 태그의 종료 코드"
  [[ ! -s "$FAKE_DOCKER_LOG" ]] || fail "잘못된 태그에서 docker를 호출함"
}

test_records_successful_tag_atomically() {
  local state_dir="${TEST_ROOT}/success-state"
  local new_tag="0123456789ab"
  : > "$FAKE_DOCKER_LOG"
  : > "$FAKE_FAIL_TAG_FILE"

  DEPLOY_STATE_DIR="$state_dir" bash "$DEPLOY_SCRIPT" "$new_tag" >/dev/null

  assert_equals "$new_tag" "$(cat "${state_dir}/current-image-tag")" "성공 태그 기록"
  grep -q "IMAGE_TAG=${new_tag} .* up -d --no-build --remove-orphans" "$FAKE_DOCKER_LOG" \
    || fail "새 SHA로 compose up을 호출하지 않음"
  [[ ! -e "${state_dir}/current-image-tag.tmp" ]] || fail "임시 상태 파일이 남음"
}

test_rolls_back_and_keeps_previous_tag_when_health_fails() {
  local state_dir="${TEST_ROOT}/rollback-state"
  local previous_tag="111111111111"
  local failed_tag="222222222222"
  mkdir -p "$state_dir"
  printf '%s\n' "$previous_tag" > "${state_dir}/current-image-tag"
  printf '%s\n' "$failed_tag" > "$FAKE_FAIL_TAG_FILE"
  : > "$FAKE_DOCKER_LOG"

  set +e
  DEPLOY_STATE_DIR="$state_dir" bash "$DEPLOY_SCRIPT" "$failed_tag" >/dev/null 2>&1
  local status=$?
  set -e

  assert_equals "1" "$status" "헬스체크 실패 배포의 종료 코드"
  assert_equals "$previous_tag" "$(cat "${state_dir}/current-image-tag")" "롤백 후 상태 태그"
  assert_equals "$previous_tag" "$(cat "$FAKE_ACTIVE_TAG_FILE")" "롤백 후 실행 태그"
  grep -q "IMAGE_TAG=${failed_tag} .* up -d --no-build --remove-orphans" "$FAKE_DOCKER_LOG" \
    || fail "실패한 SHA 배포 호출이 없음"
  grep -q "IMAGE_TAG=${previous_tag} .* up -d --no-build --remove-orphans" "$FAKE_DOCKER_LOG" \
    || fail "이전 SHA 롤백 호출이 없음"
}

test_serializes_compose_changes_with_a_shared_lock() {
  local state_dir="${TEST_ROOT}/lock-state"
  local blocked_tag="333333333333"
  local waiting_tag="444444444444"
  local first_pid
  local second_pid
  local attempt
  local overlap=0

  rm -f \
    "$FAKE_BLOCK_ENTERED_FILE" \
    "$FAKE_BLOCK_RELEASE_FILE" \
    "$FAKE_FAIL_TAG_FILE" \
    "${FAKE_COMPOSE_ENTERED_DIR}/${blocked_tag}" \
    "${FAKE_COMPOSE_ENTERED_DIR}/${waiting_tag}"
  printf '%s\n' "$blocked_tag" > "$FAKE_BLOCK_TAG_FILE"
  : > "$FAKE_DOCKER_LOG"

  DEPLOY_STATE_DIR="$state_dir" \
    bash "$DEPLOY_SCRIPT" "$blocked_tag" >/dev/null 2>&1 &
  first_pid=$!

  for ((attempt = 1; attempt <= 100; attempt++)); do
    [[ -e "$FAKE_BLOCK_ENTERED_FILE" ]] && break
    /usr/bin/sleep 0.05
  done
  [[ -e "$FAKE_BLOCK_ENTERED_FILE" ]] || fail "first deployment did not enter compose"

  DEPLOY_STATE_DIR="$state_dir" \
    bash "$DEPLOY_SCRIPT" "$waiting_tag" >/dev/null 2>&1 &
  second_pid=$!

  for ((attempt = 1; attempt <= 100; attempt++)); do
    [[ -e "${FAKE_COMPOSE_ENTERED_DIR}/${waiting_tag}" ]] && break
    /usr/bin/sleep 0.05
  done
  if [[ -e "${FAKE_COMPOSE_ENTERED_DIR}/${waiting_tag}" ]]; then
    overlap=1
  fi

  : > "$FAKE_BLOCK_RELEASE_FILE"
  wait "$first_pid"
  wait "$second_pid"

  [[ "$overlap" == "0" ]] || fail "second deployment entered compose before the first released the lock"
  [[ -e "${FAKE_COMPOSE_ENTERED_DIR}/${waiting_tag}" ]] \
    || fail "second deployment did not run after the lock was released"
}

test_rejects_non_sha_before_docker_call
test_records_successful_tag_atomically
test_rolls_back_and_keeps_previous_tag_when_health_fails
test_serializes_compose_changes_with_a_shared_lock

printf 'PASS: deploy.sh behavior\n'
