#!/usr/bin/env bash
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REQUESTED_ADMIN_EMAIL="${1:-}"
DEPLOY_ENV_FILE="${DEPLOY_ENV_FILE:-/var/lib/jenkins/ajt-secrets/prod.env}"
DEPLOY_STATE_DIR="${DEPLOY_STATE_DIR:-/var/lib/jenkins/ajt-deploy}"
DEPLOY_COMPOSE_FILE="${DEPLOY_COMPOSE_FILE:-${REPO_ROOT}/docker-compose.yml}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-ajt-prod}"
STATE_FILE="${DEPLOY_STATE_DIR}/current-image-tag"

die_config() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

is_commit_sha() {
  [[ "$1" =~ ^[0-9a-f]{7,40}$ ]]
}

[[ -f "$DEPLOY_ENV_FILE" ]] || die_config "deploy env file not found: $DEPLOY_ENV_FILE"
[[ -f "$DEPLOY_COMPOSE_FILE" ]] || die_config "compose file not found: $DEPLOY_COMPOSE_FILE"
[[ -f "$STATE_FILE" ]] || die_config "deployed image state not found: $STATE_FILE"

IMAGE_TAG="${IMAGE_TAG:-$(tr -d '[:space:]' < "$STATE_FILE")}"
is_commit_sha "$IMAGE_TAG" || die_config "invalid deployed image tag: $IMAGE_TAG"

command -v docker >/dev/null 2>&1 || die_config "docker command not found"
command -v openssl >/dev/null 2>&1 || die_config "openssl command not found"
command -v htpasswd >/dev/null 2>&1 || die_config "htpasswd command not found"

if [[ -n "$REQUESTED_ADMIN_EMAIL" \
      && ! "$REQUESTED_ADMIN_EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
  die_config "invalid admin email: $REQUESTED_ADMIN_EMAIL"
fi

compose_exec() {
  DEPLOY_ENV_FILE="$DEPLOY_ENV_FILE" IMAGE_TAG="$IMAGE_TAG" \
    docker compose \
      --project-name "$COMPOSE_PROJECT_NAME" \
      --env-file "$DEPLOY_ENV_FILE" \
      --file "$DEPLOY_COMPOSE_FILE" \
      exec -T "$@"
}

mysql_exec() {
  local sql="$1"

  printf '%s\n' "$sql" |
    compose_exec mysql \
      sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names --default-character-set=utf8mb4 ajt'
}

configured_admin_email="$(
  compose_exec backend sh -c 'printf "%s" "${SUPER_ADMIN_EMAIL:-}"' |
    tr -d '\r\n'
)"

if [[ ! "$configured_admin_email" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
  die_config "invalid or missing SUPER_ADMIN_EMAIL in deployed backend"
fi

ADMIN_EMAIL="${REQUESTED_ADMIN_EMAIL:-$configured_admin_email}"
if [[ ! "$ADMIN_EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
  die_config "invalid admin email: $ADMIN_EMAIL"
fi
if [[ "${ADMIN_EMAIL,,}" != "${configured_admin_email,,}" ]]; then
  die_config "admin email does not match deployed SUPER_ADMIN_EMAIL"
fi

existing_count="$(mysql_exec "SELECT COUNT(*) FROM member WHERE email = '${ADMIN_EMAIL}';" | tr -d '[:space:]')"
if [[ "$existing_count" != "0" ]]; then
  printf 'ERROR: member already exists: %s\n' "$ADMIN_EMAIL" >&2
  exit 3
fi

# 초기 비밀번호는 15자 영숫자(대소문자+숫자, 약 89비트)로 만든다. 백엔드 정책(8~100자) 안에 든다.
# pipefail 환경이라 파이프를 일찍 닫는 head -c 대신, 입력을 끝까지 읽는 cut 으로 15자를 취한다.
admin_password="$(openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | cut -c1-15)"
password_hash="$(htpasswd -bnBC 12 "" "$admin_password" | cut -d: -f2 | tr -d '\n')"
[[ -n "$password_hash" ]] || die_config "failed to generate BCrypt password hash"

mysql_exec "
START TRANSACTION;
INSERT INTO department (name)
VALUES ('최고관리자')
ON DUPLICATE KEY UPDATE department_id = LAST_INSERT_ID(department_id);
SET @department_id = LAST_INSERT_ID();
INSERT INTO member (
  department_id,
  email,
  name,
  password_hash,
  role,
  signup_status,
  account_status
)
VALUES (
  @department_id,
  '${ADMIN_EMAIL}',
  '최고관리자',
  '${password_hash}',
  'ADMIN',
  'APPROVED',
  'ACTIVE'
);
INSERT INTO wiki_scope (
  scope_key,
  visibility_type,
  department_refs,
  index_path,
  scope_version
)
VALUES (
  'ALL',
  'ALL',
  JSON_ARRAY(),
  'wiki/ALL/index.md',
  0
)
ON DUPLICATE KEY UPDATE scope_key = 'ALL';
INSERT INTO document_category (
  scope_key,
  name,
  description
)
VALUES (
  'ALL',
  '일반',
  '전체 공개 문서 기본 카테고리'
)
ON DUPLICATE KEY UPDATE document_category_id = LAST_INSERT_ID(document_category_id);
COMMIT;
" >/dev/null

printf '최고관리자 생성 완료\n'
printf '이메일: %s\n' "$ADMIN_EMAIL"
printf '초기 비밀번호: %s\n' "$admin_password"
printf '주의: 이 비밀번호는 다시 확인할 수 없습니다. 안전한 전달 수단에 즉시 저장하세요.\n'
