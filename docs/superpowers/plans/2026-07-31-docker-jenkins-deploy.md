# Docker Jenkins Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 단일 Ubuntu 24.04 서버에서 `master` 커밋을 테스트하고 커밋 SHA 이미지로 빌드한 뒤, 사람의 승인 후 443 포트에 배포하고 실패 시 직전 이미지로 롤백한다.

**Architecture:** Jenkins와 Docker는 같은 서버에서 실행한다. Jenkins는 백엔드·프론트엔드 검증을 통과한 커밋에 `ajt-backend:<commit-sha>`, `ajt-frontend:<commit-sha>` 태그를 붙이고, 승인 후 고정 Compose 프로젝트 `ajt-prod`를 갱신한다. MySQL과 업로드 파일은 고정 named volume에 보존하며, 별도 서버의 AI는 `AI_BASE_URL`과 `AI_INTERNAL_API_KEY`로만 연결하고 애플리케이션 헬스체크와 배포 성공 판정에서는 제외한다.

**Tech Stack:** Jenkins Declarative Pipeline, Docker 29, Docker Compose v5, Bash, Spring Boot/Gradle/Java 21, React/Vite/Node 22, MySQL 8.4, nginx

## Global Constraints

- 배포 대상은 `frontend`, `backend`, `mysql`뿐이며 AI 컨테이너는 만들지 않는다.
- `master` 직접 push는 하지 않고 `develop` 검증 후 MR과 Squash merge로 반영한다.
- Jenkins는 빌드·테스트를 먼저 완료하고 `input` 승인을 받은 경우에만 운영 배포한다.
- 운영 이미지는 `latest`가 아니라 7~40자의 Git commit SHA로 식별한다.
- 운영 Compose 프로젝트 이름은 `ajt-prod`, 외부 HTTPS 포트는 `443`이다.
- 외부 AI 장애는 `/api/v1/health` 및 Docker 헬스체크를 실패시키지 않는다.
- 초기 운영 DB에는 더미 데이터를 넣지 않고 최고관리자 한 명만 별도 스크립트로 생성한다.
- 최고관리자 비밀번호는 서버에서 `openssl`로 한 번 생성하고 BCrypt 해시만 DB에 저장한다.
- 비밀값은 Git에 커밋하지 않고 Jenkins가 읽을 수 있는 권한 `600`의 서버 env 파일에 둔다.

---

### Task 1: 운영 Compose 계약과 커밋 SHA 이미지

**Files:**
- Modify: `.env.example`
- Modify: `backend/Dockerfile`
- Modify: `docker-compose.yml`
- Modify: `frontend/Dockerfile`

**Interfaces:**
- Consumes: 서버 env 파일의 `IMAGE_TAG`, `BACKEND_IMAGE`, `FRONTEND_IMAGE`, `FRONTEND_PORT`, DB·인증·AI 환경변수
- Produces: `ajt-backend:<IMAGE_TAG>`, `ajt-frontend:<IMAGE_TAG>`로 실행되는 healthy Compose 스택

- [ ] **Step 1: 현재 Compose가 이미지 태그 계약을 거부하는지 확인**

Run on Ubuntu:

```bash
IMAGE_TAG=invalid docker compose -p ajt-prod --env-file .env.example -f docker-compose.yml config
```

Expected: 현재 파일에는 SHA 검증이나 이미지 참조가 없어 명령이 성공하거나 build 설정만 출력되어 요구사항을 충족하지 못한다.

- [ ] **Step 2: 운영 환경변수 계약 추가**

`.env.example`에 아래 변수를 추가하고 AI 주소 설명을 외부 서버 기준으로 바꾼다.

```dotenv
IMAGE_TAG=
BACKEND_IMAGE=ajt-backend
FRONTEND_IMAGE=ajt-frontend
FRONTEND_PORT=443
AI_BASE_URL=
AI_INTERNAL_API_KEY=
```

- [ ] **Step 3: 백엔드 런타임 이미지에 헬스체크 도구 추가**

