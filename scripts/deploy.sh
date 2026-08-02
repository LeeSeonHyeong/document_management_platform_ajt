#!/usr/bin/env bash
set -Eeuo pipefail

IMAGE_TAG="${1:-}"
DEPLOY_ENV_FILE="${DEPLOY_ENV_FILE:-/var/lib/jenkins/ajt-secrets/prod.env}"
DEPLOY_STATE_DIR="${DEPLOY_STATE_DIR:-/var/lib/jenkins/ajt-deploy}"
DEPLOY_HEALTHCHECK_URL="${DEPLOY_HEALTHCHECK_URL:-https://127.0.0.1/api/v1/health}"
DEPLOY_HEALTHCHECK_ATTEMPTS="${DEPLOY_HEALTHCHECK_ATTEMPTS:-30}"
DEPLOY_HEALTHCHECK_INTERVAL="${DEPLOY_HEALTHCHECK_INTERVAL:-5}"
DEPLOY_COMPOSE_FILE="${DEPLOY_COMPOSE_FILE:-docker-compose.yml}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-ajt-prod}"
BACKEND_IMAGE="${BACKEND_IMAGE:-ajt-backend}"
FRONTEND_IMAGE="${FRONTEND_IMAGE:-ajt-frontend}"
AI_IMAGE="${AI_IMAGE:-ajt-ai}"
DEPLOY_LOCK_FILE="${DEPLOY_LOCK_FILE:-/var/lib/jenkins/ajt-deploy/deploy.lock}"
STATE_FILE="${DEPLOY_STATE_DIR}/current-image-tag"

usage() {
  printf 'Usage: %s <git-commit-sha>\n' "$0" >&2
}

die_config() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

is_commit_sha() {
  [[ "$1" =~ ^[0-9a-f]{7,40}$ ]]
}

acquire_deploy_lock() {
  if [[ "${DEPLOY_LOCK_HELD:-0}" == "1" ]]; then
    return 0
  fi

  mkdir -p "$(dirname "$DEPLOY_LOCK_FILE")"
  exec env DEPLOY_LOCK_HELD=1 \
    flock --exclusive "$DEPLOY_LOCK_FILE" "$0" "$@"
}

compose_for_tag() {
  local tag="$1"
  shift

  DEPLOY_ENV_FILE="$DEPLOY_ENV_FILE" IMAGE_TAG="$tag" \
    BACKEND_IMAGE="$BACKEND_IMAGE" FRONTEND_IMAGE="$FRONTEND_IMAGE" AI_IMAGE="$AI_IMAGE" \
    docker compose \
      --project-name "$COMPOSE_PROJECT_NAME" \
      --env-file "$DEPLOY_ENV_FILE" \
      --file "$DEPLOY_COMPOSE_FILE" \
      "$@"
}

wait_until_healthy() {
  local attempt

  for ((attempt = 1; attempt <= DEPLOY_HEALTHCHECK_ATTEMPTS; attempt++)); do
    if curl --insecure --fail --silent --show-error --max-time 10 "$DEPLOY_HEALTHCHECK_URL" >/dev/null; then
      return 0
    fi

    if ((attempt < DEPLOY_HEALTHCHECK_ATTEMPTS)); then
      sleep "$DEPLOY_HEALTHCHECK_INTERVAL"
    fi
  done

  return 1
}

if [[ -z "$IMAGE_TAG" ]]; then
  usage
  exit 2
fi

is_commit_sha "$IMAGE_TAG" || die_config "image tag must be a 7-40 character lowercase Git commit SHA"
[[ -f "$DEPLOY_ENV_FILE" ]] || die_config "deploy env file not found: $DEPLOY_ENV_FILE"
[[ -f "$DEPLOY_COMPOSE_FILE" ]] || die_config "compose file not found: $DEPLOY_COMPOSE_FILE"
[[ "$DEPLOY_HEALTHCHECK_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] \
  || die_config "DEPLOY_HEALTHCHECK_ATTEMPTS must be a positive integer"
[[ "$DEPLOY_HEALTHCHECK_INTERVAL" =~ ^[0-9]+$ ]] \
  || die_config "DEPLOY_HEALTHCHECK_INTERVAL must be a non-negative integer"

command -v docker >/dev/null 2>&1 || die_config "docker command not found"
command -v curl >/dev/null 2>&1 || die_config "curl command not found"
command -v flock >/dev/null 2>&1 || die_config "flock command not found"

acquire_deploy_lock "$IMAGE_TAG"

mkdir -p "$DEPLOY_STATE_DIR"

previous_tag=""
if [[ -f "$STATE_FILE" ]]; then
  previous_tag="$(tr -d '[:space:]' < "$STATE_FILE")"
  is_commit_sha "$previous_tag" || die_config "invalid previous image tag in $STATE_FILE"
fi

for image in \
  "${BACKEND_IMAGE}:${IMAGE_TAG}" \
  "${FRONTEND_IMAGE}:${IMAGE_TAG}" \
  "${AI_IMAGE}:${IMAGE_TAG}"
do
  docker image inspect "$image" >/dev/null \
    || die_config "deploy image not found: $image"
done

printf 'Deploying image tag %s to Compose project %s\n' "$IMAGE_TAG" "$COMPOSE_PROJECT_NAME"
if compose_for_tag "$IMAGE_TAG" up -d --no-build --remove-orphans && wait_until_healthy; then
  printf '%s\n' "$IMAGE_TAG" > "${STATE_FILE}.tmp"
  mv "${STATE_FILE}.tmp" "$STATE_FILE"
  printf 'Deployment succeeded: %s\n' "$IMAGE_TAG"
  exit 0
fi

printf 'Deployment healthcheck failed: %s\n' "$IMAGE_TAG" >&2

if [[ -z "$previous_tag" ]]; then
  printf 'No previous image tag exists; stopping the failed first deployment\n' >&2
  compose_for_tag "$IMAGE_TAG" down --remove-orphans || true
  exit 1
fi

printf 'Rolling back to previous image tag: %s\n' "$previous_tag" >&2
for image in \
  "${BACKEND_IMAGE}:${previous_tag}" \
  "${FRONTEND_IMAGE}:${previous_tag}" \
  "${AI_IMAGE}:${previous_tag}"
do
  if ! docker image inspect "$image" >/dev/null; then
    printf 'ERROR: rollback image not found: %s\n' "$image" >&2
    exit 1
  fi
done

if ! compose_for_tag "$previous_tag" up -d --no-build --remove-orphans; then
  printf 'ERROR: rollback compose update failed: %s\n' "$previous_tag" >&2
  exit 1
fi

if ! wait_until_healthy; then
  printf 'ERROR: rollback healthcheck failed: %s\n' "$previous_tag" >&2
  exit 1
fi

printf 'Rollback succeeded: %s\n' "$previous_tag" >&2
exit 1
