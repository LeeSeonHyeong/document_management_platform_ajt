# HttpOnly 쿠키 인증 설계

## 목표

공개 API의 Bearer access token 인증을 같은 사이트 환경의 HttpOnly JWT 쿠키 인증으로 교체하고, 모든 상태 변경 요청에 CSRF 검증을 적용한다.

## 인증 쿠키

- 로그인 성공 시 Spring Boot는 JWT를 `AJT_ACCESS_TOKEN` 쿠키로 발급한다.
- 쿠키 속성은 운영 기본값 `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`이다.
- 로컬 HTTP 프로파일에서만 `Secure=false`를 사용한다.
- 응답 JSON과 프론트엔드 JavaScript는 JWT 값에 접근하지 않는다.
- 보호 API는 `Authorization` 헤더가 아니라 `AJT_ACCESS_TOKEN` 쿠키에서 JWT를 읽는다.

## CSRF

- `GET /api/v1/auth/csrf`는 JavaScript가 읽을 수 있는 `XSRF-TOKEN` 쿠키를 발급한다.
- POST, PUT, PATCH, DELETE 요청은 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보내야 한다.
- CSRF 토큰은 인증 정보가 아니며 HttpOnly를 적용하지 않는다.
- CSRF 헤더가 없거나 값이 다르면 403을 반환한다.

## 로그인과 로그아웃

- `POST /api/v1/auth/login`은 `Set-Cookie: AJT_ACCESS_TOKEN=...`과 로그인 사용자 정보·만료 초만 반환한다.
- `POST /api/v1/auth/logout`은 CSRF 검증 뒤 `AJT_ACCESS_TOKEN`을 `Max-Age=0`으로 만료한다.
- refresh token과 서버 토큰 블랙리스트는 제공하지 않는다.

## 계약 영향

- Postman 계약은 v1.3.0, 요구사항은 v2.8이다.
- 공개 API는 CSRF 발급·로그아웃 API가 추가돼 55개다.
- Postman은 `accessToken` 환경값을 사용하지 않고 Cookie Jar와 `csrfToken` 환경값을 사용한다.
- FastAPI 내부 API와 DB 테이블은 변경하지 않는다.

## 테스트

- 로그인 성공 시 인증 쿠키 속성과 토큰 비노출 확인
- 인증 쿠키가 있는 보호 API 접근과 없는 접근의 200·401 확인
- CSRF 헤더 누락·불일치의 403 확인
- 로그아웃 후 만료 쿠키와 보호 API 401 확인
