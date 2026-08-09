import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import {
  buildSavedExamples,
  internalContractVersion,
  publicContractVersion,
} from "./postman-contract-examples.mjs";

const outputDir = dirname(fileURLToPath(import.meta.url));
const collectionSchema =
  "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

const cookieAuth = { type: "noauth" };
const csrfProtectedMethods = new Set(["POST", "PUT", "PATCH", "DELETE"]);

const internalApiKeyAuth = {
  type: "apikey",
  apikey: [
    { key: "key", value: "X-Internal-API-Key", type: "string" },
    { key: "value", value: "{{internalApiKey}}", type: "string" },
    { key: "in", value: "header", type: "string" },
  ],
};

// Wiki 조회 창구 전용. 내부 API 키와 별개다 — 키는 호출자의 신원이고 이 값은 그 요청이
// 볼 수 있는 범위다. Spring Boot 가 변환·수정 요청 시작에 발급하고 요청 1개·scopeKey
// 1개·scopeVersion 1개에 묶는다. requestId 는 로그에 남는 correlation ID 이므로 인가에
// 쓰지 않는다.
const wikiCapabilityHeader = {
  key: "X-Wiki-Capability",
  value: "{{wikiCapability}}",
  description: "요청 단위 열람 허가. 요청 종료·timeout·취소 시 만료된다",
};

function docs({
  summary,
  usage,
  auth = "HttpOnly 인증 쿠키 필요",
  pathParams = [],
  queryParams = [],
  requestBody = [],
  policy = [],
  response = [],
  errors = [],
}) {
  const section = (title, values, emptyText = "없음") => [
    `### ${title}`,
    values.length ? values.map((value) => `- ${value}`).join("\n") : emptyText,
  ];

  return [
    summary,
    usage,
    "",
    ...section("Authorization", [auth]),
    "",
    ...section("Path Params", pathParams),
    "",
    ...section("Query Params", queryParams),
    "",
    ...section("Request Body", requestBody),
    "",
    ...section("정책", policy),
    "",
    ...section("Response", response),
    "",
    ...section("Error", errors),
  ].join("\n").replaceAll(
    "accessToken이 유효하지 않음",
    "인증 쿠키가 없거나 유효하지 않음",
  );
}

function rawJson(value) {
  return {
    mode: "raw",
    raw: JSON.stringify(value, null, 2),
    options: { raw: { language: "json" } },
  };
}

function formData(fields) {
  return {
    mode: "formdata",
    formdata: fields.map((field) => ({
      key: field.key,
      value: field.type === "file" ? undefined : field.value,
      src: field.type === "file" ? [] : undefined,
      type: field.type ?? "text",
      description: field.description,
      disabled: field.disabled ?? false,
    })),
  };
}

