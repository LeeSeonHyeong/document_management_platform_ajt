# AI Docker·Jenkins Integrated Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package the FastAPI AI server as a GMS-only container and deploy it with backend and frontend under one Git SHA through the develop/master approval pipelines.

**Architecture:** Add an internal-only `ai` Compose service whose FastAPI healthcheck gates backend startup. Jenkins tests and builds `ajt-ai:${IMAGE_TAG}` alongside the existing images, while `deploy.sh` treats all three application images as one rollback unit.

**Tech Stack:** Docker multi-stage builds, Docker Compose v5, Jenkins Declarative Pipeline, Bash, Python 3.12, uv, FastAPI, pytest.

## Global Constraints

- Production uses `AI_RUNTIME=deepagents` and `SCHEDULE_EXTRACTOR_PROVIDER=anthropic`.
- Production must not resolve or call Ollama or `localhost:11434`.
- `ANTHROPIC_BASE_URL` is `https://gms.ssafy.io/gmsapi/api.anthropic.com`.
- AI port 8000 is Compose-internal and must not be published on the host.
- `AI_INTERNAL_API_KEY` is injected into the AI container as `INTERNAL_API_KEY`; no second value is maintained.
- `ANTHROPIC_API_KEY` and `AI_INTERNAL_API_KEY` remain empty in examples and are supplied only by Jenkins-owned server env files.
- Backend, frontend, and AI images use the same 7–40 character lowercase Git SHA tag.
- Develop and master share `/var/lib/jenkins/ajt-deploy/deploy.lock` and retain manual approval.
- Local Ollama support remains in AI source code; only the production Compose path forbids it.

---

### Task 1: Public AI Health Endpoint

**Files:**
- Create: `ai/tests/api/test_health.py`
- Modify: `ai/src/wiki_api/app.py`

**Interfaces:**
- Consumes: `wiki_api.app.create_app(api_key, backend_base_url)`
- Produces: unauthenticated `GET /health -> 200 {"status":"UP"}`

- [ ] **Step 1: Write the failing route test**

```python
from fastapi.testclient import TestClient

from wiki_api.app import create_app


def test_health_is_public_and_returns_only_service_status():
    client = TestClient(create_app(api_key="internal-secret"))

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}
```

This catches removal of the operational route, accidental internal-key protection, and accidental exposure of configuration details.

- [ ] **Step 2: Run the focused test and verify RED**

Run from `ai/`:

```bash
uv run pytest tests/api/test_health.py -q
```

Expected: FAIL because `/health` returns 404.

- [ ] **Step 3: Add the minimal route**

Inside `create_app` after error-handler installation:

```python
    @app.get("/health", include_in_schema=False)
    async def health() -> dict[str, str]:
        return {"status": "UP"}
```

- [ ] **Step 4: Verify GREEN and API regressions**

```bash
uv run pytest tests/api/test_health.py tests/api/test_api_errors.py -q
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add ai/tests/api/test_health.py ai/src/wiki_api/app.py
git commit -m "feat(ai): 컨테이너 헬스 엔드포인트 추가"
```

### Task 2: Reproducible AI Test and Runtime Images

**Files:**
- Create: `ai/Dockerfile`
- Create: `ai/.dockerignore`

**Interfaces:**
- Consumes: `ai/pyproject.toml`, `ai/uv.lock`, `ai/src`, `ai/tests`
- Produces: Docker targets `test` and `runtime`; runtime listens on `0.0.0.0:8000`

- [ ] **Step 1: Verify the missing image target fails**

```bash
docker build --target test --tag ajt-ai-test:plan-red ai
```

Expected: FAIL because `ai/Dockerfile` does not exist.

- [ ] **Step 2: Add `.dockerignore`**

```dockerignore
.venv/
__pycache__/
*.py[cod]
.pytest_cache/
.ruff_cache/
src/.env
experiments/
docs/
viewer/
```

- [ ] **Step 3: Add a Python 3.12 multi-stage Dockerfile**

