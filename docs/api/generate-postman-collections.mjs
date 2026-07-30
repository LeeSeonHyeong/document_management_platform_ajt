import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import {
  buildSavedExamples,
  contractVersion,
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
        ],
        response: [
          "`Set-Cookie`: `AJT_ACCESS_TOKEN` JWT HttpOnly 쿠키 (`Secure`, `SameSite=Lax`, `Path=/`)",
          "`expiresIn`: 토큰 만료까지 남은 초",
          "`user`: 로그인 사용자 ID, 이름, 역할, 부서, 계정 상태",
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
          "사용자는 이 화면에서 정보를 직접 수정하거나 비밀번호를 변경할 수 없습니다.",
          "비밀번호 변경은 이메일 재설정 절차로만 수행합니다.",
        ],
        response: [
          "`userId`, `email`, `name`, `employeeNo`, `role`",
          "`department`: 소속 부서 ID와 이름",
          "`signupStatus`, `accountStatus`, `createdAt`, `updatedAt`",
        ],
        errors: ["`401 Unauthorized`: accessToken이 유효하지 않음"],
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
          "`keyword`: 이름 또는 이메일 검색어",
          "`sort`: 정렬필드와 방향",
        ],
        policy: ["관리자만 조회할 수 있습니다."],
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
        summary: "관리자가 사용자 이름, 역할, 소속 부서와 계정 상태를 수정합니다.",
        usage: "사용자 관리 상세 화면에서 사용합니다.",
        pathParams: ["`userId`: 수정할 사용자 ID"],
        requestBody: [
          "`name`: 변경할 이름",
          "`role`: `admin` 또는 `employee`",
          "`departmentId`: 변경할 부서 ID",
          "`accountStatus`: `active` 또는 `inactive`",
        ],
        policy: [
          "전달하지 않은 필드는 변경하지 않습니다.",
          "사용자는 삭제하지 않고 비활성화합니다.",
          "처리되지 않은 문의가 남은 담당자의 비활성화 또는 employee 전환은 허용하지 않습니다.",
          "사용자 비밀번호는 이 API에서 변경하지 않고 이메일 재설정으로만 변경합니다.",
        ],
        response: ["수정된 사용자 전체 정보"],
        errors: [
          "`400 Bad Request`: 필드값 또는 부서가 유효하지 않음",
          "`403 Forbidden`: 관리자 권한 없음",
          "`404 Not Found`: 존재하지 않는 사용자",
          "`409 Conflict`: 변경할 수 없는 사용자 상태",
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
          "관리자만 조회할 수 있습니다.",
          "거부된 신청도 삭제하지 않고 목록에 보존합니다.",
        ],
        response: [
          "`items`: 사용자 ID, 이메일, 이름, 소속 부서, 가입 상태와 신청 시각",
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
      query: [{ key: "scopeKey", value: "D1-D2" }],
      description: docs({
        summary: "Wiki 공간에 속한 원본문서 카테고리 목록을 조회합니다.",
        usage: "원본문서 업로드와 카테고리 관리 화면에서 사용합니다.",
        queryParams: ["`scopeKey`: 카테고리를 조회할 Wiki 공간 키"],
        response: ["`items`: 카테고리 ID, 이름, 설명, scopeKey"],
        errors: [
          "`400 Bad Request`: scopeKey 형식 오류",
          "`404 Not Found`: 존재하지 않거나 접근할 수 없는 Wiki 공간",
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
          description: "관리자가 지정한 문서 카테고리 ID",
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
        summary: "Wiki 생성에 사용할 원본문서를 업로드합니다.",
        usage: "관리자 Wiki 원본문서 업로드 화면에서 사용합니다.",
        requestBody: [
          "`files`: TXT, MD, PDF, DOCX 파일. 최대 20건, 파일당 20MB, 총 100MB",
          "`documentCategoryId`: 관리자가 선택한 카테고리 ID",
          "`visibilityType`: `all` 또는 `department`",
          "`departmentIds`: 부서 공개일 때 선택한 부서 ID 목록",
        ],
        policy: [
          "부서 ID는 중복 제거 후 정렬해 `D1-D2` 형태의 scopeKey를 만듭니다.",
          "같은 업로드 묶음은 AI 작업 하나로 만들고 문서별로 직렬 처리합니다.",
        ],
        response: [
          "`202 Accepted`",
          "`jobId`: AI 작업 ID",
          "`documentIds`: 생성된 문서 ID 목록",
          "`scopeKey`, `status`, `createdAt`",
        ],
        errors: [
          "`400 Bad Request`: 파일 형식, 개별 20MB, 최대 20개, 총 100MB, 카테고리 또는 부서 오류",
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
      ],
      description: docs({
        summary: "사용자가 접근 가능한 Wiki 원본문서 목록을 조회합니다.",
        usage: "문서 목록과 관리자 문서 처리 현황 화면에서 사용합니다.",
        queryParams: [
          "`page`, `size`: 페이지네이션",
          "`scopeKey`: Wiki 공간 필터",
          "`categoryId`: 문서 카테고리 필터",
          "`status`: 문서 처리 상태 필터",
          "`keyword`: 파일명 또는 문서 검색어",
          "`fileType`: txt, md, pdf 또는 docx",
          "`departmentId`: 해당 부서를 포함하는 scopeKey 필터",
          "`uploadedFrom`, `uploadedTo`: 업로드 기간",
        ],
        policy: ["권한이 없는 문서는 목록에 포함하지 않습니다."],
        response: [
          "`items`: 문서 ID, 파일명, scopeKey, 카테고리, 상태, 업로드자와 시각",
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
          "문서 메타데이터, 카테고리, 공개 범위와 처리 상태",
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
        ],
        response: [
          "`202 Accepted`와 새 scope 재처리 `jobId`",
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
          "삭제 후 해당 scopeKey의 현재 문서를 기준으로 Wiki를 재처리합니다.",
          "삭제 복구와 과거 버전 조회는 제공하지 않습니다.",
        ],
        response: ["`202 Accepted`와 Wiki 재처리 `jobId`"],
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
      name: "AI 작업 상태 조회",
      method: "GET",
      path: "/api/v1/ai-jobs/:jobId",
      description: docs({
        summary: "비동기 AI 작업의 전체 상태와 문서별 처리 결과를 조회합니다.",
        usage: "문서 업로드 진행 화면과 관리자 작업 상태 화면에서 사용합니다.",
        pathParams: ["`jobId`: 조회할 AI 작업 ID"],
        response: [
          "`status`: `waiting`, `processing`, `completed`, `failed`, `cancelled`",
          "`documentResults`: 문서별 순서, 상태, 현재 단계, 요약과 실패 사유",
          "`createdAt`, `startedAt`, `finishedAt`, `failureReason`",
        ],
        errors: [
          "`401 Unauthorized`: accessToken이 유효하지 않음",
          "`404 Not Found`: 존재하지 않거나 조회할 수 없는 작업",
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
          "`relatedWikis`: 연관 Wiki 목록",
        ],
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
        policy: ["관리자만 조회할 수 있으며 메시지는 Wiki ID에 연결됩니다."],
        response: [
          "`items`: messageId, senderType, content, createdAt와 작업 상태",
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
          "AI는 해당 Wiki, 연결 문서와 기존 검수 대화만 사용합니다.",
          "같은 scopeKey에서 문서 변환 중이면 409 Conflict를 반환합니다.",
          "백엔드가 링크와 관계를 검증한 변경 결과는 별도 승인 없이 현재 Wiki에 반영합니다.",
        ],
        response: [
          "`200 OK`",
          "`adminMessage`, `agentMessage`, `updatedWiki`",
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
        usage: "달력과 관리자 일정 검수 화면에서 사용합니다.",
        queryParams: [
          "`startDate`, `endDate`: 조회 기간, 최대 1년",
          "`status`: 관리자의 `draft` 또는 `approved` 필터",
          "`visibilityType`: `all`, `department`, `personal`",
          "`departmentId`: 부서 일정 필터",
        ],
        policy: [
          "사원에게는 승인된 전체·소속 부서 일정과 본인 개인 일정만 반환합니다.",
          "관리자는 draft 일정도 조회할 수 있습니다.",
        ],
        response: ["`items`: 일정 ID, 제목, 기간, 공개 범위, 상태와 위치"],
        errors: [
          "`400 Bad Request`: 날짜 범위 또는 필터값 오류",
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
      }),
      description: docs({
        summary: "일정 정보를 수정합니다.",
        usage: "일정 수정과 draft 검수 화면에서 사용합니다.",
        pathParams: ["`scheduleId`: 수정할 일정 ID"],
        requestBody: [
          "`title`, `content`, `targetText`, `location`",
          "`visibilityType`, `departmentIds`, `startAt`, `endAt`",
        ],
        policy: [
          "전달하지 않은 필드는 유지합니다.",
          "사원은 본인 개인 일정만 수정할 수 있습니다.",
        ],
        response: ["수정된 일정 전체 정보"],
        errors: [
          "`400 Bad Request`: 날짜 또는 공개 범위 오류",
          "`403 Forbidden`: 수정 권한 없음",
          "`404 Not Found`: 존재하지 않는 일정",
          "`409 Conflict`: 수정할 수 없는 상태",
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
        ],
        policy: [
          "사원은 본인 문의만 조회합니다.",
          "관리자는 본인이 담당자로 지정된 문의만 조회합니다.",
        ],
        response: [
          "`items`: 문의 ID, 제목, 작성자, 담당자 이름·부서, 우선순위, 상태와 생성 시각",
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
          "문의 제목, 내용, 작성자, 담당자 이름·부서와 상태",
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
        ],
        response: ["답변 ID, 내용, 관리자 ID와 답변 시각"],
        errors: [
          "`400 Bad Request`: 답변 내용이 비어 있음",
          "`403 Forbidden`: 지정된 담당자가 아님",
          "`404 Not Found`: 존재하지 않는 문의",
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
      name: "Wiki 변환 문맥 선택",
      method: "POST",
      path: "/internal/v1/wiki-context-selections",
      baseVariable: "aiBaseUrl",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        jobId: "42",
        documentId: "15",
        scopeKey: "D1-D2",
        changeType: "document_replaced",
        parsedMarkdown: "# 취업 규칙\n본문...",
        removedParsedMarkdown: "# 기존 취업 규칙\n이전 본문...",
        currentIndex: "# 목차\n- [휴가 규정](pages/101.md)",
      }),
      description: docs({
        summary: "새 원본문서와 현재 목차를 분석해 변환에 필요한 Wiki만 선택합니다.",
        usage: "Spring Boot가 전역 직렬 Wiki 변환에서 본문 조회 전에 먼저 호출합니다.",
        auth: "`X-Internal-API-Key` 필요",
        requestBody: [
          "`jobId`, `documentId`, `scopeKey`",
          "`changeType`: `document_added`, `document_removed` 또는 `document_replaced`",
          "`parsedMarkdown`: added·replaced의 새 문서 파싱 결과",
          "`removedParsedMarkdown`: removed·replaced의 제거 또는 교체 전 문서 파싱 결과",
          "`currentIndex`: 요청 scopeKey의 현재 index.md 내용",
        ],
        policy: [
          "FastAPI는 실제 Wiki 본문·파일 경로·DB 정보에 접근하지 않습니다.",
          "removed·replaced는 Spring Boot가 원본·파싱 파일을 삭제 또는 교체하기 전에 호출합니다.",
          "wikiIds는 currentIndex의 링크에서 식별하며, 중복 없이 관련도 순서로 최대 5개를 반환합니다.",
          "Spring Boot는 응답 ID의 존재와 scopeKey를 재검증한 뒤 선택 본문만 Wiki 변환 API에 전달합니다.",
        ],
        response: [
          "`wikiIds`: 변환 문맥으로 필요한 Wiki 문자열 ID 배열, 최대 5개",
          "`reason`: 선택 근거 요약",
        ],
        errors: [
          "`400 Bad Request`: 문서 Markdown 또는 목차 형식 오류",
          "`401 Unauthorized`: 내부 API 키 오류",
          "`500 Internal Server Error`: `WIKI_CONTEXT_SELECTION_FAILED`; 오류 본문의 `failureStage`는 실패 단계를 제공합니다.",
        ],
      }),
    }),
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
        currentIndex: "# 목차\n- [휴가 규정](pages/101.md)",
        currentCategories: [{ categoryId: "10", name: "인사·복무" }],
        selectedWikis: [
          {
            wikiId: "101",
            categoryId: "10",
            title: "휴가 규정",
            summary: "연차와 반차 사용 기준",
            contentMarkdown: "# 휴가 규정\n...",
            documentRefs: ["15", "18"],
            wikiRefs: ["108"],
          },
        ],
      }),
      description: docs({
        summary: "새 문서와 문맥 선택된 Wiki만 분석해 변경 결과를 생성합니다.",
        usage: "Spring Boot의 전역 직렬 Wiki 변환 작업에서 호출합니다.",
        auth: "`X-Internal-API-Key` 필요",
        requestBody: [
          "`jobId`, `documentId`, `scopeKey`",
          "`changeType`: `document_added`, `document_removed` 또는 `document_replaced`",
          "`parsedMarkdown`: added·replaced의 새 문서 파싱 결과; removed에서는 생략",
          "`removedParsedMarkdown`: removed·replaced의 제거 또는 교체 전 문서 파싱 결과",
          "`currentIndex`: 현재 Wiki 목차",
          "`currentCategories`: 같은 공간의 현재 카테고리 ID와 이름",
          "`selectedWikis`: 선택 API 후 Spring Boot가 재검증해 읽은 Wiki ID, 본문, 관계 JSON",
          "`selectedWikis[].summary`: `wiki.summary`(DR-029 개정분)에서 채웁니다. 값이 없으면 생략할 수 있습니다.",
        ],
        policy: [
          "다른 scopeKey의 문서와 Wiki는 사용하지 않습니다.",
          "FastAPI는 selectedWikis 외의 실제 Wiki 본문·파일 경로·DB 정보에 접근하지 않습니다.",
          "`currentCategories`는 해당 scopeKey의 전체 카테고리를 전달합니다.",
          "Wiki 및 Wiki 카테고리 생성·수정·병합·제거 결과를 반환할 수 있습니다.",
          "새 Wiki와 카테고리는 temp 참조값을 사용하고 Spring Boot가 실제 ID를 발급합니다.",
          "FastAPI는 구조화된 변경 결과만 반환하고 Spring Boot가 링크·관계를 검증 후 반영합니다.",
        ],
        response: [
          "`summary`: 문서별 작업 요약",
          "`categoryChanges`, `wikiChanges`, `relationChanges`",
          "`wikiChanges[].wikiCategoryRef`: 그 Wiki가 속할 카테고리. 같은 응답의 `tempCategoryId` 또는 기존 `wikiCategoryId`",
          "`wikiChanges[].wikiPath`: `action`이 `create`일 때만. 에이전트가 발급한 신규 페이지 경로 (DR-016)",
          "`wikiChanges[].evidence`(선택): 문서 ID, 각주, 위치와 인용 근거",
          "`indexEntries`: AI가 정한 목차 구조·순서·제목·요약",
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
        currentWiki: {
          title: "휴가 규정",
          contentMarkdown: "# 휴가 규정\n...",
        },
        evidenceDocuments: [],
        chatHistory: [],
      }),
      description: docs({
        summary: "관리자의 자연어 지시를 바탕으로 Wiki 수정 결과를 생성합니다.",
        usage: "Spring Boot가 Wiki 상세 관리자 대화를 처리할 때 호출합니다.",
        auth: "`X-Internal-API-Key` 필요",
        requestBody: [
          "`wikiId`, `scopeKey`, `instruction`",
          "`currentWiki`: 현재 Wiki 본문",
          "`evidenceDocuments`: 연결 원본문서",
          "`chatHistory`: 해당 Wiki 관리자 대화",
        ],
        policy: [
          "관련 없는 로그와 다른 scopeKey 자료는 전달하지 않습니다.",
          "원본문서에서 근거를 찾을 수 없는 변경은 경고하거나 생성하지 않습니다.",
        ],
        response: [
          "`agentMessage`: 관리자에게 보여줄 응답",
          "`wikiChanges`, `categoryChanges`, `relationChanges`, `indexEntries`",
          "`wikiChanges[].wikiCategoryRef`·`wikiPath`는 Wiki 변환과 같은 규칙을 따릅니다.",
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
  folder("답변 생성", "질문 자동 분류·자료 선택과 선택 본문 기반 답변 생성 API", [
    request({
      name: "답변 자료 선택",
      method: "POST",
      path: "/internal/v1/answer-context-selections",
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
          },
        ],
        scheduleSummaries: [
          {
            scheduleId: "31",
            title: "8월 휴가 일정",
            startAt: "2026-08-03T01:00:00Z",
            endAt: "2026-08-03T03:00:00Z",
            targetText: "개발부",
            location: "본사",
          },
        ],
      }),
      description: docs({
        summary: "질문을 Wiki·일정·혼합으로 자동 분류하고 필요한 자료 ID를 선택합니다.",
        usage: "Spring Boot가 최종 답변용 본문을 읽기 전에 1차로 호출합니다.",
        auth: "`X-Internal-API-Key` 필요",
        requestBody: [
          "`questionId`, `conversationId`, `question`",
          "`conversationMessages`: 같은 사용자 대화의 이전 질문·답변",
          "`wikiIndexes`: 권한 있는 공간별 scopeKey와 index.md 내용",
          "`scheduleSummaries`: 권한 있는 일정의 ID, 제목, 기간, 대상과 장소",
        ],
        policy: [
          "questionType은 wiki, schedule 또는 mixed입니다.",
          "이전 대화 문맥을 포함해 현재 질문의 종류와 필요한 자료를 판단합니다.",
          "Wiki와 일정은 각각 최대 5개를 관련도 순서로 선택합니다.",
          "본문과 원본문서는 이 단계에 전달하지 않습니다.",
        ],
        response: [
          "`questionType`",
          "`wikiIds`, `scheduleIds`: 관련도 순서의 선택 ID 배열",
          "`reason`: 선택 근거 요약",
        ],
        errors: [
          "`400 Bad Request`: 질문 또는 목차·일정 요약 형식 오류",
          "`401 Unauthorized`: 내부 API 키 오류",
          "`500 Internal Server Error`: `ANSWER_CONTEXT_SELECTION_FAILED`",
        ],
      }),
    }),
    request({
      name: "답변 생성",
      method: "POST",
      path: "/internal/v1/answers",
      baseVariable: "aiBaseUrl",
      headers: [{ key: "Content-Type", value: "application/json" }],
      body: rawJson({
        questionId: "500",
        conversationId: "chat-123",
        questionType: "mixed",
        question: "연차 규정과 다음 휴가 일정을 알려줘.",
        conversationMessages: [
          { role: "user", content: "연차 신청 방법을 알려줘." },
          { role: "assistant", content: "연차 신청 절차는 다음과 같습니다." },
        ],
        selectedWikis: [
          {
            wikiId: "101",
            title: "휴가 규정",
            contentMarkdown: "# 휴가 규정\n...",
          },
        ],
        selectedSchedules: [
          {
            scheduleId: "31",
            title: "8월 휴가 일정",
            content: "개발부 휴가 일정",
            startAt: "2026-08-03T01:00:00Z",
            endAt: "2026-08-03T03:00:00Z",
            targetText: "개발부",
            location: "본사",
          },
        ],
      }),
      description: docs({
        summary: "Spring Boot가 권한 검증한 Wiki 또는 일정으로 답변을 생성합니다.",
        usage: "Wiki·일정 챗봇 질문 처리에서 내부 호출합니다.",
        auth: "`X-Internal-API-Key` 필요",
        requestBody: [
          "`questionId`, `conversationId`, `questionType`, `question`",
          "`conversationMessages`: 같은 사용자 대화의 이전 질문·답변",
          "`selectedWikis`: 백엔드가 재검증하고 파일에서 읽은 Wiki 본문",
          "`selectedWikis[].summary`: `wiki.summary`에서 채웁니다. 값이 없으면 생략할 수 있습니다.",
          "`selectedSchedules`: 백엔드가 재검증한 일정 내용",
        ],
        policy: [
          "현재 질문은 같은 conversationId의 이전 질문·답변 문맥과 함께 처리합니다.",
          "wiki는 selectedWikis, schedule은 selectedSchedules, mixed는 두 배열을 사용합니다.",
          "원본문서는 전달하지 않으며 Spring Boot가 최종 Wiki 출처에 하위 근거로 붙입니다.",
          "출처는 두 개 이상 반환할 수 있습니다.",
        ],
        response: [
          "`answer`: 생성된 답변",
          "`sources`: 실제 사용한 wikiId 또는 scheduleId와 제목 배열",
        ],
        errors: [
          "`400 Bad Request`: 질문 또는 컨텍스트 오류",
          "`401 Unauthorized`: 내부 API 키 오류",
          "`500 Internal Server Error`: `ANSWER_GENERATION_FAILED`",
        ],
      }),
    }),
  ]),
];

const publicCollection = {
  info: {
    name: "AJT Backend Public API",
    description:
      `Frontend → Spring Boot 공개 API입니다. 개발 계약 v${contractVersion}이며 P0 Request의 Saved Examples에서 성공·오류 응답을 확인합니다.`,
    schema: collectionSchema,
  },
  auth: cookieAuth,
  variable: [
    { key: "backendBaseUrl", value: "http://localhost:8080", type: "string" },
    { key: "contractVersion", value: contractVersion, type: "string" },
    { key: "userId", value: "1", type: "string" },
    { key: "departmentId", value: "1", type: "string" },
    { key: "categoryId", value: "1", type: "string" },
    { key: "documentId", value: "1", type: "string" },
    { key: "jobId", value: "1", type: "string" },
    { key: "wikiId", value: "1", type: "string" },
    { key: "conversationId", value: "chat-123", type: "string" },
    { key: "scheduleId", value: "1", type: "string" },
    { key: "inquiryId", value: "1", type: "string" },
    { key: "attachmentId", value: "1", type: "string" },
  ],
  item: publicFolders,
};

const internalCollection = {
  info: {
    name: "AJT FastAPI Internal API",
    description:
      `Spring Boot → FastAPI 내부 API입니다. 개발 계약 v${contractVersion}이며 Frontend는 직접 호출하지 않습니다.`,
    schema: collectionSchema,
  },
  auth: internalApiKeyAuth,
  variable: [
    { key: "aiBaseUrl", value: "http://localhost:8000", type: "string" },
    { key: "contractVersion", value: contractVersion, type: "string" },
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