function urlObject(baseVariable, path, query = []) {
  const variables = [...path.matchAll(/:([A-Za-z0-9_]+)/g)].map((match) => ({
    key: match[1],
    value: `{{${match[1]}}}`,
    description: `${match[1]} 경로 변수`,
  }));
  const enabledQuery = query.filter((item) => !item.disabled);
  const queryString = enabledQuery.length
    ? `?${enabledQuery
        .map((item) => `${item.key}=${encodeURIComponent(item.value)}`)
        .join("&")}`
    : "";

  return {
    raw: `{{${baseVariable}}}${path}${queryString}`,
    host: [`{{${baseVariable}}}`],
    path: path.replace(/^\//, "").split("/"),
    query,
    variable: variables,
  };
}

function request({
  name,
  method,
  path,
  description,
  baseVariable = "backendBaseUrl",
  auth,
  query = [],
  body,
  headers = [],
}) {
  const requestHeaders = [...headers];
  const requiresCsrfHeader =
    baseVariable === "backendBaseUrl" && csrfProtectedMethods.has(method);
  const hasCsrfHeader = requestHeaders.some(
    (header) => header.key.toLowerCase() === "x-xsrf-token",
  );
  if (requiresCsrfHeader && !hasCsrfHeader) {
    requestHeaders.push({ key: "X-XSRF-TOKEN", value: "{{csrfToken}}" });
  }

  const requestValue = {
    method,
    header: requestHeaders,
    url: urlObject(baseVariable, path, query),
    description,
  };
  if (auth === "noauth") {
    requestValue.auth = { type: "noauth" };
  }
  if (body) {
    requestValue.body = body;
  }
  return {
    name,
    request: requestValue,
    response: buildSavedExamples(method, path, requestValue),
  };
}

function folder(name, description, items) {
  return { name, description, item: items };
}

const publicFolders = [
  folder("인증", "회원가입, 로그인과 이메일 비밀번호 재설정 API", [
    request({
      name: "로그인",
      method: "POST",
      path: "/api/v1/auth/login",
      auth: "noauth",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        email: "employee@ajt.com",
        password: "password123!",
      }),
      description: docs({
        summary: "등록된 이메일과 비밀번호로 로그인합니다.",
        usage: "로그인 화면에서 사용합니다.",
        auth: "불필요",
        requestBody: [
          "`email`: 가입된 이메일",
          "`password`: 사용자 비밀번호",
        ],
        policy: [
          "가입 상태가 `approved`이고 계정 상태가 `active`인 사용자만 로그인할 수 있습니다.",
          "초기 범위에서는 refreshToken을 발급하지 않습니다.",
          "JWT는 `AJT_ACCESS_TOKEN` HttpOnly 쿠키로만 발급하며 응답 본문과 JavaScript에 노출하지 않습니다.",
          "`role=admin`은 관리자 페이지 접근 가능 여부입니다. 부서관리자도 `role=admin`이라 관리자 페이지·직원 관리 메뉴에 접근할 수 있습니다.",
          "`isSuperAdmin`은 가입 승인/거절 등 최고관리자 전용 작업 가능 여부입니다. 가입 승인 메뉴/버튼은 `isSuperAdmin=true`일 때만 노출합니다.",
        ],
        response: [
          "`Set-Cookie`: `AJT_ACCESS_TOKEN` JWT HttpOnly 쿠키 (`Secure`, `SameSite=Lax`, `Path=/`)",
          "`expiresIn`: 토큰 만료까지 남은 초",
          "`user`: 로그인 사용자 ID, 이름, 역할, 부서, 계정 상태와 최고관리자 여부(`isSuperAdmin`)",
          "`user.isSuperAdmin`: `true`=최고관리자, `false`=부서관리자 또는 일반 사원. 직원 목록 조회는 `role=admin`이면 가능하지만, 직원 상세 조회·수정과 가입 승인/거절은 `isSuperAdmin=true`만 가능합니다(S15P11B106-222).",
        ],
        errors: [
          "`400 Bad Request`: 이메일 또는 비밀번호 형식 오류",
          "`401 Unauthorized`: 자격 증명이 틀렸거나 가입 미승인·비활성화 계정",
          "`429 Too Many Requests`: 로그인 요청 횟수 제한 초과",
        ],
      }),
    }),
    request({
      name: "CSRF 토큰 발급",
      method: "GET",
      path: "/api/v1/auth/csrf",
      auth: "noauth",
      description: docs({
        summary: "상태 변경 요청에 사용할 CSRF 토큰 쿠키를 발급합니다.",
        usage: "프론트엔드는 로그인 전과 새로고침 후 먼저 호출합니다.",
        auth: "불필요",
        policy: [
          "`XSRF-TOKEN`은 JavaScript가 읽을 수 있는 쿠키이며 인증 JWT가 아닙니다.",
          "POST, PUT, PATCH, DELETE 요청은 이 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보냅니다.",
        ],
        response: ["`Set-Cookie`: `XSRF-TOKEN` 쿠키", "`message`: 발급 결과"],
        errors: ["`500 Internal Server Error`: CSRF 토큰 발급 실패"],
      }),
    }),
    request({
      name: "로그아웃",
      method: "POST",
      path: "/api/v1/auth/logout",
      headers: [{ key: "X-XSRF-TOKEN", value: "{{csrfToken}}" }],
      description: docs({
        summary: "현재 인증 쿠키를 만료시켜 로그아웃합니다.",
        usage: "로그아웃 버튼에서 사용합니다.",
        auth: "HttpOnly 인증 쿠키 및 CSRF 헤더 필요",
        policy: [
          "서버 토큰 블랙리스트와 refreshToken은 제공하지 않습니다.",
          "성공 시 `AJT_ACCESS_TOKEN` 쿠키를 `Max-Age=0`으로 만료합니다.",
        ],
        response: ["`Set-Cookie`: 만료된 `AJT_ACCESS_TOKEN` 쿠키", "`message`: 로그아웃 결과"],
        errors: ["`401 Unauthorized`: 인증 쿠키가 없거나 유효하지 않음", "`403 Forbidden`: CSRF 토큰 누락 또는 불일치"],
      }),
    }),
    request({
      name: "회원가입",
      method: "POST",
      path: "/api/v1/auth/signup",
      auth: "noauth",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        email: "employee@ajt.com",
        password: "password123!",
        name: "홍길동",
        departmentId: "1",
      }),
      description: docs({
        summary: "이메일, 비밀번호, 이름과 소속 부서로 가입 승인을 신청합니다.",
        usage: "회원가입 화면에서 사용합니다.",
        auth: "불필요",
        requestBody: [
          "`email`: 로그인에 사용할 이메일",
          "`password`: 비밀번호 정책을 만족하는 비밀번호",
          "`name`: 사용자 이름",
          "`departmentId`: 소속 부서 ID",
        ],
        policy: [
          "회원가입 이메일 인증은 수행하지 않습니다.",
          "사번은 회원가입 시 입력받지 않고 관리자가 가입을 승인할 때 시스템이 생성합니다.",
          "신규 신청은 역할 `employee`, 가입 상태 `pending`, 계정 상태 `inactive`로 저장합니다.",
          "`rejected` 상태의 같은 이메일로 재신청하면 기존 행의 입력 정보를 갱신하고 `pending`으로 전환합니다.",
          "`pending` 또는 `approved` 상태의 같은 이메일은 중복 신청할 수 없습니다.",
        ],
        response: [
          "`202 Accepted`",
          "`userId`: 생성되거나 재사용된 사용자 ID",
          "`email`, `name`, `role`, `departmentId`, `signupStatus`, `accountStatus`",
        ],
        errors: [
          "`400 Bad Request`: 입력 형식 오류 또는 존재하지 않는 부서",
          "`409 Conflict`: 가입 승인 대기 또는 승인 완료된 이메일",
        ],
      }),
    }),
    request({
      name: "회원가입용 부서 목록 조회",
      method: "GET",
      path: "/api/v1/signup-departments",
      auth: "noauth",
      description: docs({
        summary: "로그인하지 않은 사용자가 회원가입 시 선택할 부서 목록을 조회합니다.",
        usage: "회원가입 화면의 소속 부서 선택 영역에서 사용합니다.",
        auth: "불필요",
        policy: [
          "회원가입에 사용할 수 있는 부서만 반환합니다.",
          "관리자·직원 정보는 공개하지 않습니다.",
        ],
        response: ["`items`: `departmentId`, `name`"],
        errors: [],
      }),
    }),
    request({
      name: "비밀번호 재설정 메일 요청",
      method: "POST",
      path: "/api/v1/auth/password-reset-requests",
      auth: "noauth",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({ email: "employee@ajt.com" }),
      description: docs({
        summary: "등록 이메일로 비밀번호 재설정 인증번호 전송을 요청합니다.",
        usage: "비밀번호 찾기 화면에서 사용합니다.",
        auth: "불필요",
        requestBody: ["`email`: 비밀번호를 재설정할 계정 이메일"],
        policy: [
          "이메일 등록 여부와 관계없이 같은 응답을 반환합니다.",
          "6자리 인증번호를 발송하며, 인증번호는 5분간 유효하고 DB에 저장하지 않습니다.",
        ],
        response: [
          "`200 OK`",
          "`message`: `입력한 이메일이 등록되어 있다면 비밀번호 재설정 안내를 전송했습니다.`",
        ],
        errors: [
          "`400 Bad Request`: 이메일 형식 오류",
          "`429 Too Many Requests`: 이메일 또는 IP 요청 횟수 제한 초과",
        ],
      }),
    }),
    request({
      name: "비밀번호 재설정 인증번호 확인",
      method: "POST",
      path: "/api/v1/auth/password-reset-verify",
      auth: "noauth",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({ email: "employee@ajt.com", code: "123456" }),
      description: docs({
        summary: "이메일로 받은 6자리 인증번호를 확인합니다.",
        usage: "비밀번호 재설정 인증번호 입력 화면에서 사용합니다.",
        auth: "불필요",
        requestBody: [
          "`email`: 재설정할 계정 이메일",
          "`code`: 이메일로 받은 6자리 인증번호",
        ],
        policy: [
          "인증번호가 유효하면 비밀번호 수정 화면으로 진행합니다.",
          "실제 변경은 password-resets에서 인증번호를 다시 확인합니다.",
        ],
        response: [
          "`200 OK`",
          "`message`: `인증번호가 확인되었습니다.`",
        ],
        errors: ["`400 Bad Request`: `INVALID_OR_EXPIRED_RESET_CODE`"],
      }),
    }),
    request({
      name: "비밀번호 재설정",
      method: "POST",
      path: "/api/v1/auth/password-resets",
      auth: "noauth",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        email: "employee@ajt.com",
        code: "123456",
        newPassword: "newPassword123!",
      }),
      description: docs({
        summary: "이메일로 받은 인증번호로 새 비밀번호를 설정합니다.",
        usage: "비밀번호 재설정(새 비밀번호 입력) 화면에서 사용합니다.",
        auth: "불필요",
        requestBody: [
          "`email`: 재설정할 계정 이메일",
          "`code`: 이메일로 받은 6자리 인증번호",
          "`newPassword`: 새 비밀번호",
        ],
        policy: [
          "인증번호가 유효하면 비밀번호를 변경하고 인증번호는 즉시 폐기됩니다.",
          "변경 완료 후 자동 로그인하지 않습니다.",
        ],
        response: ["`204 No Content`: 비밀번호 변경 완료"],
        errors: [
          "`400 Bad Request`: 새 비밀번호 정책 위반",
          "`400 Bad Request`: `INVALID_OR_EXPIRED_RESET_CODE`",
        ],
      }),
    }),
  ]),

  folder("사용자", "조회 전용 내 정보와 관리자용 사용자 계정 관리 API", [
    request({
      name: "내 정보 조회",
      method: "GET",
      path: "/api/v1/me",
      description: docs({
        summary: "로그인한 사용자의 계정 및 소속 정보를 조회합니다.",
        usage: "마이페이지의 읽기 전용 내 정보 화면에서 사용합니다.",
        policy: [
          "사용자는 이 화면에서 이름·이메일·부서 정보를 직접 수정할 수 없습니다.",
          "비밀번호 변경은 별도 `PATCH /api/v1/me/password` API로 수행할 수 있습니다.",
          "이메일 재설정 절차도 별도로 유지합니다.",
          "프론트는 새로고침 후 이 응답으로 메뉴를 복원합니다. `role=admin`이면 관리자 페이지·직원 관리 메뉴에 접근하고, 가입 승인/거절 메뉴·대기 count 조회는 `isSuperAdmin=true`일 때만 노출·호출합니다.",
        ],
        response: [
          "`userId`, `email`, `name`, `employeeNo`, `role`",
          "`department`: 소속 부서 ID와 이름",
          "`isSuperAdmin`: 최고관리자 전용 작업 가능 여부. 직원 목록 조회는 `role=admin`이면 가능하고, 직원 상세 조회·수정과 가입 승인/거절은 `isSuperAdmin=true`만 가능(S15P11B106-222)",
          "`signupStatus`, `accountStatus`, `createdAt`, `updatedAt`",
        ],
        errors: ["`401 Unauthorized`: accessToken이 유효하지 않음"],
      }),
    }),
    request({
      name: "내 비밀번호 변경",
      method: "PATCH",
      path: "/api/v1/me/password",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        currentPassword: "oldPassword123!",
        newPassword: "newPassword123!",
      }),
      description: docs({
        summary: "로그인한 사용자가 현재 비밀번호를 확인한 뒤 본인 비밀번호를 변경합니다.",
        usage: "마이페이지 비밀번호 변경 화면에서 사용합니다.",
        requestBody: [
          "`currentPassword`: 현재 비밀번호",
          "`newPassword`: 새 비밀번호(8자 이상 100자 이하)",
        ],
        policy: [
          "인증된 본인 계정에만 적용되며 대상 사용자를 지정하는 입력은 받지 않습니다.",
          "현재 비밀번호가 일치해야 변경합니다.",
          "새 비밀번호는 회원가입·재설정과 동일한 정책(8자 이상 100자 이하)을 적용하고 현재 비밀번호와 같으면 거부합니다.",
          "관리자가 타인 비밀번호를 변경하는 기능이 아니며, 이메일 인증번호 재설정과는 별개 기능입니다.",
        ],
        response: ["성공 시 본문 없이 `204 No Content`를 반환합니다."],
        errors: [
          "`400 Bad Request`: 새 비밀번호 형식 오류(`INVALID_REQUEST`), 현재 비밀번호 불일치(`INVALID_CURRENT_PASSWORD`), 또는 새 비밀번호가 현재와 동일(`NEW_PASSWORD_SAME_AS_CURRENT`)",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`403 Forbidden`: CSRF 토큰이 없거나 올바르지 않음",
        ],
      }),
    }),
    request({
      name: "사용자 목록 조회",
      method: "GET",
      path: "/api/v1/users",
      query: [
        { key: "page", value: "1", disabled: true },
        { key: "size", value: "20", disabled: true },
        { key: "status", value: "active", disabled: true },
        { key: "signupStatus", value: "approved", disabled: true },
        { key: "role", value: "admin", disabled: true },
        { key: "managerAssignable", value: "true", disabled: true },
        { key: "departmentId", value: "1", disabled: true },
        { key: "keyword", value: "홍길동", disabled: true },
        { key: "sort", value: "createdAt,desc", disabled: true },
      ],
      description: docs({
        summary: "관리자가 사용자 목록을 검색하고 조회합니다.",
        usage: "사용자 관리 목록 화면에서 사용합니다.",
        queryParams: [
          "`page`: 페이지 번호, 기본값 1",
          "`size`: 페이지 크기, 기본값 20, 최대 100",
          "`status`: `active` 또는 `inactive`",
          "`signupStatus`: `pending`, `approved` 또는 `rejected`",
          "`role`: `employee` 또는 `admin`",
          "`managerAssignable`: 부서 관리자 지정 가능 사용자만 조회할지 여부",
          "`departmentId`: 특정 부서 소속 사용자만 조회(부서 ID 문자열)",
          "`keyword`: 이름 또는 이메일 검색어",
          "`sort`: 정렬필드와 방향",
        ],
        policy: ["관리자(최고관리자·부서관리자)는 사용자 목록을 조회할 수 있습니다. 단, 사용자 상세 조회와 수정은 최고관리자만 가능하며 부서관리자는 목록 조회만 할 수 있습니다(S15P11B106-222)."],
        response: [
          "`items`: 사용자 ID, 이메일, 이름, 역할, 부서, 가입 상태, 계정 상태와 부서 관리자 지정 여부",
          "`page`, `size`, `totalCount`, `totalPages`",
        ],
        errors: [
          "`400 Bad Request`: 페이지, 필터 또는 정렬값 오류",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`403 Forbidden`: 관리자 권한 없음",
        ],
      }),
    }),
    request({
      name: "사용자 단건 조회",
      method: "GET",
      path: "/api/v1/users/:userId",
      description: docs({
        summary: "최고관리자가 특정 사용자 한 명의 최신 상세 정보를 조회합니다.",
        usage: "사용자 관리 상세·수정 화면에서 대상 사용자를 불러올 때 사용합니다.",
        pathParams: ["`userId`: 조회할 사용자 ID"],
        policy: [
          "사용자 단건 상세 조회는 최고관리자만 가능합니다(S15P11B106-222). 부서관리자는 목록만 조회할 수 있으며, 상세 조회 API를 직접 호출하면 403(`ADMIN_PERMISSION_REQUIRED`)으로 거절합니다.",
          "`PATCH /api/v1/users/{userId}` 수정 화면과 짝이 되는 조회 API입니다.",
        ],
        response: [
          "대상 사용자 전체 정보(수정 API 응답과 동일한 `UserResponse`).",
          "`isSuperAdmin`: 대상 사용자의 최고관리자 여부(boolean).",
        ],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`403 Forbidden`: 최고관리자 권한 없음(부서관리자·사원 포함)",
          "`404 Not Found`: 존재하지 않는 사용자",
        ],
      }),
    }),
    request({
      name: "사용자 정보 및 상태 수정",
      method: "PATCH",
      path: "/api/v1/users/:userId",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        name: "홍길동",
        role: "employee",
        departmentId: "2",
        accountStatus: "active",
      }),
      description: docs({
        summary: "최고관리자가 사용자 이름, 역할, 소속 부서와 계정 상태를 수정합니다.",
        usage: "사용자 관리 상세 화면에서 사용합니다.",
        pathParams: ["`userId`: 수정할 사용자 ID"],
        requestBody: [
          "`name`: 변경할 이름",
          "`role`: `admin` 또는 `employee`",
          "`departmentId`: 변경할 부서 ID",
          "`accountStatus`: `active` 또는 `inactive`",
        ],
        policy: [
          "사용자 수정은 최고관리자만 가능합니다(S15P11B106-222). 부서관리자는 목록만 조회할 수 있으며, 수정 API를 직접 호출하면 403(`ADMIN_PERMISSION_REQUIRED`)으로 거절합니다.",
          "전달하지 않은 필드는 변경하지 않습니다.",
          "사용자는 삭제하지 않고 비활성화합니다.",
          "처리되지 않은 문의가 남은 담당자의 비활성화 또는 employee 전환은 허용하지 않습니다.",
          "최고관리자는 자기 자신을 employee로 강등하거나 비활성화할 수 없습니다.",
          "최고관리자는 부서관리자를 employee로 강등하거나 비활성화할 수 있습니다.",
          "사용자 비밀번호는 이 API에서 변경하지 않고 이메일 재설정으로만 변경합니다.",
        ],
        response: ["수정된 사용자 전체 정보(`isSuperAdmin` 포함)."],
        errors: [
          "`400 Bad Request`: 필드값 또는 부서가 유효하지 않음",
          "`403 Forbidden`: 최고관리자 권한 없음(부서관리자·사원 포함, `ADMIN_PERMISSION_REQUIRED`)",
          "`404 Not Found`: 존재하지 않는 사용자",
          "`409 Conflict`: 변경할 수 없는 사용자 상태(미승인 계정·자기 강등/비활성화·미처리 문의 담당자 강등 등)",
        ],
      }),
    }),
    request({
      name: "가입 신청 목록 조회",
      method: "GET",
      path: "/api/v1/signup-requests",
      query: [
        { key: "page", value: "1", disabled: true },
        { key: "size", value: "20", disabled: true },
        { key: "status", value: "pending", disabled: true },
        { key: "keyword", value: "홍길동", disabled: true },
      ],
      description: docs({
        summary: "관리자가 회원가입 신청을 상태별로 조회합니다.",
        usage: "가입 승인 대기·승인·거부 관리 화면에서 사용합니다.",
        queryParams: [
          "`page`: 페이지 번호, 기본값 1",
          "`size`: 페이지 크기, 기본값 20, 최대 100",
          "`status`: `pending`, `approved` 또는 `rejected`",
          "`keyword`: 이름 또는 이메일 검색어",
        ],
        policy: [
          "최고관리자만 조회할 수 있습니다(부서관리자는 `role=admin`이지만 접근 불가).",
          "거부된 신청도 삭제하지 않고 목록에 보존합니다.",
        ],
        response: [
          "`items`: 사용자 ID, 이메일, 이름, 사번, 소속 부서, 가입 상태와 신청 시각",
          "`employeeNo`는 승인 완료 신청에만 값이 있고 대기·거부 신청은 `null`이다",
          "`page`, `size`, `totalCount`, `totalPages`",
        ],
        errors: [
          "`400 Bad Request`: 페이지 또는 필터값 오류",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`403 Forbidden`: 관리자 권한 없음",
        ],
      }),
    }),
    request({
      name: "가입 신청 승인",
      method: "POST",
      path: "/api/v1/signup-requests/:userId/approve",
      description: docs({
        summary: "관리자가 승인 대기 중인 가입 신청을 승인합니다.",
        usage: "가입 승인 관리 화면의 승인 동작에서 사용합니다.",
        pathParams: ["`userId`: 승인할 가입 신청 사용자 ID"],
        policy: [
          "최고관리자만 승인할 수 있습니다(부서관리자는 `role=admin`이지만 접근 불가).",
          "`pending` 상태에서만 승인할 수 있습니다.",
          "승인 시 시스템이 중복되지 않는 사번을 생성합니다.",
          "승인하면 가입 상태를 `approved`, 계정 상태를 `active`로 변경합니다.",
        ],
        response: [
          "승인된 사용자 ID, 생성된 `employeeNo`, 가입 상태 `approved`, 계정 상태 `active`와 승인 시각",
        ],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 가입 신청",
          "`409 Conflict`: 가입 상태가 `pending`이 아님",
        ],
      }),
    }),
    request({
      name: "가입 신청 거부",
      method: "POST",
      path: "/api/v1/signup-requests/:userId/reject",
      description: docs({
        summary: "관리자가 승인 대기 중인 가입 신청을 거부합니다.",
        usage: "가입 승인 관리 화면의 거부 동작에서 사용합니다.",
        pathParams: ["`userId`: 거부할 가입 신청 사용자 ID"],
        policy: [
          "최고관리자만 거부할 수 있습니다(부서관리자는 `role=admin`이지만 접근 불가).",
          "`pending` 상태에서만 거부할 수 있습니다.",
          "거부하면 가입 상태를 `rejected`, 계정 상태를 `inactive`로 변경하고 사용자 행을 보존합니다.",
        ],
        response: [
          "거부된 사용자 ID, 가입 상태 `rejected`, 계정 상태 `inactive`와 처리 시각",
        ],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 가입 신청",
          "`409 Conflict`: 가입 상태가 `pending`이 아님",
        ],
      }),
    }),
  ]),

  folder("부서", "회원과 공개 범위에서 사용하는 부서 관리 API", [
    request({
      name: "부서 목록 조회",
      method: "GET",
      path: "/api/v1/departments",
      description: docs({
        summary: "사용 가능한 전체 부서 목록을 조회합니다.",
        usage: "로그인 후 사용자 관리와 문서·일정 공개 범위 선택에서 사용합니다.",
        response: ["`items`: 부서 ID, 이름과 지정 관리자 정보. 관리자가 없으면 `manager`는 null"],
        errors: ["`401 Unauthorized`: 인증이 필요한 화면에서 토큰이 유효하지 않음"],
      }),
    }),
    request({
      name: "부서 생성",
      method: "POST",
      path: "/api/v1/departments",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({ name: "개발부", managerId: null }),
      description: docs({
        summary: "관리자가 새 부서를 생성합니다.",
        usage: "부서 관리 화면에서 사용합니다.",
        requestBody: [
          "`name`: 부서 이름",
          "`managerId`: 지정할 관리자 ID. 미지정이면 null",
        ],
        policy: [
          "관리자는 `approved`·`active` 상태의 `admin`이며 다른 부서를 담당하지 않아야 합니다.",
        ],
        response: ["`departmentId`: 생성된 부서 ID", "`name`: 부서 이름", "`manager`: 지정 관리자 또는 null"],
        errors: [
          "`400 Bad Request`: 이름이 비어 있거나 길이 제한 위반",
          "`403 Forbidden`: 관리자 권한 없음",
          "`409 Conflict`: 중복 부서 이름",
        ],
      }),
    }),
    request({
      name: "부서 수정",
      method: "PATCH",
      path: "/api/v1/departments/:departmentId",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({ name: "플랫폼개발부", managerId: "7" }),
      description: docs({
        summary: "관리자가 부서 이름과 부서 관리자를 수정합니다.",
        usage: "부서 관리 화면에서 사용합니다.",
        pathParams: ["`departmentId`: 수정할 부서 ID"],
        requestBody: [
          "`name`: 변경할 부서 이름",
          "`managerId`: 지정할 관리자 ID. 관리자를 해제하려면 null",
        ],
        policy: [
          "부서 관리자는 이 API와 부서 관리 화면에서만 지정하거나 해제합니다.",
          "관리자는 `approved`·`active` 상태의 `admin`이며 다른 부서를 담당하지 않아야 합니다.",
        ],
        response: ["수정된 부서 ID, 이름과 지정 관리자 정보"],
        errors: [
          "`400 Bad Request`: 부서 이름 형식 오류",
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 부서",
          "`409 Conflict`: 중복 부서 이름 또는 이미 다른 부서를 담당하는 관리자",
        ],
      }),
    }),
    request({
      name: "부서 삭제",
      method: "DELETE",
      path: "/api/v1/departments/:departmentId",
      description: docs({
        summary: "관리자가 사용하지 않는 부서를 하드 삭제합니다.",
        usage: "부서 관리 화면에서 사용합니다.",
        pathParams: ["`departmentId`: 삭제할 부서 ID"],
        policy: ["직원이 1명이라도 소속되어 있거나 다른 업무 데이터에서 참조 중인 부서는 삭제할 수 없습니다."],
        response: ["`204 No Content`: 삭제 완료"],
        errors: [
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 부서",
          "`409 Conflict`: 다른 데이터에서 사용 중인 부서",
        ],
      }),
    }),
  ]),

  folder("문서 카테고리", "관리자가 scopeKey별로 관리하는 원본문서 카테고리 API", [
    request({
      name: "문서 카테고리 목록 조회",
      method: "GET",
      path: "/api/v1/document-categories",
      query: [{ key: "scopeKey", value: "D1-D2", disabled: true }],
      description: docs({
        summary: "원본문서 카테고리 목록을 조회합니다.",
        usage: "원본문서 업로드(scopeKey 지정)와 카테고리 관리 화면(scopeKey 생략, 부서별 조회)에서 사용합니다.",
        queryParams: [
          "`scopeKey`: (선택) 특정 Wiki 공간의 카테고리만 조회. 생략하면 로그인 관리자가 접근 가능한 모든 공개 범위의 카테고리를 반환한다(S15P11B106-290). 최고관리자=전체, 부서관리자=담당 부서 범위.",
        ],
        response: ["`items`: 카테고리 ID, 이름, 설명, scopeKey"],
        errors: [
          "`400 Bad Request`: scopeKey 형식 오류",
          "`403 Forbidden`: scopeKey 없이 전체 조회는 관리자만 가능",
          "`404 Not Found`: (scopeKey 지정 시) 존재하지 않거나 접근할 수 없는 Wiki 공간",
        ],
      }),
    }),
    request({
      name: "문서 카테고리 생성",
      method: "POST",
      path: "/api/v1/document-categories",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        scopeKey: "D1-D2",
        name: "사내 규정",
        description: "개발부와 인사부 공통 규정",
      }),
      description: docs({
        summary: "관리자가 특정 Wiki 공간의 원본문서 카테고리를 생성합니다.",
        usage: "문서 카테고리 관리 화면에서 사용합니다.",
        requestBody: [
          "`scopeKey`: 카테고리가 속할 Wiki 공간 키",
          "`name`: 카테고리 이름",
          "`description`: 카테고리 설명",
        ],
        policy: [
          "해당 scopeKey가 없으면 부서 조합을 검증한 뒤 빈 Wiki 공간과 index.md를 함께 생성합니다.",
        ],
        response: ["생성된 카테고리 ID, scopeKey, 이름과 설명"],
        errors: [
          "`400 Bad Request`: 입력값 또는 scopeKey 오류",
          "`403 Forbidden`: 관리자 권한 없음",
          "`409 Conflict`: 같은 공간의 중복 카테고리 이름",
        ],
      }),
    }),
    request({
      name: "문서 카테고리 수정",
      method: "PATCH",
      path: "/api/v1/document-categories/:categoryId",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        name: "취업 규정",
        description: "최신 취업 규정",
      }),
      description: docs({
        summary: "관리자가 원본문서 카테고리 이름과 설명을 수정합니다.",
        usage: "문서 카테고리 관리 화면에서 사용합니다.",
        pathParams: ["`categoryId`: 수정할 카테고리 ID"],
        requestBody: [
          "`name`: 변경할 이름",
          "`description`: 변경할 설명",
        ],
        response: ["수정된 카테고리 정보"],
        errors: [
          "`400 Bad Request`: 입력값 오류",
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 카테고리",
          "`409 Conflict`: 중복 이름",
        ],
      }),
    }),
    request({
      name: "문서 카테고리 삭제",
      method: "DELETE",
      path: "/api/v1/document-categories/:categoryId",
      description: docs({
        summary: "관리자가 사용하지 않는 원본문서 카테고리를 하드 삭제합니다.",
        usage: "문서 카테고리 관리 화면에서 사용합니다.",
        pathParams: ["`categoryId`: 삭제할 카테고리 ID"],
        policy: ["문서가 사용하는 카테고리는 삭제할 수 없습니다."],
        response: ["`204 No Content`: 삭제 완료"],
        errors: [
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 카테고리",
          "`409 Conflict`: 문서에서 사용 중인 카테고리",
        ],
      }),
    }),
  ]),

  folder("문서 및 AI 작업", "Wiki 원본문서와 비동기 처리 상태 API", [
    request({
      name: "Wiki 원본문서 업로드",
      method: "POST",
      path: "/api/v1/documents",
      body: formData([
        {
          key: "files",
          type: "file",
          description: "TXT, MD, PDF, DOCX. 최대 20건, 파일당 20MB, 총 100MB",
        },
        {
          key: "documentCategoryId",
          value: "{{categoryId}}",
          description: "선택. 확정 전 업로드는 보내지 않는다",
        },
        {
          key: "visibilityType",
          value: "department",
          description: "선택. all 또는 department. documentCategoryId와 함께 보낸다",
        },
        {
          key: "departmentIds",
          value: "1,2",
          description: "department일 때 공개 부서 ID 목록",
        },
      ]),
      description: docs({
        summary: "Wiki 생성에 사용할 원본문서를 업로드합니다.",
        usage: "관리자 Wiki 원본문서 업로드 화면에서 사용합니다.",
        requestBody: [
          "`files`: TXT, MD, PDF, DOCX 파일. 최대 20건, 파일당 20MB, 총 100MB",
          "`documentCategoryId`(선택): 카테고리 ID. 확정 전 업로드는 보내지 않습니다.",
          "`visibilityType`(선택): `all` 또는 `department`. `documentCategoryId`와 함께 보냅니다.",
          "`departmentIds`: 부서 공개일 때 선택한 부서 ID 목록",
        ],
        policy: [
          "부서 ID는 중복 제거 후 정렬해 `D1-D2` 형태의 scopeKey를 만듭니다.",
          "카테고리·공개 범위는 선택입니다. 관리자가 파일을 고르는 즉시 업로드하고 분류는 그 뒤에 `PATCH /api/v1/documents/{documentId}`로 지정합니다.",
          "둘 다 없으면 확정 전 업로드입니다. 카테고리는 `null`로 저장되고 파일은 임시 scope(부서관리자는 담당 부서, 최고관리자는 `ALL`)에 놓입니다. 확정 시 실제 범위로 옮겨집니다.",
          "둘 중 하나만 보내면 `400`입니다. 카테고리는 공개 범위에 속하므로 반쪽만으로는 검증할 수 없습니다.",
          "업로드는 AI 작업을 만들지 않습니다. 분류를 확정한 뒤 `POST /api/v1/ai-jobs`로 작업을 만들고 시작합니다.",
        ],
        response: [
          "`202 Accepted`",
          "`documentIds`: 생성된 문서 ID 목록",
          "`scopeKey`: 문서가 놓인 공개 범위(확정 전이면 임시 scope)",
          "`status`: 업로드된 문서 상태(`uploaded`), `createdAt`",
        ],
        errors: [
          "`400 Bad Request`: 파일 형식, 개별 20MB, 최대 20개, 총 100MB, 카테고리 또는 부서 오류, 카테고리·공개 범위 중 하나만 보낸 경우",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`403 Forbidden`: 관리자 권한 없음",
        ],
      }),
    }),
    request({
      name: "Wiki 원본문서 목록 조회",
      method: "GET",
      path: "/api/v1/documents",
      query: [
        { key: "page", value: "1", disabled: true },
        { key: "size", value: "20", disabled: true },
        { key: "scopeKey", value: "D1-D2", disabled: true },
        { key: "categoryId", value: "{{categoryId}}", disabled: true },
        { key: "status", value: "completed", disabled: true },
        { key: "keyword", value: "규정", disabled: true },
        { key: "fileType", value: "pdf", disabled: true },
        { key: "departmentId", value: "1", disabled: true },
        { key: "uploadedFrom", value: "2026-07-01", disabled: true },
        { key: "uploadedTo", value: "2026-07-31", disabled: true },
        { key: "classified", value: "false", disabled: true },
      ],
      description: docs({
        summary: "사용자가 접근 가능한 Wiki 원본문서 목록을 조회합니다.",
        usage: "문서 목록과 관리자 문서 처리 현황 화면에서 사용합니다.",
        queryParams: [
          "`page`, `size`: 페이지네이션",
          "`scopeKey`: Wiki 공간 필터",
          "`categoryId`: 문서 카테고리 필터",
          "`status`: 문서 처리 상태 필터",
          "`classified`: 분류 완료 여부 필터. `true`면 카테고리가 지정된 문서만, `false`면 확정 전 업로드만. 관리자 「AI 작업 대기」 목록이 `false`로 조회해 새로고침 후에도 목록을 되살립니다.",
          "`keyword`: 파일명 또는 문서 검색어",
          "`fileType`: txt, md, pdf 또는 docx",
          "`departmentId`: 해당 부서를 포함하는 scopeKey 필터",
          "`uploadedFrom`, `uploadedTo`: 업로드 기간",
        ],
        policy: ["권한이 없는 문서는 목록에 포함하지 않습니다."],
        response: [
          "`items`: 문서 ID, 파일명, MIME 타입(mimeType), 파일 크기(fileSize), 카테고리(documentCategoryId·documentCategoryName), scopeKey, 공개 유형(visibilityType: all/department), 공개 부서 목록(departments[].departmentId·name), 상태, 업로드자(uploadedBy.userId·name)와 업로드 시각(uploadedAt)",
          "`page`, `size`, `totalCount`, `totalPages`",
        ],
        errors: [
          "`400 Bad Request`: 페이지 또는 필터값 오류",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
        ],
      }),
    }),
    request({
      name: "Wiki 원본문서 상세 조회",
      method: "GET",
      path: "/api/v1/documents/:documentId",
      description: docs({
        summary: "Wiki 원본문서의 상세 정보와 연결 Wiki를 조회합니다.",
        usage: "원본문서 상세 화면에서 사용합니다.",
        pathParams: ["`documentId`: 조회할 문서 ID"],
        response: [
          "문서 메타데이터(mimeType·fileSize·uploadedBy·uploadedAt), 카테고리(documentCategoryId·documentCategoryName), 공개 범위(visibilityType·departments)와 처리 상태",
          "`relatedWikis`: 반영 완료된 연결 Wiki 목록",
          "`downloadUrl`: 권한 검증이 적용된 다운로드 URL",
        ],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`404 Not Found`: 문서가 없거나 접근 권한이 없음",
        ],
      }),
    }),
    request({
      name: "Wiki 원본문서 메타데이터 수정",
      method: "PATCH",
      path: "/api/v1/documents/:documentId",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        documentCategoryId: "4",
        visibilityType: "department",
        departmentIds: ["1", "3"],
      }),
      description: docs({
        summary: "관리자가 문서 카테고리 또는 공개 범위를 수정합니다.",
        usage: "원본문서 상세 관리 화면에서 사용합니다.",
        pathParams: ["`documentId`: 수정할 문서 ID"],
        requestBody: [
          "`documentCategoryId`: 새 카테고리 ID",
          "`visibilityType`: `all` 또는 `department`",
          "`departmentIds`: 부서 공개일 때 새 부서 목록",
        ],
        policy: [
          "공개 범위가 바뀌면 새 scopeKey 카테고리를 지정해야 합니다.",
          "기존 범위와 새 범위 Wiki를 각각 최신 문서 기준으로 재처리합니다.",
          "아직 AI 작업을 거치지 않은 문서(확정 전 업로드)는 걷어낼 Wiki도 재처리할 것도 없으므로, 분류와 파일 위치만 맞추고 작업을 만들지 않습니다. 이때 `jobId`·`status`는 `null`이고 `reprocessJobs`는 빈 배열입니다(정상).",
        ],
        response: [
          "`202 Accepted`와 새 scope 재처리 `jobId`",
          "`jobId`·`status`(nullable): 확정 전 문서의 분류를 지정한 경우 `null`입니다. 프론트는 이 값을 실패로 보지 않습니다.",
          "`reprocessJobs`: 공개 범위가 바뀐 경우 이전·새 scope별 재처리 job 목록",
          "수정된 문서 정보",
        ],
        errors: [
          "`400 Bad Request`: 카테고리와 공개 범위 조합 오류",
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 문서",
          "`409 Conflict`: 처리 중인 문서",
        ],
      }),
    }),
    request({
      name: "Wiki 원본문서 다운로드",
      method: "GET",
      path: "/api/v1/documents/:documentId/file",
      description: docs({
        summary: "권한 검증 후 Wiki 원본문서 파일을 다운로드합니다.",
        usage: "원본문서 상세 화면의 다운로드 동작에서 사용합니다.",
        pathParams: ["`documentId`: 다운로드할 문서 ID"],
        policy: [
          "백엔드가 사용자 역할과 scopeKey 접근 권한을 검사합니다.",
          "로컬 저장소의 실제 경로는 응답에 노출하지 않습니다.",
        ],
        response: ["원본 파일 스트림과 원본 파일명"],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`404 Not Found`: 문서가 없거나 접근 권한이 없음",
        ],
      }),
    }),
    request({
      name: "Wiki 원본문서 파일 교체",
      method: "PUT",
      path: "/api/v1/documents/:documentId/file",
      body: formData([
        {
          key: "file",
          type: "file",
          description: "교체할 TXT, MD, PDF 또는 DOCX 파일",
        },
      ]),
      description: docs({
        summary: "기존 문서 ID를 유지하면서 원본 파일을 교체합니다.",
        usage: "원본문서 상세 관리 화면에서 사용합니다.",
        pathParams: ["`documentId`: 파일을 교체할 문서 ID"],
        requestBody: ["`file`: 새 원본문서 파일"],
        policy: [
          "기존 파일은 새 처리 결과가 정상 반영될 때까지만 임시 유지합니다.",
          "교체 후 해당 scopeKey의 최신 Wiki 기준으로 재처리합니다.",
        ],
        response: ["`202 Accepted`", "`jobId`, `documentId`, `status`"],
        errors: [
          "`400 Bad Request`: 파일 형식 또는 용량 오류",
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 문서",
          "`409 Conflict`: 처리 중인 문서",
        ],
      }),
    }),
    request({
      name: "Wiki 원본문서 삭제",
      method: "DELETE",
      path: "/api/v1/documents/:documentId",
      description: docs({
        summary: "Wiki 원본문서와 관련 파일을 하드 삭제합니다.",
        usage: "원본문서 상세 관리 화면에서 사용합니다.",
        pathParams: ["`documentId`: 삭제할 문서 ID"],
        policy: [
          "**Wiki 걷어내기가 끝난 뒤에 지웁니다.** 이 문서를 근거로 쓴 Wiki가 있으면 문서를 `deleting` 상태로 두고 걷어내기 작업을 만든 뒤 응답합니다. 작업이 성공해야 문서 행과 파일이 지워집니다.",
          "그래서 이 응답의 `deleted`는 **항상 `true`가 아닙니다.** 걷어낼 내용이 없어 즉시 지운 경우에만 `true`입니다.",
          "걷어내기가 실패하면 문서는 `failed`로 남고 원본 파일도 그대로 유지됩니다. 관리자가 같은 삭제 요청을 다시 보내면 재시도됩니다 — 별도 재시도 API는 없습니다.",
          "삭제한 문서에 파싱 본문이 없거나 Wiki에 반영된 적이 없어 걷어낼 내용이 없으면 작업을 만들지 않고 즉시 지운 뒤 `deleted=true`·`reprocessRequired=false`·`jobId=null`·`status=skipped`로 응답합니다(정상).",
          "삭제 요청 자체의 실패·권한 없음·처리 중인 문서는 성공 응답이 아니라 아래 에러로 응답합니다.",
          "프론트는 `reprocessRequired=true`이고 `jobId`가 있을 때만 AI 작업 상태(`GET /api/v1/ai-jobs/{jobId}`)를 조회합니다.",
          "삭제 복구와 과거 버전 조회는 제공하지 않습니다.",
        ],
        response: [
          "`202 Accepted`",
          "`deleted`: **이 응답 시점에** 실제로 지워졌는지. 걷어낼 내용이 없어 즉시 지운 경우만 `true`",
          "`reprocessRequired`: 삭제로 Wiki 걷어내기 작업이 필요/생성됐는지 여부",
          "`jobId`(nullable): 걷어내기 작업 ID. `reprocessRequired=false`이면 `null`이 정상입니다.",
          "`scopeKey`: 삭제 대상 문서의 공개 범위 키",
          "`status`: 걷어내기 작업이 생성되면 `deleting`, 걷어낼 내용이 없어 즉시 지웠으면 `skipped`",
        ],
        errors: [
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 문서",
          "`409 Conflict`: 처리 중인 문서",
        ],
      }),
    }),
    request({
      name: "실패 문서 재처리",
      method: "POST",
      path: "/api/v1/documents/:documentId/retry",
      description: docs({
        summary: "파싱·AI 처리에 실패했거나 작업이 취소된 문서를 다시 처리합니다.",
        usage: "관리자 작업 상태 화면에서 사용합니다.",
        pathParams: ["`documentId`: 재처리할 실패 문서 ID"],
        policy: [
          "failed 또는 cancelled 상태인 문서만 재처리할 수 있습니다.",
          "정상 파싱 파일이 있으면 재사용하고 없으면 원본부터 다시 파싱합니다.",
          "재처리 시점의 최신 Wiki를 기준으로 분석합니다.",
        ],
        response: ["`202 Accepted`", "`jobId`, `documentId`, `status`"],
        errors: [
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 문서",
          "`409 Conflict`: failed·cancelled 상태가 아니거나 이미 처리 중",
        ],
      }),
    }),
    request({
      name: "AI 작업 생성 및 시작",
      method: "POST",
      path: "/api/v1/ai-jobs",
      body: rawJson({ documentIds: ["15", "16"] }),
      description: docs({
        summary: "분류가 끝난 문서들로 AI 작업을 만들고 바로 시작합니다.",
        usage: "관리자 문서 관리의 「AI 작업 시작」에서 사용합니다.",
        requestBody: ["`documentIds`: 대기 목록에서 분류를 확정한 문서 ID 목록"],
        policy: [
          "업로드는 작업을 만들지 않습니다(`POST /api/v1/documents`). 이 API가 작업 생성과 시작을 함께 합니다.",
          "작업은 scopeKey 하나에 묶이므로, 공개 범위가 섞여 오면 범위별로 작업을 나눠 만듭니다. 프론트가 미리 묶어 보낼 필요는 없습니다.",
          "카테고리가 지정되지 않은 문서(확정 전 업로드)가 섞여 있으면 아무 작업도 만들지 않고 `400`입니다.",
          "부서관리자는 담당 부서 scope 문서만 넣을 수 있고, 담당 밖 문서는 존재를 숨겨 `404`입니다.",
        ],
        response: [
          "`202 Accepted`",
          "`jobs[]`: 범위별로 만들어진 작업. `jobId`, `scopeKey`, `status`(`processing`), `documentIds`",
        ],
        errors: [
          "`400 Bad Request`: documentIds가 비었거나, 카테고리가 없는 문서가 섞인 경우",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 문서, 또는 부서관리자 담당 밖 문서",
          "`409 Conflict`: 이미 처리 중이거나 처리가 끝난 문서",
        ],
      }),
    }),
    request({
      name: "AI 작업 이력 목록 조회",
      method: "GET",
      path: "/api/v1/ai-jobs",
      query: [
        { key: "page", value: "1", disabled: true },
        { key: "size", value: "20", disabled: true },
      ],
      description: docs({
        summary: "종료된 것을 포함한 AI 작업 이력을 최신순으로 조회합니다.",
        usage: "관리자 문서 관리의 요약 목록 화면에서 사용합니다. 작업 회차별로 묶어 문서별 변경 요약을 보여줍니다.",
        queryParams: ["`page`, `size`: 페이지네이션"],
        policy: [
          "관리자만 조회할 수 있습니다.",
          "생성 시각 내림차순이며, 같은 시각이면 나중에 만들어진 작업이 앞에 옵니다.",
          "한 문서를 재처리하면 작업이 새로 생기므로 같은 문서가 여러 회차에 나타납니다.",
        ],
        response: [
          "`items`: 단건 조회와 같은 구조. `jobId`, `status`, `documentResults`, `createdAt`, `startedAt`, `finishedAt`, `failureReason`",
          "`documentResults[].summary`: 그 회차에 이 문서로 무엇이 바뀌었는지에 대한 AI 작업 요약",
          "`documentResults[].affectedWikis`: 그 작업 회차에서 실제로 생성하거나 변경한 Wiki 페이지 목록. 각 항목은 `wikiId`와 당시 제목 `title` 스냅샷을 담으며, 아직 결과가 없거나 영향 Wiki가 없으면 빈 배열",
          "`documentResults[].failureStage`: 실제로 어디서 실패했는지. 실패하지 않았거나 단계를 알 수 없으면 `null`",
          "`documentResults[].originalFileName`: 그때 그 파일 이름의 스냅샷. 문서를 하드 삭제해도 이력에 남는다(DR-021·DR-024와 같은 방식). 이 필드가 생기기 전 작업은 `null`",
          "`page`, `size`, `totalCount`, `totalPages`",
        ],
        errors: [
          "`400 Bad Request`: page 또는 size 값 오류",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`403 Forbidden`: 관리자 권한 없음",
        ],
      }),
    }),
    request({
      name: "AI 작업 상태 조회",
      method: "GET",
      path: "/api/v1/ai-jobs/:jobId",
      description: docs({
        summary: "비동기 AI 작업의 전체 상태와 문서별 처리 결과를 조회합니다.",
        usage: "문서 업로드 진행 화면과 관리자 작업 상태 화면에서 사용합니다.",
        pathParams: ["`jobId`: 조회할 AI 작업 ID"],
        response: [
          "`status`: `waiting`, `processing`, `completed`, `failed`, `cancelled`",
          "`documentResults`: 문서별 순서, 파일명 스냅샷, 상태, 현재 단계, 요약과 실패 사유·실패 단계",
          "`documentResults[].affectedWikis`: 그 작업 회차에서 실제로 생성하거나 변경한 Wiki 페이지 목록. 각 항목은 `wikiId`와 당시 제목 `title` 스냅샷을 담으며, 아직 결과가 없거나 영향 Wiki가 없으면 빈 배열",
          "`documentResults[].currentStage`는 문서 상태에서 역산한 진행 위치라 실패 지점이 아니다. 어디서 실패했는지는 `failureStage`가 알려준다",
          "`createdAt`, `startedAt`, `finishedAt`, `failureReason`",
        ],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`404 Not Found`: 존재하지 않거나 조회할 수 없는 작업",
        ],
      }),
    }),
    request({
      name: "AI 작업 시작",
      method: "POST",
      path: "/api/v1/ai-jobs/:jobId/start",
      description: docs({
        summary: "업로드로 만들어진 대기 작업의 파싱·Wiki 변환을 시작합니다.",
        usage: "관리자 AI 작업 대기 화면의 시작 동작에서 사용합니다.",
        pathParams: ["`jobId`: 시작할 AI 작업 ID"],
        policy: [
          "업로드는 작업을 waiting으로만 만들고, 이 API를 호출해야 처리가 시작됩니다.",
          "관리자가 대기 화면에서 문서별 공개 범위를 확정한 뒤 호출합니다.",
          "waiting 상태의 작업만 시작할 수 있어 같은 작업을 중복으로 시작할 수 없습니다.",
        ],
        response: ["`202 Accepted`", "`jobId`, `status`: `processing`"],
        errors: [
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 작업",
          "`409 Conflict`: 이미 시작되었거나 종료된 작업",
        ],
      }),
    }),
    request({
      name: "AI 작업 중단",
      method: "POST",
      path: "/api/v1/ai-jobs/:jobId/cancel",
      description: docs({
        summary: "아직 처리되지 않은 문서가 남은 Wiki 변환 작업을 중단합니다.",
        usage: "관리자 작업 상태 화면의 중단 동작에서 사용합니다.",
        pathParams: ["`jobId`: 중단할 AI 작업 ID"],
        policy: [
          "현재 처리 중인 문서는 완료한 뒤 나머지 문서를 cancelled로 전환합니다.",
          "이미 반영된 문서 결과는 유지합니다.",
          "failed와 cancelled 문서는 새 작업으로 재처리할 수 있습니다.",
        ],
        response: ["`202 Accepted`", "`jobId`, `status`: `cancelled` 요청 상태"],
        errors: [
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 작업",
          "`409 Conflict`: 이미 종료되었거나 중단할 수 없는 작업",
        ],
      }),
    }),
  ]),

  folder("Wiki 및 질문", "독립 Wiki 공간, Wiki 상세, 관리자 수정 대화와 사용자 질문 API", [
    request({
      name: "Wiki 공간 목록 조회",
      method: "GET",
      path: "/api/v1/wiki-spaces",
      description: docs({
        summary: "사용자가 접근 가능한 독립 Wiki 공간 목록을 조회합니다.",
        usage: "Wiki 공간 선택과 사이드바에서 사용합니다.",
        response: [
          "`items`: scopeKey, 공개 유형, 부서 목록, 표시 이름과 Wiki 수",
        ],
        errors: ["`401 Unauthorized`: accessToken이 유효하지 않음"],
      }),
    }),
    request({
      name: "Wiki 카테고리 목록 조회",
      method: "GET",
      path: "/api/v1/wiki-categories",
      query: [{ key: "scopeKey", value: "D1-D2" }],
      description: docs({
        summary: "AI가 관리하는 Wiki 카테고리를 scopeKey별로 조회합니다.",
        usage: "Wiki 목록의 카테고리 필터와 관리자 조회 화면에서 사용합니다.",
        queryParams: ["`scopeKey`: 카테고리를 조회할 Wiki 공간 키"],
        policy: ["사용자는 접근 가능한 Wiki 공간의 카테고리만 조회할 수 있습니다."],
        response: ["`items`: wikiCategoryId, scopeKey, 이름과 설명"],
        errors: [
          "`400 Bad Request`: scopeKey 형식 오류",
          "`404 Not Found`: 공간이 없거나 접근 권한이 없음",
        ],
      }),
    }),
    request({
      name: "Wiki 목록 조회",
      method: "GET",
      path: "/api/v1/wikis",
      query: [
        { key: "scopeKey", value: "D1-D2", disabled: true },
        { key: "wikiCategoryId", value: "1", disabled: true },
        { key: "keyword", value: "연차", disabled: true },
        { key: "page", value: "1", disabled: true },
        { key: "size", value: "20", disabled: true },
      ],
      description: docs({
        summary: "사용자가 접근 가능한 Wiki 목록을 검색합니다.",
        usage: "Wiki 목록과 검색 화면에서 사용합니다.",
        queryParams: [
          "`scopeKey`: 독립 Wiki 공간 필터",
          "`wikiCategoryId`: AI가 관리하는 Wiki 카테고리 필터",
          "`keyword`: Wiki 제목과 본문 검색어",
          "`page`, `size`: 페이지네이션",
        ],
        policy: ["권한이 없는 Wiki는 목록과 검색 결과에 포함하지 않습니다."],
        response: [
          "`items`: Wiki ID, 제목, 요약, 카테고리, scopeKey, 수정 시각",
          "`items[].pageKey`: 본문 파일명(확장자 제외). 본문 내부 링크 `pages/{pageKey}.md` 를 wikiId 로 되돌릴 때 씁니다",
          "페이지 정보",
        ],
        errors: [
          "`400 Bad Request`: 필터 또는 페이지값 오류",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
        ],
      }),
    }),
    request({
      name: "Wiki 상세 조회",
      method: "GET",
      path: "/api/v1/wikis/:wikiId",
      description: docs({
        summary: "Wiki 본문, 연결 원본문서와 연관 Wiki를 조회합니다.",
        usage: "Wiki 상세 화면에서 사용합니다.",
        pathParams: ["`wikiId`: 조회할 Wiki ID"],
        response: [
          "Wiki ID, 제목, Markdown 본문, 카테고리와 scopeKey",
          "`evidenceDocuments`: 연결 원본문서 목록",
          "`relatedWikis`: 연관 Wiki 목록 (wikiId·pageKey·title. pageKey 는 본문 내부 링크를 wikiId 로 되돌리는 키)",
        ],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`404 Not Found`: Wiki가 없거나 접근 권한이 없음",
        ],
      }),
    }),
    request({
      name: "Wiki 파일 다운로드",
      method: "GET",
      path: "/api/v1/wikis/:wikiId/file",
      description: docs({
        summary: "Wiki 본문을 Markdown 파일로 다운로드합니다.",
        usage: "Wiki 상세 화면의 다운로드 동작에서 사용합니다.",
        pathParams: ["`wikiId`: 다운로드할 Wiki ID"],
        policy: ["사용자는 접근 가능한 Wiki만 다운로드할 수 있습니다."],
        response: ["Wiki 본문 Markdown 파일 스트림과 `{제목}.md` 파일명"],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`404 Not Found`: Wiki가 없거나 접근 권한이 없음",
        ],
      }),
    }),
    request({
      name: "Wiki 관리자 대화 조회",
      method: "GET",
      path: "/api/v1/wikis/:wikiId/chat-messages",
      description: docs({
        summary: "관리자와 AI 에이전트가 Wiki에 관해 나눈 대화를 조회합니다.",
        usage: "Wiki 상세 화면의 관리자 수정 대화 영역에서 사용합니다.",
        pathParams: ["`wikiId`: 대화를 조회할 Wiki ID"],
        policy: ["관리자만 조회할 수 있습니다. 메시지 저장은 Wiki ID 단위지만, 조회는 같은 scopeKey(부서) 전체의 대화를 시간순으로 묶어서 돌려줍니다 — 같은 부서 안에서 다른 Wiki로 이동해도 대화가 이어집니다."],
        response: [
          "`items`: messageId, senderType, content, createdAt, wikiId, wikiTitle",
          "`wikiId`·`wikiTitle`: 해당 메시지가 관계된 Wiki. 관리자 메시지는 보낸 시점에 보고 있던 Wiki, 에이전트 메시지는 그 지시로 실제 변경된 Wiki입니다(여러 Wiki가 바뀌었으면 첫 번째만). Wiki가 하드 삭제되었으면 wikiId는 null입니다.",
        ],
        errors: [
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 Wiki",
        ],
      }),
    }),
    request({
      name: "Wiki 수정 대화 전송",
      method: "POST",
      path: "/api/v1/wikis/:wikiId/chat-messages",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({ content: "중복된 휴가 규정을 하나로 정리해줘." }),
      description: docs({
        summary: "관리자가 AI 에이전트에게 Wiki 수정 지시를 전송합니다.",
        usage: "Wiki 상세 화면의 관리자 대화 입력 영역에서 사용합니다.",
        pathParams: ["`wikiId`: 수정할 Wiki ID"],
        requestBody: ["`content`: 자연어 수정 요청"],
        policy: [
          "AI는 해당 Wiki, 연결 문서와 같은 scopeKey(부서)의 기존 검수 대화 전체를 사용합니다.",
          "같은 scopeKey에서 문서 변환 중이면 409 Conflict를 반환합니다.",
          "백엔드가 링크와 관계를 검증한 변경 결과는 별도 승인 없이 현재 Wiki에 반영합니다.",
        ],
        response: [
          "`200 OK`",
          "`adminMessage`, `agentMessage`(각각 wikiId·wikiTitle 포함), `updatedWiki`",
        ],
        errors: [
          "`400 Bad Request`: 내용이 비어 있음",
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 Wiki",
          "`409 Conflict`: 동일 Wiki 수정 작업이 처리 중",
        ],
      }),
    }),
    request({
      name: "Wiki 또는 일정 질문",
      method: "POST",
      path: "/api/v1/questions",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        conversationId: "chat-123",
        question: "연차는 언제까지 신청해야 하나요?",
      }),
      description: docs({
        summary: "접근 가능한 Wiki 또는 일정을 대상으로 AI에게 질문합니다.",
        usage: "Wiki·일정 챗봇 입력 영역에서 사용합니다.",
        requestBody: [
          "`conversationId`: 기존 대화의 후속 질문이면 대화 ID. 새 대화면 생략 또는 null",
          "`question`: 사용자 질문",
        ],
        policy: [
          "같은 conversationId의 이전 질문과 답변을 문맥으로 사용하는 멀티턴 방식입니다.",
          "새 질문이면 대화를 생성하고 서버가 conversationId를 발급합니다.",
          "다른 사용자의 conversationId는 사용할 수 없습니다.",
          "AI가 질문을 wiki, schedule 또는 mixed로 자동 판단합니다.",
          "AI는 index.md와 일정 요약에서 종류별 최대 5개 자료를 먼저 선택합니다.",
          "백엔드는 선택 ID의 권한을 다시 검사한 뒤 선택 본문만 AI에 전달합니다.",
          "Wiki 질문은 Wiki만 사용하고 원본문서는 Wiki 근거로만 표시합니다.",
          "mixed 질문은 Wiki와 일정 출처를 함께 사용할 수 있습니다.",
          "답변 출처는 두 개 이상일 수 있습니다.",
          "권한이 없는 자료는 답변과 출처에 포함하지 않습니다.",
        ],
        response: [
          "`conversationId`, `questionId`, `questionType`, `answer`",
          "`sources`: Wiki 또는 일정 출처 배열",
          "Wiki 출처의 `evidenceDocuments`: 연결 원본문서 목록",
        ],
        errors: [
          "`400 Bad Request`: 질문이 비어 있거나 길이 제한 위반",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`404 Not Found`: 대화가 없거나 현재 사용자의 대화가 아님",
          "`503 Service Unavailable`: AI 답변 서버 이용 불가",
        ],
      }),
    }),
    request({
      name: "내 질문 이력 조회",
      method: "GET",
      path: "/api/v1/questions",
      query: [
        { key: "conversationId", value: "chat-123", disabled: true },
        { key: "questionType", value: "wiki", disabled: true },
        { key: "page", value: "1", disabled: true },
        { key: "size", value: "20", disabled: true },
      ],
      description: docs({
        summary: "로그인한 사용자의 Wiki·일정 질문 이력을 조회합니다.",
        usage: "챗봇 질문 이력 화면에서 사용합니다.",
        queryParams: [
          "`conversationId`: 특정 대화의 질문·답변만 조회",
          "`questionType`: `wiki`, `schedule` 또는 `mixed`",
          "`page`, `size`: 페이지네이션",
        ],
        response: [
          "`items`: conversationId, questionId, 질문, 답변, 출처, 생성 시각",
          "페이지 정보",
        ],
        errors: [
          "`400 Bad Request`: 필터 또는 페이지값 오류",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
        ],
      }),
    }),
  ]),

  folder("일정", "일정 문서 추출, 초안 승인과 전체·부서·개인 일정 API", [
    request({
      name: "일정 원본문서 업로드",
      method: "POST",
      path: "/api/v1/schedule-sources",
      body: formData([
        {
          key: "file",
          type: "file",
          description: "TXT, MD, DOCX, PDF, CSV 또는 XLSX 파일",
        },
        {
          key: "visibilityType",
          value: "department",
          description: "all 또는 department",
        },
        {
          key: "departmentIds",
          value: "1,2",
          description: "department일 때 공개 부서 ID 목록",
        },
      ]),
      description: docs({
        summary: "일정 추출에 사용할 원본문서를 업로드합니다.",
        usage: "관리자 일정 문서 업로드 화면에서 사용합니다.",
        requestBody: [
          "`file`: TXT, MD, DOCX, PDF, CSV, XLSX. 파일당 20MB",
          "`visibilityType`: `all` 또는 `department`",
          "`departmentIds`: 부서 공개일 때 공개 부서 목록",
        ],
        policy: [
          "일정 원본문서는 Wiki와 일반 문서 검색에 사용하지 않습니다.",
          "파싱과 추출을 최대 180초 동안 동기로 처리합니다.",
          "추출된 일정은 각각 별도 draft로 저장합니다.",
          "일정이 없으면 원본·파싱 파일을 삭제하고 no_schedule을 반환합니다.",
        ],
        response: [
          "`201 Created`: sourceGroupKey와 생성된 draftSchedules",
          "`200 OK`: status가 no_schedule이고 draftSchedules는 빈 배열",
        ],
        errors: [
          "`400 Bad Request`: 파일 형식, 용량 또는 공개 범위 오류",
          "`403 Forbidden`: 관리자 권한 없음",
        ],
      }),
    }),
    request({
      name: "일정 목록 조회",
      method: "GET",
      path: "/api/v1/schedules",
      query: [
        { key: "startDate", value: "2026-08-01" },
        { key: "endDate", value: "2026-08-31" },
        { key: "status", value: "approved", disabled: true },
        { key: "visibilityType", value: "department", disabled: true },
        { key: "departmentId", value: "1", disabled: true },
      ],
      description: docs({
        summary: "기간과 공개 범위에 맞는 일정 목록을 조회합니다.",
        usage:
          "달력과 관리자 일정 검수 화면에서 사용합니다. 예: `GET /api/v1/schedules?status=draft`는 관리자 승인 대기 초안 전체를 조회합니다.",
        queryParams: [
          "`startDate`, `endDate`: 조회 기간, 최대 1년. 단, 관리자 `status=draft` 조회에서는 생략할 수 있으며 생략 시 전체 draft 목록을 반환합니다.",
          "`status`: 관리자의 `draft` 또는 `approved` 필터",
          "`visibilityType`: `all`, `department`, `personal`",
          "`departmentId`: 부서 일정 필터",
        ],
        policy: [
          "사원에게는 승인된 전체·소속 부서 일정과 본인 개인 일정만 반환합니다.",
          "관리자는 `status=draft`로 승인 대기 초안 일정을 조회할 수 있습니다.",
          "`status=draft` 조회는 검수 대기 목록 성격이므로 `startDate`/`endDate`를 생략할 수 있습니다. 기간이 주어지면 해당 기간 내 draft만, 기간이 없으면 전체 draft를 반환합니다.",
          "사원에게 draft 일정은 노출되지 않습니다.",
        ],
        response: [
          "`items`: 일정 ID, 제목, 기간, 공개 범위, 상태와 위치",
          "`items[].updatedAt`: 낙관적 동시성 토큰. 목록에서 바로 수정할 때 이 값을 수정 요청의 `expectedUpdatedAt`으로 보냅니다.",
        ],
        errors: [
          "`400 Bad Request`: 날짜 범위 또는 필터값 오류. status가 없거나 `approved`인 일반 조회에서 `startDate`/`endDate`가 없으면 400입니다. 단, 관리자 `status=draft` 조회에서 기간 생략은 400이 아닙니다.",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
        ],
      }),
    }),
    request({
      name: "수동 또는 개인 일정 생성",
      method: "POST",
      path: "/api/v1/schedules",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        title: "개인 일정",
        content: "개인 일정 내용",
        targetText: "본인",
        location: "본사",
        visibilityType: "personal",
        departmentIds: [],
        startAt: "2026-08-03T01:00:00Z",
        endAt: "2026-08-03T03:00:00Z",
      }),
      description: docs({
        summary: "관리자가 전체·부서 일정을, 사원이 개인 일정을 직접 생성합니다.",
        usage: "일정 직접 등록 화면에서 사용합니다.",
        requestBody: [
          "`title`, `content`, `targetText`, `location`",
          "`visibilityType`: `all`, `department`, `personal`",
          "`departmentIds`: 부서 일정일 때 부서 목록",
          "`startAt`, `endAt`: RFC 3339 UTC",
        ],
        policy: [
          "관리자 수동 일정과 사용자 개인 일정은 생성 즉시 approved입니다.",
          "사원은 personal 일정만 생성할 수 있습니다.",
          "사원 화면은 공개 범위 선택을 노출하지 않고 `visibilityType: personal`을 전송합니다.",
          "일정에는 첨부파일을 등록하지 않습니다.",
        ],
        response: ["`201 Created`", "생성된 일정 전체 정보"],
        errors: [
          "`400 Bad Request`: 날짜 또는 공개 범위 오류",
          "`403 Forbidden`: 허용되지 않는 공개 범위 생성",
        ],
      }),
    }),
    request({
      name: "일정 상세 조회",
      method: "GET",
      path: "/api/v1/schedules/:scheduleId",
      description: docs({
        summary: "권한에 맞는 일정 상세를 조회합니다.",
        usage: "일정 상세와 draft 검수 화면에서 사용합니다.",
        pathParams: ["`scheduleId`: 조회할 일정 ID"],
        policy: [
          "사원 응답에는 원본문서·파싱 파일·파일 경로·sourceGroupKey를 포함하지 않습니다.",
          "관리자 응답에서 문서 추출 일정은 sourceDocument를 제공하고 수동·개인 일정은 null로 반환합니다.",
        ],
        response: [
          "일정 제목, 내용, 대상, 장소, 공개 범위, 기간과 상태",
          "`updatedAt`: 낙관적 동시성 토큰. 수정 화면은 이 값을 그대로 수정 요청의 `expectedUpdatedAt`으로 보냅니다.",
          "`sourceDocument`: 관리자에게만 제공하는 원본 파일명과 원본문서 조회 URL. 수동·개인 일정이면 null",
        ],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`404 Not Found`: 일정이 없거나 접근 권한이 없음",
        ],
      }),
    }),
    request({
      name: "일정 수정",
      method: "PATCH",
      path: "/api/v1/schedules/:scheduleId",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        title: "수정된 회의",
        content: "수정된 회의 내용",
        targetText: "개발팀",
        location: "4층 회의실",
        visibilityType: "department",
        departmentIds: ["1", "2"],
        startAt: "2026-08-03T02:00:00Z",
        endAt: "2026-08-03T04:00:00Z",
        expectedUpdatedAt: "2026-07-27T09:00:00Z",
      }),
      description: docs({
        summary: "일정 정보를 수정합니다.",
        usage: "일정 수정과 draft 검수 화면에서 사용합니다.",
        pathParams: ["`scheduleId`: 수정할 일정 ID"],
        requestBody: [
          "`title`, `content`, `targetText`, `location`",
          "`visibilityType`, `departmentIds`, `startAt`, `endAt`",
          "`expectedUpdatedAt`(선택): 마지막으로 조회한 일정의 `updatedAt`을 그대로 보냅니다. 값이 현재와 다르면 409로 거절합니다(오래된 화면의 덮어쓰기 방지). 프론트는 조회 응답의 `updatedAt`을 그대로 실어 보내고, 409가 오면 최신 일정을 재조회합니다.",
        ],
        policy: [
          "전달하지 않은 필드는 유지합니다.",
          "사원은 본인 개인 일정만 수정할 수 있습니다.",
          "동시 수정 시 먼저 저장한 요청이 이깁니다. 나중 요청이 오래된 `expectedUpdatedAt`으로 덮어쓰려 하면 409로 거절합니다(수정 화면 진입부터 락을 잡지 않는 낙관적 방식).",
        ],
        response: [
          "수정된 일정 전체 정보",
          "`updatedAt`: 수정 성공 후 증가된 최신 동시성 토큰. 이어서 수정하려면 이 값을 다음 요청의 `expectedUpdatedAt`으로 보냅니다.",
        ],
        errors: [
          "`400 Bad Request`: 날짜 또는 공개 범위 오류",
          "`403 Forbidden`: 수정 권한 없음",
          "`404 Not Found`: 존재하지 않는 일정",
          "`409 Conflict`: 다른 사용자가 먼저 수정한 일정(동시성 충돌, `SCHEDULE_VERSION_CONFLICT`)",
        ],
      }),
    }),
    request({
      name: "일정 원본문서 조회",
      method: "GET",
      path: "/api/v1/schedules/:scheduleId/source-file",
      description: docs({
        summary: "관리자가 문서에서 추출된 일정의 원본문서를 조회합니다.",
        usage: "관리자 일정 상세와 draft 검수 화면에서 사용합니다.",
        pathParams: [
          "`scheduleId`: 원본문서를 조회할 일정 ID",
        ],
        policy: [
          "관리자만 호출할 수 있습니다.",
          "파싱 파일은 API로 제공하지 않습니다.",
          "수동 일정과 개인 일정에는 원본문서가 없습니다.",
        ],
        response: ["원본문서 파일 스트림과 원본 파일명"],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 일정 또는 연결된 원본문서가 없음",
        ],
      }),
    }),
    request({
      name: "일정 삭제 또는 draft 거부",
      method: "DELETE",
      path: "/api/v1/schedules/:scheduleId",
      description: docs({
        summary: "일정을 하드 삭제합니다. draft 일정 삭제는 거부로 처리합니다.",
        usage: "일정 삭제와 관리자 draft 거부 동작에서 사용합니다.",
        pathParams: ["`scheduleId`: 삭제할 일정 ID"],
        policy: [
          "거부된 draft는 별도 상태로 보존하지 않습니다.",
          "일정 원본문서에서 추출된 마지막 일정이 삭제되면 원본·파싱 파일도 함께 삭제합니다.",
        ],
        response: ["`204 No Content`: 삭제 완료"],
        errors: [
          "`403 Forbidden`: 삭제 권한 없음",
          "`404 Not Found`: 존재하지 않는 일정",
        ],
      }),
    }),
    request({
      name: "일정 draft 승인",
      method: "POST",
      path: "/api/v1/schedules/:scheduleId/approve",
      description: docs({
        summary: "AI가 추출한 일정 draft 한 건을 승인합니다.",
        usage: "관리자 일정 검수 화면에서 사용합니다.",
        pathParams: ["`scheduleId`: 승인할 draft 일정 ID"],
        policy: [
          "한 문서에서 추출한 일정도 일정별로 따로 승인합니다.",
          "승인된 일정만 사용자에게 공개합니다.",
        ],
        response: ["승인되어 `approved` 상태가 된 일정 정보"],
        errors: [
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 일정",
          "`409 Conflict`: draft 상태가 아니거나 이미 승인됨",
        ],
      }),
    }),
  ]),

  folder("문의", "사원이 담당자를 직접 선택하고 지정 담당자가 답변하는 API", [
    request({
      name: "문의 담당자 후보 조회",
      method: "GET",
      path: "/api/v1/inquiry-assignees",
      query: [
        { key: "keyword", value: "김관리", disabled: true },
      ],
      description: docs({
        summary: "문의 등록 시 선택할 수 있는 관리자 후보를 조회합니다.",
        usage: "문의 작성 화면의 담당자 선택 영역에서 사용합니다.",
        queryParams: ["`keyword`: 관리자 이름 검색어"],
        policy: [
          "부서 관리자 지정 여부와 관계없이 `approved`·`active` 상태의 모든 `admin`을 반환합니다.",
          "담당자 이름과 해당 회원의 소속 부서를 함께 보여줍니다.",
        ],
        response: [
          "`items`: `assigneeId`, `name`, `department`(`departmentId`, `name`)",
        ],
        errors: [
          "`400 Bad Request`: 검색어 형식 오류",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
        ],
      }),
    }),
    request({
      name: "문의 등록",
      method: "POST",
      path: "/api/v1/inquiries",
      body: formData([
        {
          key: "assigneeId",
          value: "7",
          description: "문의 등록자가 선택한 담당자 회원 ID",
        },
        { key: "title", value: "연차 문의", description: "문의 제목" },
        { key: "content", value: "연차 사용 기준이 궁금합니다.", description: "문의 내용" },
        { key: "priority", value: "normal", description: "high, normal 또는 low" },
        {
          key: "attachments",
          type: "file",
          description: "PNG, JPG, JPEG. 최대 5개, 파일당 20MB, 총 100MB",
          disabled: true,
        },
      ]),
      description: docs({
        summary: "사원이 모든 승인·활성 관리자 중 담당자 1명을 선택해 문의를 등록합니다.",
        usage: "문의 작성 화면에서 사용합니다.",
        requestBody: [
          "`assigneeId`: 담당자 회원 ID",
          "`title`: 문의 제목",
          "`content`: 문의 내용",
          "`priority`: `high`, `normal` 또는 `low`",
          "`attachments`: PNG, JPG, JPEG 이미지 최대 5개, 파일당 20MB, 총 100MB",
        ],
        policy: [
          "담당자는 부서 관리자 지정 여부와 관계없이 `approved`·`active` 상태의 `admin`이어야 합니다.",
          "등록 후 담당자를 변경할 수 없습니다.",
          "문의 처리 이력, 이메일 알림과 답변 임시 저장은 제공하지 않습니다.",
        ],
        response: [
          "`201 Created`",
          "`inquiryId`, `priority`, `status`, `createdAt`",
          "`author`: 작성자 ID, 이름과 소속 부서",
          "`assignee`: 담당자 ID, 이름과 소속 부서",
        ],
        errors: [
          "`400 Bad Request`: 제목, 내용, 담당자 또는 이미지 파일 오류",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`409 Conflict`: 선택한 회원이 현재 지정 가능한 담당자가 아님",
        ],
      }),
    }),
    request({
      name: "문의 목록 조회",
      method: "GET",
      path: "/api/v1/inquiries",
      query: [
        { key: "page", value: "1", disabled: true },
        { key: "size", value: "20", disabled: true },
        { key: "status", value: "pending", disabled: true },
        { key: "priority", value: "high", disabled: true },
        { key: "memberId", value: "1", disabled: true },
        { key: "createdFrom", value: "2026-07-01", disabled: true },
        { key: "createdTo", value: "2026-07-31", disabled: true },
        { key: "sort", value: "createdAt,desc", disabled: true },
        { key: "keyword", value: "연차", disabled: true },
      ],
      description: docs({
        summary: "사용자가 조회할 수 있는 문의 목록을 조회합니다.",
        usage: "내 문의와 관리자 문의 관리 화면에서 사용합니다.",
        queryParams: [
          "`page`, `size`: 페이지네이션",
          "`status`: 답변 대기 또는 답변 완료",
          "`priority`: high, normal 또는 low",
          "`memberId`: 등록자 ID 필터",
          "`createdFrom`, `createdTo`: 등록 기간",
          "`sort`: 정렬 필드와 방향. 기본값 `createdAt,desc`",
          "`keyword`: 문의 제목 또는 요청자 이름 검색어. 대소문자 구분 없이 부분 일치합니다",
        ],
        policy: [
          "사원은 본인 문의만 조회합니다.",
          "관리자는 본인이 담당자로 지정된 문의만 조회합니다.",
        ],
        response: [
          "`items`: 문의 ID, 제목, 작성자 이름·부서, 담당자 이름·부서, 우선순위, 상태와 생성 시각",
          "페이지 정보",
        ],
        errors: [
          "`400 Bad Request`: 필터 또는 페이지값 오류",
          "`401 Unauthorized`: accessToken이 유효하지 않음",
        ],
      }),
    }),
    request({
      name: "문의 상세 조회",
      method: "GET",
      path: "/api/v1/inquiries/:inquiryId",
      description: docs({
        summary: "문의 내용, 이미지 첨부파일과 답변을 조회합니다.",
        usage: "문의 상세 화면에서 사용합니다.",
        pathParams: ["`inquiryId`: 조회할 문의 ID"],
        response: [
          "문의 제목, 내용, 작성자 이름·부서, 담당자 이름·부서와 상태",
          "`attachments`: 이미지 파일 정보와 downloadUrl",
          "`answer`: 답변 내용, 관리자와 답변 시각. 미답변이면 null",
        ],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`404 Not Found`: 문의가 없거나 조회 권한이 없음",
        ],
      }),
    }),
    request({
      name: "문의 삭제",
      method: "DELETE",
      path: "/api/v1/inquiries/:inquiryId",
      description: docs({
        summary: "문의와 첨부파일 및 답변을 하드 삭제합니다.",
        usage: "문의 상세 화면에서 사용합니다.",
        pathParams: ["`inquiryId`: 삭제할 문의 ID"],
        policy: ["작성자 또는 지정 담당자만 삭제할 수 있습니다."],
        response: ["`204 No Content`: 삭제 완료"],
        errors: [
          "`403 Forbidden`: 삭제 권한 없음",
          "`404 Not Found`: 존재하지 않는 문의",
        ],
      }),
    }),
    request({
      name: "문의 첨부 이미지 다운로드",
      method: "GET",
      path: "/api/v1/inquiries/:inquiryId/attachments/:attachmentId",
      description: docs({
        summary: "문의 조회 권한을 검사한 뒤 첨부 이미지를 반환합니다.",
        usage: "문의 상세 화면의 첨부 이미지 표시와 다운로드에서 사용합니다.",
        pathParams: [
          "`inquiryId`: 첨부 이미지가 속한 문의 ID",
          "`attachmentId`: 첨부파일 JSON 내부 식별자",
        ],
        response: ["이미지 파일 스트림과 원본 파일명"],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`404 Not Found`: 문의·첨부파일이 없거나 접근 권한이 없음",
        ],
      }),
    }),
    request({
      name: "문의 답변 작성 또는 수정",
      method: "PUT",
      path: "/api/v1/inquiries/:inquiryId/answer",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({ content: "연차는 사내 규정에 따라 사용할 수 있습니다." }),
      description: docs({
        summary: "지정된 담당자가 문의 답변을 작성하거나 수정합니다.",
        usage: "관리자 문의 상세 화면에서 사용합니다.",
        pathParams: ["`inquiryId`: 답변할 문의 ID"],
        requestBody: ["`content`: 답변 내용"],
        policy: [
          "문의당 답변은 한 건만 존재합니다.",
          "답변이 없으면 생성하고 이미 있으면 전체 교체합니다.",
          "답변 임시 저장은 제공하지 않습니다.",
          "두 담당자가 거의 동시에 답변을 등록해 답변이 중복 생성되는 경우, 먼저 저장한 요청만 성공하고 나중 요청은 409로 거부합니다.",
        ],
        response: ["답변 ID, 내용, 관리자 ID와 답변 시각"],
        errors: [
          "`400 Bad Request`: 답변 내용이 비어 있음",
          "`403 Forbidden`: 지정된 담당자가 아님",
          "`404 Not Found`: 존재하지 않는 문의",
          "`409 Conflict`: 이미 답변이 등록된 문의(동시 등록 충돌, `INQUIRY_ANSWER_ALREADY_EXISTS`)",
        ],
      }),
    }),
    request({
      name: "문의 답변 삭제",
      method: "DELETE",
      path: "/api/v1/inquiries/:inquiryId/answer",
      description: docs({
        summary: "문의 답변을 하드 삭제하고 문의를 답변 대기 상태로 되돌립니다.",
        usage: "관리자 문의 상세 화면에서 사용합니다.",
        pathParams: ["`inquiryId`: 답변을 삭제할 문의 ID"],
        policy: ["지정된 담당자만 삭제할 수 있습니다."],
        response: ["`204 No Content`: 답변 삭제 완료"],
        errors: [
          "`403 Forbidden`: 답변 삭제 권한 없음",
          "`404 Not Found`: 문의 또는 답변이 존재하지 않음",
        ],
      }),
    }),
  ]),
];

