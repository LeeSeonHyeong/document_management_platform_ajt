# AI Docker·Jenkins 통합 배포 설계

## 배경

현재 `develop`에는 FastAPI AI 서버와 GMS 비전 OCR 코드가 있지만 배포 구성에는
MySQL, Spring Boot, React/Nginx만 포함된다. Jenkins도 backend와 frontend 이미지만
빌드하며, Spring Boot는 Compose 밖의 AI 주소를 `AI_BASE_URL`로 받는다.

운영 서버에는 GPU와 Ollama 런타임이 없다. AI의 위키 변환, 챗봇, 일정 추출, 비전 OCR은
모두 SSAFY GMS의 Anthropic 호환 API를 사용해야 한다.

## 목표

- `mysql`, `ai`, `backend`, `frontend`를 하나의 Compose 프로젝트로 배포한다.
- backend, frontend, AI 이미지를 동일한 12자리 Git SHA 태그로 빌드한다.
- develop 8090과 master 443 배포 모두 사람이 승인한 뒤에만 실행한다.
- 세 애플리케이션 이미지가 하나의 배포 단위로 함께 배포되고 함께 롤백되게 한다.
- 운영 Compose에서는 Ollama를 선택할 수 없게 하고 GMS Anthropic을 고정한다.
- 실제 API 키는 Git에 저장하지 않고 Jenkins가 읽는 서버 환경파일에서만 주입한다.

## 비목표

- AI 애플리케이션의 프롬프트, 모델 호출 알고리즘, API 계약을 변경하지 않는다.
- AI의 요청별 임시 작업 공간을 영속 볼륨으로 보존하지 않는다.
- AI 포트 8000을 호스트나 인터넷에 공개하지 않는다.
- 로컬 개발과 실험에서 사용하는 Ollama 지원 코드를 제거하지 않는다. 운영 Compose 경로만
  Anthropic으로 고정한다.

## 구조

```text
Browser
  -> frontend:443
      -> backend:8080
          -> ai:8000
              -> SSAFY GMS Anthropic API
          <- ai:8000
      <- backend:8080

ai:8000
  -> backend:8080/internal/*  # 위키 조회용 내부 API
```

Compose 네트워크 밖으로 공개되는 포트는 frontend의 443 또는 8090뿐이다. `backend`,
`mysql`, `ai`는 Compose 서비스 이름으로만 통신한다.

## AI 이미지

`ai/Dockerfile`은 Python 3.12 기반 다단계 이미지로 만든다.

- 잠금 파일 `uv.lock`을 사용해 의존성을 재현 가능하게 설치한다.
- 운영 런타임에는 `deepagents` optional dependency를 포함한다.
- 테스트 단계에는 dev dependency와 `ai/tests`를 포함한다.
- 운영 단계는 비루트 사용자로 실행한다.
- 시작 명령은 `python -m wiki_api.serve --host 0.0.0.0 --port 8000`이다.
- `ai/.dockerignore`는 실험 결과, 문서, 로컬 가상환경, 실제 `.env`를 빌드 문맥에서 제외한다.

AI 앱은 인증이 필요 없는 `GET /health`에서 `{"status":"UP"}`를 반환한다. 이 엔드포인트는
Docker healthcheck 전용이며 `/internal/v1` 계약에는 포함하지 않는다.

## Compose 연결

`docker-compose.yml`에 `ai` 서비스를 추가한다.

```text
AI image:      ajt-ai:${IMAGE_TAG}
AI port:       expose 8000 only
AI runtime:    deepagents
AI provider:   anthropic
AI base URL:   SSAFY GMS Anthropic gateway
Backend URL:   http://backend:8080
```

운영 Compose는 다음 값을 고정한다.

```dotenv
AI_RUNTIME=deepagents
SCHEDULE_EXTRACTOR_PROVIDER=anthropic
ANTHROPIC_BASE_URL=https://gms.ssafy.io/gmsapi/api.anthropic.com
AI_MODEL=anthropic:claude-opus-4-6
AI_MODEL_FAST=anthropic:claude-haiku-4-5-20251001
AI_MODEL_QUALITY=anthropic:claude-sonnet-4-6
SCHEDULE_EXTRACTOR_MODEL=claude-opus-4-6
```

`SCHEDULE_EXTRACTOR_PROVIDER`와 GMS 주소는 서버 환경파일 값으로 덮어쓰지 못하게 Compose에
직접 지정한다. 따라서 운영 배포가 실수로 `ollama`나 `localhost:11434`를 호출하지 않는다.