```dockerfile
# syntax=docker/dockerfile:1
FROM python:3.12-slim AS base

ARG UV_VERSION=0.8.15
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy
WORKDIR /app

RUN python -m pip install --no-cache-dir "uv==${UV_VERSION}"
COPY pyproject.toml uv.lock ./

FROM base AS test
RUN uv sync --frozen --extra deepagents --no-install-project
COPY src ./src
COPY tests ./tests
RUN uv sync --frozen --extra deepagents
CMD ["uv", "run", "--no-sync", "pytest", "-m", "not ocr and not llm", "-q"]

FROM base AS runtime-build
RUN uv sync --frozen --no-dev --extra deepagents --no-install-project
COPY src ./src
RUN uv sync --frozen --no-dev --extra deepagents

FROM python:3.12-slim AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/app/.venv/bin:${PATH}" \
    PYTHONPATH=/app/src
WORKDIR /app

RUN groupadd --system --gid 10001 ajt \
    && useradd --system --uid 10001 --gid ajt --home-dir /app \
      --shell /usr/sbin/nologin ajt
COPY --from=runtime-build --chown=ajt:ajt /app/.venv ./.venv
COPY --from=runtime-build --chown=ajt:ajt /app/src ./src

USER ajt
EXPOSE 8000
CMD ["python", "-m", "wiki_api.serve", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 4: Build and run the test target**

```bash
docker build --target test --tag ajt-ai-test:plan-green ai
docker run --rm ajt-ai-test:plan-green
```

Expected: image build succeeds and deterministic AI tests pass without GMS credentials.

- [ ] **Step 5: Build the runtime image and smoke-test health**

```bash
docker build --target runtime --tag ajt-ai:plan-green ai
docker run --rm -d --name ajt-ai-plan -p 127.0.0.1:18000:8000 \
  -e INTERNAL_API_KEY=test-internal \
  -e BACKEND_BASE_URL=http://127.0.0.1:9 \
  -e AI_RUNTIME=deepagents \
  -e SCHEDULE_EXTRACTOR_PROVIDER=anthropic \
  -e ANTHROPIC_API_KEY=test-gms \
  -e ANTHROPIC_BASE_URL=https://gms.ssafy.io/gmsapi/api.anthropic.com \
  ajt-ai:plan-green
curl --fail --silent http://127.0.0.1:18000/health
docker stop ajt-ai-plan
```

Expected health body: `{"status":"UP"}`. The smoke test does not call GMS.

- [ ] **Step 6: Commit**

```bash
git add ai/Dockerfile ai/.dockerignore
git commit -m "feat(infra): FastAPI AI 런타임 이미지 추가"
```

### Task 3: GMS-only Compose Wiring

**Files:**
- Create: `scripts/tests/compose-ai-test.sh`
- Modify: `docker-compose.yml`
- Modify: `.env.example`

**Interfaces:**
- Consumes: `AI_IMAGE`, `IMAGE_TAG`, `AI_INTERNAL_API_KEY`, `ANTHROPIC_API_KEY`
- Produces: Compose services `mysql`, `ai`, `backend`, `frontend` with internal URLs and no AI host port

- [ ] **Step 1: Write the failing rendered-Compose test**

The test creates a mode-600 temporary env file with literal fake secrets, renders
`docker compose config`, and asserts observable configuration:

```bash
services="$(DEPLOY_ENV_FILE="$env_file" IMAGE_TAG=0123456789ab \
  docker compose --env-file "$env_file" --file docker-compose.yml config --services)"
grep -Fxq ai <<<"$services"

rendered="$(DEPLOY_ENV_FILE="$env_file" IMAGE_TAG=0123456789ab \
  docker compose --env-file "$env_file" --file docker-compose.yml config)"
grep -Fq 'image: ajt-ai:0123456789ab' <<<"$rendered"
grep -Fq 'AI_BASE_URL: http://ai:8000' <<<"$rendered"
grep -Fq 'BACKEND_BASE_URL: http://backend:8080' <<<"$rendered"
grep -Fq 'SCHEDULE_EXTRACTOR_PROVIDER: anthropic' <<<"$rendered"
grep -Fq 'ANTHROPIC_BASE_URL: https://gms.ssafy.io/gmsapi/api.anthropic.com' <<<"$rendered"
! grep -Fq 'localhost:11434' <<<"$rendered"
! grep -Fq 'SCHEDULE_EXTRACTOR_PROVIDER: ollama' <<<"$rendered"
```

Extract the AI service section and verify that it has no `ports:` key:

```bash
ai_section="$(awk '
  /^  ai:$/ { in_ai=1 }
  in_ai && /^  [[:alnum:]_-]+:$/ && $0 != "  ai:" { exit }
  in_ai { print }
