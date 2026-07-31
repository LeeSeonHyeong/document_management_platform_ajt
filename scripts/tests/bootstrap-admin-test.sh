#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BOOTSTRAP_SCRIPT="${REPO_ROOT}/scripts/bootstrap-admin.sh"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT

FAKE_BIN="${TEST_ROOT}/bin"
FAKE_DOCKER_LOG="${TEST_ROOT}/docker.log"
FAKE_OPENSSL_LOG="${TEST_ROOT}/openssl.log"
ENV_FILE="${TEST_ROOT}/deploy.env"
COMPOSE_FILE="${TEST_ROOT}/compose.yml"
STATE_DIR="${TEST_ROOT}/state"
mkdir -p "$FAKE_BIN" "$STATE_DIR"
touch "$ENV_FILE"
printf 'services: {}\n' > "$COMPOSE_FILE"
printf '0123456789ab\n' > "${STATE_DIR}/current-image-tag"

cat > "${FAKE_BIN}/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
sql="$(cat)"
printf '%s\n---SQL---\n%s\n' "$*" "$sql" >> "$FAKE_DOCKER_LOG"

if [[ "$sql" == *"SELECT COUNT(*)"* ]]; then
  printf '%s\n' "${FAKE_MEMBER_EXISTS:-0}"
fi
EOF

cat > "${FAKE_BIN}/openssl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'called\n' >> "$FAKE_OPENSSL_LOG"
printf '0123456789abcdef0123456789abcdef\n'
EOF

cat > "${FAKE_BIN}/htpasswd" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf ':%s\n' '$2y$12$abcdefghijklmnopqrstuv12345678901234567890123456789'
EOF

chmod +x "${FAKE_BIN}/docker" "${FAKE_BIN}/openssl" "${FAKE_BIN}/htpasswd"

export PATH="${FAKE_BIN}:${PATH}"
export FAKE_DOCKER_LOG
export FAKE_OPENSSL_LOG
export DEPLOY_ENV_FILE="$ENV_FILE"
export DEPLOY_COMPOSE_FILE="$COMPOSE_FILE"
export DEPLOY_STATE_DIR="$STATE_DIR"
export COMPOSE_PROJECT_NAME=ajt-prod

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

test_rejects_invalid_email_before_database_call() {
  : > "$FAKE_DOCKER_LOG"

  set +e
  bash "$BOOTSTRAP_SCRIPT" "invalid'@example.com" >/dev/null 2>&1
  local status=$?
  set -e

  assert_equals "2" "$status" "잘못된 이메일의 종료 코드"
  [[ ! -s "$FAKE_DOCKER_LOG" ]] || fail "잘못된 이메일로 DB를 호출함"
}

test_existing_admin_does_not_generate_new_password() {
  : > "$FAKE_DOCKER_LOG"
  : > "$FAKE_OPENSSL_LOG"

  set +e
  FAKE_MEMBER_EXISTS=1 bash "$BOOTSTRAP_SCRIPT" admin@ajt.local >/dev/null 2>&1
  local status=$?
  set -e

  assert_equals "3" "$status" "이미 존재하는 관리자의 종료 코드"
  [[ ! -s "$FAKE_OPENSSL_LOG" ]] || fail "기존 관리자에게 새 비밀번호를 생성함"
}

test_creates_one_admin_with_random_password() {
  local output="${TEST_ROOT}/bootstrap.out"
  : > "$FAKE_DOCKER_LOG"
  : > "$FAKE_OPENSSL_LOG"

  FAKE_MEMBER_EXISTS=0 bash "$BOOTSTRAP_SCRIPT" admin@ajt.local > "$output"

  assert_equals "1" "$(grep -c '^called$' "$FAKE_OPENSSL_LOG")" "랜덤 비밀번호 생성 횟수"
  assert_equals "1" "$(grep -c '^이메일: admin@ajt.local$' "$output")" "이메일 출력 횟수"
  assert_equals "1" "$(grep -c '^초기 비밀번호: 0123456789abcdef0123456789abcdef$' "$output")" \
    "초기 비밀번호 출력 횟수"
  grep -q "INSERT INTO member" "$FAKE_DOCKER_LOG" || fail "member INSERT가 없음"
  tr -d '[:space:]' < "$FAKE_DOCKER_LOG" | grep -q "'ADMIN','APPROVED','ACTIVE'" \
    || fail "최고관리자 상태값이 잘못됨"
}

test_rejects_invalid_email_before_database_call
test_existing_admin_does_not_generate_new_password
test_creates_one_admin_with_random_password

printf 'PASS: bootstrap-admin.sh behavior\n'
