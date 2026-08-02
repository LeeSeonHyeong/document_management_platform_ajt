# Jenkins develop·master 이중 배포 설계

## 목표

한 대의 Jenkins 서버에서 같은 저장소를 두 개의 일반 Pipeline Job으로 운영한다.

- `S15P11B106-develop`: `develop`을 검증하고 승인 후 8090의 `ajt-develop`에 배포한다.
- `S15P11B106-pipeline`: `master`를 검증하고 승인 후 443의 `ajt-prod`에 배포한다.
- feature·fix 브랜치는 두 배포 Job의 대상이 아니다.
- 두 환경 모두 테스트와 이미지 빌드가 성공한 뒤 사람의 승인을 받아야 배포한다.
- 빌드한 Git SHA와 배포하는 Git SHA가 같아야 한다.

AI Docker 이미지 빌드와 AI 서비스 배포는 일정 추출 API 전환이 `develop`에 병합된 뒤 이 구조에 추가한다. 이번 단계에서는 기존 backend·frontend·MySQL 배포 흐름만 이중 환경으로 분리한다.

## 선택한 방식

기존 Job을 develop과 master 사이에서 계속 바꾸거나 Multibranch Pipeline으로 교체하지 않는다. 기존 master Job을 보존하고 develop용 일반 Pipeline Job을 하나 추가한다.

이 방식은 다음 장점이 있다.

- Job의 SCM 브랜치와 배포 대상이 고정되어 develop이 443 운영 환경을 덮지 않는다.
- master 전환 시 Jenkinsfile이나 Job 설정을 다시 바꿀 필요가 없다.
- 기존 `gitlab-token` Credential과 Pipeline 플러그인을 그대로 사용한다.
- 현재 운영 Job을 삭제하거나 형식을 변환하지 않아 복구가 쉽다.

## Job 구성

### Develop Job

| 항목 | 값 |
| --- | --- |
| Job 이름 | `S15P11B106-develop` |
| 유형 | Pipeline |
| SCM 브랜치 | `*/develop` |
| Script Path | `Jenkinsfile` |
| Credential | `gitlab-token` |
| GitLab 브랜치 필터 | `develop`만 허용 |
| 환경 파일 | `/var/lib/jenkins/ajt-secrets/develop.env` |
| Compose 프로젝트 | `ajt-develop` |
| 상태 디렉터리 | `/var/lib/jenkins/ajt-deploy/develop` |
| 확인 URL | `https://127.0.0.1:8090/api/v1/health` |

### Master Job

| 항목 | 값 |
| --- | --- |
| Job 이름 | `S15P11B106-pipeline` |
| 유형 | 기존 Pipeline 유지 |
| SCM 브랜치 | `*/master` |
| Script Path | `Jenkinsfile` |
| Credential | `gitlab-token` |
| GitLab 브랜치 필터 | `master`만 허용 |
| 환경 파일 | `/var/lib/jenkins/ajt-secrets/prod.env` |
| Compose 프로젝트 | `ajt-prod` |
| 상태 디렉터리 | `/var/lib/jenkins/ajt-deploy/prod` |
| 확인 URL | `https://127.0.0.1/api/v1/health` |

develop 검증이 끝나고 해당 Jenkinsfile이 master에 병합되기 전까지 master Job의 자동 트리거와 정기 빌드는 비활성화한다. 기존 운영 컨테이너는 중단하지 않는다.

## Jenkinsfile 동작

Checkout 직후 `BRANCH_NAME` 또는 `GIT_BRANCH`에서 실제 브랜치를 구한다. 정확히 `develop` 또는 `master`일 때만 배포 대상으로 인정하고, 브랜치에 맞는 환경 값을 설정한다. 다른 값은 실패 처리하여 잘못된 Job 설정이 엉뚱한 환경에 배포되는 것을 막는다.

두 Job의 공통 흐름은 다음과 같다.

1. SCM Checkout
2. 12자리 Git SHA 산출
3. 브랜치와 배포 대상 매핑
4. Backend 테스트
5. Frontend lint·테스트·빌드
6. backend·frontend Docker 이미지 빌드
7. 배포 대상과 SHA를 표시하고 사람 승인 대기
8. 배포 직전 다시 Checkout하여 SHA 일치 확인
9. `scripts/deploy.sh` 실행
10. HTTP healthcheck 성공 시 상태 SHA 저장
11. 실패하면 같은 환경의 직전 SHA로 롤백

승인 문구에는 브랜치, Compose 프로젝트, 포트, 이미지 SHA를 표시한다. 승인 제한시간은 기존과 같이 24시간이다.

## 환경과 비밀값

develop과 master는 서로 다른 env 파일과 Docker volume 이름을 사용한다. 실제 env 파일은 Git에 커밋하지 않고 Jenkins 사용자만 읽을 수 있게 한다.