' <<<"$rendered")"
! grep -Fq 'ports:' <<<"$ai_section"
```

- [ ] **Step 2: Run the test and verify RED**

```bash
bash scripts/tests/compose-ai-test.sh
```

Expected: FAIL because the `ai` service is missing.

- [ ] **Step 3: Add the AI service and internal dependency graph**

Implement these effective values in Compose:

```yaml
ai:
  image: "${AI_IMAGE:-ajt-ai}:${IMAGE_TAG:?IMAGE_TAG is required}"
  env_file:
    - "${DEPLOY_ENV_FILE:-.env}"
  environment:
    INTERNAL_API_KEY: "${AI_INTERNAL_API_KEY:?AI_INTERNAL_API_KEY is required}"
    BACKEND_BASE_URL: "http://backend:8080"
    AI_RUNTIME: "deepagents"
    SCHEDULE_EXTRACTOR_PROVIDER: "anthropic"
    ANTHROPIC_BASE_URL: "https://gms.ssafy.io/gmsapi/api.anthropic.com"
    AI_MODEL: "${AI_MODEL:-anthropic:claude-opus-4-6}"
    AI_MODEL_FAST: "${AI_MODEL_FAST:-anthropic:claude-haiku-4-5-20251001}"
    AI_MODEL_QUALITY: "${AI_MODEL_QUALITY:-anthropic:claude-sonnet-4-6}"
    SCHEDULE_EXTRACTOR_MODEL: "${SCHEDULE_EXTRACTOR_MODEL:-claude-opus-4-6}"
  expose:
    - "8000"
```

The AI healthcheck calls `http://127.0.0.1:8000/health`. Backend gains
`AI_BASE_URL: http://ai:8000` and depends on healthy MySQL and healthy AI.

- [ ] **Step 4: Update the environment example**

Add `AI_IMAGE=ajt-ai`, retain empty `AI_INTERNAL_API_KEY`, add empty
`ANTHROPIC_API_KEY`, document fixed GMS values, and remove the statement that AI runs
outside this Compose project.

- [ ] **Step 5: Verify GREEN and missing-secret fail-fast behavior**

```bash
bash scripts/tests/compose-ai-test.sh
```

Expected: PASS. A separate render without `AI_INTERNAL_API_KEY` must exit nonzero.

- [ ] **Step 6: Commit**

```bash
git add scripts/tests/compose-ai-test.sh docker-compose.yml .env.example
git commit -m "feat(infra): Compose에 GMS AI 서비스 연결"
```

### Task 4: Three-image Deployment and Rollback

**Files:**
- Modify: `scripts/tests/deploy-test.sh`
- Modify: `scripts/deploy.sh`

**Interfaces:**
- Consumes: `AI_IMAGE`, `IMAGE_TAG`, environment-specific state file
- Produces: fail-fast validation and atomic rollback for backend, frontend, AI

- [ ] **Step 1: Extend the fake Docker behavior and write failing tests**

Add a fake missing-image selector. `docker image inspect` exits 1 only when its image
matches the selector. Add tests that prove:

```text
new AI image missing -> exit before compose up
previous AI image missing during rollback -> explicit rollback error
all images present -> existing success path records the new SHA
```

The missing-new-AI test must set `FAKE_MISSING_IMAGE=ajt-ai:555555555555` and assert no
`compose up` entry exists in the Docker log.

- [ ] **Step 2: Run the deployment tests and verify RED**

```bash
bash scripts/tests/deploy-test.sh
```

Expected: FAIL because `deploy.sh` does not inspect `ajt-ai:555555555555`.

- [ ] **Step 3: Add AI image validation**

Add:

```bash
AI_IMAGE="${AI_IMAGE:-ajt-ai}"
```

Validate all three current images before Compose changes. Before rollback, validate all
three previous-tag images and stop with a clear error if any are unavailable.

- [ ] **Step 4: Verify GREEN and existing lock behavior**

```bash
bash scripts/tests/deploy-test.sh
```

Expected: all deployment, rollback, and shared-lock cases pass.

- [ ] **Step 5: Commit**

```bash
git add scripts/tests/deploy-test.sh scripts/deploy.sh
git commit -m "feat(infra): AI 이미지를 배포 롤백 단위에 포함"
```

### Task 5: Jenkins AI Test and Build Stages

**Files:**
- Modify: `Jenkinsfile`

**Interfaces:**
- Consumes: Dockerfile targets `test`, `runtime`; `AI_IMAGE=ajt-ai`
- Produces: approved deployment with backend/frontend/AI images built from one SHA

- [ ] **Step 1: Establish the current pipeline limitation**

Run the current branch resolver and inspect the current Docker Build output contract:

```bash
bash scripts/tests/resolve-deploy-target-test.sh
git grep -n 'AI_IMAGE\|--target test\|ajt-ai' -- Jenkinsfile
```

Expected: resolver passes; grep finds no AI test/build wiring. This is a configuration
change, so the executable proof is supplied by the Docker target and final Jenkins build.

- [ ] **Step 2: Add AI environment and test stage**

Add `AI_IMAGE = 'ajt-ai'`. Insert an `AI Test` stage before `Docker Build`:

