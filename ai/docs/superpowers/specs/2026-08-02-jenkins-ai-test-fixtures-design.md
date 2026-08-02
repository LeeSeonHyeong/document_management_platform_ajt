# Jenkins AI Test Fixtures Design

## Goal

Jenkins AI 테스트 이미지가 저장소 공통 API 계약을 읽을 수 있게 하고, 로컬 전용
`claude-code` 런타임 단위 테스트가 Jenkins 컨테이너의 CLI 설치 여부에 의존하지 않게 한다.

## Design

- AI Docker 빌드 컨텍스트와 런타임 이미지는 변경하지 않는다.
- Jenkins의 AI 테스트 컨테이너 실행 시 `${WORKSPACE}/docs`를 `/docs`에 읽기 전용으로
  마운트한다. 계약 테스트가 사용하는 절대 경로와 일치하며 운영 이미지에는 문서가 남지 않는다.
- `claude-code` 배선 자체를 검증하는 세 단위 테스트에서만 `shutil.which("claude")`가
  실행 파일 경로를 반환하도록 모킹한다.
- 실제 CLI가 없을 때 기동을 거부하는 전용 테스트와 운영 가드는 그대로 유지한다.

## Verification

- Jenkins 정적 회귀 검사가 문서 마운트 옵션을 요구한다.
- `ai/tests/api/test_serve.py`가 통과한다.
- Jenkins AI Test 전체에서 계약 테스트와 serve 테스트를 포함해 통과한다.

