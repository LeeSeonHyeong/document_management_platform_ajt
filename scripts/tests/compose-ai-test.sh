#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

ENV_FILE="$TMP_DIR/deploy.env"
cat > "$ENV_FILE" <<'EOF'
SPRING_PROFILES_ACTIVE=prod
FRONTEND_PORT=8090
MYSQL_VOLUME_NAME=ajt-compose-test-mysql
AJT_FILES_VOLUME_NAME=ajt-compose-test-files
AJT_ACCESS_TOKEN_SECRET=test-access-secret-long-enough
AJT_PASSWORD_RESET_SECRET=test-reset-secret-long-enough
AJT_AUTH_COOKIE_SECURE=true
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/ajt
SPRING_DATASOURCE_USERNAME=ajt
SPRING_DATASOURCE_PASSWORD=test-db-password
MYSQL_ROOT_PASSWORD=test-root-password
MYSQL_DATABASE=ajt
MYSQL_USER=ajt
MYSQL_PASSWORD=test-db-password
AI_INTERNAL_API_KEY=test-internal-key
ANTHROPIC_API_KEY=test-gms-key
DOCUMENT_STORAGE_ROOT=/data/ajt/documents
SCHEDULE_SOURCE_STORAGE_ROOT=/data/ajt/schedule-sources
INQUIRY_STORAGE_ROOT=/data/ajt/inquiries
EOF

if grep -q '^[[:space:]]*env_file:' "$ROOT_DIR/docker-compose.yml"; then
  echo 'ERROR: deployment env file must not be injected wholesale into containers' >&2
  exit 1
fi

if [[ "$(grep -c 'ANTHROPIC_API_KEY:' "$ROOT_DIR/docker-compose.yml")" != "1" ]]; then
  echo 'ERROR: GMS API key must be injected only into the AI service' >&2
  exit 1
fi

if ! grep -q 'SCHEDULE_EXTRACTOR_MODEL: "claude-haiku-4-5-20251001"' \
  "$ROOT_DIR/docker-compose.yml"; then
  echo 'ERROR: schedule extractor must use claude-haiku-4-5-20251001' >&2
  exit 1
fi

rendered="$TMP_DIR/compose.yml"
DEPLOY_ENV_FILE="$ENV_FILE" IMAGE_TAG=123456789abc \
  docker compose --env-file "$ENV_FILE" --file "$ROOT_DIR/docker-compose.yml" config \
  > "$rendered"

grep -q '^  ai:' "$rendered"
grep -q 'image: ajt-ai:123456789abc' "$rendered"
grep -q 'AI_BASE_URL: http://ai:8000' "$rendered"
grep -q 'BACKEND_BASE_URL: http://backend:8080' "$rendered"
grep -q 'SCHEDULE_EXTRACTOR_PROVIDER: anthropic' "$rendered"
grep -q 'SCHEDULE_EXTRACTOR_MODEL: claude-haiku-4-5-20251001' "$rendered"
grep -q 'ANTHROPIC_BASE_URL: https://gms.ssafy.io/gmsapi/api.anthropic.com' "$rendered"
grep -q 'condition: service_healthy' "$rendered"

if grep -q '11434\|SCHEDULE_EXTRACTOR_PROVIDER: ollama' "$rendered"; then
  echo 'ERROR: rendered production Compose still contains Ollama configuration' >&2
  exit 1
fi

if awk '/^  ai:/{in_ai=1; next} in_ai && /^  [a-zA-Z0-9_-]+:/{in_ai=0} in_ai && /published:/{found=1} END{exit !found}' "$rendered"; then
  echo 'ERROR: AI service must not publish a host port' >&2
  exit 1
fi

missing_key_env="$TMP_DIR/missing-gms-key.env"
grep -v '^ANTHROPIC_API_KEY=' "$ENV_FILE" > "$missing_key_env"
if DEPLOY_ENV_FILE="$missing_key_env" IMAGE_TAG=123456789abc \
  docker compose --env-file "$missing_key_env" --file "$ROOT_DIR/docker-compose.yml" config \
  >/dev/null 2>&1; then
  echo 'ERROR: Compose accepted a deployment without ANTHROPIC_API_KEY' >&2
  exit 1
fi

missing_internal_key_env="$TMP_DIR/missing-internal-key.env"
grep -v '^AI_INTERNAL_API_KEY=' "$ENV_FILE" > "$missing_internal_key_env"
if DEPLOY_ENV_FILE="$missing_internal_key_env" IMAGE_TAG=123456789abc \
  docker compose --env-file "$missing_internal_key_env" --file "$ROOT_DIR/docker-compose.yml" config \
  >/dev/null 2>&1; then
  echo 'ERROR: Compose accepted a deployment without AI_INTERNAL_API_KEY' >&2
  exit 1
fi

echo 'compose AI integration test passed'
