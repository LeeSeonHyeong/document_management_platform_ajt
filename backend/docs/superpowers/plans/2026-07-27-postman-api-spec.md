# Postman API Specification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AJT 공개 API와 FastAPI 내부 API를 Postman에 바로 가져올 수 있는 Collection과 Environment 파일로 작성한다.

**Architecture:** Postman Collection v2.1 JSON을 사용한다. 요청별 description을 Postman Docs 원문으로 사용하고, Collection 변수와 Environment 변수를 분리한다.

**Tech Stack:** Postman Collection v2.1, JSON, Node.js

## Global Constraints

- 공개 API 기본 경로는 `/api/v1`이다.
- 내부 AI API 기본 경로는 `/internal/v1`이다.
- Frontend는 FastAPI를 직접 호출하지 않는다.
- JSON 필드는 camelCase, enum은 소문자 snake_case, 오류 코드는 대문자 SNAKE_CASE를 사용한다.
- Postman에서 구조를 먼저 합의하고 Notion에는 확정 내용과 링크를 정리한다.

---

### Task 1: Collection 생성기 작성

**Files:**
- Create: `postman/generate-postman-collections.mjs`

**Interfaces:**
- Consumes: 프로젝트 요구사항 정의서와 REST API 개발 컨벤션
- Produces: 공개 API Collection, 내부 API Collection, 로컬 Environment

- [ ] API 요청 생성 헬퍼와 공통 Docs 설명 형식을 작성한다.
- [ ] 공개 API 요청을 도메인 폴더별로 정의한다.
- [ ] FastAPI 내부 요청을 정의한다.
- [ ] Collection v2.1 JSON과 Environment JSON을 출력한다.

### Task 2: 생성 결과 검증

**Files:**
- Test: `postman/*.json`

**Interfaces:**
- Consumes: Task 1의 생성 파일
- Produces: Postman import 가능한 JSON 검증 결과

- [ ] Node.js로 모든 JSON 파일을 파싱한다.
- [ ] Collection 스키마 URL, Request 개수와 description 필수 구역을 검사한다.
- [ ] URL이 공개 API와 내부 API 경계를 위반하지 않는지 검사한다.

### Task 3: 개발 계약 v1.0.0 고정

**Files:**
- Modify: `postman/generate-postman-collections.mjs`
- Modify: `scripts/validate-artifact-consistency.mjs`
- Create: `postman/README.md`

**Interfaces:**
- Consumes: 확정된 요구사항 v2.5와 공개 API 53개·내부 API 6개
- Produces: 프론트엔드·Spring Boot·FastAPI가 공통으로 사용하는 P0 Saved Example과 버전 규칙

- [ ] 검사기에 `contractVersion: 1.0.0`과 P0 성공·오류 Saved Example 조건을 추가하고 실패를 확인한다.
- [ ] 생성기에 공통 오류 응답과 P0 성공 응답 예시를 추가한다.
- [ ] 공개 API와 내부 API Collection을 다시 생성한다.
- [ ] README에 계약 변경 규칙, 데이터 타입과 6명 담당 범위를 기록한다.
- [ ] JSON 파싱과 정합성 검사를 실행해 통과를 확인한다.