const internalFolders = [
  folder("원본문서 처리", "Spring Boot가 FastAPI에 호출하는 Wiki·일정 공용 파싱 API", [
    request({
      name: "원본문서 파싱",
      method: "POST",
      path: "/internal/v1/source-parses",
      baseVariable: "aiBaseUrl",
      body: formData([
        { key: "requestId", value: "parse-request-1", description: "내부 요청 추적 ID" },
        {
          key: "sourceType",
          value: "wiki",
          description: "wiki 또는 schedule",
        },
        {
          key: "sourceId",
          value: "{{documentId}}",
          description: "documentId 또는 sourceGroupKey",
        },
        { key: "file", type: "file", description: "Spring Boot가 전달하는 원본 파일" },
        {
          key: "originalFileName",
          value: "policy.pdf",
          description: "원본 파일명",
        },
        {
          key: "mimeType",
          value: "application/pdf",
          description: "검증된 MIME 타입",
        },
      ]),
      description: docs({
        summary: "Wiki 또는 일정 원본문서에서 Markdown 텍스트를 추출합니다.",
        usage: "Spring Boot의 Wiki 비동기 작업과 일정 동기 처리에서 공용으로 호출합니다.",
        auth: "`X-Internal-API-Key` 필요",
        requestBody: [
          "`requestId`: 내부 요청 추적 ID",
          "`sourceType`: `wiki` 또는 `schedule`",
          "`sourceId`: Wiki documentId 또는 일정 sourceGroupKey",
          "`file`: 원본문서 파일",
          "`originalFileName`, `mimeType`: 검증된 파일 메타데이터",
        ],
        policy: [
          "Wiki는 TXT·MD·PDF·DOCX를 지원합니다.",
          "일정은 TXT·MD·DOCX·PDF·CSV·XLSX를 지원합니다.",
          "PDF·DOCX 텍스트가 없으면 내부적으로 OCR을 시도합니다.",
          "FastAPI는 DB와 서비스 파일을 직접 수정하지 않습니다.",
        ],
        response: [
          "`requestId`, `sourceType`, `sourceId`",
          "`parsedMarkdown`: 추출된 Markdown",
          "`warnings`: 경고 목록",
        ],
        errors: [
          "`400 Bad Request`: 메타데이터 또는 파일 형식 오류",
          "`401 Unauthorized`: 내부 API 키 오류",
          "`500 Internal Server Error`: `DOCUMENT_PARSE_FAILED`",
        ],
      }),
    }),
  ]),
  folder("Wiki 처리", "Wiki 전체 구조 변환과 관리자 수정 대화 API", [
    request({
      name: "Wiki 변환",
      method: "POST",
      path: "/internal/v1/wiki-transformations",
      baseVariable: "aiBaseUrl",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        jobId: "42",
        documentId: "15",
        scopeKey: "D1-D2",
        changeType: "document_replaced",
        parsedMarkdown: "# 취업 규칙\n본문...",
        removedParsedMarkdown: "# 기존 취업 규칙\n이전 본문...",
        wikiCapability: "{{wikiCapability}}",
        scopeVersion: 47,
      }),
      description: docs({
        summary: "새 문서를 분석해 Wiki 변경 결과를 생성합니다.",
        usage:
          "Spring Boot의 전역 직렬 Wiki 변환 작업에서 호출합니다. 호출은 한 번이며 자료 선택 단계가 따로 없습니다.",
        auth: "`X-Internal-API-Key` 필요",
        requestBody: [
          "`jobId`, `documentId`, `scopeKey`",
          "`changeType`: `document_added`, `document_removed` 또는 `document_replaced`",
          "`parsedMarkdown`: added·replaced의 새 문서 파싱 결과; removed에서는 생략",
          "`removedParsedMarkdown`: removed·replaced의 제거 또는 교체 전 문서 파싱 결과",
          "`wikiCapability`: Wiki 조회 API 호출에 실을 요청 단위 열람 허가. **필수입니다.** 빠지면 에이전트가 현재 Wiki를 전혀 읽지 못한 채 변환하게 됩니다",
          "`scopeVersion`: 요청 시작 시점의 `wiki_scope.scope_version`. **필수입니다.** 조회 응답의 값과 다르면 FastAPI가 중단합니다",
        ],
        policy: [
          "다른 scopeKey의 문서와 Wiki는 사용하지 않습니다.",
          "현재 목차·카테고리·Wiki 본문을 전달하지 않습니다. 에이전트가 Wiki 조회 API로 직접 읽습니다.",
          "FastAPI는 Wiki 조회 API 외의 실제 Wiki 본문·파일 경로·DB 정보에 접근하지 않습니다.",
          "Wiki 및 Wiki 카테고리 생성·수정·병합·제거 결과를 반환할 수 있습니다.",
          "새 Wiki와 카테고리는 temp 참조값을 사용하고 Spring Boot가 실제 ID를 발급합니다.",
          "FastAPI는 구조화된 변경 결과만 반환하고 Spring Boot가 링크·관계를 검증 후 반영합니다.",
          "응답의 `wikiPath`가 필수인 이유: Wiki 본문의 내부 링크는 `](pages/{pageKey}.md)`처럼 파일명 기준입니다. 경로 없이 `pages/{wikiId}.md`로 적재하면 그 링크가 어느 페이지도 가리키지 못하고 병합·제거 시 끊어질 링크를 판별할 수 없습니다.",
          "`wikiCapability`는 로그·오류 응답·telemetry에 남기지 않습니다.",
        ],
        response: [
          "`summary`: 문서별 작업 요약",
          "`categoryChanges`, `wikiChanges`, `relationChanges`",
          "`wikiChanges[].wikiCategoryRef`: 그 Wiki가 속할 카테고리. 같은 응답의 `tempCategoryId` 또는 기존 `wikiCategoryId`",
          "`wikiChanges[].wikiPath`: `action`이 `create`일 때만. 에이전트가 발급한 신규 페이지 경로 (DR-016)",
          "`wikiChanges[].evidence`(선택): 문서 ID, 각주, 위치와 인용 근거. Wiki-원본문서 연결은 오직 이 필드로만 전달됩니다 — Spring Boot는 `evidence`를 `wiki.document_refs`에 반영하고, 여기엔 항상 그 변경이 속한 원본문서 ID가 포함됩니다.",
          "`relationChanges`는 Wiki-Wiki 관계 전용입니다. Wiki-원본문서 연결은 `relationChanges`가 아니라 위 `wikiChanges[].evidence`가 전달합니다.",
          "`relationChanges[].action`: `add` 또는 `remove`",
          "`relationChanges[].type`: 항상 `wiki_wiki`",
          "`relationChanges[].sourceWikiRef`: 관계의 출발 Wiki. 같은 응답의 `tempWikiId` 또는 기존 `wikiId`",
          "`relationChanges[].targetWikiRef`: 관계의 대상 Wiki. 같은 응답의 `tempWikiId` 또는 기존 `wikiId`",
          "`indexEntries`: AI가 정한 목차 구조·순서·제목·요약",
          "`indexEntries[].wikiRef`, `order`, `title`, `summary`",
        ],
        errors: [
          "`400 Bad Request`: 현재 Wiki 구조 또는 요청값 오류",
          "`401 Unauthorized`: 내부 API 키 오류",
          "`500 Internal Server Error`: `WIKI_TRANSFORMATION_FAILED`; 오류 본문의 `failureStage`는 실패 단계를 제공합니다.",
        ],
      }),
    }),
    request({
      name: "Wiki 관리자 수정",
      method: "POST",
      path: "/internal/v1/wiki-edits",
      baseVariable: "aiBaseUrl",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        wikiId: "100",
        scopeKey: "D1-D2",
        instruction: "중복된 휴가 규정을 하나로 정리해줘.",
        wikiCapability: "{{wikiCapability}}",
        scopeVersion: 47,
        adminInstructionDocumentId: "817",
        chatHistory: [],
      }),
      description: docs({
        summary: "관리자의 자연어 지시를 바탕으로 Wiki 수정 결과를 생성합니다.",
        usage:
          "Spring Boot가 Wiki 상세 관리자 대화를 처리할 때 호출합니다. 수정 대상 본문과 근거 원본문서를 함께 보내지 않습니다.",
        auth: "`X-Internal-API-Key` 필요",
        requestBody: [
          "`wikiId`, `scopeKey`, `instruction`",
          "`chatHistory`: 해당 Wiki 관리자 대화",
          "`wikiCapability`: Wiki 조회 API 호출에 실을 요청 단위 열람 허가. **필수입니다.** 빠지면 에이전트가 수정 대상 본문을 전혀 읽지 못한 채 수정하게 됩니다",
          "`scopeVersion`: 요청 시작 시점의 `wiki_scope.scope_version`. **필수입니다.** 조회 응답의 값과 다르면 FastAPI가 중단합니다",
          "`adminInstructionDocumentId`: 이번 관리자 지시를 저장한 원본문서 ID. `wikiChanges[].evidence[].documentId`가 이 값을 인용해야 합니다. **필수 문자열입니다.**",
        ],
        policy: [
          "관련 없는 로그와 다른 scopeKey 자료는 전달하지 않습니다.",
          "현재 Wiki 본문과 근거 원본문서를 전달하지 않습니다. 에이전트가 Wiki 조회 API로 직접 읽습니다.",
          "원본문서에서 근거를 찾을 수 없는 변경은 경고하거나 생성하지 않습니다.",
          "응답의 `wikiPath`가 필수인 이유는 Wiki 변환과 같습니다 — 본문의 내부 링크가 파일명 기준입니다.",
          "`wikiCapability`는 로그·오류 응답·telemetry에 남기지 않습니다.",
        ],
        response: [
          "`agentMessage`: 관리자에게 보여줄 응답",
          "`wikiChanges`, `categoryChanges`, `relationChanges`, `indexEntries`",
          "`wikiChanges[].wikiCategoryRef`·`wikiPath`는 Wiki 변환과 같은 규칙을 따릅니다.",
          "`relationChanges[]`·`indexEntries[]`는 Wiki 변환과 같은 필드를 따릅니다 (`sourceWikiRef` 등). `relationChanges`는 Wiki-Wiki 전용이고, Wiki-원본문서 연결은 `wikiChanges[].evidence`가 전달합니다.",
        ],
        errors: [
          "`400 Bad Request`: 지시 내용 또는 Wiki 컨텍스트 오류",
          "`401 Unauthorized`: 내부 API 키 오류",
          "`500 Internal Server Error`: `WIKI_EDIT_FAILED`",
        ],
      }),
    }),
  ]),
  folder("일정 처리", "파싱된 일정 문서에서 일정별 초안을 추출하는 API", [
    request({
      name: "일정 추출",
      method: "POST",
      path: "/internal/v1/schedule-extractions",
      baseVariable: "aiBaseUrl",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        sourceGroupKey: "schedule-source-20260727-01",
        parsedMarkdown: "# 8월 일정\n...",
        visibilityType: "department",
        departmentIds: ["1", "2"],
      }),
      description: docs({
        summary: "파싱된 일정 문서에서 일정을 개별 항목으로 추출합니다.",
        usage: "Spring Boot 일정 문서 처리 작업에서 내부 호출합니다.",
        auth: "`X-Internal-API-Key` 필요",
        requestBody: [
          "`sourceGroupKey`: 일정 원본문서 묶음 키",
          "`parsedMarkdown`: 일정 문서 파싱 결과",
          "`visibilityType`, `departmentIds`: 업로드 시 선택한 공개 범위",
        ],
        policy: [
          "추출된 일정은 각각 별도의 draft로 반환합니다.",
          "일정이 없으면 오류가 아닌 no_schedule과 빈 schedules를 반환합니다.",
          "FastAPI는 일정과 파일을 직접 저장하지 않습니다.",
        ],
        response: [
          "`status`: extracted 또는 no_schedule",
          "`schedules`: 순서, 제목, 내용, 대상, 장소, 공개 범위, 시작·종료 시각",
          "`warnings`: 불명확한 날짜와 필드 경고",
        ],
        errors: [
          "`400 Bad Request`: 날짜 또는 공개 범위 오류",
          "`401 Unauthorized`: 내부 API 키 오류",
          "`500 Internal Server Error`: `SCHEDULE_EXTRACTION_FAILED`",
        ],
      }),
    }),
  ]),
  folder("답변 생성", "에이전트가 스스로 조회해 답변하는 챗봇 API. 호출은 한 번이다", [
    request({
      name: "답변 생성",
      method: "POST",
      path: "/internal/v1/answers",
      baseVariable: "aiBaseUrl",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        questionId: "500",
        conversationId: "chat-123",
        question: "연차 규정과 다음 휴가 일정을 알려줘.",
        conversationMessages: [
          { role: "user", content: "연차 신청 방법을 알려줘." },
          { role: "assistant", content: "연차 신청 절차는 다음과 같습니다." },
        ],
        wikiIndexes: [
          {
            scopeKey: "D1-D2",
            indexMarkdown: "# 사내 규정\n- [휴가 규정](pages/101.md)",
            wikiCapability: "{{wikiCapability}}",
          },
        ],
      }),
      description: docs({
        summary:
          "에이전트가 목차를 보고 필요한 Wiki 본문과 일정을 직접 조회해 답변을 생성합니다.",
        usage:
          "Wiki·일정 챗봇 질문 처리에서 내부 호출합니다. 호출은 한 번이며 자료 선택 단계가 따로 없습니다.",
        auth: "`X-Internal-API-Key` 필요",
        requestBody: [
          "`questionId`, `conversationId`, `question`",
          "`conversationMessages`: 같은 사용자 대화의 이전 질문·답변. AI가 최근 12개·4,000자로 자릅니다",
          "`wikiIndexes`: 권한 있는 공간별 scopeKey와 index.md 내용",
          "`wikiIndexes[].wikiCapability`: 그 범위의 Wiki 조회 API 호출에 실을 열람 허가. **범위마다 하나씩 필수로 발급합니다.** 빠진 범위는 AI가 조회할 수 없어 그 범위의 Wiki는 답변 근거가 되지 못합니다",
        ],
        policy: [
          "본문과 일정 목록을 전달하지 않습니다. 에이전트가 Wiki 조회 API와 일정 조회 API로 직접 읽습니다.",
          "`sources`는 에이전트가 실제로 읽은 Wiki·일정 **중 답변에 사용한 것**입니다. 에이전트가 사용했다고 신고해도 실제로 읽지 않은 자료는 출처가 되지 않습니다.",
          "`questionType`은 에이전트가 **질문 맥락을 보고 판단**합니다 (FR-QNA-002). 읽은 자료의 종류로 정하지 않습니다.",
          "근거를 찾아봤지만 없으면 `200`에 빈 `sources`로 정보가 부족함을 안내합니다 (FR-QNA-007). 조회를 아예 시도하지 않은 실행만 `NO_WIKI_OR_SCHEDULE_WAS_READ` 실패입니다.",
          "일정 기간은 에이전트가 질문에 맞춰 정합니다. 백엔드가 미리 고르지 않습니다.",
          "AI는 25초 안에 응답합니다. 백엔드 읽기 타임아웃은 그보다 넉넉해야 합니다.",
          "`wikiCapability`는 로그·오류 응답·telemetry에 남기지 않습니다.",
        ],
        response: [
          "`answer`: 생성된 답변. 근거가 없으면 정보가 부족하다는 안내입니다",
          "`sources`: 답변에 사용한 출처 배열. `type`·`wikiId` 또는 `scheduleId`·`title`. 백엔드가 `answer_source.source_title`에 제목을 저장하므로 `title`은 필수입니다",
          "`questionType`: `wiki` | `schedule` | `mixed`. 백엔드가 `question` 테이블에 저장합니다",
        ],
        errors: [
          "`400 Bad Request`: `INVALID_ANSWER_REQUEST`",
          "`401 Unauthorized`: 내부 API 키 오류",
          "`500 Internal Server Error`: `NO_WIKI_OR_SCHEDULE_WAS_READ` · `WIKI_QUERY_FAILED` · `SCHEDULE_QUERY_FAILED` · `AGENT_TURN_LIMIT_REACHED` · `AGENT_TIMED_OUT` · `MODEL_CALL_FAILED` · `ANSWER_WAS_EMPTY`",
        ],
      }),
    }),
  ]),
  folder(
    "Wiki 조회 창구",
    "FastAPI가 Spring Boot에 호출하는 Wiki 조회 창구. 방향이 다른 폴더와 반대다 — FastAPI가 호출자이고 Spring Boot가 응답한다. FR-WIKI-002가 요구하는 \"백엔드가 검색·본문·관계 조회 수단을 제공하고 에이전트가 필요한 Wiki를 선택해 조회한다\"의 창구다.",
    [
      request({
        name: "Wiki 본문 검색",
        method: "GET",
        path: "/internal/v1/wiki-search",
        query: [
          { key: "scopeKey", value: "D1-D2", description: "조회 범위" },
          { key: "query", value: "연차 이월", description: "검색어" },
          { key: "limit", value: "10", description: "결과 개수. 기본 10, 최대 50" },
        ],
        headers: [wikiCapabilityHeader],
        description: docs({
          summary: "범위 안의 Wiki 본문을 전문 검색합니다.",
          usage: "에이전트가 고칠 Wiki를 찾을 때 호출합니다.",
          auth: "`X-Internal-API-Key`와 `X-Wiki-Capability` 필요",
          queryParams: [
            "`scopeKey`: 조회 범위. 허가에 묶인 값과 다르면 404",
            "`query`: 검색어. 빈 문자열은 400",
            "`limit`: 결과 개수. 기본 `10`, 최대 `50`. 넘으면 400",
          ],
          policy: [
            "`wiki_search_chunk` 파생 색인을 사용합니다(DR-029).",
            "반영이 완료된 Wiki만 검색합니다. 작업 공간의 검증 전 내용은 색인하지 않습니다(DR-006).",
            "**서버가 항상 `boolean phrase`로 수행합니다.** 질의 모드를 파라미터로 열지 않습니다 — 모든 어절 AND(`+req`)는 자연어 질의 R@5를 0.00으로 전멸시킵니다.",
            "정렬은 관련도 내림차순이고 동률은 `wikiId` 오름차순 → `chunkIndex` 오름차순입니다. 동률 순서가 흔들리면 같은 질의가 다른 결과를 냅니다.",
            "페이지네이션은 없습니다. `limit` 안에서 끝냅니다.",
            "허가 범위 밖은 존재를 노출하지 않도록 404로 거부합니다(NFR-SEC-003 · FR-ACL-006).",
          ],
          response: [
            "`scopeVersion`: 응답 시점의 범위 버전(DR-030)",
            "`items[].wikiId`, `title`, `breadcrumb`: 위치를 보여주는 헤더 경로",
            "`items[].snippet`: 일치 구간",
            "`items[].chunkIndex`, `contentHash`",
          ],
          errors: [
            "`400 Bad Request`: 검색어 또는 파라미터 오류",
            "`401 Unauthorized`: 내부 API 키 오류",
            "`404 Not Found`: `WIKI_CAPABILITY_EXPIRED`(허가 만료·철회) 또는 `WIKI_SCOPE_NOT_FOUND`(허가 범위 밖·범위 없음). HTTP 상태는 같고 `code`로 구분합니다",
          ],
        }),
      }),
      request({
        name: "Wiki 목록",
        method: "GET",
        path: "/internal/v1/wiki-pages",
        query: [
          { key: "scopeKey", value: "D1-D2", description: "조회 범위" },
          { key: "limit", value: "200", description: "페이지 크기. 기본 200, 최대 500" },
          { key: "cursor", value: "", description: "이전 응답의 nextCursor. 첫 호출은 생략", disabled: true },
        ],
        headers: [wikiCapabilityHeader],
        description: docs({
          summary: "범위 안의 Wiki 카탈로그를 조회합니다.",
          usage: "에이전트가 어떤 Wiki가 있는지 파악할 때 호출합니다.",
          auth: "`X-Internal-API-Key`와 `X-Wiki-Capability` 필요",
          queryParams: [
            "`scopeKey`: 조회 범위",
            "`limit`: 페이지 크기. 기본 `200`, 최대 `500`. 넘으면 400",
            "`cursor`: 이전 응답의 `nextCursor`. 첫 호출에서는 생략합니다",
          ],
          policy: [
            "본문을 싣지 않습니다. 본문은 단건 조회로 가져갑니다.",
            "`summary`는 `wiki.summary`에서 채웁니다.",
            "`wikiPath`는 필수입니다. FastAPI가 Wiki ID를 자기 페이지 주소로 되돌릴 때 씁니다(DR-016).",
            "정렬은 `wikiId` 오름차순입니다. 안정 정렬이라 커서가 항목을 건너뛰거나 겹치지 않습니다.",
            "`cursor` 문자열의 내부 형식은 Spring Boot가 정합니다. FastAPI는 받은 값을 그대로 되돌려줍니다.",
            "FastAPI는 `nextCursor`가 `null`이 될 때까지 이어 받습니다. 페이지 사이에 `scopeVersion`이 바뀌면 중단합니다.",
          ],
          response: [
            "`scopeVersion`",
            "`nextCursor`: 다음 페이지 커서. 더 없으면 `null`",
            "`items[].wikiId`, `title`, `summary`",
            "`items[].wikiCategoryId`, `categoryName`",
            "`items[].wikiPath`, `contentHash`, `updatedAt`",
          ],
          errors: [
            "`400 Bad Request`: 파라미터 오류 · `limit` 초과 · 잘못된 `cursor`",
            "`401 Unauthorized`: 내부 API 키 오류",
            "`404 Not Found`: `WIKI_CAPABILITY_EXPIRED`(허가 만료·철회) 또는 `WIKI_SCOPE_NOT_FOUND`(허가 범위 밖·범위 없음). HTTP 상태는 같고 `code`로 구분합니다 — 상태를 나누면 존재가 노출됩니다",
          ],
        }),
      }),
      request({
        name: "Wiki 본문",
        method: "GET",
        path: "/internal/v1/wikis/:wikiId/content",
        query: [{ key: "scopeKey", value: "D1-D2", description: "조회 범위" }],
        headers: [wikiCapabilityHeader],
        description: docs({
          summary: "Wiki 1건의 Markdown 원문을 조회합니다.",
          usage: "에이전트가 고칠 Wiki를 읽을 때 호출합니다.",
          auth: "`X-Internal-API-Key`와 `X-Wiki-Capability` 필요",
          pathParams: ["`wikiId`: 조회할 Wiki ID"],
          queryParams: ["`scopeKey`: 조회 범위"],
          policy: [
            "프론트매터를 포함한 원문을 그대로 돌려줍니다.",
            "Spring Boot가 `wiki.wiki_path`로 파일을 읽어 싣습니다. FastAPI는 서비스 파일에 직접 접근하지 않습니다.",
            "`contentHash`가 이미 받은 값과 같으면 FastAPI는 다시 요청하지 않습니다.",
          ],
          response: [
            "`scopeVersion`",
            "`wikiId`, `title`, `wikiPath`",
            "`contentMarkdown`: 프론트매터 포함 원문",
            "`contentHash`",
          ],
          errors: [
            "`400 Bad Request`: 파라미터 오류",
            "`401 Unauthorized`: 내부 API 키 오류",
            "`404 Not Found`: `WIKI_CAPABILITY_EXPIRED` · `WIKI_SCOPE_NOT_FOUND` · `WIKI_NOT_FOUND`. HTTP 상태는 같고 `code`로 구분합니다",
          ],
        }),
      }),
      request({
        name: "Wiki 관계",
        method: "GET",
        path: "/internal/v1/wikis/:wikiId/relations",
        query: [{ key: "scopeKey", value: "D1-D2", description: "조회 범위" }],
        headers: [wikiCapabilityHeader],
        description: docs({
          summary: "Wiki 1건의 인용·링크 관계를 조회합니다.",
          usage: "에이전트가 병합·제거로 남의 링크를 깨뜨리지 않도록 확인할 때 호출합니다.",
          auth: "`X-Internal-API-Key`와 `X-Wiki-Capability` 필요",
          pathParams: ["`wikiId`: 조회할 Wiki ID"],
          queryParams: ["`scopeKey`: 조회 범위"],
          policy: [
            "`wiki.wiki_refs`·`document_refs` JSON을 사용합니다. 별도 관계 테이블을 만들지 않습니다(DR-002).",
            "`backlinks`는 저장되지 않은 역방향이므로 Spring Boot가 계산합니다.",
          ],
          response: [
            "`scopeVersion`",
            "`wikiRefs`: 이 Wiki가 참조하는 Wiki ID",
            "`documentRefs`: 근거 원본문서 ID",
            "`backlinks`: 이 Wiki를 참조하는 Wiki ID",
          ],
          errors: [
            "`400 Bad Request`: 파라미터 오류",
            "`401 Unauthorized`: 내부 API 키 오류",
            "`404 Not Found`: `WIKI_CAPABILITY_EXPIRED` · `WIKI_SCOPE_NOT_FOUND` · `WIKI_NOT_FOUND`. HTTP 상태는 같고 `code`로 구분합니다",
          ],
        }),
      }),
      request({
        name: "Wiki 범위 관계",
        method: "GET",
        path: "/internal/v1/wiki-spaces/:scopeKey/relations",
        headers: [wikiCapabilityHeader],
        description: docs({
          summary: "범위 전체의 Wiki 참조 관계를 한 번에 조회합니다.",
          usage:
            "에이전트가 병합·제거로 남의 링크를 깨뜨리지 않으려면 범위 전체의 참조 관계를 알아야 합니다. Wiki 1건씩 조회하면 Wiki 장수만큼 호출이 나가므로 FastAPI는 이 API를 1회 호출해 그래프를 받습니다.",
          auth: "`X-Internal-API-Key`와 `X-Wiki-Capability` 필요",
          pathParams: ["`scopeKey`: 조회 범위"],
          policy: [
            "`wiki.wiki_refs`·`document_refs` JSON을 사용합니다. 별도 관계 테이블을 만들지 않습니다(DR-002).",
            "역방향(`backlinks`)은 싣지 않습니다. 범위 전체 간선이 있으면 소비자가 뒤집어 구합니다.",
            "Wiki가 0장인 범위는 `items`를 빈 배열로 반환합니다.",
          ],
          response: [
            "`scopeVersion`",
            "`items[].wikiId`",
            "`items[].wikiRefs`: 이 Wiki가 참조하는 Wiki ID",
            "`items[].documentRefs`: 근거 원본문서 ID",
          ],
          errors: [
            "`401 Unauthorized`: 내부 API 키 오류",
            "`404 Not Found`: `WIKI_CAPABILITY_EXPIRED`(허가 만료·철회) 또는 `WIKI_SCOPE_NOT_FOUND`(허가 범위 밖·범위 없음). HTTP 상태는 같고 `code`로 구분합니다",
          ],
        }),
      }),
      request({
        name: "Wiki 목차",
        method: "GET",
        path: "/internal/v1/wiki-spaces/:scopeKey/index",
        headers: [wikiCapabilityHeader],
        description: docs({
          summary: "범위의 현재 목차 Markdown을 조회합니다.",
          usage: "에이전트가 목차 구조를 파악하고 갱신안을 만들 때 호출합니다.",
          auth: "`X-Internal-API-Key`와 `X-Wiki-Capability` 필요",
          pathParams: ["`scopeKey`: 조회 범위"],
          response: ["`scopeVersion`", "`scopeKey`", "`indexMarkdown`: 현재 목차 원문"],
          errors: [
            "`401 Unauthorized`: 내부 API 키 오류",
            "`404 Not Found`: `WIKI_CAPABILITY_EXPIRED`(허가 만료·철회) 또는 `WIKI_SCOPE_NOT_FOUND`(허가 범위 밖·범위 없음). HTTP 상태는 같고 `code`로 구분합니다",
          ],
        }),
      }),
      request({
        name: "Wiki 카테고리",
        method: "GET",
        path: "/internal/v1/wiki-spaces/:scopeKey/categories",
        headers: [wikiCapabilityHeader],
        description: docs({
          summary: "범위의 카테고리 목록과 사용량을 조회합니다.",
          usage: "에이전트가 카테고리를 생성·병합·삭제할지 판단할 때 호출합니다(FR-WIKI-014).",
          auth: "`X-Internal-API-Key`와 `X-Wiki-Capability` 필요",
          pathParams: ["`scopeKey`: 조회 범위"],
          policy: ["`wikiCount`는 병합 판단에 사용합니다."],
          response: [
            "`scopeVersion`",
            "`items[].wikiCategoryId`, `name`, `wikiCount`",
          ],
          errors: [
            "`401 Unauthorized`: 내부 API 키 오류",
            "`404 Not Found`: `WIKI_CAPABILITY_EXPIRED`(허가 만료·철회) 또는 `WIKI_SCOPE_NOT_FOUND`(허가 범위 밖·범위 없음). HTTP 상태는 같고 `code`로 구분합니다",
          ],
        }),
      }),
      request({
        name: "원본문서 파싱본",
        method: "GET",
        path: "/internal/v1/documents/:documentId/parsed",
        query: [{ key: "scopeKey", value: "D1-D2", description: "조회 범위" }],
        headers: [wikiCapabilityHeader],
        description: docs({
          summary: "원본문서의 파싱 Markdown을 조회합니다.",
          usage: "각주의 인용을 원문과 대조할 때 호출합니다(FR-WIKI-001 · NFR-AI-002).",
          auth: "`X-Internal-API-Key`와 `X-Wiki-Capability` 필요",
          pathParams: ["`documentId`: 조회할 원본문서 ID"],
          queryParams: ["`scopeKey`: 조회 범위"],
          response: ["`documentId`", "`originalFileName`", "`parsedMarkdown`"],
          errors: [
            "`400 Bad Request`: 파라미터 오류",
            "`401 Unauthorized`: 내부 API 키 오류",
            "`404 Not Found`: `WIKI_CAPABILITY_EXPIRED` · `WIKI_SCOPE_NOT_FOUND` · `DOCUMENT_NOT_FOUND`. HTTP 상태는 같고 `code`로 구분합니다",
          ],
        }),
      }),
    ],
  ),
  folder(
    "일정 조회 API",
    "FastAPI가 Spring Boot에 호출하는 일정 조회. 방향이 Wiki 조회 API와 같다 — FastAPI가 호출자다. 챗봇 에이전트가 질문에 맞는 기간을 정해 직접 조회한다. 요청에 일정 목록을 싣지 않는다.",
    [
      request({
        name: "일정 목록",
        method: "GET",
        path: "/internal/v1/schedules",
        query: [
          { key: "questionId", value: "500", description: "권한 판정 기준이 되는 질문 ID" },
          { key: "from", value: "2026-08-01", description: "조회 시작일 (YYYY-MM-DD)" },
          { key: "to", value: "2026-08-31", description: "조회 종료일 (YYYY-MM-DD)" },
          { key: "keyword", value: "워크샵", description: "제목 부분 일치 (선택)" },
          { key: "limit", value: "50", description: "결과 개수. 기본 50, 최대 50" },
        ],
        description: docs({
          summary: "기간 안에서 질문자가 볼 수 있는 일정의 제목·시각·대상을 조회합니다.",
          usage:
            "챗봇 에이전트가 일정 질문에 답할 때 호출합니다. 내용(`content`)은 오지 않으므로 일정 상세를 따로 조회합니다.",
          auth: "`X-Internal-API-Key` 필요",
          queryParams: [
            "`questionId`: **권한 판정의 근거입니다.** 백엔드가 이 번호로 질문한 사용자를 찾아 그 사용자가 볼 수 있는 일정만 반환합니다",
            "`from`, `to`: 조회 기간",
            "`keyword`: 제목 부분 일치. 한국어는 조사가 붙으므로 단어 단위가 아니라 부분 일치로 봅니다",
            "`limit`: 최대 50. 넘겨도 50으로 자릅니다",
          ],
          policy: [
            "권한은 요청에 실린 값이 아니라 `questionId`로 판정합니다. 내부 API 키는 호출자가 AI 서버임만 증명합니다.",
            "승인된 일정만 반환합니다.",
            "시작 시각이 가까운 순서로 채우고 `limit`을 넘으면 자릅니다.",
            "`truncated`가 필요한 이유는 잘린 사실을 모르면 에이전트가 전부 본 것으로 단정하기 때문입니다.",
          ],
          response: [
            "`items[]`: `scheduleId` · `title` · `startAt` · `endAt` · `targetText` · `location`",
            "`truncated`: 상한에 걸려 잘렸는지",
          ],
          errors: [
            "`400 Bad Request`: 파라미터 오류 (`questionId`·`from`·`to` 누락 포함)",
            "`401 Unauthorized`: 내부 API 키 오류",
            "`404 Not Found`: `QUESTION_NOT_FOUND`",
          ],
        }),
      }),
      request({
        name: "일정 상세",
        method: "GET",
        path: "/internal/v1/schedules/:scheduleId",
        query: [
          { key: "questionId", value: "500", description: "권한 판정 기준이 되는 질문 ID" },
        ],
        description: docs({
          summary: "일정 하나의 내용까지 조회합니다.",
          usage: "에이전트가 목록에서 고른 일정을 근거로 쓰려면 호출해야 합니다.",
          auth: "`X-Internal-API-Key` 필요",
          pathParams: ["`scheduleId`: 조회할 일정 ID"],
          queryParams: ["`questionId`: 권한 판정 기준"],
          policy: [
            "질문자가 볼 수 없는 일정은 `SCHEDULE_NOT_FOUND`입니다. 존재 여부를 흘리지 않습니다.",
          ],
          response: [
            "`scheduleId` · `title` · `content` · `startAt` · `endAt` · `targetText` · `location`",
          ],
          errors: [
            "`400 Bad Request`: 파라미터 오류",
            "`401 Unauthorized`: 내부 API 키 오류",
            "`404 Not Found`: `QUESTION_NOT_FOUND` · `SCHEDULE_NOT_FOUND`. HTTP 상태는 같고 `code`로 구분합니다",
          ],
        }),
      }),
    ],
  ),
];

