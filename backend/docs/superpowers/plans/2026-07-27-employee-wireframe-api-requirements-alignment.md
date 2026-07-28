# 사원 와이어프레임 API·요구사항 정합성 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 수정된 사원 와이어프레임을 기준으로 요구사항 정의서와 Postman 공개 API 명세를 통일한다.

**Architecture:** 요구사항 정의서를 정책 원문으로 갱신하고 Postman 생성기를 같은 요청·응답·권한 규칙으로 수정한 뒤 컬렉션을 재생성한다. SQL·ERD와 PNG는 이번 작업에서 변경하지 않는다.

**Tech Stack:** Markdown, Node.js, Postman Collection v2.1 JSON

## Global Constraints

- 아이디 찾기, 사원 검색, 본인 정보 수정과 직접 비밀번호 변경 API를 만들지 않는다.
- 회원가입 사번은 승인 시 시스템이 생성한다.
- 문의 담당자 후보는 모든 `APPROVED`·`ACTIVE` 상태 `ADMIN`이다.
- 일정에는 별도 첨부파일이 없고 일정 원본문서는 사원에게 노출하지 않는다.
- 질문은 `conversationId` 기반 멀티턴이며 질문 유형은 AI가 자동 판단한다.
- 관리자·사원 PNG, SQL과 ERD는 수정하지 않는다.

---

### Task 1: 회귀 검증 기준 갱신

**Files:**
- Modify: `scripts/validate-artifact-consistency.mjs`

**Interfaces:**
- Consumes: 생성된 공개 Postman 컬렉션과 요구사항 정의서
- Produces: 신규 엔드포인트·멀티턴·일정 첨부 제거를 검사하는 정합성 검증

- [ ] 신규 엔드포인트 3개와 공개 API 53개를 기대하도록 검증을 먼저 수정한다.
- [ ] 질문 요청의 `conversationId`, 일정 요청의 첨부파일 부재, 문의 후보 조건을 검사한다.
- [ ] 검증을 실행해 기존 산출물에서 의도한 실패를 확인한다.

### Task 2: 요구사항 정의서 갱신

**Files:**
- Modify: `요구사항정의서_v2 1.md`

**Interfaces:**
- Consumes: 확정 정책
- Produces: 사원 기능의 최신 요구사항 원문

- [ ] 버전과 변경 요약을 갱신한다.
- [ ] 가입 승인 시 사번 생성, 가입용 부서 조회, 조회 전용 내 정보를 명시한다.
- [ ] 멀티턴 질문 정책을 복원하고 단일턴·범위 제외 문구를 제거한다.
- [ ] 모든 관리자 문의 후보와 담당자 부서 표시를 명시한다.
- [ ] 일정 첨부파일 요구사항을 제거하고 일정 원본문서의 사원 비노출을 유지한다.
- [ ] 아이디 찾기·사원 검색·본인 수정·직접 비밀번호 변경이 없음을 명시한다.

### Task 3: Postman 생성기와 컬렉션 갱신

**Files:**
- Modify: `postman/generate-postman-collections.mjs`
- Regenerate: `postman/AJT-Backend-Public-API.postman_collection.json`
- Regenerate: `postman/AJT-FastAPI-Internal-API.postman_collection.json`
- Regenerate: `postman/AJT-Local.postman_environment.json`

**Interfaces:**
- Consumes: 최신 요구사항
- Produces: 프론트엔드·백엔드 협업용 Postman 명세

- [ ] `GET /api/v1/signup-departments`, `GET /api/v1/me`, `GET /api/v1/inquiry-assignees`를 추가한다.
- [ ] 가입 승인 API에 시스템 사번 생성을 명시한다.
- [ ] 질문 API 요청·응답과 이력 필터에 `conversationId`를 추가한다.
- [ ] 일정 생성·수정·상세에서 첨부파일을 제거하고 기존 다운로드 API를 관리자용 원본문서 API로 바꾼다.
- [ ] 문의 후보·등록 설명을 모든 활성·승인 관리자로 수정하고 목록 정렬을 추가한다.
- [ ] 생성기를 실행해 컬렉션을 재생성한다.

### Task 4: 최종 검증

**Files:**
- Verify: 요구사항, Postman 생성기와 생성 컬렉션

**Interfaces:**
- Consumes: Task 1~3 결과
- Produces: 정합성 검증 결과

- [ ] JavaScript 문법과 JSON 파싱을 검사한다.
- [ ] Postman Collection v2.1 공식 스키마를 검사한다.
- [ ] 일정 첨부파일·단일턴·부서 관리자 전용 문의 후보 구정책을 검색한다.
- [ ] 전체 정합성 검증을 실행해 통과 결과를 확인한다.
