#!/usr/bin/env bash
# 하네스 vault를 지우고 다시 심는다 (반복 실험용 초기화). 인자는 seed.py 그대로 전달.
#   ./tools/harness/reset.sh 08-compensation.md
#   ./tools/harness/reset.sh --all
set -euo pipefail
HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AI_ROOT="$(cd "$HARNESS_DIR/../.." && pwd)"

rm -rf "$HARNESS_DIR/vault"
cd "$AI_ROOT"
uv run python tools/harness/seed.py "$@"
