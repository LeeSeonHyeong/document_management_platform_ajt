# AJT 단일 서버 Jenkins 배포 런북

## 1. 배포 원칙

- 서버: Ubuntu 24.04 x86_64 한 대
- Jenkins: 호스트 systemd 서비스
- Docker: 호스트 Docker daemon
- 운영 Compose 프로젝트: `ajt-prod`
- 운영 포트: `443`
- 검증 Compose 프로젝트: `ajt-develop`
- 검증 포트: `8090`
- 이미지: `ajt-backend:<12자리 Git SHA>`, `ajt-frontend:<12자리 Git SHA>`, `ajt-ai:<12자리 Git SHA>`
- 자동 실행: GitLab `develop`·`master` push webhook을 Job별로 분리
- 배포 반영: Jenkins 테스트·이미지 빌드 성공 후 사람의 승인
- AI: 같은 Compose의 내부 전용 FastAPI 서비스. 호스트 포트는 열지 않고 모든 모델 호출은 SSAFY GMS Anthropic 경로를 사용

`develop`과 `master`에는 직접 push하지 않는다. feature→develop, develop→master MR을 각각 승인 후 Squash merge한다.

## 2. 서버 사전 점검

아래 명령은 배포 중인 컨테이너를 변경하지 않는다.

```bash
cd /home/ubuntu/S15P11B106

cat /etc/os-release
uname -m
docker --version
docker compose version
java -version
systemctl is-active docker
systemctl is-active jenkins
git branch --show-current
git status --short
df -h /
free -h
```

필수 조건:

```text
Ubuntu 24.04 LTS
x86_64
Docker active
Jenkins active
Java 21
Git 작업 트리 clean
```

## 3. Jenkins 사용자 준비

Jenkins가 Docker daemon과 Gradle wrapper를 실행할 수 있게 한다.

```bash
sudo usermod -aG docker jenkins
sudo apt-get update
sudo apt-get install -y apache2-utils curl
sudo systemctl restart jenkins

sudo -u jenkins docker version
sudo -u jenkins java -version
sudo -u jenkins git --version
```

`sudo -u jenkins docker version`이 권한 오류 없이 client/server 정보를 모두 출력해야 한다.

프론트 검증은 Jenkinsfile이 `node:22-alpine` 컨테이너에서 실행하므로 호스트에 Node를 설치하지 않는다.

## 4. 배포 비밀 환경파일 준비

디렉터리와 빈 파일을 먼저 최소 권한으로 만든다.

```bash
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-secrets
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-deploy
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-deploy/develop
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-deploy/prod

if ! sudo test -e /var/lib/jenkins/ajt-secrets/prod.env; then
  sudo install -m 600 -o jenkins -g jenkins /dev/null \
    /var/lib/jenkins/ajt-secrets/prod.env
fi
```

`.env.example`을 기준으로 `/var/lib/jenkins/ajt-secrets/prod.env`를 작성한다.

```bash
sudo -u jenkins nano /var/lib/jenkins/ajt-secrets/prod.env
```

운영 고정값:

```dotenv
SPRING_PROFILES_ACTIVE=prod
BACKEND_IMAGE=ajt-backend
FRONTEND_IMAGE=ajt-frontend
AI_IMAGE=ajt-ai
FRONTEND_PORT=443
MYSQL_VOLUME_NAME=ajt-prod-mysql-data
AJT_FILES_VOLUME_NAME=ajt-prod-files
AJT_AUTH_COOKIE_SECURE=true
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/ajt?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
SPRING_DATASOURCE_USERNAME=ajt
MYSQL_DATABASE=ajt
MYSQL_USER=ajt
DOCUMENT_STORAGE_ROOT=/data/ajt/documents
SCHEDULE_SOURCE_STORAGE_ROOT=/data/ajt/schedule-sources
INQUIRY_STORAGE_ROOT=/data/ajt/inquiries
```

다음 값은 각각 실제 값으로 채운다.

```dotenv
AJT_ACCESS_TOKEN_SECRET=<openssl rand -base64 48>
AJT_PASSWORD_RESET_SECRET=<openssl rand -base64 48>
SPRING_DATASOURCE_PASSWORD=<openssl rand -hex 24>
MYSQL_ROOT_PASSWORD=<openssl rand -hex 24>
MYSQL_PASSWORD=<SPRING_DATASOURCE_PASSWORD와 동일>
AI_INTERNAL_API_KEY=<openssl rand -base64 48>
ANTHROPIC_API_KEY=<SSAFY GMS에서 발급한 API 키>
```

