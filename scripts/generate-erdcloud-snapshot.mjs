import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const templatePath = process.argv[2];
const outputPath = process.argv[3] ?? "erdTable-snapshot-ajt.json";

if (!templatePath) {
  throw new Error(
    "Usage: node scripts/generate-erdcloud-snapshot.mjs <template.json> [output.json]",
  );
}

const template = JSON.parse(readFileSync(resolve(templatePath), "utf8"));
const alphabet =
  "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

function makeId(seed) {
  const digest = createHash("sha256").update(`ajt-erd:${seed}`).digest();
  let result = "";

  for (let index = 0; index < 17; index += 1) {
    result += alphabet[digest[index] % alphabet.length];
  }

  return result;
}

function column(
  name,
  pName,
  type,
  isAllowNull = false,
  comment = "",
  reference = null,
) {
  return {
    name,
    pName,
    type,
    isAllowNull,
    comment,
    reference,
  };
}

function table(name, pName, x, y, pk, fields) {
  return {
    name,
    pName,
    position: { x, y },
    pk,
    fields,
  };
}

const tables = [
  table(
    "부서",
    "department",
    0,
    0,
    column("부서 ID", "department_id", "BIGINT UNSIGNED"),
    [
      column(
        "관리자 ID",
        "manager_id",
        "BIGINT UNSIGNED",
        true,
        "부서별 관리자 1명",
        { table: "member", relType: "ZERO_OR_ONE" },
      ),
      column("부서명", "name", "VARCHAR(50)"),
    ],
  ),
  table(
    "사용자",
    "member",
    0,
    400,
    column("사용자 ID", "member_id", "BIGINT UNSIGNED"),
    [
      column(
        "부서 ID",
        "department_id",
        "BIGINT UNSIGNED",
        false,
        "",
        { table: "department", relType: "ZERO_OR_MANY" },
      ),
      column("이메일", "email", "VARCHAR(255)"),
      column("사용자명", "name", "VARCHAR(50)"),
      column("비밀번호 해시", "password_hash", "VARCHAR(255)"),
      column("사번", "employee_no", "VARCHAR(20)", true),
      column(
        "사용자 역할",
        "role",
        "VARCHAR(30)",
        false,
        "EMPLOYEE, ADMIN",
      ),
      column(
        "가입 상태",
        "signup_status",
        "VARCHAR(30)",
        false,
        "PENDING, APPROVED, REJECTED",
      ),
      column("생성일시", "created_at", "DATETIME(6)"),
      column("수정일시", "updated_at", "DATETIME(6)"),
      column(
        "계정 상태",
        "account_status",
        "VARCHAR(30)",
        false,
        "ACTIVE, INACTIVE",
      ),
    ],
  ),
  table(
    "위키 범위",
    "wiki_scope",
    500,
    0,
    column("범위 키", "scope_key", "VARCHAR(255)", false, "ALL, D1, D1-D2"),
    [
      column(
        "공개 유형",
        "visibility_type",
        "VARCHAR(30)",
        false,
        "ALL, DEPARTMENT",
      ),
      column(
        "부서 ID 목록",
        "department_refs",
        "JSON",
        false,
        "정렬된 부서 ID 배열; ALL이면 빈 배열",
      ),
      column("목차 경로", "index_path", "VARCHAR(500)"),
      column("생성일시", "created_at", "DATETIME(6)"),
      column("수정일시", "updated_at", "DATETIME(6)"),
    ],
  ),
  table(
    "원본문서 카테고리",
    "document_category",
    500,
    400,
    column("문서 카테고리 ID", "document_category_id", "BIGINT UNSIGNED"),
    [
      column(
        "범위 키",
        "scope_key",
        "VARCHAR(255)",
        false,
        "관리자가 범위별 관리",
        { table: "wiki_scope", relType: "ZERO_OR_MANY" },
      ),
      column("카테고리명", "name", "VARCHAR(50)"),
      column("설명", "description", "VARCHAR(1000)", true),
      column("생성일시", "created_at", "DATETIME(6)"),
      column("수정일시", "updated_at", "DATETIME(6)"),
    ],
  ),
  table(
    "원본 문서",
    "document",
    500,
    800,
    column("문서 ID", "document_id", "BIGINT UNSIGNED"),
    [
      column(
        "업로드 관리자 ID",
        "uploader_id",
        "BIGINT UNSIGNED",
        false,
        "",
        { table: "member", relType: "ZERO_OR_MANY" },
      ),
      column(
        "문서 카테고리 ID",
        "document_category_id",
        "BIGINT UNSIGNED",
        false,
        "",
        { table: "document_category", relType: "ZERO_OR_MANY" },
      ),
      column(
        "범위 키",
        "scope_key",
        "VARCHAR(255)",
        false,
        "",
        { table: "wiki_scope", relType: "ZERO_OR_MANY" },
      ),
      column("원본 파일명", "original_file_name", "VARCHAR(255)"),
      column("원본 경로", "original_path", "VARCHAR(500)"),
      column("파싱 경로", "parsed_path", "VARCHAR(500)", true),
      column("MIME 타입", "mime_type", "VARCHAR(100)"),
      column("파일 크기", "file_size", "BIGINT UNSIGNED"),
      column("문서 설명", "description", "VARCHAR(1000)", true),
      column("생성일시", "created_at", "DATETIME(6)"),
      column("수정일시", "updated_at", "DATETIME(6)"),
      column(
        "연결 Wiki ID 목록",
        "document_wiki_refs",
        "JSON",
        false,
        "빈 관계는 []",
      ),
      column(
        "문서 처리 상태",
        "status",
        "VARCHAR(30)",
        false,
        "UPLOADED, PARSING, PROCESSING, COMPLETED, FAILED, CANCELLED",
      ),
      column("실패 사유", "failure_reason", "VARCHAR(1000)", true),
    ],
  ),
  table(
    "AI 작업",
    "ai_job",
    1000,
    0,
    column("작업 ID", "job_id", "BIGINT UNSIGNED"),
    [
      column(
        "요청 관리자 ID",
        "requester_id",
        "BIGINT UNSIGNED",
        false,
        "",
        { table: "member", relType: "ZERO_OR_MANY" },
      ),
      column(
        "범위 키",
        "scope_key",
        "VARCHAR(255)",
        false,
        "",
        { table: "wiki_scope", relType: "ZERO_OR_MANY" },
      ),
      column("작업 공간 경로", "workspace_path", "VARCHAR(500)"),
      column(
        "작업 상태",
        "status",
        "VARCHAR(30)",
        false,
        "WAITING, PROCESSING, COMPLETED, FAILED, CANCELLED",
      ),
      column("문서 ID 목록", "document_ids", "JSON"),
      column(
        "문서별 처리 결과",
        "document_results",
        "JSON",
        true,
        "순서, 상태, 현재 단계, 요약, 실패 사유",
      ),
      column("실패 사유", "failure_reason", "VARCHAR(1000)", true),
      column("생성일시", "created_at", "DATETIME(6)"),
      column("시작일시", "started_at", "DATETIME(6)", true),
      column("종료일시", "finished_at", "DATETIME(6)", true),
    ],
  ),
  table(
    "위키 카테고리",
    "wiki_category",
    1000,
    550,
    column("Wiki 카테고리 ID", "wiki_category_id", "BIGINT UNSIGNED"),
    [
      column(
        "범위 키",
        "scope_key",
        "VARCHAR(255)",
        false,
        "AI 에이전트가 범위별 관리",
        { table: "wiki_scope", relType: "ZERO_OR_MANY" },
      ),
      column("카테고리명", "name", "VARCHAR(50)"),
      column("설명", "description", "VARCHAR(1000)", true),
      column("생성일시", "created_at", "DATETIME(6)"),
      column("수정일시", "updated_at", "DATETIME(6)"),
    ],
  ),
  table(
    "위키",
    "wiki",
    1000,
    950,
    column("Wiki ID", "wiki_id", "BIGINT UNSIGNED"),
    [
      column(
        "Wiki 카테고리 ID",
        "wiki_category_id",
        "BIGINT UNSIGNED",
        false,
        "",
        { table: "wiki_category", relType: "ZERO_OR_MANY" },
      ),
      column(
        "범위 키",
        "scope_key",
        "VARCHAR(255)",
        false,
        "",
        { table: "wiki_scope", relType: "ZERO_OR_MANY" },
      ),
      column("제목", "title", "VARCHAR(200)"),
      column("Wiki 경로", "wiki_path", "VARCHAR(500)"),
      column("연관 Wiki ID 목록", "wiki_refs", "JSON", false, "빈 관계는 []"),
      column(
        "근거 원본문서 ID 목록",
        "document_refs",
        "JSON",
        false,
        "챗봇은 Wiki만 검색하고 이 목록은 근거 표시용",
      ),
      column("생성일시", "created_at", "DATETIME(6)"),
      column("수정일시", "updated_at", "DATETIME(6)"),
    ],
  ),
  table(
    "위키 검수 채팅",
    "wiki_chat_message",
    1500,
    1100,
    column("메시지 ID", "message_id", "BIGINT UNSIGNED"),
    [
      column(
        "Wiki ID",
        "wiki_id",
        "BIGINT UNSIGNED",
        true,
        "Wiki 삭제 후 NULL",
        { table: "wiki", relType: "ZERO_OR_MANY" },
      ),
      column(
        "Wiki 제목 스냅샷",
        "wiki_title_snapshot",
        "VARCHAR(200)",
        false,
        "Wiki 삭제 후 채팅 이력 표시용",
      ),
      column(
        "관리자 ID",
        "member_id",
        "BIGINT UNSIGNED",
        true,
        "AGENT 메시지는 NULL",
        { table: "member", relType: "ZERO_OR_MANY" },
      ),
      column(
        "발신자 유형",
        "sender_type",
        "VARCHAR(30)",
        false,
        "ADMIN, AGENT",
      ),
      column("메시지 내용", "content", "TEXT"),
      column("생성일시", "created_at", "DATETIME(6)"),
    ],
  ),
  table(
    "일정",
    "schedule",
    1500,
    0,
    column("일정 ID", "schedule_id", "BIGINT UNSIGNED"),
    [
      column(
        "작성자 ID",
        "author_id",
        "BIGINT UNSIGNED",
        false,
        "",
        { table: "member", relType: "ZERO_OR_MANY" },
      ),
      column(
        "원본 그룹 키",
        "source_group_key",
        "VARCHAR(100)",
        true,
        "수동·개인 일정은 NULL",
      ),
      column(
        "일정 원본 경로",
        "source_original_path",
        "VARCHAR(500)",
        true,
        "TXT, MD, DOCX, PDF, CSV, XLSX",
      ),
      column(
        "일정 원본 파일명",
        "source_original_file_name",
        "VARCHAR(255)",
        true,
        "업로드 당시 원본 파일명; 저장 경로는 확장자만 유지",
      ),
      column("일정 파싱 경로", "source_parsed_path", "VARCHAR(500)", true),
      column("제목", "title", "VARCHAR(200)"),
      column("내용", "content", "TEXT", true),
      column("대상 설명", "target_text", "VARCHAR(500)", true),
      column("장소", "location", "VARCHAR(200)", true),
      column(
        "공개 범위",
        "visibility_type",
        "VARCHAR(30)",
        false,
        "ALL, DEPARTMENT, PERSONAL",
      ),
      column("시작일시", "start_at", "DATETIME(6)"),
      column("종료일시", "end_at", "DATETIME(6)"),
      column(
        "일정 상태",
        "status",
        "VARCHAR(30)",
        false,
        "DRAFT, APPROVED",
      ),
      column("생성일시", "created_at", "DATETIME(6)"),
      column("수정일시", "updated_at", "DATETIME(6)"),
    ],
  ),
  table(
    "일정 공개 부서",
    "schedule_department",
    2000,
    0,
    column("일정 공개 부서 ID", "schedule_department_id", "BIGINT UNSIGNED"),
    [
      column(
        "일정 ID",
        "schedule_id",
        "BIGINT UNSIGNED",
        false,
        "",
        { table: "schedule", relType: "ZERO_OR_MANY" },
      ),
      column(
        "부서 ID",
        "department_id",
        "BIGINT UNSIGNED",
        false,
        "",
        { table: "department", relType: "ZERO_OR_MANY" },
      ),
    ],
  ),
  table(
    "문의",
    "inquiry",
    2000,
    400,
    column("문의 ID", "inquiry_id", "BIGINT UNSIGNED"),
    [
      column(
        "작성자 ID",
        "member_id",
        "BIGINT UNSIGNED",
        false,
        "",
        { table: "member", relType: "ZERO_OR_MANY" },
      ),
      column(
        "담당자 ID",
        "assignee_id",
        "BIGINT UNSIGNED",
        false,
        "문의 등록 시 직접 선택한 부서 관리자",
        { table: "member", relType: "ZERO_OR_MANY" },
      ),
      column("제목", "title", "VARCHAR(200)"),
      column("내용", "content", "TEXT"),
      column(
        "우선순위",
        "priority",
        "VARCHAR(30)",
        false,
        "HIGH, NORMAL, LOW",
      ),
      column(
        "처리 상태",
        "status",
        "VARCHAR(30)",
        false,
        "PENDING, DONE",
      ),
      column(
        "첨부 이미지 목록",
        "attachment_refs",
        "JSON",
        false,
        "PNG, JPG, JPEG; 최대 5개; 파일당 20MB; 총 100MB",
      ),
      column("생성일시", "created_at", "DATETIME(6)"),
      column("수정일시", "updated_at", "DATETIME(6)"),
    ],
  ),
  table(
    "문의 답변",
    "inquiry_reply",
    2500,
    400,
    column("문의 답변 ID", "inquiry_reply_id", "BIGINT UNSIGNED"),
    [
      column(
        "문의 ID",
        "inquiry_id",
        "BIGINT UNSIGNED",
        false,
        "문의당 답변 1개",
        { table: "inquiry", relType: "ZERO_OR_ONE" },
      ),
      column(
        "답변 관리자 ID",
        "member_id",
        "BIGINT UNSIGNED",
        false,
        "",
        { table: "member", relType: "ZERO_OR_MANY" },
      ),
      column("답변 내용", "reply", "TEXT"),
      column("생성일시", "created_at", "DATETIME(6)"),
      column("수정일시", "updated_at", "DATETIME(6)"),
    ],
  ),
  table(
    "AI 질문",
    "ai_question",
    2000,
    950,
    column("AI 질문 ID", "ai_question_id", "BIGINT UNSIGNED"),
    [
      column(
        "질문자 ID",
        "member_id",
        "BIGINT UNSIGNED",
        false,
        "",
        { table: "member", relType: "ZERO_OR_MANY" },
      ),
      column(
        "대화 키",
        "conversation_key",
        "VARCHAR(100)",
        false,
        "conversationId 기반 멀티턴 대화 단위 키",
      ),
      column("질문 내용", "content", "TEXT"),
      column(
        "질문 유형",
        "question_type",
        "VARCHAR(30)",
        true,
        "WIKI, SCHEDULE, MIXED; 판별 전·실패 시 NULL",
      ),
      column("성공 여부", "success", "BOOLEAN"),
      column("실패 사유", "failure_reason", "VARCHAR(1000)", true),
      column("생성일시", "created_at", "DATETIME(6)"),
    ],
  ),
  table(
    "AI 답변",
    "ai_answer",
    2500,
    950,
    column("AI 답변 ID", "ai_answer_id", "BIGINT UNSIGNED"),
    [
      column(
        "AI 질문 ID",
        "ai_question_id",
        "BIGINT UNSIGNED",
        false,
        "질문당 답변 1개",
        { table: "ai_question", relType: "ZERO_OR_ONE" },
      ),
      column("답변 내용", "content", "TEXT"),
      column("생성일시", "created_at", "DATETIME(6)"),
    ],
  ),
  table(
    "답변 출처",
    "answer_source",
    2500,
    1300,
    column("답변 출처 ID", "answer_source_id", "BIGINT UNSIGNED"),
    [
      column(
        "AI 답변 ID",
        "ai_answer_id",
        "BIGINT UNSIGNED",
        false,
        "답변 하나에 출처 여러 행 허용",
        { table: "ai_answer", relType: "ZERO_OR_MANY" },
      ),
      column(
        "Wiki ID",
        "wiki_id",
        "BIGINT UNSIGNED",
        true,
        "Wiki 질문 출처; 일정 ID와 둘 중 하나만 입력",
        { table: "wiki", relType: "ZERO_OR_MANY" },
      ),
      column(
        "일정 ID",
        "schedule_id",
        "BIGINT UNSIGNED",
        true,
        "일정 질문 출처; Wiki ID와 둘 중 하나만 입력",
        { table: "schedule", relType: "ZERO_OR_MANY" },
      ),
      column(
        "출처 제목",
        "source_title",
        "VARCHAR(255)",
        false,
        "출처 삭제 후에도 표시할 제목 스냅샷",
      ),
    ],
  ),
];