`backend/Dockerfile`의 runtime 단계에 아래를 추가한다.

```dockerfile
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
```

- [ ] **Step 4: Compose를 사전 빌드된 SHA 이미지와 고정 볼륨으로 전환**

`docker-compose.yml`에서 backend/frontend의 `build`를 제거하고 다음 이미지 계약을 사용한다.

```yaml
backend:
  image: "${BACKEND_IMAGE:-ajt-backend}:${IMAGE_TAG:?IMAGE_TAG is required}"

frontend:
  image: "${FRONTEND_IMAGE:-ajt-frontend}:${IMAGE_TAG:?IMAGE_TAG is required}"
```

백엔드는 `curl -fsS http://127.0.0.1:8080/actuator/health`, 프론트는 `wget --no-check-certificate --spider --quiet https://127.0.0.1/api/v1/health`를 사용해 healthcheck를 구성한다. 프론트는 backend가 healthy가 된 다음 시작한다. named volume은 환경변수로 분리하고 운영 env에서 다음 이름을 사용한다.

```yaml
volumes:
  mysql-data:
    name: "${MYSQL_VOLUME_NAME:-ajt-prod-mysql-data}"
  ajt-files:
    name: "${AJT_FILES_VOLUME_NAME:-ajt-prod-files}"
```

- [ ] **Step 5: Compose 문법과 최종 병합값 확인**

Run on Ubuntu with a non-secret validation env:

```bash
cp .env.example /tmp/ajt-compose-check.env
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=0123456789ab/' /tmp/ajt-compose-check.env
docker compose -p ajt-prod --env-file /tmp/ajt-compose-check.env -f docker-compose.yml config
```

Expected: services는 `mysql`, `backend`, `frontend` 세 개이고 backend/frontend 이미지는 모두 `0123456789ab` 태그이며 AI service는 없다.

- [ ] **Step 6: 커밋**

```bash
git add .env.example backend/Dockerfile docker-compose.yml frontend/Dockerfile
git commit -m "chore(docker): 운영 SHA 이미지와 헬스체크 구성"
```

### Task 2: 자동 롤백 배포 스크립트

**Files:**
- Create: `scripts/deploy.sh`
- Test: `scripts/tests/deploy-test.sh`

**Interfaces:**
- Consumes: `scripts/deploy.sh <image-tag>`, `DEPLOY_ENV_FILE`, `DEPLOY_STATE_DIR`, `DEPLOY_HEALTHCHECK_URL`
- Produces: 성공 시 `<DEPLOY_STATE_DIR>/current-image-tag`, 실패 시 직전 태그 스택 복원

- [ ] **Step 1: 실패하는 배포 스크립트 행동 테스트 작성**

`scripts/tests/deploy-test.sh`는 임시 `PATH`에 fake `docker`와 fake `curl`을 만들고 다음을 검증한다.

```text
1. SHA가 아닌 태그는 docker 호출 전에 종료 코드 2로 거부한다.
2. 성공 배포는 새 SHA를 current-image-tag에 원자적으로 기록한다.
3. 새 SHA 헬스체크 실패 시 이전 SHA로 compose up을 다시 호출하고 state는 이전 SHA를 유지한다.
```

- [ ] **Step 2: 테스트가 스크립트 부재로 실패하는지 확인**

Run on Ubuntu:

```bash
bash scripts/tests/deploy-test.sh
```

Expected: FAIL because `scripts/deploy.sh` does not exist.

- [ ] **Step 3: 최소 배포 스크립트 구현**

`scripts/deploy.sh`는 다음 계약을 구현한다.

```bash
#!/usr/bin/env bash
set -Eeuo pipefail

IMAGE_TAG="${1:-}"
DEPLOY_ENV_FILE="${DEPLOY_ENV_FILE:-/var/lib/jenkins/ajt-secrets/prod.env}"
DEPLOY_STATE_DIR="${DEPLOY_STATE_DIR:-/var/lib/jenkins/ajt-deploy}"
DEPLOY_HEALTHCHECK_URL="${DEPLOY_HEALTHCHECK_URL:-https://127.0.0.1/api/v1/health}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-ajt-prod}"
```