`AI_BASE_URL`, `AI_RUNTIME`, `SCHEDULE_EXTRACTOR_PROVIDER`, `ANTHROPIC_BASE_URL`은
`docker-compose.yml`이 각각 내부 서비스 주소, `deepagents`, `anthropic`, SSAFY GMS 주소로
고정한다. 운영 환경파일에서 Ollama 주소나 프로바이더를 지정하지 않는다.

검사할 때 값 자체를 출력하지 않는다.

```bash
sudo stat -c '%U %G %a %n' /var/lib/jenkins/ajt-secrets/prod.env
sudo -u jenkins test -r /var/lib/jenkins/ajt-secrets/prod.env

for key in AI_INTERNAL_API_KEY ANTHROPIC_API_KEY; do
  sudo -u jenkins grep -qE "^${key}=.+" /var/lib/jenkins/ajt-secrets/prod.env \
    && echo "${key}=SET" \
    || echo "${key}=MISSING"
done
```

예상 권한은 `jenkins jenkins 600`이다.

## 5. Jenkins Pipeline job과 GitLab webhook

필요한 Jenkins 플러그인:

- Pipeline
- Git
- GitLab
- Credentials Binding

Jenkins에 GitLab clone용 자격증명을 `Username with password` 타입으로 등록한다. password에는 저장소를 읽을 수 있는 최소 권한의 GitLab token을 넣는다.

두 개의 일반 Pipeline Job을 사용한다. 기존 `S15P11B106-pipeline`은 master 전용으로 유지하고 develop 전용 Job을 추가한다.

```text
공통 설정
  Definition: Pipeline script from SCM
  SCM: Git
  Repository URL: https://lab.ssafy.com/s15-webmobile2-sub1/S15P11B106.git
  Credentials: gitlab-token
  Script Path: Jenkinsfile

develop Job
  Job name: S15P11B106-develop
  Branch Specifier: */develop
  GitLab branch filter: develop

master Job
  Job name: S15P11B106-pipeline
  Branch Specifier: */master
  GitLab branch filter: master
```

develop 검증이 끝나고 같은 Jenkinsfile이 master에 병합되기 전까지 `S15P11B106-pipeline` Job은 Jenkins 화면에서 Disable한다. 기존 443 컨테이너는 중단하지 않는다.

변경 브랜치의 첫 Jenkins 문법 검증 때만 `S15P11B106-develop`의 Branch Specifier를 `*/codex/jenkins-dual-deploy`로 두고 자동 트리거를 끈다. `Build Now` 실행은 Jenkinsfile 문법을 통과한 뒤 resolver가 지원하지 않는 feature 브랜치를 오류로 거부하면 성공이다. feature 브랜치를 develop 환경으로 위장해 배포하지 않는다. MR이 develop에 병합되면 Branch Specifier를 `*/develop`으로 바꾸고 수동 빌드에서 승인 화면과 8090 배포를 검증한 다음 develop Webhook을 활성화한다.

Build Triggers에서 `Build when a change is pushed to GitLab`을 활성화한다.

```text
Push Events: enabled
Opened Merge Request Events: disabled
Accepted Merge Request Events: disabled
Branch filter type: NameBasedFilter
Include: 각 Job의 고정 브랜치(`develop` 또는 `master`)
Exclude: empty
Secret token: Generate
```

Jenkins 화면에 표시된 webhook URL과 생성한 secret token을 GitLab 프로젝트의 Webhooks에 등록한다.

```text
Trigger: Push events
Branch filter: Job과 동일한 브랜치
SSL verification: enabled
```

Job별 webhook URL과 secret token을 GitLab에 각각 등록한다. GitLab의 webhook test가 HTTP 2xx를 반환하는지 확인한다. Jenkins를 외부에 공개해야 한다면 Jenkins 포트를 전체 인터넷에 열지 말고 GitLab에서 도달 가능한 범위로 방화벽 또는 보안그룹을 제한한다.

## 6. feature/develop 8090 검증

운영 env와 별개인 `.env.develop`을 저장소 루트에 만든다. 이 파일은 `.gitignore` 대상이다.

```bash
cd /home/ubuntu/S15P11B106
cp .env.example .env.develop
chmod 600 .env.develop
nano .env.develop
```