// ============================================================
// S15P11B106-101 테스트 안정화 후처리 (2/7~6/7)
//   계약 컬렉션은 59개 요청 그대로 유지한다(요청을 추가하지 않는다 → validator 통과).
//   역할별 로그인/CSRF는 "요청 추가" 대신 컬렉션 레벨 pre-request 스크립트(pm.sendRequest)로 처리하고,
//   CSRF 저장·동적 ID 캡처·상태코드 검증은 각 요청의 test 스크립트로 붙인다.
//   (JSON을 직접 수정하지 않고 항상 이 생성기에서 재생성한다)
// ============================================================
let publicCollectionEvent = [];
(function stabilizeForLocalTesting() {
  const PW = "password123!";
  const emailByRole = {
    super: "superadmin@ajt.com",
    deptAdmin: "planning.admin@ajt.com",
    employee: "employee@ajt.com",
  };

  const testEvent = (lines) => ({
    listen: "test",
    script: { type: "text/javascript", exec: lines },
  });
  const addTest = (item, lines) => {
    item.event = item.event || [];
    item.event.push(testEvent(lines));
  };
  const expectStatus = (item, codes) =>
    addTest(item, [
      `pm.test(${JSON.stringify(item.name)} + ' 상태 ' + pm.response.code + ' (기대 ' + ${JSON.stringify(codes.join("/"))} + ')', function () {`,
      `  pm.expect(${JSON.stringify(codes)}).to.include(pm.response.code);`,
      "});",
    ]);

  const csrfSaveLines = [
    "let token = '';",
    "try { const sc = (pm.response.headers.get('Set-Cookie')) || ''; const m = String(sc).match(/XSRF-TOKEN=([^;]+)/); if (m) token = m[1]; } catch (e) {}",
    "if (!token) { try { token = pm.cookies.get('XSRF-TOKEN') || ''; } catch (e) {} }",
    "if (token) { pm.environment.set('csrfToken', token); pm.collectionVariables.set('csrfToken', token); }",
    "pm.test('CSRF 토큰 확보', function () { pm.expect(token, 'XSRF-TOKEN').to.be.a('string').and.to.have.length.above(0); });",
  ];

  const findFolder = (name) => publicFolders.find((f) => f.name === name);
  const findReq = (folder, name) =>
    folder ? folder.item.find((i) => i.name === name) : undefined;
  const cap = (item, lines) => item && addTest(item, lines);
  const setFormValue = (item, key, value) => {
    const fd = item?.request?.body?.formdata;
    const field = fd && fd.find((f) => f.key === key);
    if (field) field.value = value;
  };
  const setFormFile = (item, key, src) => {
    const fd = item?.request?.body?.formdata;
    const field = fd && fd.find((f) => f.key === key);
    if (field) {
      field.type = "file";
      field.src = src;
      field.disabled = false; // 계약 예시에서 선택 항목(disabled)이면 Newman이 건너뛰므로 활성화
      delete field.value;
    }
  };
  const setPath = (item, path) => {
    if (item) item.request.url = urlObject("backendBaseUrl", path);
  };
  const setUrl = (item, path, query = []) => {
    if (item) item.request.url = urlObject("backendBaseUrl", path, query);
  };
  const setJsonBody = (item, obj) => {
    if (item) item.request.body = rawJson(obj);
  };
  const setJsonField = (item, patch) => {
    const body = item?.request?.body;
    if (!body || body.mode !== "raw") return;
    let obj;
    try {
      obj = JSON.parse(body.raw);
    } catch {
      return;
    }
    Object.assign(obj, patch);
    item.request.body = rawJson(obj);
  };
  const reorder = (folder, orderedNames) => {
    if (!folder) return;
    const byName = new Map(folder.item.map((i) => [i.name, i]));
    const picked = [];
    for (const n of orderedNames) {
      if (byName.has(n)) {
        picked.push(byName.get(n));
        byName.delete(n);
      }
    }
    folder.item = [...picked, ...byName.values()];
  };

  const authFolder = findFolder("인증");
  const userFolder = findFolder("사용자");
  const deptFolder = findFolder("부서");
  const catFolder = findFolder("문서 카테고리");
  const docFolder = findFolder("문서 및 AI 작업");
  const wikiFolder = findFolder("Wiki 및 질문");
  const schFolder = findFolder("일정");
  const inqFolder = findFolder("문의");

  // ---- 2/7: 기존 "CSRF 토큰 발급" 요청 test 스크립트로 토큰 저장 ----
  addTest(findReq(authFolder, "CSRF 토큰 발급"), csrfSaveLines);

  // ---- 계약 예시 본문이 로컬 시드/구현과 충돌하지 않도록 안전값 오버라이드 ----
  // 회원가입: 매 실행 새 이메일 → 항상 202 성공(“이미 있어도 성공”이 아니라 실제 성공 확인)
  setJsonField(findReq(authFolder, "회원가입"), {
    email: "qa_{{$timestamp}}@ajt.com",
    password: PW,
    name: "QA가입테스트",
    departmentId: "1",
  });
  // 질문: 첫 질문은 conversationId를 null로 보낸다(가짜 문자열이면 404). null은 서버가 새 대화로
  // 처리하므로 첫 질문에서 안전하고, 계약 검사도 필드 존재로 통과한다. 후속 질문에서만 서버가 준 값 재사용.
  setJsonBody(findReq(wikiFolder, "Wiki 또는 일정 질문"), {
    conversationId: null,
    question: "연차는 언제까지 신청해야 하나요?",
  });
  // 부서 생성/수정: 시드의 "개발부" 대신 매 실행 고유 이름 → 생성 성공 → departmentId 캡처
  setJsonField(findReq(deptFolder, "부서 생성"), {
    name: "QA임시부서_{{$timestamp}}",
    managerId: null,
  });
  setJsonField(findReq(deptFolder, "부서 수정"), {
    name: "QA임시부서_수정_{{$timestamp}}",
    managerId: null,
  });
  // 문서 카테고리 생성: 존재가 확실한 ALL 스코프 + 고유 이름
  setJsonField(findReq(catFolder, "문서 카테고리 생성"), {
    scopeKey: "ALL",
    name: "QA임시분류_{{$timestamp}}",
    description: "자동 테스트용 임시 분류",
  });
  // 문서 메타수정: 업로드한 ALL 스코프 문서에 맞춰 정합한 값으로
  setJsonField(findReq(docFolder, "Wiki 원본문서 메타데이터 수정"), {
    documentCategoryId: "{{allCategoryId}}",
    visibilityType: "all",
    departmentIds: [],
  });
  // 일정 수정: 사원이 자기 개인 일정을 유효하게 수정 + 캡처한 updatedAt으로 409 회피
  setJsonField(findReq(schFolder, "일정 수정"), {
    title: "수정된 개인 일정",
    content: "수정된 내용",
    targetText: "본인",
    location: "자택",
    visibilityType: "personal",
    departmentIds: [],
    startAt: "2026-08-03T02:00:00Z",
    endAt: "2026-08-03T04:00:00Z",
    expectedUpdatedAt: "{{scheduleUpdatedAt}}",
  });

  // ---- 4/7: 동적 ID 캡처 + 고정 ID 경로를 동적 변수로 ----
  cap(findReq(userFolder, "사용자 목록 조회"), [
    "const j = pm.response.json();",
    "const emp = (j.items || []).find(u => u.role === 'employee' && u.signupStatus === 'approved');",
    "if (emp) pm.environment.set('userId', String(emp.userId));",
  ]);
  cap(findReq(userFolder, "가입 신청 목록 조회"), [
    "const j = pm.response.json();",
    "const items = j.items || [];",
    "if (items[0]) pm.environment.set('signupApproveId', String(items[0].userId));",
    "if (items[1]) pm.environment.set('signupRejectId', String(items[1].userId));",
  ]);
  setPath(findReq(userFolder, "가입 신청 승인"), "/api/v1/signup-requests/:signupApproveId/approve");
  setPath(findReq(userFolder, "가입 신청 거부"), "/api/v1/signup-requests/:signupRejectId/reject");

  cap(findReq(deptFolder, "부서 생성"), [
    "const j = pm.response.json();",
    "if (j && (j.departmentId || j.id)) pm.environment.set('departmentId', String(j.departmentId || j.id));",
  ]);

  // 계약 예시의 scopeKey=D1-D2는 시드에 없는 스코프라 404가 난다. 존재가 확실한 ALL로 조회해
  // 실제 카테고리를 캡처(문서 업로드가 all 스코프이므로 ALL 카테고리가 필요).
  setUrl(findReq(catFolder, "문서 카테고리 목록 조회"), "/api/v1/document-categories", [
    { key: "scopeKey", value: "ALL" },
  ]);
  setUrl(findReq(wikiFolder, "Wiki 카테고리 목록 조회"), "/api/v1/wiki-categories", [
    { key: "scopeKey", value: "ALL" },
  ]);
  cap(findReq(catFolder, "문서 카테고리 목록 조회"), [
    "const j = pm.response.json();",
    "const c = (j.items || [])[0];",
    "if (c) pm.environment.set('allCategoryId', String(c.categoryId || c.id));",
  ]);
  cap(findReq(catFolder, "문서 카테고리 생성"), [
    "const j = pm.response.json();",
    "if (j && (j.categoryId || j.id)) pm.environment.set('categoryId', String(j.categoryId || j.id));",
  ]);

  const docUpload = findReq(docFolder, "Wiki 원본문서 업로드");
  setFormValue(docUpload, "documentCategoryId", "{{allCategoryId}}");
  setFormValue(docUpload, "visibilityType", "all");
  setFormValue(docUpload, "departmentIds", "");
  setFormFile(docUpload, "files", "testfiles/sample-wiki.md");
  cap(docUpload, [
    "const j = pm.response.json();",
    "if (j && j.jobId) pm.environment.set('jobId', String(j.jobId));",
    "const docId = j && ((j.documentIds && j.documentIds[0]) || j.documentId || j.id);",
    "if (docId) pm.environment.set('documentId', String(docId));",
  ]);
  setFormFile(findReq(docFolder, "Wiki 원본문서 파일 교체"), "file", "testfiles/sample-wiki.md");

  cap(findReq(wikiFolder, "Wiki 목록 조회"), [
    "const j = pm.response.json();",
    "const w = (j.items || [])[0];",
    "if (w) pm.environment.set('wikiId', String(w.wikiId || w.id));",
  ]);

  const schSource = findReq(schFolder, "일정 원본문서 업로드");
  setFormValue(schSource, "departmentIds", "1");
  setFormFile(schSource, "file", "testfiles/sample-schedule.csv");
  cap(findReq(schFolder, "수동 또는 개인 일정 생성"), [
    "const j = pm.response.json();",
    "if (j && (j.scheduleId || j.id)) pm.environment.set('scheduleId', String(j.scheduleId || j.id));",
    "if (j && j.updatedAt) pm.environment.set('scheduleUpdatedAt', String(j.updatedAt));",
  ]);
  cap(findReq(schFolder, "일정 목록 조회"), [
    "const j = pm.response.json();",
    "const d = (j.items || []).find(s => s.status === 'draft');",
    "if (d) pm.environment.set('draftScheduleId', String(d.scheduleId || d.id));",
  ]);
  // 생성 응답엔 updatedAt이 없으므로 상세 조회에서 캡처 → 일정 수정의 낙관적 락(expectedUpdatedAt)에 사용
  cap(findReq(schFolder, "일정 상세 조회"), [
    "const j = pm.response.json();",
    "if (j && j.updatedAt) pm.environment.set('scheduleUpdatedAt', String(j.updatedAt));",
  ]);
  setPath(findReq(schFolder, "일정 draft 승인"), "/api/v1/schedules/:draftScheduleId/approve");
  // 원본문서 조회는 관리자 API + AI 추출 일정(draft) 대상 → 최고관리자 세션·draft ID로 조회
  setPath(findReq(schFolder, "일정 원본문서 조회"), "/api/v1/schedules/:draftScheduleId/source-file");

  cap(findReq(inqFolder, "문의 담당자 후보 조회"), [
    "const j = pm.response.json();",
    "const items = j.items || [];",
    "const a = items.find(x => x.name === '김기획') || items[0];",
    "if (a) pm.environment.set('assigneeId', String(a.assigneeId || a.id));",
  ]);
  const inqCreate = findReq(inqFolder, "문의 등록");
  setFormValue(inqCreate, "assigneeId", "{{assigneeId}}");
  setFormFile(inqCreate, "attachments", "testfiles/sample-image.png");
  cap(inqCreate, [
    "const j = pm.response.json();",
    "if (j && (j.inquiryId || j.id)) pm.environment.set('inquiryId', String(j.inquiryId || j.id));",
  ]);
  cap(findReq(inqFolder, "문의 상세 조회"), [
    "const j = pm.response.json();",
    "const at = (j.attachments || [])[0];",
    "if (at) pm.environment.set('attachmentId', String(at.attachmentId || at.id));",
  ]);

  // ---- 6/7: 폴더 내부 실행 순서(파괴적 요청 뒤로) + 로그아웃은 컬렉션 맨 끝 ----
  reorder(authFolder, [
    "CSRF 토큰 발급",
    "회원가입용 부서 목록 조회",
    "회원가입",
    "로그인",
    "비밀번호 재설정 메일 요청",
    "비밀번호 재설정 인증번호 확인",
    "비밀번호 재설정",
    "로그아웃", // 인증 폴더 맨 끝
  ]);
  reorder(docFolder, [
    "Wiki 원본문서 업로드",
    "Wiki 원본문서 목록 조회",
    "Wiki 원본문서 상세 조회",
    "Wiki 원본문서 메타데이터 수정",
    "Wiki 원본문서 다운로드",
    "Wiki 원본문서 파일 교체",
    "AI 작업 상태 조회",
    "AI 작업 시작",
    "AI 작업 중단",
    "실패 문서 재처리",
    "Wiki 원본문서 삭제",
  ]);
  reorder(schFolder, [
    "일정 원본문서 업로드",
    "일정 목록 조회",
    "수동 또는 개인 일정 생성",
    "일정 상세 조회",
    "일정 수정",
    "일정 원본문서 조회",
    "일정 draft 승인",
    "일정 삭제 또는 draft 거부",
  ]);
  reorder(inqFolder, [
    "문의 담당자 후보 조회",
    "문의 등록",
    "문의 목록 조회",
    "문의 상세 조회",
    "문의 첨부 이미지 다운로드",
    "문의 답변 작성 또는 수정",
    "문의 답변 삭제",
    "문의 삭제",
  ]);
  // 인증(로그아웃 포함) 폴더를 컬렉션 맨 끝으로 → 로그아웃이 전체에서 마지막에 실행
  const authIdx = publicFolders.findIndex((f) => f.name === "인증");
  if (authIdx >= 0) publicFolders.push(publicFolders.splice(authIdx, 1)[0]);

  // ---- 3/7: 요청을 추가하지 않고, 컬렉션 레벨 pre-request로 역할 로그인 + CSRF ----
  //   요청 이름 → 필요한 역할. 매핑된 요청 실행 전에 해당 역할로 로그인하고 CSRF 토큰을 확보한다.
  //   같은 역할이 이어지면 재로그인하지 않는다(__sessionRole 가드).
  const roleByName = {
    // 인증(공개 요청은 매핑하지 않음). 로그아웃만 세션 필요.
    "로그아웃": "employee",
    // 사용자·가입승인: 최고관리자 전용
    "내 정보 조회": "super",
    "내 비밀번호 변경": "super",
    "사용자 목록 조회": "super",
    "사용자 단건 조회": "super",
    "사용자 정보 및 상태 수정": "super",
    "가입 신청 목록 조회": "super",
    "가입 신청 승인": "super",
    "가입 신청 거부": "super",
    // 부서·카테고리·문서: 관리 작업(최고관리자로 안정 실행)
    "부서 목록 조회": "super",
    "부서 생성": "super",
    "부서 수정": "super",
    "부서 삭제": "super",
    "문서 카테고리 목록 조회": "super",
    "문서 카테고리 생성": "super",
    "문서 카테고리 수정": "super",
    "문서 카테고리 삭제": "super",
    "Wiki 원본문서 업로드": "super",
    "Wiki 원본문서 목록 조회": "super",
    "Wiki 원본문서 상세 조회": "super",
    "Wiki 원본문서 메타데이터 수정": "super",
    "Wiki 원본문서 다운로드": "super",
    "Wiki 원본문서 파일 교체": "super",
    "AI 작업 상태 조회": "super",
    "AI 작업 시작": "super",
    "AI 작업 중단": "super",
    "실패 문서 재처리": "super",
    "Wiki 원본문서 삭제": "super",
    // Wiki 조회/관리자 대화: 관리자, 질문/이력: 사원
    "Wiki 공간 목록 조회": "super",
    "Wiki 카테고리 목록 조회": "super",
    "Wiki 목록 조회": "super",
    "Wiki 상세 조회": "super",
    "Wiki 파일 다운로드": "super",
    "Wiki 관리자 대화 조회": "super",
    "Wiki 수정 대화 전송": "super",
    "Wiki 또는 일정 질문": "employee",
    "내 질문 이력 조회": "employee",
    // 일정: 원본 업로드/승인은 관리자, 개인 일정 CRUD는 사원
    "일정 원본문서 업로드": "super",
    "일정 목록 조회": "employee",
    "수동 또는 개인 일정 생성": "employee",
    "일정 상세 조회": "employee",
    "일정 수정": "employee",
    "일정 원본문서 조회": "super",
    "일정 draft 승인": "super",
    "일정 삭제 또는 draft 거부": "employee",
    // 문의: 등록·조회는 사원, 답변은 부서관리자
    "문의 담당자 후보 조회": "employee",
    "문의 등록": "employee",
    "문의 목록 조회": "employee",
    "문의 상세 조회": "employee",
    "문의 첨부 이미지 다운로드": "employee",
    "문의 답변 작성 또는 수정": "deptAdmin",
    "문의 답변 삭제": "deptAdmin",
    "문의 삭제": "employee",
  };

  const preLines = [
    "const emailByRole = " + JSON.stringify(emailByRole) + ";",
    "const roleByName = " + JSON.stringify(roleByName) + ";",
    "const role = roleByName[pm.info.requestName];",
    "if (role) {",
    "  const base = pm.environment.get('backendBaseUrl') || pm.collectionVariables.get('backendBaseUrl') || 'http://localhost:8080';",
    "  const saveCsrf = function () {",
    "    pm.sendRequest({ url: base + '/api/v1/auth/csrf', method: 'GET' }, function (e, res) {",
    "      let token = '';",
    "      try { const sc = (res && res.headers && res.headers.get('Set-Cookie')) || ''; const m = String(sc).match(/XSRF-TOKEN=([^;]+)/); if (m) token = m[1]; } catch (x) {}",
    "      if (!token) { try { token = pm.cookies.get('XSRF-TOKEN') || ''; } catch (x) {} }",
    "      if (token) { pm.environment.set('csrfToken', token); pm.collectionVariables.set('csrfToken', token); }",
    "    });",
    "  };",
    "  if (pm.environment.get('__sessionRole') !== role) {",
    "    pm.sendRequest({ url: base + '/api/v1/auth/login', method: 'POST', header: { 'Content-Type': 'application/json' }, body: { mode: 'raw', raw: JSON.stringify({ email: emailByRole[role], password: '" + PW + "' }) } }, function (err, res) {",
    "      pm.environment.set('__sessionRole', role);",
    "      saveCsrf();",
    "    });",
    "  } else {",
    "    saveCsrf();",
    "  }",
    "}",
  ];
  publicCollectionEvent = [
    { listen: "prerequest", script: { type: "text/javascript", exec: preLines } },
  ];

  // ---- 기대 상태코드 검증 (실패를 숨기지 않도록 정밀 지정). AI 미가동은 503만 허용 ----
  const EXPECT = {
    // 인증
    "CSRF 토큰 발급": [200],
    "회원가입용 부서 목록 조회": [200],
    "회원가입": [202], // 동적 이메일 → 항상 신규 성공
    "로그인": [200],
    "비밀번호 재설정 메일 요청": [200], // 7/7: SMTP 미설정에도 200
    "비밀번호 재설정 인증번호 확인": [400],
    "비밀번호 재설정": [400],
    "로그아웃": [200, 204],
    // 사용자
    "내 정보 조회": [200],
    "내 비밀번호 변경": [400], // 현재 비번 불일치 검증
    "사용자 목록 조회": [200],
    "사용자 단건 조회": [200],
    "사용자 정보 및 상태 수정": [200],
    "가입 신청 목록 조회": [200],
    "가입 신청 승인": [200, 409],
    "가입 신청 거부": [200, 409],
    // 부서
    "부서 목록 조회": [200],
    "부서 생성": [200, 201],
    "부서 수정": [200],
    "부서 삭제": [200, 204],
    // 문서 카테고리
    "문서 카테고리 목록 조회": [200],
    "문서 카테고리 생성": [200, 201],
    "문서 카테고리 수정": [200],
    "문서 카테고리 삭제": [200, 204],
    // 문서 및 AI 작업 (업로드/시작 등은 비동기 202 접수 — 실제 변환 실패는 job 상태로 확인)
    "Wiki 원본문서 업로드": [201, 202],
    "Wiki 원본문서 목록 조회": [200],
    "Wiki 원본문서 상세 조회": [200],
    "Wiki 원본문서 메타데이터 수정": [200, 202],
    "Wiki 원본문서 다운로드": [200],
    "Wiki 원본문서 파일 교체": [200, 202],
    "AI 작업 상태 조회": [200],
    "AI 작업 시작": [202, 409],
    "AI 작업 중단": [200, 202, 409],
    "실패 문서 재처리": [200, 202, 409],
    "Wiki 원본문서 삭제": [200, 202, 204],
    // Wiki 및 질문 (AI 미가동 시 503만 정상 — 500이면 실패로 노출: 이슈 B/C)
    "Wiki 공간 목록 조회": [200],
    "Wiki 카테고리 목록 조회": [200],
    "Wiki 목록 조회": [200],
    "Wiki 상세 조회": [200],
    "Wiki 파일 다운로드": [200],
    "Wiki 관리자 대화 조회": [200],
    "Wiki 수정 대화 전송": [200, 201, 202, 503],
    "Wiki 또는 일정 질문": [200, 503],
    "내 질문 이력 조회": [200],
    // 일정 (원본 업로드는 AI 추출 동기 — 미가동 시 503만 허용)
    "일정 원본문서 업로드": [201, 503],
    "일정 목록 조회": [200],
    "수동 또는 개인 일정 생성": [200, 201],
    "일정 상세 조회": [200],
    "일정 수정": [200],
    "일정 원본문서 조회": [200, 404], // 수동 생성 일정엔 원본 없음(정상 404)
    "일정 draft 승인": [200, 409],
    "일정 삭제 또는 draft 거부": [200, 204],
    // 문의
    "문의 담당자 후보 조회": [200],
    "문의 등록": [200, 201],
    "문의 목록 조회": [200],
    "문의 상세 조회": [200],
    "문의 첨부 이미지 다운로드": [200],
    "문의 답변 작성 또는 수정": [200, 201],
    "문의 답변 삭제": [200, 204],
    "문의 삭제": [200, 204],
  };
  for (const f of publicFolders) {
    for (const it of f.item) {
      if (it.request && EXPECT[it.name]) expectStatus(it, EXPECT[it.name]);
    }
  }
})();

