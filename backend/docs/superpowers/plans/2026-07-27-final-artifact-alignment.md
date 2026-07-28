# AJT Final Artifact Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 요구사항 명세서, ERDCloud 스냅샷, SQL, Postman Collection과 REST API 개발 컨벤션을 최종 확정 정책에 맞춘다.

**Architecture:** 요구사항 명세서를 정책 기준으로 사용하고, ERD 생성 스크립트와 Postman 생성 스크립트를 각 생성물의 단일 원본으로 유지한다. 자동 정합성 검증기가 요구사항 문구, DB 필드 타입, API 경로와 핵심 요청 필드를 교차 검사한다.

**Tech Stack:** Markdown, MySQL 8.4 LTS, ERDCloud snapshot JSON, Postman Collection v2.1, Node.js

## Global Constraints

- 신규 업무 테이블을 추가하지 않는다.
- Wiki 본문과 목차는 파일에 저장하고 DB에는 상대 경로만 저장한다.
- 프론트엔드는 Spring Boot만 호출하고 FastAPI는 DB를 직접 변경하지 않는다.
- 파일당 20MB, 파일 배열 최대 20개, 요청당 파일 총합 100MB를 적용한다.
- 질문 유형은 `WIKI`, `SCHEDULE`, `MIXED`이며 AI가 자동 판단한다.

---

### Task 1: 자동 정합성 검증

**Files:**
- Create: `scripts/validate-artifact-consistency.mjs`

**Interfaces:**
- Consumes: 요구사항, SQL, ERDCloud JSON, Postman JSON
- Produces: 불일치가 있으면 종료 코드 1, 모두 일치하면 종료 코드 0

- [x] 현재 산출물에서 실패하는 핵심 정책 검사를 작성한다.
- [x] 검증기를 실행해 예상한 불일치로 실패하는지 확인한다.

### Task 2: 요구사항과 컨벤션 정리

**Files:**
- Modify: `요구사항정의서_v2 1.md`
- Modify: `REST API 개발 컨벤션.md`

**Interfaces:**
- Consumes: 최종 정합성 설계
- Produces: API와 ERD가 따라야 할 확정 정책

- [x] 파일 총용량, 혼합 질문, 2단계 답변, 링크 검증과 일정 동기 처리를 반영한다.
- [x] 문의·검색·다운로드·부서 관리자 예외 정책을 명확히 한다.
- [x] MySQL 8.4 LTS와 추천 타입 규칙을 반영한다.

### Task 3: SQL과 ERDCloud 스냅샷 정리

**Files:**
- Modify: `erdTable.sql`
- Modify: `scripts/generate-erdcloud-snapshot.mjs`
- Regenerate: `erdTable-snapshot-ajt.json`

**Interfaces:**
- Consumes: 요구사항의 데이터 요구사항
- Produces: 동일한 16개 테이블과 타입·관계를 가진 SQL 및 ERDCloud JSON

- [x] SQL을 실제 MySQL 8.4 타입, 키와 제약조건으로 정리한다.
- [x] ERD 생성 스크립트의 타입·NULL·상태 설명을 SQL과 맞춘다.
- [x] 기존 스냅샷을 템플릿으로 새 ERDCloud JSON을 생성한다.

### Task 4: Postman API 정리

**Files:**
- Modify: `postman/generate-postman-collections.mjs`
- Regenerate: `postman/AJT-Backend-Public-API.postman_collection.json`
- Regenerate: `postman/AJT-FastAPI-Internal-API.postman_collection.json`
- Regenerate: `postman/AJT-Local.postman_environment.json`

**Interfaces:**
- Consumes: 확정 요구사항과 ERD
- Produces: 프론트→백엔드 및 백엔드→AI 개발 계약

- [x] 공개 질문 API를 질문만 받도록 변경한다.
- [x] AI 자료 선택 API와 최종 답변 API를 정의한다.
- [x] 파싱 API를 Wiki·일정 공용으로 만들고 일정 CSV/XLSX를 포함한다.
- [x] 문의 우선순위, 검색 필터, 다운로드, Wiki 카테고리와 작업 중단 API를 반영한다.
- [x] 일정 업로드를 동기 응답으로 바꾸고 수정 가능 필드를 완성한다.
- [x] Collection과 Environment를 재생성한다.

### Task 5: 최종 교차 검증

**Files:**
- Verify: all artifacts above

**Interfaces:**
- Consumes: 수정된 모든 산출물
- Produces: 개발 시작 가능한 정합성 검증 결과

- [x] Postman 자체 검증기를 실행한다.
- [x] 자동 정합성 검증기를 실행한다.
- [x] JSON 파싱, ERD 테이블 수, API 개수와 핵심 enum을 확인한다.
- [x] 요구사항의 미확정 표현과 제거된 정책이 남아 있지 않은지 검색한다.