검증 환경 고정값:

```dotenv
FRONTEND_PORT=8090
MYSQL_VOLUME_NAME=ajt-develop-mysql-data
AJT_FILES_VOLUME_NAME=ajt-develop-files
```

나머지 비밀값은 운영값을 복사하지 말고 검증용 랜덤값을 사용한다. 내부 공유 키는 랜덤값으로 만들고, GMS 키는 발급된 검증용 키를 넣는다.

```dotenv
AI_INTERNAL_API_KEY=<openssl rand -base64 48>
ANTHROPIC_API_KEY=<SSAFY GMS 검증용 API 키>
```

GMS 키를 아직 받지 못했다면 Jenkins의 테스트·이미지 빌드까지만 진행할 수 있다. Compose
검사와 승인 후 배포는 키가 들어오기 전까지 실패하도록 설정되어 있으므로 임시 Ollama 값이나
가짜 GMS 키로 배포하지 않는다.

Jenkins가 같은 검증 환경을 사용하도록 작성이 끝난 파일을 Jenkins 전용 경로에 복사한다. 이 명령은 develop 환경파일만 덮어쓰며 `prod.env`는 건드리지 않는다.

```bash
sudo install -m 600 -o jenkins -g jenkins \
  /home/ubuntu/S15P11B106/.env.develop \
  /var/lib/jenkins/ajt-secrets/develop.env

sudo stat -c '%U %G %a %n' /var/lib/jenkins/ajt-secrets/develop.env
sudo -u jenkins test -r /var/lib/jenkins/ajt-secrets/develop.env
```

현재 `demo`가 8090을 사용 중이면 해당 프로젝트만 중단한다.

```bash
docker compose -p demo -f docker-compose.demo.yml --env-file .env.demo down
```

feature 또는 develop의 현재 SHA로 이미지를 빌드한다.

```bash
IMAGE_TAG="$(git rev-parse --short=12 HEAD)"

docker build --tag "ajt-backend:${IMAGE_TAG}" backend
docker build --tag "ajt-frontend:${IMAGE_TAG}" frontend
docker build --target test --tag "ajt-ai-test:${IMAGE_TAG}" ai
docker run --rm "ajt-ai-test:${IMAGE_TAG}"
docker build --target runtime --tag "ajt-ai:${IMAGE_TAG}" ai
```

Compose 문법과 최종 값을 먼저 검사한다. 출력에는 환경변수가 포함될 수 있으므로 공유 채널에 그대로 붙이지 않는다.

```bash
DEPLOY_ENV_FILE="$PWD/.env.develop" IMAGE_TAG="$IMAGE_TAG" \
docker compose \
  --project-name ajt-develop \
  --env-file .env.develop \
  --file docker-compose.yml \
  config --services

DEPLOY_ENV_FILE="$PWD/.env.develop" IMAGE_TAG="$IMAGE_TAG" \
docker compose \
  --project-name ajt-develop \
  --env-file .env.develop \
  --file docker-compose.yml \
  config --images
```

예상 서비스는 `mysql`, `ai`, `backend`, `frontend`이다. 이미지에는 `mysql:8.4`와 같은 SHA의
`ajt-ai`, `ajt-backend`, `ajt-frontend`가 표시되어야 한다.

8090에 실행한다.

```bash
DEPLOY_ENV_FILE="$PWD/.env.develop" IMAGE_TAG="$IMAGE_TAG" \
docker compose \
  --project-name ajt-develop \
  --env-file .env.develop \
  --file docker-compose.yml \
  up -d --no-build --remove-orphans

DEPLOY_ENV_FILE="$PWD/.env.develop" IMAGE_TAG="$IMAGE_TAG" \
docker compose \
  --project-name ajt-develop \
  --env-file .env.develop \
  --file docker-compose.yml \
  ps

curl --insecure --fail --show-error https://127.0.0.1:8090/api/v1/health

docker exec ajt-develop-ai-1 python -c \
  "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8000/health').read().decode())"
```

현재 수동 배포 SHA를 Jenkins의 develop 롤백 기준으로 인수한다. backend, frontend, AI 이미지가 모두 로컬에 있을 때만 기록한다.