Spring Boot의 `AI_BASE_URL`은 `http://ai:8000`으로 강제한다. AI의
`BACKEND_BASE_URL`은 `http://backend:8080`으로 강제한다.

서버 환경파일에는 실제 값 없이 다음 비밀 변수 이름만 요구한다.

```dotenv
AI_INTERNAL_API_KEY=
ANTHROPIC_API_KEY=
```

Compose는 `AI_INTERNAL_API_KEY`를 AI 컨테이너의 `INTERNAL_API_KEY`로 주입한다. 별도 키를
두 번 관리하지 않으므로 Spring과 AI의 내부 인증키가 항상 같다. 비밀 값이 비어 있으면
Compose 변수 검증 또는 AI 기동 검증에서 배포가 실패해야 한다.

## 기동과 상태 확인

기동 순서는 다음과 같다.

1. MySQL과 AI를 시작한다.
2. MySQL과 AI healthcheck가 성공하면 backend를 시작한다.
3. backend healthcheck가 성공하면 frontend를 시작한다.
4. 배포 스크립트가 외부 frontend 경유 `/api/v1/health`를 확인한다.

AI 요청은 시작 시점에 backend를 호출하지 않으므로 AI와 backend의 런타임 호출 관계가
Compose 기동 순환 의존성을 만들지 않는다.

## Jenkins 파이프라인

Jenkins는 승인 전에 다음을 실행한다.

1. backend Gradle 테스트
2. frontend lint, 테스트, 빌드
3. AI의 외부 LLM·로컬 OCR을 제외한 결정적 pytest
4. backend, frontend, AI Docker 이미지 빌드
5. 세 이미지가 현재 `IMAGE_TAG`로 존재하는지 확인

그 뒤 develop 또는 master 대상이 표시된 수동 승인 단계에서 대기한다. 승인 후에는 다시
checkout한 SHA가 빌드 SHA와 같은지 검증하고 `scripts/deploy.sh`를 실행한다.

## 배포와 롤백

`scripts/deploy.sh`는 다음 이미지를 모두 검사한다.

```text
ajt-backend:${IMAGE_TAG}
ajt-frontend:${IMAGE_TAG}
ajt-ai:${IMAGE_TAG}
```

배포 상태 파일은 환경별로 하나의 SHA를 보존한다. 새 배포의 Compose 기동 또는 healthcheck가
실패하면 세 이미지 모두 직전 SHA로 되돌린다. 이전 SHA 이미지 중 하나라도 로컬에 없으면
불완전한 롤백을 시도하지 않고 명확한 오류를 남긴다.

develop과 master Job은 기존 공통 `flock` 파일을 사용하므로 같은 Docker daemon에서 동시에
Compose를 변경하지 않는다.

## 보안

- `ANTHROPIC_API_KEY`와 `AI_INTERNAL_API_KEY`는 Git, Jenkins 로그, Compose 출력에 노출하지 않는다.
- AI 포트는 `ports`로 발행하지 않는다.
- AI 내부 API는 기존 `X-Internal-API-Key` 인증을 유지한다.
- healthcheck는 비밀 값이나 모델 설정을 반환하지 않는다.
- 서버 환경파일은 `jenkins:jenkins`, 권한 `600`을 유지한다.

## 테스트

- AI `/health` 단위 테스트
- AI Dockerfile 테스트 target 실행
- Compose 서비스, 이미지, 내부 URL, GMS 고정값 검증
- Ollama provider와 `localhost:11434`가 운영 Compose 결과에 없는지 검증
- deploy 스크립트가 AI 이미지 누락을 거부하는 테스트
- 성공 배포와 세 이미지 롤백 테스트
- develop/master resolver와 공통 배포 잠금 회귀 테스트
- Bash 문법, `docker compose config`, `git diff --check`, 비밀 패턴 검사

## 전환 순서

1. 기존 Jenkins 이중 배포 작업과 최신 develop을 이 브랜치에서 합친다.
2. AI Docker·Compose·Jenkins·롤백 연결을 구현하고 검증한다.
3. 별도 MR을 develop 대상으로 생성한다.
4. 병합 후 서버의 develop/prod 환경파일에 두 API 키를 입력한다.
5. `S15P11B106-develop`을 수동 실행해 승인 전 테스트와 8090 통합 배포를 검증한다.
6. develop 검증이 끝난 뒤에만 master Job과 443 전환을 진행한다.
