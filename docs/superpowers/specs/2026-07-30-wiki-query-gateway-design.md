# Wiki 조회 창구 내부 API 설계

## 목표

FastAPI 에이전트가 요청 단위 권한 안에서만 Wiki·원본문서 파싱본을 조회하도록 계약 v1.6.0의 내부 API 7개를 제공한다.

## 범위와 제약

- 경로는 `/internal/v1`이며 `X-Internal-API-Key`와 `X-Wiki-Capability`를 모두 검증한다.
- DB 스키마는 변경하지 않는다. ERD에 이미 있는 `wiki_scope.scope_version`을 엔티티에 매핑한다.
- capability는 단일 서버 인스턴스의 메모리 TTL 레지스트리에만 보관한다. 발급 시 scopeKey·scopeVersion·만료시각을 묶고, 호출 종료·취소·만료 시 폐기한다.
- capability 값·본문·파싱본은 로그에 기록하지 않는다.
- capability 범위 밖, 만료, 존재하지 않는 범위/리소스는 모두 계약의 404 오류 코드로 반환해 존재를 숨긴다. 발급 뒤 scopeVersion이 바뀌었으면 요청을 404로 막지 않고 최신 버전을 응답해 AI가 `scope_changed`로 중단한다.

## 구성

`WikiCapabilityService`가 난수 capability를 발급·검증·폐기한다. 변환/편집을 시작하는 기존 AI 호출부가 capability를 발급해 FastAPI 요청의 `wikiCapability`에 넣고, 완료 시 폐기한다.

`InternalWikiQueryController`는 내부 API 7개만 노출한다. 컨트롤러는 내부 API 키와 capability를 확인한 뒤 `InternalWikiQueryService`에 scopeKey와 capability의 scopeVersion을 전달한다. 공개 API용 `WikiQueryService`의 로그인 사용자 권한 모델은 재사용하지 않는다.

`InternalWikiQueryService`는 검색 청크 저장소, Wiki/카테고리/범위/문서 저장소, Wiki·문서 파일 저장소를 사용한다. 검색은 `scope_key`를 SQL 조건에 포함한 자연어 FULLTEXT 검색을 사용한다. Wiki 목록·관계·카테고리는 같은 scope의 데이터만 반환하고, backlinks는 현재 scope Wiki 목록에서 계산한다.

## 응답과 오류

- 모든 Wiki 범위 응답은 현재 `scopeVersion`을 포함한다.
- 본문과 파싱본의 파일 유실은 `WIKI_NOT_FOUND` 또는 `DOCUMENT_NOT_FOUND` 404로 변환한다.
- 내부 API 키 누락·불일치는 401이다.
- capability 누락·만료·scopeKey 불일치는 `WIKI_CAPABILITY_EXPIRED` 404다. scopeVersion 차이는 정상 응답의 최신 값으로 노출한다.
- 요청 파라미터의 형식·limit 범위 오류는 400이다.

## 검증

- capability 발급·만료·폐기·scope/version 불일치 단위 테스트
- 각 endpoint의 정상 응답, 내부 키 401, capability 404, 범위 밖 Wiki/문서 404 웹 계층 테스트
- 검색 SQL에 scope 필터가 포함되고, 관계의 backlinks가 같은 scope에서만 계산되는 서비스 테스트