```bash
CURRENT_DEVELOP_IMAGE="$(docker inspect ajt-develop-backend-1 --format '{{.Config.Image}}')"
CURRENT_DEVELOP_TAG="${CURRENT_DEVELOP_IMAGE##*:}"

if [[ "$CURRENT_DEVELOP_TAG" =~ ^[0-9a-f]{7,40}$ ]] \
  && docker image inspect "ajt-backend:${CURRENT_DEVELOP_TAG}" >/dev/null 2>&1 \
  && docker image inspect "ajt-frontend:${CURRENT_DEVELOP_TAG}" >/dev/null 2>&1 \
  && docker image inspect "ajt-ai:${CURRENT_DEVELOP_TAG}" >/dev/null 2>&1; then
  printf '%s\n' "$CURRENT_DEVELOP_TAG" \
    | sudo -u jenkins tee \
      /var/lib/jenkins/ajt-deploy/develop/current-image-tag >/dev/null
else
  echo "기존 develop 이미지가 SHA 태그가 아니거나 롤백 이미지가 없어 상태 파일을 만들지 않음"
fi
```

Docker의 backend healthcheck도 같은 `/api/v1/health`를 사용한다. `/actuator/health`는 DB뿐 아니라 SMTP 같은 외부 연동 상태까지 포함하므로 컨테이너 기동 판정에는 사용하지 않는다. 비밀번호 재설정 메일을 실제로 사용할 때는 별도로 `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`을 운영 env에 설정한다.

브라우저에서 `https://i15b106.p.ssafy.io:8090`을 확인하고, 컨테이너 재시작 후 `ajt-develop-mysql-data`와 `ajt-develop-files`가 유지되는지 확인한다.

검증이 끝난 뒤 feature→develop MR에 아래 결과를 기록한다.

```text
Backend Gradle test: PASS
Frontend lint/test/build: PASS
AI pytest (non-ocr, non-llm): PASS
Backend/frontend/AI Docker image build: PASS
Compose services/images validation: PASS
8090 backend health, internal AI health, and browser smoke test: PASS
```

## 7. master 첫 운영 전환

develop→master MR을 Squash merge하고 `S15P11B106-pipeline` Job을 Enable하면 master push webhook이 Jenkins를 실행한다. Jenkins가 테스트와 세 이미지 빌드를 완료하면 `Deployment Approval`에서 최대 24시간 대기한다.

서버의 수동 관리용 clone도 merge된 master로 맞춘다.

```bash
cd /home/ubuntu/S15P11B106
git status --short
git switch master
git pull --ff-only origin master
```

승인 버튼을 누르기 전에 현재 컨테이너와 volume 연결을 기록한다.

```bash
docker compose ls
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}\t{{.Status}}'
docker volume ls

docker inspect \
  s15p11b106-frontend-1 \
  s15p11b106-backend-1 \
  s15p11b106-mysql-1 \
  --format '{{.Name}} {{range .Mounts}}{{if eq .Type "volume"}}{{.Name}} {{end}}{{end}}'
```

첫 배포 실패 시 즉시 되살릴 수 있도록 기존 운영 컨테이너와 volume을 아직 삭제하지 않는다. 443을 사용하는 기존 컨테이너만 중단한다.

```bash
docker stop s15p11b106-frontend-1 s15p11b106-backend-1 s15p11b106-mysql-1
```

이제 Jenkins에서 `배포 승인`을 누른다. 승인 화면의 대상이 반드시 `master 443`과 `ajt-prod`인지 확인한다.

성공 기준:

```bash
curl --insecure --fail --show-error https://127.0.0.1/api/v1/health

sudo -u jenkins docker ps \
  --filter label=com.docker.compose.project=ajt-prod \
  --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
```

첫 배포가 실패하면 Jenkins 로그를 확인하고 기존 컨테이너를 복구한다.

```bash
docker start s15p11b106-mysql-1
docker start s15p11b106-backend-1
docker start s15p11b106-frontend-1
curl --insecure --fail --show-error https://127.0.0.1/api/v1/health
```

## 8. 최고관리자 생성

`ajt-prod`가 healthy이고 DB가 비어 있을 때 한 번만 실행한다.

```bash
cd /home/ubuntu/S15P11B106
sudo -u jenkins bash scripts/bootstrap-admin.sh admin@ajt.local
```

출력된 초기 비밀번호는 안전한 전달 수단에 즉시 저장한다. Git, Wiki, Jenkins 로그, 공동 채팅에는 남기지 않는다.

최고관리자는 `ADMIN`, `APPROVED`, `ACTIVE`이고 어떤 부서의 `manager_id`에도 지정되지 않으므로 서비스의 최고관리자 판정과 일치한다.

