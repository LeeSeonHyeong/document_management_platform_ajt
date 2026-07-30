# AI 발급 Wiki 경로 반영 설계

## 목적

FastAPI Wiki 변환 응답의 생성 변경이 제공하는 `wikiChanges[].wikiPath`를 DB와 실제 Markdown 파일에 동일하게 반영한다. 페이지 파일명은 AI가 발급한 `pageKey`를 사용하며, Spring Boot는 Wiki ID에서 파일 경로를 유도하지 않는다.

## 범위

- `wikiPath`를 이용한 생성 Wiki 파일 저장
- 기존 Wiki의 DB `wiki_path`를 이용한 본문 수정·삭제
- Wiki 본문 Markdown 링크의 scope·존재 검증
- 생성 변경에서 `wikiPath`가 누락된 경우의 명시적 실패

프론트 공개 API와 ERD 변경은 포함하지 않는다.

## 결정

### 파일 저장

`WikiFileStorage`는 `scopeKey`와 `wikiId`를 받아 경로를 만드는 대신, 검증 완료된 전체 상대 `wikiPath`를 받아 파일을 저장한다. `LocalWikiFileStorage`는 기존의 루트 탈출 방어를 유지한다.

생성 Wiki는 `wikiPath`를 필수로 검증·저장한 다음 해당 경로에 본문을 쓴다. 수정과 삭제는 이미 저장된 `wiki.wikiPath()`를 사용한다. 따라서 DB의 `wiki_path`와 실제 파일 경로가 항상 같다.

### 본문 링크

AI 서버는 새 페이지에도 `pages/{pageKey}.md` 주소를 이미 발급한다. Spring Boot가 `tempWikiId`나 Wiki ID로 본문을 다시 쓰지 않는다.

반영 전에는 Markdown 인라인 링크와 이미지가 아닌 Wiki 페이지 링크를 수집해 다음만 허용한다.

- `pages/{pageKey}.md` 형식의 같은 scope 상대 경로
- 현재 scope에 이미 존재하거나 이번 응답의 생성 변경에 포함된 Wiki 경로

외부 URL·앵커 전용 링크·메일 링크는 Wiki 페이지 링크 검증 대상에서 제외한다. `..`을 포함하거나 존재하지 않는 Wiki 페이지를 가리키면 전체 반영을 실패시킨다.

## 반영 순서

1. 카테고리와 생성 Wiki 행을 반영해 실제 Wiki ID를 발급한다.
2. 모든 생성 Wiki의 `wikiPath`를 모아, 기존 scope Wiki 경로와 합친 허용 경로 집합을 만든다.
3. 생성·수정 본문 링크를 검증한다.
4. 검증된 본문을 해당 `wikiPath`에 쓰고 검색 청크를 갱신한다.
5. 관계와 목차를 반영한다.

## 오류와 테스트

- 생성 변경의 `wikiPath` 누락, scope 불일치, 경로 탈출, 중복 경로는 `IllegalArgumentException`으로 실패한다.
- 새 페이지가 서로 링크하는 경우와 기존 페이지를 링크하는 경우를 통과시킨다.
- 없는 페이지 또는 다른 scope 경로를 링크하는 경우 실패를 검증한다.
- 로컬 파일 저장소 테스트로 AI 발급 경로에 실제 파일이 생성되는지 확인한다.
