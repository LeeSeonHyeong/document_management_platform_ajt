import { readFileSync } from "node:fs";

const read = (path) => readFileSync(path, "utf8");
const requirements = read("docs/requirements/요구사항정의서.md");
const convention = read("docs/conventions/rest-api-convention.md");
const fileStructureDesign = read(
  "backend/docs/superpowers/specs/2026-07-26-file-directory-structure-design.md",
);
const sql = read("docs/db/erd.sql");
const executableSql = sql
  .replace(/\/\*[\s\S]*?\*\//g, "")
  .replace(/--.*$/gm, "");
const erd = JSON.parse(read("docs/db/erd-snapshot.json"));
const publicCollection = JSON.parse(
  read("docs/api/AJT-Backend-Public-API.postman_collection.json"),
);
const internalCollection = JSON.parse(
  read("docs/api/AJT-FastAPI-Internal-API.postman_collection.json"),
);

const failures = [];
const expect = (condition, message) => {
  if (!condition) failures.push(message);
};

const flattenRequests = (collection) =>
  collection.item.flatMap((folder) =>
    folder.item.map((item) => ({
    folder: folder.name,
    name: item.name,
    method: item.request.method,
    path: item.request.url.raw,
    body: item.request.body,
    description: item.request.description ?? "",
    responses: item.response ?? [],
    })),
  );

const publicRequests = flattenRequests(publicCollection);
const internalRequests = flattenRequests(internalCollection);
const findRequest = (requests, method, suffix) =>
  requests.find(
    (item) =>
      item.method === method && item.path.split("?")[0].endsWith(suffix),
  );

expect(requirements.includes("MySQL 8.4 LTS"), "요구사항에 MySQL 8.4 LTS가 없음");
expect(requirements.includes("총 파일 크기는 최대 100MB"), "요구사항에 요청당 총 100MB 제한이 없음");
expect(requirements.includes("`MIXED`"), "요구사항에 MIXED 질문 유형이 없음");
expect(requirements.includes("answer-context-selections"), "요구사항에 2단계 자료 선택 API가 없음");
expect(requirements.includes("wiki-context-selections"), "요구사항에 Wiki 변환 문맥 선택 API가 없음");
expect(requirements.includes("링크 정합성"), "요구사항에 백엔드 링크 정합성 검사가 없음");
expect(requirements.includes("`FAILED` 또는 `CANCELLED`"), "요구사항의 재처리 상태가 FAILED·CANCELLED로 통일되지 않음");
expect(requirements.includes("`signup_status`"), "요구사항에 가입 승인 상태 컬럼이 없음");
expect(requirements.includes("`assignee_id`"), "요구사항에 문의 담당자 컬럼이 없음");
expect(requirements.includes("conversationId"), "요구사항에 멀티턴 conversationId가 없음");
expect(requirements.includes("가입 승인 시") && requirements.includes("사번"), "요구사항에 승인 시 사번 생성 정책이 없음");
expect(
  requirements.includes("`schedule`에는 `attachment_refs`"),
  "요구사항에 일정 attachment_refs 미사용 정책이 명시되지 않음",
);
expect(!requirements.includes("`target_department_id`"), "요구사항에 이전 문의 대상 부서 컬럼이 남아 있음");
expect(!requirements.includes("일정 첨부파일"), "요구사항에 삭제된 일정 첨부파일 정책이 남아 있음");
expect(!requirements.includes("신청 기한"), "요구사항에 삭제된 신청 기한 정책이 남아 있음");
expect(!requirements.includes("북마크"), "요구사항에 삭제된 북마크 정책이 남아 있음");
expect(!/[재휴퇴]직/.test(requirements), "요구사항에 이전 재직·휴직·퇴사 상태가 남아 있음");
expect(!requirements.includes("복구 요청"), "요구사항에 삭제된 복구 요청 정책이 남아 있음");
expect(!convention.includes("document-parses"), "컨벤션에 이전 document-parses API가 남아 있음");
expect(
  convention.includes(
    "`timestamp`, `status`, `error`, `code`, `message`, `path`, `fieldErrors`",
  ),
  "컨벤션의 공통 오류 응답 필드가 Postman v1.3.0 계약과 일치하지 않음",
);
expect(
  convention.includes(
    "오류 응답 본문에는 `requestId`를 포함하지 않고 `X-Request-Id` 응답 헤더로 제공합니다.",
  ),
  "컨벤션에 requestId의 헤더 전용 전달 정책이 명시되지 않음",
);
expect(
  fileStructureDesign.includes("apply-state.json") &&
    !fileStructureDesign.includes("`state.json`"),
  "파일 구조 설계의 작업 상태 파일명이 apply-state.json으로 통일되지 않음",
);
expect(
  fileStructureDesign.includes("요청당 총 100MB"),
  "파일 구조 설계에 첨부파일 총 100MB 제한이 없음",
);

const requirementIds = [...requirements.matchAll(/^\| ((?:FR|DR|NFR)-[A-Z]+-\d+) \|/gm)].map(
  ([, id]) => id,
);
expect(
  new Set(requirementIds).size === requirementIds.length,
  "현재 요구사항 표에 중복 ID가 있음",
);

expect(
  /CREATE\s+DATABASE\s+IF\s+NOT\s+EXISTS\s+`ajt`\s+CHARACTER\s+SET\s+utf8mb4\s+COLLATE\s+utf8mb4_0900_ai_ci\s*;/i.test(
    executableSql,
  ),
  "SQL에 MySQL 8.4용 ajt 데이터베이스 생성 구문이 없음",
);
expect(
  /\bUSE\s+`ajt`\s*;/i.test(executableSql),
  "SQL에 USE ajt 구문이 없음",
);
expect(
  !/\b(?:DROP\s+(?:DATABASE|TABLE)|TRUNCATE(?:\s+TABLE)?|DELETE\s+FROM|INSERT\s+INTO)\b/i.test(
    executableSql,
  ),
  "SQL에 금지된 파괴적 구문 또는 초기 데이터 삽입이 있음",
);
expect(
  !/CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS/i.test(executableSql),
  "SQL 테이블 생성이 기존 테이블을 무시하도록 작성됨",
);
expect(sql.includes("DATETIME(6)"), "SQL 시간이 DATETIME(6)이 아님");
expect(!sql.includes("\t`status`\tENUM"), "SQL 상태 컬럼에 ENUM이 남아 있음");
expect(sql.includes("`password_hash`"), "SQL에 password_hash가 없음");
expect(sql.includes("`signup_status` VARCHAR(30) NOT NULL"), "SQL에 signup_status가 없음");
expect(sql.includes("`assignee_id` BIGINT UNSIGNED NOT NULL"), "SQL에 inquiry.assignee_id가 없음");
expect(
  sql.includes("`conversation_key` VARCHAR(100) NOT NULL"),
  "SQL에 ai_question.conversation_key가 없음",
);
expect(
  sql.includes(
    "KEY `idx_ai_question_member_conversation_created` (`member_id`, `conversation_key`, `created_at`)",
  ),
  "SQL에 멀티턴 질문 조회 인덱스가 없음",
);
expect(!sql.includes("`target_department_id`"), "SQL에 이전 target_department_id가 남아 있음");
expect(
  /`question_type`\s+VARCHAR\(30\)\s+NULL/.test(sql),
  "SQL question_type이 nullable VARCHAR(30)이 아님",
);

const entityByName = new Map(erd.entityData.map((entity) => [entity.pName, entity]));
const entityById = new Map(erd.entityData.map((entity) => [entity._id, entity]));
const field = (table, name) => {
  const entity = entityByName.get(table);
  return [...entity.keys.pks, ...entity.fields].find((item) => item.pName === name);
};

expect(entityByName.size === erd.entityData.length, "ERD 테이블 물리명이 중복됨");
expect(entityById.size === erd.entityData.length, "ERD 엔터티 ID가 중복됨");

const erdFieldIds = new Set();
for (const entity of erd.entityData) {
  for (const item of [...entity.keys.pks, ...entity.fields]) {
    expect(!erdFieldIds.has(item._id), `ERD 필드 ID가 중복됨: ${item._id}`);
    erdFieldIds.add(item._id);
    if (item.relEntity !== null) {
      const targetEntity = entityById.get(item.relEntity);
      expect(targetEntity, `ERD 관계 대상 엔터티가 없음: ${entity.pName}.${item.pName}`);
      expect(
        targetEntity &&
          [...targetEntity.keys.pks, ...targetEntity.fields].some(
            (target) => target._id === item.relFieldId,
          ),
        `ERD 관계 대상 필드가 없음: ${entity.pName}.${item.pName}`,
      );
    }
  }
}

const sqlTables = new Map();
for (const match of sql.matchAll(
  /CREATE TABLE `([^`]+)` \(([\s\S]*?)\n\) ENGINE=InnoDB[^;]*;/g,
)) {
  const [, tableName, body] = match;
  const columns = new Map();
  for (const line of body.split("\n")) {
    const column = line.match(
      /^\s+`([^`]+)`\s+(BIGINT UNSIGNED|DATETIME\(6\)|VARCHAR\(\d+\)|JSON|TEXT|BOOLEAN)(?=\s)/,
    );
    if (column) columns.set(column[1], column[2]);
  }
  sqlTables.set(tableName, columns);
}