## 9. 기존 더미 환경 제거

새 운영 로그인까지 확인한 후에만 실행한다.

삭제 대상 후보:

```text
demo
s15p11b106
companya
companyb
companyc
```

현재 브랜치의 `docker-compose.yml`은 새 운영 volume 이름을 사용하므로, 과거 회사 프로젝트를 현재 Compose 파일의 `down --volumes`로 제거하지 않는다. 먼저 컨테이너별 mount를 조회해 과거 volume 이름을 정확히 기록한다.

```bash
docker inspect \
  demo-frontend-1 demo-backend-1 \
  s15p11b106-frontend-1 s15p11b106-backend-1 s15p11b106-mysql-1 \
  companya-frontend-1 companya-backend-1 companya-mysql-1 \
  companyb-frontend-1 companyb-backend-1 companyb-mysql-1 \
  companyc-frontend-1 companyc-backend-1 companyc-mysql-1 \
  --format '{{.Name}} {{range .Mounts}}{{if eq .Type "volume"}}{{.Name}} {{end}}{{end}}'
```

목록에서 `ajt-prod-mysql-data`, `ajt-prod-files`가 아닌 과거 volume만 삭제 대상으로 확정한다. 컨테이너와 volume 이름을 명시적으로 지정하며 `docker volume prune`은 사용하지 않는다.

## 10. 이후 배포와 롤백

두 번째 배포부터 `scripts/deploy.sh`가 환경별 직전 성공 SHA를 다음 파일에 나누어 보관한다. 새 배포의 healthcheck가 실패하면 같은 Compose 프로젝트와 volume을 유지한 채 해당 환경의 직전 backend/frontend/AI 이미지로 자동 복원한다.

```text
develop: /var/lib/jenkins/ajt-deploy/develop/current-image-tag
master:  /var/lib/jenkins/ajt-deploy/prod/current-image-tag
공통 잠금: /var/lib/jenkins/ajt-deploy/deploy.lock
```

기존 master 상태 파일이 있다면 기록된 세 이미지가 모두 로컬에 있을 때만 prod 상태 디렉터리로 복사한다. AI 통합 전 SHA라 `ajt-ai` 이미지가 없으면 상태 파일을 이관하지 않고 첫 통합 배포로 취급한다.

```bash
if sudo test -f /var/lib/jenkins/ajt-deploy/current-image-tag; then
  CURRENT_PROD_TAG="$(sudo sh -c \
    "tr -d '[:space:]' </var/lib/jenkins/ajt-deploy/current-image-tag")"

  if [[ "$CURRENT_PROD_TAG" =~ ^[0-9a-f]{7,40}$ ]] \
    && docker image inspect "ajt-backend:${CURRENT_PROD_TAG}" >/dev/null 2>&1 \
    && docker image inspect "ajt-frontend:${CURRENT_PROD_TAG}" >/dev/null 2>&1 \
    && docker image inspect "ajt-ai:${CURRENT_PROD_TAG}" >/dev/null 2>&1; then
    printf '%s\n' "$CURRENT_PROD_TAG" \
      | sudo -u jenkins tee \
        /var/lib/jenkins/ajt-deploy/prod/current-image-tag >/dev/null
  fi
fi
```

현재 배포 SHA 확인:

```bash
sudo -u jenkins cat /var/lib/jenkins/ajt-deploy/develop/current-image-tag
sudo -u jenkins cat /var/lib/jenkins/ajt-deploy/prod/current-image-tag
```

수동으로 검증된 SHA를 다시 배포할 때:

```bash
cd /home/ubuntu/S15P11B106
sudo -u jenkins env \
  DEPLOY_ENV_FILE=/var/lib/jenkins/ajt-secrets/prod.env \
  DEPLOY_STATE_DIR=/var/lib/jenkins/ajt-deploy/prod \
  DEPLOY_HEALTHCHECK_URL=https://127.0.0.1/api/v1/health \
  DEPLOY_LOCK_FILE=/var/lib/jenkins/ajt-deploy/deploy.lock \
  COMPOSE_PROJECT_NAME=ajt-prod \
  BACKEND_IMAGE=ajt-backend \
  FRONTEND_IMAGE=ajt-frontend \
  AI_IMAGE=ajt-ai \
  bash scripts/deploy.sh <12자리-SHA>
```