태그는 `^[0-9a-f]{7,40}$`로 검증한다. `docker image inspect`로 두 이미지를 확인하고 `IMAGE_TAG=<tag> docker compose ... up -d --no-build --remove-orphans`를 실행한다. 최대 30회, 5초 간격으로 HTTPS health URL을 확인한다. 성공 시 임시 파일을 `mv`하여 state를 갱신하고, 실패 시 이전 태그가 있으면 같은 Compose 명령으로 복원한 후 롤백 헬스체크까지 확인하고 비정상 종료한다.

- [ ] **Step 4: 배포 행동 테스트 통과 확인**

Run on Ubuntu:

```bash
bash -n scripts/deploy.sh
bash scripts/tests/deploy-test.sh
```

Expected: 모든 시나리오 PASS.

- [ ] **Step 5: 커밋**

```bash
git add scripts/deploy.sh scripts/tests/deploy-test.sh
git commit -m "ci: 운영 배포 실패 시 직전 이미지 롤백"
```

### Task 3: Jenkins 빌드·테스트·수동 승인 파이프라인

**Files:**
- Modify: `Jenkinsfile`

**Interfaces:**
- Consumes: Jenkins SCM checkout, `master` push webhook, 서버 Docker daemon, `/var/lib/jenkins/ajt-secrets/prod.env`
- Produces: 검증된 SHA 이미지 두 개와 승인된 운영 배포

- [ ] **Step 1: 현재 Jenkinsfile 기준 실행 결과 기록**

Run:

```bash
git show HEAD:Jenkinsfile
```

Expected: Hello/Test 예제만 있고 Gradle, npm, Docker build, approval, deploy 단계가 없다.

- [ ] **Step 2: Pipeline을 빌드·테스트 단계로 교체**

Declarative Pipeline에 아래 순서를 구현한다.

```text
Checkout
Prepare: IMAGE_TAG=$(git rev-parse --short=12 HEAD)
Backend Test: ./gradlew clean test --no-daemon
Frontend Install: npm ci
Frontend Lint: npm run lint
Frontend Test: npm run test
Frontend Build: npm run build
Docker Build: docker build -t ajt-backend:${IMAGE_TAG} backend
              docker build -t ajt-frontend:${IMAGE_TAG} frontend
```

동시 운영 배포를 막기 위해 `disableConcurrentBuilds()`를 사용한다.

- [ ] **Step 3: master 전용 승인과 배포 단계 추가**

`master`가 아닌 브랜치는 이미지 빌드까지만 수행한다. `master`에서는 executor를 점유하지 않는 `agent none` 승인 stage를 두고 다음 문구로 `input`을 요청한다.

```text
<IMAGE_TAG> 이미지를 운영 443 포트에 배포하시겠습니까?
```

승인된 경우에만 아래를 실행한다.

```bash
DEPLOY_ENV_FILE=/var/lib/jenkins/ajt-secrets/prod.env \
DEPLOY_STATE_DIR=/var/lib/jenkins/ajt-deploy \
DEPLOY_HEALTHCHECK_URL=https://127.0.0.1/api/v1/health \
bash scripts/deploy.sh "$IMAGE_TAG"
```

- [ ] **Step 4: Jenkins 문법과 master 조건 확인**

Jenkins의 Pipeline Syntax/Replay validation 또는 첫 feature branch 수동 빌드로 문법을 검증한다.

Expected: feature branch는 build/test/image build 후 종료하고 Approval/Deploy는 skipped다. master는 Approval에서 대기한다.

- [ ] **Step 5: 커밋**

```bash
git add Jenkinsfile
git commit -m "ci: master 수동 승인 운영 배포 파이프라인 구성"
```

### Task 4: 최고관리자 단일 부트스트랩