// 부서 참조 무결성을 DB가 강제하도록 schedule_department 조인 테이블을 유지하고,
// Wiki 본문 전문 검색을 위한 파생 색인 wiki_search_chunk 를 더해 17개다.
expect(sqlTables.size === 17, `SQL CREATE TABLE 수가 17개가 아님: ${sqlTables.size}`);
expect(
  sqlTables.size === entityByName.size &&
    [...sqlTables.keys()].every((name) => entityByName.has(name)),
  "SQL과 ERD의 테이블 목록이 일치하지 않음",
);

for (const [tableName, columns] of sqlTables) {
  const entity = entityByName.get(tableName);
  if (!entity) continue;
  const erdColumns = new Map(
    [...entity.keys.pks, ...entity.fields].map((item) => [item.pName, item.type]),
  );
  expect(
    columns.size === erdColumns.size &&
      [...columns].every(
        ([name, type]) => erdColumns.get(name)?.toUpperCase() === type,
      ),
    `SQL과 ERD의 컬럼 또는 타입이 일치하지 않음: ${tableName}`,
  );
}

for (const match of sql.matchAll(
  /REFERENCES `([^`]+)` \(`([^`]+)`\)/g,
)) {
  const [, targetTable, targetColumn] = match;
  expect(
    sqlTables.get(targetTable)?.has(targetColumn),
    `존재하지 않는 FK 대상: ${targetTable}.${targetColumn}`,
  );
}