const diagramId = makeId("diagram");
const tableByName = new Map();

for (const definition of tables) {
  const entityId = makeId(`entity:${definition.pName}`);
  const pk = {
    _id: makeId(`field:${definition.pName}:${definition.pk.pName}`),
    name: definition.pk.name,
    pName: definition.pk.pName,
    domain: "",
    type: definition.pk.type,
    defaultValue: "",
    isAllowNull: false,
    comment: definition.pk.comment,
    relEntity: null,
    relFieldId: null,
    relType: null,
    relGroupId: null,
  };

  tableByName.set(definition.pName, {
    entityId,
    pkId: pk._id,
    definition,
    pk,
  });
}

const entityData = tables.map((definition) => {
  const current = tableByName.get(definition.pName);
  const fields = definition.fields.map((field) => {
    const reference = field.reference;
    const target = reference ? tableByName.get(reference.table) : null;

    if (reference && !target) {
      throw new Error(
        `Unknown relation target: ${definition.pName}.${field.pName} -> ${reference.table}`,
      );
    }

    return {
      _id: makeId(`field:${definition.pName}:${field.pName}`),
      name: field.name,
      pName: field.pName,
      domain: "",
      type: field.type,
      defaultValue: "",
      isAllowNull: field.isAllowNull,
      comment: field.comment,
      relEntity: target?.entityId ?? null,
      relFieldId: target?.pkId ?? null,
      relType: reference?.relType ?? null,
      relGroupId: reference
        ? makeId(`relation:${definition.pName}:${field.pName}:${reference.table}`)
        : null,
    };
  });

  return {
    _id: current.entityId,
    _diagramId: diagramId,
    position: definition.position,
    name: definition.name,
    pName: definition.pName,
    fields,
    keys: {
      pks: [current.pk],
      fks: [],
    },
    color: "rgba(10, 10, 10, 0.5)",
  };
});