**Files:**
- Delete: `scripts/add-company.sh`
- Create: `scripts/bootstrap-admin.sh`
- Test: `scripts/tests/bootstrap-admin-test.sh`

**Interfaces:**
- Consumes: `scripts/bootstrap-admin.sh [admin-email]`, 운영 Compose의 healthy MySQL
- Produces: `본사` 부서와 부서장으로 지정되지 않은 `ADMIN/APPROVED/ACTIVE` 최고관리자 한 명, 화면에 한 번 출력되는 랜덤 평문 비밀번호

- [ ] **Step 1: 실패하는 부트스트랩 행동 테스트 작성**

fake `docker`, `openssl`, `htpasswd`를 사용해 다음을 검증한다.

```text
1. 잘못된 이메일은 DB 호출 전에 거부한다.
2. 이미 같은 이메일이 있으면 새 비밀번호나 INSERT를 출력하지 않는다.
3. 빈 DB에서는 openssl이 만든 비밀번호를 BCrypt 처리하고 관리자 한 명만 INSERT한다.
4. 성공 출력에는 이메일과 평문 비밀번호가 한 번씩만 나타난다.
```

- [ ] **Step 2: 테스트가 새 스크립트 부재로 실패하는지 확인**

Run on Ubuntu:

```bash
bash scripts/tests/bootstrap-admin-test.sh
```

Expected: FAIL because `scripts/bootstrap-admin.sh` does not exist.

- [ ] **Step 3: 최고관리자 생성 스크립트 구현**

기본 이메일은 `admin@ajt.local`로 두고 이메일을 제한된 패턴으로 검증한다. `openssl rand -hex 16`으로 비밀번호를 만들고 `htpasswd -bnBC 12`로 BCrypt 해시를 만든다. MySQL 컨테이너 내부의 `MYSQL_ROOT_PASSWORD`를 이용해 `본사` 부서를 `INSERT IGNORE`한 후 다음 값으로 회원을 한 번만 생성한다.

```text
name=최고관리자
role=ADMIN
signup_status=APPROVED
account_status=ACTIVE
department.manager_id=NULL
```

- [ ] **Step 4: 스크립트 테스트와 ERD 정합성 확인**

Run on Ubuntu:

```bash
bash -n scripts/bootstrap-admin.sh
bash scripts/tests/bootstrap-admin-test.sh
```

Expected: 모든 시나리오 PASS. `docs/db/erd.sql`은 변경되지 않는다.

- [ ] **Step 5: 커밋**

```bash
git add scripts/add-company.sh scripts/bootstrap-admin.sh scripts/tests/bootstrap-admin-test.sh
git commit -m "chore(infra): 최고관리자 초기 계정 생성 스크립트 추가"
```

### Task 5: 서버 준비와 develop 8090 검증

**Files:**
- Create: `docs/infra/deployment-runbook.md`

**Interfaces:**
- Consumes: Ubuntu 24.04 서버, Jenkins/Java 21/Docker, feature branch 이미지
- Produces: 재현 가능한 Jenkins 권한 설정, 비밀 env 생성, develop 검증 절차

- [ ] **Step 1: Jenkins의 Docker 권한과 필수 도구 확인**

Run on Ubuntu:

```bash
sudo usermod -aG docker jenkins
sudo apt-get update
sudo apt-get install -y apache2-utils curl
sudo systemctl restart jenkins
sudo -u jenkins docker version
```

Expected: Jenkins 사용자로 Docker client/server 정보가 출력된다.

- [ ] **Step 2: 운영 env 디렉터리와 파일 준비**

Run on Ubuntu:

```bash
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-secrets
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-deploy
sudo install -m 600 -o jenkins -g jenkins /dev/null /var/lib/jenkins/ajt-secrets/prod.env
```

파일에는 `.env.example`의 운영값을 넣고 `FRONTEND_PORT=443`을 지정한다. 외부 AI 서버 주소와 공유 키는 담당자가 전달한 실제 값만 입력하며, 값이 준비되지 않았으면 master 운영 배포 전에 해당 항목을 미완료 체크로 남긴다.