const publicCollection = {
  info: {
    name: "AJT Backend Public API",
    description:
      `Frontend → Spring Boot 공개 API입니다. 개발 계약 v${publicContractVersion}이며 P0 Request의 Saved Examples에서 성공·오류 응답을 확인합니다.`,
    schema: collectionSchema,
  },
  auth: cookieAuth,
  event: publicCollectionEvent,
  variable: [
    { key: "backendBaseUrl", value: "http://localhost:8080", type: "string" },
    { key: "contractVersion", value: publicContractVersion, type: "string" },
    { key: "userId", value: "1", type: "string" },
    { key: "departmentId", value: "1", type: "string" },
    { key: "categoryId", value: "1", type: "string" },
    { key: "documentId", value: "1", type: "string" },
    { key: "jobId", value: "1", type: "string" },
    { key: "wikiId", value: "1", type: "string" },
    { key: "scheduleId", value: "1", type: "string" },
    { key: "inquiryId", value: "1", type: "string" },
    { key: "attachmentId", value: "1", type: "string" },
    // S15P11B106-101: 실행 중 응답에서 채워지는 동적 ID (고정 ID 제거)
    { key: "csrfToken", value: "", type: "string" },
    { key: "allCategoryId", value: "1", type: "string" },
    { key: "signupApproveId", value: "9", type: "string" },
    { key: "signupRejectId", value: "10", type: "string" },
    { key: "draftScheduleId", value: "4", type: "string" },
    { key: "assigneeId", value: "3", type: "string" },
  ],
  item: publicFolders,
};

