# AI Docker 테스트 컨텍스트와 일정 모델 변경 설계

## 배경

Jenkins의 AI 테스트 이미지는 `src/`와 `tests/`만 복사한다. 일부 테스트는
`ai/experiments/query_gateway.py`와 `ai/experiments/experiment.py`를 직접 임포트하므로,
테스트 수집 단계에서 `ModuleNotFoundError`가 발생한다. 또한 서버 배포의 일정 추출 모델은
현재 `claude-opus-4-6`으로 고정되어 있으나 `claude-haiku-4-5-20251001`을 사용해야 한다.

## 결정

1. Docker 빌드 컨텍스트에서는 `experiments/`를 허용한다.
2. `ai/Dockerfile`의 `test` 스테이지에만 `experiments/`를 복사한다.
3. `runtime-build`와 최종 `runtime` 스테이지에는 `experiments/`를 복사하지 않는다.
4. `docker-compose.yml`의 `SCHEDULE_EXTRACTOR_MODEL`을
   `claude-haiku-4-5-20251001`로 고정한다.
5. `AI_MODEL`, `AI_MODEL_FAST`, `AI_MODEL_QUALITY`은 변경하지 않는다.

## 검증

- Compose 회귀 검사는 일정 추출 모델이 정확히 Haiku인지 확인한다.
- Dockerfile 회귀 검사는 테스트 스테이지에 `COPY experiments ./experiments`가 존재하고,
  런타임 스테이지에는 해당 폴더가 복사되지 않는지 확인한다.
- 서버 Jenkins에서 AI 테스트 이미지 빌드와 비-LLM·비-OCR pytest 실행을 최종 확인한다.

## 영향

테스트 이미지와 빌드 컨텍스트 크기는 증가하지만 운영 AI 이미지 크기와 파일 구성은 변하지
않는다. 일정 추출 호출만 Haiku로 바뀌며 위키 생성, 챗봇, OCR 모델은 기존 설정을 유지한다.
