# AJT 단일 서버 Jenkins 배포 런북

## 1. 배포 원칙

- 서버: Ubuntu 24.04 x86_64 한 대
- Jenkins: 호스트 systemd 서비스
- Docker: 호스트 Docker daemon
- 운영 Compose 프로젝트: `ajt-prod`
- 운영 포트: `443`
- 검증 Compose 프로젝트: `ajt-develop`
- 검증 포트: `8090`
- 이미지: `ajt-backend:<12자리 Git SHA>`, `ajt-frontend:<12자리 Git SHA>`
- 자동 실행: GitLab `master` push webhook
- 운영 반영: Jenkins 테스트·이미지 빌드 성공 후 사람의 승인
- AI: 별도 서버. 이 저장소에서는 컨테이너를 실행하지 않고 `AI_BASE_URL`과 `AI_INTERNAL_API_KEY`만 주입

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

## 4. 운영 비밀 환경파일 준비

디렉터리와 빈 파일을 먼저 최소 권한으로 만든다.

```bash
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-secrets
sudo install -d -m 700 -o jenkins -g jenkins /var/lib/jenkins/ajt-deploy
sudo install -m 600 -o jenkins -g jenkins /dev/null /var/lib/jenkins/ajt-secrets/prod.env
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
AI_BASE_URL=<AI 담당자가 전달한 실제 내부 URL>
AI_INTERNAL_API_KEY=<AI 담당자와 합의한 공유 키>
```

검사할 때 값 자체를 출력하지 않는다.

```bash
sudo stat -c '%U %G %a %n' /var/lib/jenkins/ajt-secrets/prod.env
sudo -u jenkins test -r /var/lib/jenkins/ajt-secrets/prod.env
```

예상 권한은 `jenkins jenkins 600`이다.

## 5. Jenkins Pipeline job과 GitLab webhook

필요한 Jenkins 플러그인:

- Pipeline
- Git
- GitLab
- Credentials Binding

Jenkins에 GitLab clone용 자격증명을 `Username with password` 타입으로 등록한다. password에는 저장소를 읽을 수 있는 최소 권한의 GitLab token을 넣는다.

Pipeline job을 다음과 같이 만든다.

```text
Job name: ajt-master-deploy
Definition: Pipeline script from SCM
SCM: Git
Repository URL: https://lab.ssafy.com/s15-webmobile2-sub1/S15P11B106.git
Credentials: 위에서 등록한 GitLab 자격증명
Branch Specifier: */master
Script Path: Jenkinsfile
```

Build Triggers에서 `Build when a change is pushed to GitLab`을 활성화한다.

```text
Push Events: enabled
Opened Merge Request Events: disabled
Accepted Merge Request Events: disabled
Branch filter type: NameBasedFilter
Include: master
Exclude: empty
Secret token: Generate
```

Jenkins 화면에 표시된 webhook URL과 생성한 secret token을 GitLab 프로젝트의 Webhooks에 등록한다.

```text
Trigger: Push events
Branch filter: master
SSL verification: enabled
```

GitLab의 webhook test가 HTTP 2xx를 반환하는지 확인한다. Jenkins를 외부에 공개해야 한다면 Jenkins 포트를 전체 인터넷에 열지 말고 GitLab에서 도달 가능한 범위로 방화벽 또는 보안그룹을 제한한다.

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

나머지 비밀값은 운영값을 복사하지 말고 검증용 랜덤값을 사용한다. AI 서버가 아직 준비되지 않았으면 AI 기능 검증은 제외하고 아래처럼 즉시 연결 거부되는 로컬 discard 포트와 검증용 랜덤 키를 사용한다. 운영 env에는 이 값을 사용하지 않는다.

```dotenv
AI_BASE_URL=http://127.0.0.1:9
AI_INTERNAL_API_KEY=<openssl rand -base64 48>
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

예상 서비스는 `mysql`, `backend`, `frontend`이고 AI 서비스는 없어야 한다.

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
```

Docker의 backend healthcheck도 같은 `/api/v1/health`를 사용한다. `/actuator/health`는 DB뿐 아니라 SMTP 같은 외부 연동 상태까지 포함하므로 컨테이너 기동 판정에는 사용하지 않는다. 비밀번호 재설정 메일을 실제로 사용할 때는 별도로 `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`을 운영 env에 설정한다.

브라우저에서 `https://i15b106.p.ssafy.io:8090`을 확인하고, 컨테이너 재시작 후 `ajt-develop-mysql-data`와 `ajt-develop-files`가 유지되는지 확인한다.

검증이 끝난 뒤 feature→develop MR에 아래 결과를 기록한다.

```text
Backend Gradle test: PASS
Frontend lint/test/build: PASS
Docker image build: PASS
Compose services/images validation: PASS
8090 health and browser smoke test: PASS
```

## 7. master 첫 운영 전환

develop→master MR을 Squash merge하면 GitLab push webhook이 Jenkins를 실행한다. Jenkins가 테스트와 두 이미지 빌드를 완료하면 `Production Approval`에서 최대 24시간 대기한다.

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

이제 Jenkins에서 `운영 배포 승인`을 누른다.

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

두 번째 운영 배포부터 `scripts/deploy.sh`가 직전 성공 SHA를 `/var/lib/jenkins/ajt-deploy/current-image-tag`에 보관한다. 새 배포의 healthcheck가 실패하면 같은 Compose 프로젝트와 volume을 유지한 채 직전 backend/frontend 이미지로 자동 복원한다.

현재 배포 SHA 확인:

```bash
sudo -u jenkins cat /var/lib/jenkins/ajt-deploy/current-image-tag
```

수동으로 검증된 SHA를 다시 배포할 때:

```bash
cd /home/ubuntu/S15P11B106
sudo -u jenkins env \
  DEPLOY_ENV_FILE=/var/lib/jenkins/ajt-secrets/prod.env \
  DEPLOY_STATE_DIR=/var/lib/jenkins/ajt-deploy \
  DEPLOY_HEALTHCHECK_URL=https://127.0.0.1/api/v1/health \
  COMPOSE_PROJECT_NAME=ajt-prod \
  bash scripts/deploy.sh <12자리-SHA>
```