const internalCollection = {
  info: {
    name: "AJT FastAPI Internal API",
    description:
      `Spring Boot → FastAPI 내부 API입니다. 개발 계약 v${internalContractVersion}이며 Frontend는 직접 호출하지 않습니다.`,
    schema: collectionSchema,
  },
  auth: internalApiKeyAuth,
  variable: [
    { key: "aiBaseUrl", value: "http://localhost:8000", type: "string" },
    { key: "contractVersion", value: internalContractVersion, type: "string" },
    { key: "internalApiKey", value: "local-dev-key", type: "string" },
    { key: "jobId", value: "1", type: "string" },
    { key: "documentId", value: "1", type: "string" },
  ],
  item: internalFolders,
};

const environment = {
  id: "2cfe56bf-01fd-4b79-86c7-5ef3b1ca6150",
  name: "AJT Local",
  values: [
    {
      key: "backendBaseUrl",
      value: "http://localhost:8080",
      type: "default",
      enabled: true,
    },
    {
      key: "aiBaseUrl",
      value: "http://localhost:8000",
      type: "default",
      enabled: true,
    },
    {
      key: "csrfToken",
      value: "",
      type: "secret",
      enabled: true,
    },
    // S15P11B106-101: 실행 중 응답에서 채워지는 동적 ID
    { key: "userId", value: "1", type: "default", enabled: true },
    { key: "departmentId", value: "1", type: "default", enabled: true },
    { key: "categoryId", value: "1", type: "default", enabled: true },
    { key: "allCategoryId", value: "1", type: "default", enabled: true },
    { key: "documentId", value: "1", type: "default", enabled: true },
    { key: "jobId", value: "1", type: "default", enabled: true },
    { key: "wikiId", value: "1", type: "default", enabled: true },
    { key: "scheduleId", value: "1", type: "default", enabled: true },
    { key: "draftScheduleId", value: "4", type: "default", enabled: true },
    { key: "inquiryId", value: "1", type: "default", enabled: true },
    { key: "attachmentId", value: "1", type: "default", enabled: true },
    { key: "assigneeId", value: "3", type: "default", enabled: true },
    { key: "signupApproveId", value: "9", type: "default", enabled: true },
    { key: "signupRejectId", value: "10", type: "default", enabled: true },
    {
      // Wiki 조회 창구의 요청 단위 열람 허가. Spring Boot가 변환·수정 요청 시작에
      // 발급하므로 사람이 채우는 값이 아니다. Postman 에서 창구를 직접 호출해 볼 때만
      // 넣는다. 로그에 남기지 않는다.
      key: "wikiCapability",
      value: "",
      type: "secret",
      enabled: true,
    },
    {
      key: "internalApiKey",
      value: "local-dev-key",
      type: "secret",
      enabled: true,
    },
  ],
  _postman_variable_scope: "environment",
  _postman_exported_at: "2026-07-27T00:00:00.000Z",
  _postman_exported_using: "Codex",
};

mkdirSync(outputDir, { recursive: true });
writeFileSync(
  `${outputDir}/AJT-Backend-Public-API.postman_collection.json`,
  `${JSON.stringify(publicCollection, null, 2)}\n`,
);
writeFileSync(
  `${outputDir}/AJT-FastAPI-Internal-API.postman_collection.json`,
  `${JSON.stringify(internalCollection, null, 2)}\n`,
);
writeFileSync(
  `${outputDir}/AJT-Local.postman_environment.json`,
  `${JSON.stringify(environment, null, 2)}\n`,
);

console.log(
  JSON.stringify(
    {
      publicFolders: publicFolders.length,
      publicRequests: publicFolders.reduce(
        (count, current) => count + current.item.length,
        0,
      ),
      internalFolders: internalFolders.length,
      internalRequests: internalFolders.reduce(
        (count, current) => count + current.item.length,
        0,
      ),
    },
    null,
    2,
  ),
);