const constraintNames = [...sql.matchAll(/CONSTRAINT `([^`]+)`/g)].map(
  ([, name]) => name,
);
expect(
  new Set(constraintNames).size === constraintNames.length,
  "SQL 제약조건 이름이 중복됨",
);
expect(
  constraintNames.every((name) => name.length <= 64),
  "SQL 제약조건 이름이 MySQL 64자 제한을 초과함",
);
expect(
  (sql.match(/CREATE TABLE /g) ?? []).length ===
    (sql.match(/\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;/g) ?? [])
      .length,
  "SQL CREATE TABLE 문장의 종료 구문이 맞지 않음",
);

expect(field("member", "password_hash"), "ERD에 password_hash가 없음");
expect(field("member", "signup_status")?.type === "VARCHAR(30)", "ERD에 signup_status가 없음");
expect(field("inquiry", "assignee_id")?.type === "BIGINT UNSIGNED", "ERD에 inquiry.assignee_id가 없음");
expect(!field("inquiry", "target_department_id"), "ERD에 이전 target_department_id가 남아 있음");
expect(
  field("ai_question", "conversation_key")?.type === "VARCHAR(100)" &&
    !field("ai_question", "conversation_key")?.isAllowNull,
  "ERD에 필수 ai_question.conversation_key가 없음",
);
expect(
  !field("schedule", "attachment_refs") &&
    !sqlTables.get("schedule")?.has("attachment_refs"),
  "일정에서 제거한 attachment_refs가 SQL 또는 ERD에 남아 있음",
);
expect(field("member", "created_at")?.type === "DATETIME(6)", "ERD 시간 타입이 DATETIME(6)이 아님");
expect(field("document", "status")?.type === "VARCHAR(30)", "ERD 문서 상태가 VARCHAR(30)이 아님");
expect(field("ai_question", "question_type")?.isAllowNull, "ERD question_type이 nullable이 아님");
expect(field("ai_question", "question_type")?.comment.includes("MIXED"), "ERD question_type에 MIXED가 없음");

const publicQuestion = findRequest(publicRequests, "POST", "/api/v1/questions");
const publicQuestionBody = JSON.parse(publicQuestion?.body?.raw ?? "{}");
expect(publicQuestionBody.question, "공개 질문 API에 question이 없음");
expect(!("questionType" in publicQuestionBody), "공개 질문 API가 questionType을 받고 있음");

expect(
  findRequest(internalRequests, "POST", "/internal/v1/answer-context-selections"),
  "AI 자료 선택 API가 없음",
);
expect(
  findRequest(internalRequests, "POST", "/internal/v1/wiki-context-selections"),
  "Wiki 변환 문맥 선택 API가 없음",
);
expect(
  findRequest(internalRequests, "POST", "/internal/v1/answers"),
  "AI 답변 생성 API가 없음",
);
expect(
  findRequest(internalRequests, "POST", "/internal/v1/source-parses"),
  "Wiki·일정 공용 파싱 API가 없음",
);
expect(
  findRequest(publicRequests, "GET", "/api/v1/signup-requests"),
  "가입 요청 목록 API가 없음",
);
expect(
  findRequest(publicRequests, "POST", "/api/v1/signup-requests/:userId/approve"),
  "가입 승인 API가 없음",
);
expect(
  findRequest(publicRequests, "POST", "/api/v1/signup-requests/:userId/reject"),
  "가입 거부 API가 없음",
);
expect(
  findRequest(publicRequests, "GET", "/api/v1/signup-departments"),
  "비로그인 가입용 부서 목록 API가 없음",
);
expect(
  findRequest(publicRequests, "GET", "/api/v1/me"),
  "조회 전용 내 정보 API가 없음",
);
expect(
  findRequest(publicRequests, "GET", "/api/v1/inquiry-assignees"),
  "문의 담당자 후보 API가 없음",
);
expect(
  findRequest(publicRequests, "GET", "/api/v1/schedules/:scheduleId/source-file"),
  "관리자 일정 원본문서 API가 없음",
);
expect(
  !findRequest(
    publicRequests,
    "GET",
    "/api/v1/schedules/:scheduleId/attachments/:attachmentId",
  ),
  "삭제된 일정 첨부파일 API가 남아 있음",
);
expect(publicRequests.length === 56, `공개 API 수가 56개가 아님: ${publicRequests.length}`);
// 기존 7개 + Wiki 조회 창구 8개. 창구는 FastAPI 가 Spring Boot 를 호출하는 반대 방향이라
// 같은 내부 컬렉션에 있지만 baseVariable 이 backendBaseUrl 이다.
expect(internalRequests.length === 15, `내부 API 수가 15개가 아님: ${internalRequests.length}`);

const collectionVariable = (collection, key) =>
  collection.variable?.find((item) => item.key === key)?.value;
// 1.5.0 은 Wiki 조회 창구 7개 신설. 1.6.0 은 그 창구를 실제로 부를 입력 계약이다 —
// wiki-transformations·wiki-edits 에 wikiCapability·scopeVersion 을 더하고
// selectedWikis[].wikiPath 를 필수화한다. 필수화는 하위 호환이 아니라 minor 이상이다.
// 1.4.0 을 건너뛴 이유는 README 가 1.4.0, 컬렉션이 1.3.1 로 갈라져 있었기 때문이다.
// 1.6.1 은 범위 단위 관계 조회 창구 1개 추가다 — 기존 필드·경로 무변경이라 patch 다
// (S15P11B106-153).
// 1.6.2 는 relationChanges[] 필드 이름을 Spring 어휘(sourceWikiRef)에 맞춘 것과 그
// action 값을 Spring 어휘(add/remove)에 맞춘 것이다 — 필드·값 이름만 바뀌고 구조는
// 그대로라 patch 다 (S15P11B106-157).
const expectedContractVersion = "1.6.2";
expect(
  collectionVariable(publicCollection, "contractVersion") === expectedContractVersion,
  `공개 API 계약 버전이 ${expectedContractVersion}이 아님`,
);
expect(
  collectionVariable(internalCollection, "contractVersion") === expectedContractVersion,
  `내부 API 계약 버전이 ${expectedContractVersion}이 아님`,
);

const p0PublicEndpoints = [
  ["POST", "/api/v1/auth/login"],
  ["GET", "/api/v1/auth/csrf"],
  ["POST", "/api/v1/auth/logout"],
  ["POST", "/api/v1/auth/signup"],
  ["GET", "/api/v1/signup-departments"],
  ["GET", "/api/v1/me"],
  ["GET", "/api/v1/users"],
  ["POST", "/api/v1/signup-requests/:userId/approve"],
  ["GET", "/api/v1/departments"],
  ["POST", "/api/v1/documents"],
  ["GET", "/api/v1/ai-jobs/:jobId"],
  ["GET", "/api/v1/wiki-spaces"],
  ["GET", "/api/v1/wiki-categories"],
  ["GET", "/api/v1/wikis"],
  ["GET", "/api/v1/wikis/:wikiId"],
  ["POST", "/api/v1/questions"],
  ["GET", "/api/v1/questions"],
  ["POST", "/api/v1/schedule-sources"],
  ["GET", "/api/v1/schedules"],
  ["POST", "/api/v1/schedules"],
  ["GET", "/api/v1/schedules/:scheduleId"],
  ["GET", "/api/v1/inquiry-assignees"],
  ["POST", "/api/v1/inquiries"],
  ["GET", "/api/v1/inquiries"],
  ["GET", "/api/v1/inquiries/:inquiryId"],
];

const assertSavedExamples = (requestItem, label) => {
  expect(requestItem, `${label} 요청이 없음`);
  if (!requestItem) return;
  const success = requestItem.responses.find(
    (response) => response.code >= 200 && response.code < 300,
  );
  const error = requestItem.responses.find((response) => response.code >= 400);
  expect(success, `${label} 성공 Saved Example이 없음`);
  expect(error, `${label} 오류 Saved Example이 없음`);
  for (const response of [success, error].filter(Boolean)) {
    try {
      JSON.parse(response.body);
    } catch {
      expect(false, `${label} ${response.name} 본문이 JSON이 아님`);
    }
    expect(
      response.header?.some(
        (header) =>
          header.key.toLowerCase() === "x-request-id" &&
          typeof header.value === "string" &&
          header.value.length > 0,
      ),
      `${label} ${response.name}에 X-Request-Id 응답 헤더가 없음`,
    );
  }
  if (error) {
    const errorBody = JSON.parse(error.body);
    const expectedErrorFields = [
      "timestamp",
      "status",
      "error",
      "code",
      "message",
      "path",
      "fieldErrors",
    ].sort();
    const permitsFailureStage = [
      "/internal/v1/wiki-context-selections",
      "/internal/v1/wiki-transformations",
    ].includes(requestItem.path);
    const actualErrorFields = Object.keys(errorBody).sort();
    const allowedErrorFields = [
      ...expectedErrorFields,
      ...(permitsFailureStage ? ["failureStage"] : []),
    ].sort();
    expect(
      expectedErrorFields.every((field) => Object.hasOwn(errorBody, field)) &&
        actualErrorFields.every((field) => allowedErrorFields.includes(field)),
      `${label} 오류 Saved Example의 필드 집합이 v1.3.0 계약과 일치하지 않음`,
    );
    if (permitsFailureStage) {
      expect(
        ["context_load", "agent_timeout", "agent_error", "lint_failed", "assemble"].includes(
          errorBody.failureStage,
        ),
        `${label} 오류 Saved Example의 failureStage 값이 유효하지 않음`,
      );
    }
    expect(
      !Object.hasOwn(errorBody, "requestId"),
      `${label} 오류 Saved Example 본문에 금지된 requestId가 있음`,
    );
  }
};

for (const [method, suffix] of p0PublicEndpoints) {
  assertSavedExamples(
    findRequest(publicRequests, method, suffix),
    `P0 공개 ${method} ${suffix}`,
  );
}
for (const requestItem of internalRequests) {
  assertSavedExamples(
    requestItem,
    `P0 내부 ${requestItem.method} ${requestItem.path}`,
  );
}

const parseRawRequestBody = (method, path) => {
  const requestItem = findRequest(internalRequests, method, path);
  expect(requestItem, `${method} ${path} 내부 API가 없음`);
  try {
    return JSON.parse(requestItem.body.raw);
  } catch {
    expect(false, `${method} ${path} 요청 본문이 JSON이 아님`);
    return {};
  }
};

const contextSelectionBody = parseRawRequestBody(
  "POST",
  "/internal/v1/wiki-context-selections",
);
expect(
  contextSelectionBody.changeType === "document_replaced" &&
    typeof contextSelectionBody.removedParsedMarkdown === "string",
  "Wiki 문맥 선택 요청에 document_replaced changeType 또는 removedParsedMarkdown이 없음",
);

const transformationBody = parseRawRequestBody(
  "POST",
  "/internal/v1/wiki-transformations",
);
expect(
  transformationBody.changeType === "document_replaced" &&
    typeof transformationBody.removedParsedMarkdown === "string",
  "Wiki 변환 요청에 document_replaced changeType 또는 removedParsedMarkdown이 없음",
);

const transformationRequest = findRequest(
  internalRequests,
  "POST",
  "/internal/v1/wiki-transformations",
);
const transformationSuccess = transformationRequest.responses.find(
  (response) => response.code >= 200 && response.code < 300,
);
const transformationSuccessBody = JSON.parse(transformationSuccess.body);
expect(
  Array.isArray(transformationSuccessBody.wikiChanges?.[0]?.evidence),
  "Wiki 변환 성공 Saved Example에 evidence가 없음",
);

expect(publicQuestionBody.conversationId, "질문 API에 conversationId가 없음");

const passwordResetRequest = findRequest(
  publicRequests,
  "POST",
  "/api/v1/auth/password-reset-requests",
);
expect(
  passwordResetRequest?.description.includes("`200 OK`") &&
    passwordResetRequest.description.includes(
      "입력한 이메일이 등록되어 있다면 비밀번호 재설정 안내를 전송했습니다.",
    ) &&
    passwordResetRequest.description.includes(
      "이메일 등록 여부와 관계없이 같은 응답을 반환합니다.",
    ),
  "비밀번호 재설정 메일 요청이 200 OK 일반화 메시지 계약과 일치하지 않음",
);
expect(
  !passwordResetRequest?.description.includes("204 No Content"),
  "비밀번호 재설정 메일 요청에 이전 204 No Content 계약이 남아 있음",
);

const scheduleCreate = findRequest(publicRequests, "POST", "/api/v1/schedules");
expect(
  !scheduleCreate?.body?.formdata?.some((item) => item.key === "attachments"),
  "일정 생성 API에 삭제된 attachments가 남아 있음",
);
const scheduleUpdate = findRequest(
  publicRequests,
  "PATCH",
  "/api/v1/schedules/:scheduleId",
);
expect(
  !scheduleUpdate?.body?.formdata?.some(
    (item) => item.key === "attachments" || item.key === "removeAttachmentIds",
  ),
  "일정 수정 API에 삭제된 첨부파일 필드가 남아 있음",
);

const inquiryCreate = findRequest(publicRequests, "POST", "/api/v1/inquiries");
expect(
  inquiryCreate?.body?.formdata?.some((item) => item.key === "priority"),
  "문의 등록 API에 priority가 없음",
);
expect(
  inquiryCreate?.body?.formdata?.some((item) => item.key === "assigneeId"),
  "문의 등록 API에 assigneeId가 없음",
);
expect(
  !inquiryCreate?.body?.formdata?.some((item) => item.key === "targetDepartmentId"),
  "문의 등록 API에 이전 targetDepartmentId가 남아 있음",
);
expect(
  !JSON.stringify(publicCollection).includes("현재 부서 관리자로"),
  "문의 담당자 후보가 아직 부서 관리자로 제한되어 있음",
);

if (failures.length) {
  console.error(`Artifact consistency validation failed (${failures.length})`);
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}

console.log(
  `Artifact consistency validation passed: ${entityByName.size} tables, ${publicRequests.length} public APIs, ${internalRequests.length} internal APIs`,
);