```groovy
dir('ai') {
    sh '''
        docker build --target test --tag "ajt-ai-test:${IMAGE_TAG}" .
        docker run --rm "ajt-ai-test:${IMAGE_TAG}"
    '''
}
```

- [ ] **Step 3: Build the AI runtime image and pass its name to deploy**

The Docker build stage adds:

```bash
docker build --target runtime --tag "${AI_IMAGE}:${IMAGE_TAG}" ai
```

The deployment environment adds:

```bash
AI_IMAGE="${AI_IMAGE}"
```

- [ ] **Step 4: Verify Jenkinsfile syntax-adjacent contracts**

```bash
bash -n scripts/deploy.sh scripts/resolve-deploy-target.sh
bash scripts/tests/resolve-deploy-target-test.sh
```

After merge, the real `S15P11B106-develop` job is the required Jenkins Declarative
Pipeline parse and execution test. It must reach `Deployment Approval` with target
`develop 8090` before any deployment occurs.

- [ ] **Step 5: Commit**

```bash
git add Jenkinsfile
git commit -m "ci: AI 테스트와 이미지 빌드를 승인 배포에 연결"
```

### Task 6: Runbook, Full Verification, Push, and MR

**Files:**
- Modify: `docs/infra/deployment-runbook.md`

**Interfaces:**
- Consumes: completed AI image, Compose, Jenkins, deployment contracts
- Produces: server operator steps for secrets, first develop deployment, and rollback

- [ ] **Step 1: Update the runbook**

Document:

- AI runs inside the same Compose project without a host port;
- both Jenkins env files require `AI_INTERNAL_API_KEY` and `ANTHROPIC_API_KEY`;
- no real key may be printed during validation;
- rendered Compose must show `anthropic` and the GMS base URL and must not show Ollama;
- the first develop build must pause at approval and deploy four healthy services;
- current and previous SHA checks include `ajt-ai`.

- [ ] **Step 2: Run all fast tests**

```bash
uv run --project ai pytest ai/tests/api/test_health.py ai/tests/api/test_settings.py -q
bash scripts/tests/compose-ai-test.sh
bash scripts/tests/deploy-test.sh
bash scripts/tests/resolve-deploy-target-test.sh
bash scripts/tests/bootstrap-admin-test.sh
node scripts/validate-artifact-consistency.mjs
```

Expected: every command exits 0.

- [ ] **Step 3: Run Docker verification**

```bash
docker build --target test --tag ajt-ai-test:verification ai
docker run --rm ajt-ai-test:verification
docker build --target runtime --tag ajt-ai:verification ai
```

Expected: both image builds and the test container succeed.

- [ ] **Step 4: Render the full Compose model with fake secrets**

Use a temporary env file, never a real secret file:

```bash
temp_root="$(mktemp -d)"
temp_env="${temp_root}/deploy.env"
trap 'rm -rf "$temp_root"' EXIT
cat > "$temp_env" <<'EOF'
SPRING_PROFILES_ACTIVE=prod
FRONTEND_PORT=8090
MYSQL_VOLUME_NAME=ajt-plan-mysql-data
AJT_FILES_VOLUME_NAME=ajt-plan-files
AJT_ACCESS_TOKEN_SECRET=test-access-secret
AJT_PASSWORD_RESET_SECRET=test-reset-secret
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

DEPLOY_ENV_FILE="$temp_env" IMAGE_TAG=0123456789ab \
docker compose --env-file "$temp_env" --file docker-compose.yml config --services

DEPLOY_ENV_FILE="$temp_env" IMAGE_TAG=0123456789ab \
docker compose --env-file "$temp_env" --file docker-compose.yml config --images
```

Expected services: `mysql`, `ai`, `backend`, `frontend`. Expected application images:
`ajt-ai:0123456789ab`, `ajt-backend:0123456789ab`, `ajt-frontend:0123456789ab`.

- [ ] **Step 5: Run repository hygiene checks**

```bash
git diff --check origin/develop...HEAD
git status --short
git diff --name-only origin/develop...HEAD
```

Inspect the changed files for literal API key patterns before committing.

- [ ] **Step 6: Commit the runbook**

```bash
git add docs/infra/deployment-runbook.md
git commit -m "docs(infra): GMS AI 통합 배포 절차 추가"
```

- [ ] **Step 7: Push and create the MR**

```bash
git push -u origin codex/ai-docker-jenkins-deploy
```

Create a develop-targeted MR titled:

```text
ci: GMS AI Docker Jenkins 통합 배포
```

The MR must list the AI test image result, Compose contract result, deployment rollback
test result, and the remaining server-side Jenkins develop approval test.