- [ ] **Step 3: develop 검증용 8090 env와 이미지 준비**

운영 env를 복사하지 말고 별도 랜덤 비밀값으로 `/var/lib/jenkins/ajt-secrets/develop.env`를 만든다. `FRONTEND_PORT=8090`을 사용하고 Compose 프로젝트는 `ajt-develop`로 분리한다.

- [ ] **Step 4: 기존 demo 8090만 중단하고 develop 스택 검증**

Run on Ubuntu:

```bash
docker compose -p demo -f docker-compose.demo.yml --env-file .env.demo down
```

그 후 develop SHA 이미지를 빌드하고 `ajt-develop` 프로젝트로 8090에 실행한다. 브라우저 로그인, `/api/v1/health`, 프론트 정적 파일, DB 초기화, 재기동 후 volume 유지 여부를 확인한다.

- [ ] **Step 5: 런북에 실제 명령과 성공 기준 기록**

`docs/infra/deployment-runbook.md`에 서버 준비, Jenkins job/webhook, develop 검증, master 전환, 롤백, 관리자 생성 명령을 순서대로 기록한다.

- [ ] **Step 6: 커밋**

```bash
git add docs/infra/deployment-runbook.md
git commit -m "docs(infra): Jenkins 운영 배포 절차 문서화"
```

### Task 6: master 첫 운영 전환

**Files:**
- Verify only: no source changes

**Interfaces:**
- Consumes: 승인된 develop MR, develop→master MR, Jenkins의 성공한 master build
- Produces: 기존 더미 환경이 제거되고 `ajt-prod`만 443에서 실행되는 운영 서버

- [ ] **Step 1: MR과 CI 확인**

feature→develop MR은 Squash merge하고 8090 검증 결과를 본문에 기록한다. develop→master MR도 승인과 Squash merge를 거친다.

- [ ] **Step 2: 삭제 대상 프로젝트와 볼륨을 정확히 재확인**

Run on Ubuntu:

```bash
docker compose ls
docker volume ls
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}\t{{.Status}}'
```

Expected delete projects: `demo`, `s15p11b106`, `companya`, `companyb`, `companyc`. 목록이 다르면 삭제하지 않고 다시 검토한다.

- [ ] **Step 3: Jenkins master build의 배포 승인 직전에 기존 443 스택 중단**

첫 배포 실패 시 즉시 복구할 수 있도록 기존 컨테이너와 volume은 삭제하지 않는다. 443을 사용하는 기존 `s15p11b106` 컨테이너만 이름을 명시해 `docker stop`하고, 실패 시 `docker start`로 복원한다.

- [ ] **Step 4: Jenkins 승인 후 운영 배포 확인**

Expected:

```text
https://i15b106.p.ssafy.io/api/v1/health -> HTTP 200, status=UP
docker compose -p ajt-prod ps -> mysql/backend/frontend healthy
443 -> ajt-prod frontend only
```

- [ ] **Step 5: 최고관리자 생성**

Run once on Ubuntu:

```bash
sudo -u jenkins bash scripts/bootstrap-admin.sh admin@ajt.local
```

평문 비밀번호를 안전한 전달 수단에 즉시 저장하고 터미널 스크롤백·공유 문서·Git에는 남기지 않는다.

- [ ] **Step 6: 기존 더미 컨테이너와 volume 제거**

새 운영 로그인까지 확인한 다음 과거 컨테이너의 mount를 `docker inspect`로 기록한다. `ajt-prod-mysql-data`, `ajt-prod-files`를 제외한 과거 volume만 이름을 명시해 제거하고 `docker volume prune`은 사용하지 않는다.

- [ ] **Step 7: 로그인 및 롤백 리허설**

최고관리자 로그인을 확인한다. 테스트용 이전 SHA와 현재 SHA로 `scripts/deploy.sh`를 실행해 state 파일과 롤백 동작을 확인하되 운영 데이터 볼륨은 삭제하지 않는다.
