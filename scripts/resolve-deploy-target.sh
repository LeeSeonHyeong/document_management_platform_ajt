#!/usr/bin/env bash
set -euo pipefail

branch="${1:-}"

case "$branch" in
  develop|origin/develop|\*/develop)
    printf '%s\n' \
      'DEPLOY_ENV_FILE=/var/lib/jenkins/ajt-secrets/develop.env' \
      'DEPLOY_STATE_DIR=/var/lib/jenkins/ajt-deploy/develop' \
      'DEPLOY_HEALTHCHECK_URL=https://127.0.0.1:8090/api/v1/health' \
      'COMPOSE_PROJECT_NAME=ajt-develop' \
      'DEPLOY_TARGET_LABEL=develop 8090'
    ;;
  master|origin/master|\*/master)
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
