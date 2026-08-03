# develop 자동 배포 설계

## 목적

`develop` 브랜치에 Merge Request가 병합되면 Jenkins가 변경을 감지해 테스트와 Docker 이미지 빌드를 수행하고, 성공한 동일 커밋을 별도 승인 없이 8090 검증 환경에 배포한다.

운영 브랜치인 `master`는 기존 정책을 유지한다. 테스트와 이미지 빌드가 성공하더라도 사람의 명시적 승인이 있어야 443 운영 환경에 배포한다.

## 범위

- `S15P11B106-develop` Job만 SCM 변경 감지를 활성화한다.
- `develop` 배포는 수동 승인 입력을 건너뛴다.
- `master` 배포는 24시간 제한의 수동 승인 입력을 유지한다.
- 테스트를 통과하지 못하거나 이미지 빌드가 실패하면 어느 환경에도 배포하지 않는다.
- 빌드 커밋과 배포 커밋의 SHA 일치 검증, 배포 잠금, healthcheck 및 자동 롤백은 기존 동작을 유지한다.

다음 작업은 자동화하지 않는다.

- Docker volume 삭제와 DB 초기화
- 최고관리자 초기 계정 생성
- `ALL` Wiki와 기본 카테고리 부트스트랩

## 설계

### 배포 대상 판별

`scripts/resolve-deploy-target.sh`가 기존 환경별 설정과 함께 승인 필요 여부를 반환한다.

- `develop`, `origin/develop`, `*/develop`: `DEPLOY_APPROVAL_REQUIRED=false`
- `master`, `origin/master`, `*/master`: `DEPLOY_APPROVAL_REQUIRED=true`

Jenkinsfile에서 브랜치 문자열을 다시 해석하지 않고 이 값을 사용한다. 배포 환경과 승인 정책을 한 곳에서 결정해 분기 규칙의 중복과 불일치를 막는다.

### 승인 단계

`Deployment Approval` 단계는 다음처럼 동작한다.

- `DEPLOY_APPROVAL_REQUIRED=false`: 승인 단계를 건너뛰고 즉시 다음 단계로 이동한다.
- `DEPLOY_APPROVAL_REQUIRED=true`: 기존과 동일하게 최대 24시간 사람의 승인을 기다리고 승인자를 기록한다.

Declarative Pipeline의 stage-level `input` 지시문은 기본적으로 `when`보다 먼저 실행된다. `when`에 `beforeInput true`를 지정해 승인 필요 여부를 먼저 평가하고, master에서만 기존 stage-level `input`을 실행한다.

### 자동 실행 트리거

`S15P11B106-develop` Job에는 `Poll SCM` 스케줄 `H/2 * * * *`를 설정한다. 현재 Jenkins 8080 포트는 제한된 IP만 접근할 수 있어 GitLab Webhook보다 인바운드 방화벽 변경이 필요 없는 Poll SCM을 사용한다.

`S15P11B106-pipeline` master Job의 트리거 설정은 이 작업에서 변경하지 않는다. master Job이 기존 Webhook이나 수동 실행으로 시작되더라도 수동 승인 정책은 유지한다.

## 동작 흐름

### develop

1. MR이 `develop`에 병합된다.
2. develop Job이 최대 약 2분 안에 변경을 감지한다.
3. Jenkins가 해당 커밋을 checkout한다.
4. Backend, Frontend, AI 테스트를 실행한다.
5. 세 Docker 이미지를 동일한 Git SHA 태그로 빌드한다.
6. 승인 입력을 건너뛴다.
7. 8090 `ajt-develop` 환경에 배포한다.
8. healthcheck 실패 시 기존 롤백 절차를 실행한다.

### master

1. master Job이 실행된다.
2. 테스트와 이미지 빌드를 수행한다.
3. 최대 24시간 배포 승인을 기다린다.
4. 승인된 경우에만 443 `ajt-prod` 환경에 배포한다.

## 오류 처리

- 승인 정책 값이 누락되거나 `true`/`false`가 아니면 배포 전에 Pipeline을 실패시킨다.
- 테스트 또는 Docker 빌드가 실패하면 승인·배포 단계로 진행하지 않는다.
- develop 자동 배포에서도 기존 배포 잠금 파일을 사용해 동시 배포를 방지한다.
- `disableConcurrentBuilds()`를 유지해 동일 Job의 실행이 겹치지 않게 한다.

## 검증

- resolver 테스트에서 develop 변형은 승인 불필요, master 변형은 승인 필요임을 검증한다.
- Jenkins Pipeline 정적 테스트에서 승인 정책 값을 읽고 검증하는지 확인한다.
- Jenkins Pipeline 정적 테스트에서 develop 자동 경로와 master 수동 `input` 경로가 모두 존재하는지 확인한다.
- 기존 AI 테스트, 배포 대상 판별 테스트와 shell 구문 검사를 함께 실행한다.
- 실제 Jenkins에서는 develop Job의 자동 실행, 승인 단계 무대기 통과, 8090 healthcheck를 확인한다.

## 운영 전환

나중에 master를 실제 운영 배포 대상으로 사용할 때도 이 설계는 변경하지 않는다. master Job은 수동 실행 또는 별도로 승인된 트리거 정책을 사용하되, Jenkinsfile 내부의 사람 승인 단계는 계속 유지한다.