const snapshot = {
  entityData,
  domainData: [],
  memoData: [],
  creatorName: template.creatorName ?? "",
  creatorThumb: template.creatorThumb ?? "",
  createdAt: new Date().toISOString(),
};

const entityIds = new Set(entityData.map((entity) => entity._id));
const primaryKeyIds = new Set(
  entityData.flatMap((entity) => entity.keys.pks.map((pk) => pk._id)),
);
const everyId = [
  diagramId,
  ...entityData.flatMap((entity) => [
    entity._id,
    ...entity.keys.pks.map((pk) => pk._id),
    ...entity.fields.map((field) => field._id),
    ...entity.fields
      .filter((field) => field.relGroupId)
      .map((field) => field.relGroupId),
  ]),
];

if (new Set(everyId).size !== everyId.length) {
  throw new Error("Generated ERDCloud IDs are not unique");
}

for (const entity of entityData) {
  for (const field of entity.fields) {
    if (field.relEntity && !entityIds.has(field.relEntity)) {
      throw new Error(
        `Missing relation entity for ${entity.pName}.${field.pName}`,
      );
    }

    if (field.relFieldId && !primaryKeyIds.has(field.relFieldId)) {
      throw new Error(
        `Missing relation field for ${entity.pName}.${field.pName}`,
      );
    }
  }
}

writeFileSync(resolve(outputPath), `${JSON.stringify(snapshot, null, 2)}\n`, "utf8");