```text
/var/lib/jenkins/ajt-secrets/develop.env  owner=jenkins:jenkins mode=600
/var/lib/jenkins/ajt-secrets/prod.env     owner=jenkins:jenkins mode=600
```

`develop.env`의 포트와 volume 이름은 다음처럼 운영 환경과 분리한다.

```dotenv
FRONTEND_PORT=8090
MYSQL_VOLUME_NAME=ajt-develop-mysql-data
AJT_FILES_VOLUME_NAME=ajt-develop-files
```

API 키와 DB 비밀번호는 로그에 출력하지 않는다. Compose의 전체 렌더링 결과도 공유 채널에 붙이지 않는다.

## 기존 develop 배포 인수

현재 수동으로 실행 중인 `ajt-develop`의 컨테이너와 volume을 그대로 사용한다. Jenkins 첫 배포 전에 실행 중인 backend 이미지의 SHA 태그를 확인한다.

- 해당 SHA 이미지가 로컬에 남아 있으면 develop 상태 파일에 기록하여 첫 Jenkins 배포도 롤백할 수 있게 한다.
- 이미지 태그가 없거나 로컬 이미지가 없으면 상태 파일을 만들지 않는다. 이 경우 첫 Jenkins 배포 실패 시 배포 스크립트의 기존 정책대로 해당 Compose 프로젝트의 컨테이너와 네트워크를 내리되 volume은 보존한다.
- DB와 파일 volume은 삭제하거나 초기화하지 않는다.

기존 master 배포 상태 파일 `/var/lib/jenkins/ajt-deploy/current-image-tag`가 있다면 master Job을 다시 활성화하기 전에 `/var/lib/jenkins/ajt-deploy/prod/current-image-tag`로 복사한다. 기록된 backend·frontend 이미지가 로컬에 모두 존재할 때만 복사하여 유효하지 않은 태그로 롤백하는 일을 막는다.

## 동시 실행과 안전장치

각 Job 안에서는 `disableConcurrentBuilds()`를 유지한다. master Job을 비활성화한 상태에서 develop을 먼저 검증하므로 초기 도입 중에는 두 배포가 동시에 실행되지 않는다.

master Job을 다시 활성화하기 전에는 서버 단위 배포 잠금을 추가한다. 잠금은 build·test가 아니라 실제 `scripts/deploy.sh` 실행 구간에만 적용하여 두 Job이 동시에 Compose 상태를 바꾸지 못하게 한다. Jenkins 플러그인 추가를 피하기 위해 Jenkins가 소유한 공통 잠금 파일 `/var/lib/jenkins/ajt-deploy/deploy.lock`과 `flock`을 사용한다.

각 환경은 Compose 프로젝트, env 파일, 상태 파일, 포트와 volume이 모두 분리되어야 한다. 이미지 이름은 공유하지만 SHA 태그가 달라 충돌하지 않는다.

## GitLab Webhook

develop Job의 GitLab 트리거는 develop push 또는 merge만 허용한다. 기존 master Job도 master만 허용하도록 `branchFilterType=All` 설정을 수정한다. 다른 브랜치 push가 고정 브랜치의 불필요한 빌드를 일으키지 않게 한다.

Webhook은 Job별 URL과 토큰을 사용한다. develop Webhook을 먼저 추가해 성공·실패 응답을 확인한 뒤 master Webhook을 유지하거나 재설정한다.

## 검증

구현 후 다음 순서로 확인한다.

1. Jenkinsfile의 develop·master 매핑을 각각 검증한다.
2. `scripts/tests/deploy-test.sh`로 성공, 최초 실패, 이전 SHA 롤백을 검증한다.
3. Jenkins의 Pipeline 문법 검사를 통과한다.
4. develop Job을 수동 실행하여 테스트와 이미지 빌드까지만 확인한다.
5. 승인 후 `ajt-develop`만 재배포되는지 확인한다.
6. 8090 healthcheck와 브라우저 로그인을 확인한다.
7. MySQL·파일 volume이 기존 이름으로 유지되는지 확인한다.
8. GitLab develop Webhook으로 동일 흐름이 자동 시작되는지 확인한다.
9. master Job이 비활성화되어 443 환경에 변화가 없는지 확인한다.

## AI 배포 후속 작업

일정 추출 API 전환이 완료되면 동일한 파이프라인에 다음을 추가한다.

- AI 단위·계약 테스트
- `ajt-ai:<Git SHA>` 이미지 빌드
- Compose `ai` 서비스와 내부 healthcheck
- backend의 `AI_BASE_URL=http://ai:8000`
- AI의 `BACKEND_BASE_URL=http://backend:8080`
- 세 이미지의 SHA 일치 검증과 일괄 롤백

AI API 계약과 환경변수 이름이 확정되기 전에는 이 항목을 현재 Jenkins 변경에 포함하지 않는다.
