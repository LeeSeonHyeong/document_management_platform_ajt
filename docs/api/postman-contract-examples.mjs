export const contractVersion = "1.6.4";

const timestamp = "2026-07-27T09:00:00Z";
const requestId = "01KABCDEF123456789";

const page = (items) => ({
  items,
  page: 1,
  size: 20,
  totalCount: items.length,
  totalPages: items.length ? 1 : 0,
});

const department = {
  departmentId: "1",
  name: "개발부",
};

const user = {
  userId: "1",
  email: "employee@ajt.com",
  name: "홍길동",
  employeeNo: "AJT-2026-0001",
  role: "employee",
  department,
  signupStatus: "approved",
  accountStatus: "active",
};

const schedule = {
  scheduleId: "31",
  title: "프로젝트 회의",
  content: "주간 진행 상황 공유",
  targetText: "개발부",
  location: "3층 회의실",
  visibilityType: "department",
  departmentIds: ["1"],
  startAt: "2026-08-03T01:00:00Z",
  endAt: "2026-08-03T03:00:00Z",
  status: "approved",
};

const inquiryAssignee = {
  assigneeId: "7",
  name: "김관리",
  department: {
    departmentId: "2",
    name: "인사부",
  },
};

const inquiry = {
  inquiryId: "55",
  title: "연차 문의",
  content: "연차 사용 기준이 궁금합니다.",
  priority: "normal",
  status: "pending",
  author: {
    userId: "1",
    name: "홍길동",
  },
  assignee: inquiryAssignee,
  attachments: [],
  answer: null,
  createdAt: timestamp,
};

const contracts = {
  "POST /api/v1/auth/login": {
    success: {
      httpStatus: 200,
      body: {
        expiresIn: 3600,
        user: { ...user, isSuperAdmin: false },
      },
      headers: [
        {
          key: "Set-Cookie",
          value: "AJT_ACCESS_TOKEN=eyJhbGciOiJIUzI1NiJ9.example; Path=/; HttpOnly; Secure; SameSite=Lax",
        },
      ],
    },
    error: {
      httpStatus: 401,
      errorCode: "INVALID_CREDENTIALS",
      message: "이메일 또는 비밀번호가 올바르지 않습니다.",
    },
  },
  "GET /api/v1/auth/csrf": {
    success: {
      httpStatus: 200,
      body: { message: "CSRF 토큰을 발급했습니다." },
      headers: [
        {
          key: "Set-Cookie",
          value: "XSRF-TOKEN=csrf-token-example; Path=/; Secure; SameSite=Lax",
        },
      ],
    },
    error: {
      httpStatus: 500,
      errorCode: "CSRF_TOKEN_ISSUE_FAILED",
      message: "CSRF 토큰을 발급하지 못했습니다.",
    },
  },
  "POST /api/v1/auth/logout": {
    success: {
      httpStatus: 200,
      body: { message: "로그아웃했습니다." },
      headers: [
        {
          key: "Set-Cookie",
          value: "AJT_ACCESS_TOKEN=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0",
        },
      ],
    },
    error: {
      httpStatus: 401,
      errorCode: "UNAUTHORIZED",
      message: "인증이 필요합니다.",
    },
  },
  "POST /api/v1/auth/signup": {
    success: {
      httpStatus: 202,
      body: {
        userId: "10",
        email: "employee@ajt.com",
        name: "홍길동",
        role: "employee",
        departmentId: "1",
        employeeNo: null,
        signupStatus: "pending",
        accountStatus: "inactive",
      },
    },
    error: {
      httpStatus: 409,
      errorCode: "SIGNUP_ALREADY_PENDING",
      message: "이미 가입 승인 대기 중인 이메일입니다.",
    },
  },
  "GET /api/v1/signup-departments": {
    success: {
      httpStatus: 200,
      body: {
        items: [
          department,
          {
            departmentId: "2",
            name: "인사부",
          },
        ],
      },
    },
    error: {
      httpStatus: 500,
      errorCode: "INTERNAL_SERVER_ERROR",
      message: "부서 목록을 불러오지 못했습니다.",
    },
  },
  "GET /api/v1/me": {
    success: {
      httpStatus: 200,
      body: {
        ...user,
        isSuperAdmin: false,
        createdAt: "2026-07-01T02:00:00Z",
        updatedAt: "2026-07-27T09:00:00Z",
      },
    },
    error: {
      httpStatus: 401,
      errorCode: "INVALID_ACCESS_TOKEN",
      message: "로그인이 필요합니다.",
    },
  },
  "GET /api/v1/users": {
    success: {
      httpStatus: 200,
      body: page([
        {
          ...user,
          isDepartmentManager: false,
          createdAt: "2026-07-01T02:00:00Z",
        },
      ]),
    },
    error: {
      httpStatus: 403,
      errorCode: "ADMIN_PERMISSION_REQUIRED",
      message: "관리자 권한이 필요합니다.",
    },
  },
  "POST /api/v1/signup-requests/:userId/approve": {
    success: {
      httpStatus: 200,
      body: {
        userId: "10",
        employeeNo: "AJT-2026-0010",
        signupStatus: "approved",
        accountStatus: "active",
        approvedAt: timestamp,
      },
    },
    error: {
      httpStatus: 409,
      errorCode: "INVALID_SIGNUP_STATUS",
      message: "승인 대기 상태의 신청만 승인할 수 있습니다.",
    },
  },
  "GET /api/v1/departments": {
    success: {
      httpStatus: 200,
      body: {
        items: [
          {
            ...department,
            manager: {
              userId: "7",
              name: "김관리",
            },
          },
          {
            departmentId: "2",
            name: "인사부",
            manager: null,
          },
        ],
      },
    },
    error: {
      httpStatus: 401,
      errorCode: "INVALID_ACCESS_TOKEN",
      message: "로그인이 필요합니다.",
    },
  },
  "POST /api/v1/documents": {
    success: {
      httpStatus: 202,
      body: {
        jobId: "42",
        documentIds: ["15", "16"],
        scopeKey: "D1-D2",
        status: "waiting",
        createdAt: timestamp,
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_DOCUMENT_UPLOAD",
      message: "파일 형식, 개수 또는 용량 제한을 확인해주세요.",
      fieldErrors: [
        {
          field: "files",
          reason: "파일당 최대 크기는 20MB입니다.",
        },
      ],
    },
  },
  "GET /api/v1/ai-jobs/:jobId": {
    success: {
      httpStatus: 200,
      body: {
        jobId: "42",
        status: "processing",
        documentResults: [
          {
            documentId: "15",
            order: 1,
            status: "completed",
            currentStage: "wiki_applied",
            summary: "휴가 규정을 Wiki에 반영했습니다.",
            failureReason: null,
          },
          {
            documentId: "16",
            order: 2,
            status: "processing",
            currentStage: "parsing",
            summary: null,
            failureReason: null,
          },
        ],
        createdAt: timestamp,
        startedAt: "2026-07-27T09:00:02Z",
        finishedAt: null,
        failureReason: null,
      },
    },
    error: {
      httpStatus: 404,
      errorCode: "AI_JOB_NOT_FOUND",
      message: "AI 작업을 찾을 수 없습니다.",
    },
  },
  "GET /api/v1/wiki-spaces": {
    success: {
      httpStatus: 200,
      body: {
        items: [
          {
            scopeKey: "D1-D2",
            visibilityType: "department",
            departments: [
              department,
              { departmentId: "2", name: "인사부" },
            ],
            displayName: "개발부 + 인사부",
            wikiCount: 5,
          },
        ],
      },
    },
    error: {
      httpStatus: 401,
      errorCode: "INVALID_ACCESS_TOKEN",
      message: "로그인이 필요합니다.",
    },
  },
  "GET /api/v1/wiki-categories": {
    success: {
      httpStatus: 200,
      body: {
        items: [
          {
            wikiCategoryId: "9",
            scopeKey: "D1-D2",
            name: "휴가 및 근태",
            description: "AI가 분류한 휴가·근태 규정",
          },
        ],
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_SCOPE_KEY",
      message: "scopeKey 형식이 올바르지 않습니다.",
    },
  },
  "GET /api/v1/wikis": {
    success: {
      httpStatus: 200,
      body: page([
        {
          wikiId: "101",
          title: "휴가 규정",
          summary: "연차와 반차 사용 기준",
          wikiCategoryId: "9",
          wikiCategoryName: "휴가 및 근태",
          scopeKey: "D1-D2",
          updatedAt: timestamp,
        },
      ]),
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_WIKI_FILTER",
      message: "Wiki 조회 조건이 올바르지 않습니다.",
    },
  },
  "GET /api/v1/wikis/:wikiId": {
    success: {
      httpStatus: 200,
      body: {
        wikiId: "101",
        title: "휴가 규정",
        contentMarkdown: "# 휴가 규정\n연차와 반차를 사용할 수 있습니다.",
        category: {
          wikiCategoryId: "9",
          name: "휴가 및 근태",
        },
        scopeKey: "D1-D2",
        evidenceDocuments: [
          {
            documentId: "15",
            originalFileName: "취업규칙.pdf",
            downloadUrl: "/api/v1/documents/15/file",
          },
        ],
        relatedWikis: [],
        updatedAt: timestamp,
      },
    },
    error: {
      httpStatus: 404,
      errorCode: "WIKI_NOT_FOUND",
      message: "Wiki가 없거나 접근할 수 없습니다.",
    },
  },
  "POST /api/v1/questions": {
    success: {
      httpStatus: 200,
      body: {
        conversationId: "chat-123",
        questionId: "500",
        questionType: "mixed",
        answer: "연차 규정과 다음 휴가 일정은 다음과 같습니다.",
        sources: [
          {
            type: "wiki",
            wikiId: "101",
            title: "휴가 규정",
            evidenceDocuments: [
              {
                documentId: "15",
                originalFileName: "취업규칙.pdf",
                downloadUrl: "/api/v1/documents/15/file",
              },
            ],
          },
          {
            type: "schedule",
            scheduleId: "31",
            title: "8월 휴가 일정",
          },
        ],
        createdAt: timestamp,
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_QUESTION",
      message: "질문 내용을 입력해주세요.",
      fieldErrors: [
        {
          field: "question",
          reason: "질문은 비어 있을 수 없습니다.",
        },
      ],
    },
  },
  "GET /api/v1/questions": {
    success: {
      httpStatus: 200,
      body: page([
        {
          conversationId: "chat-123",
          questionId: "500",
          questionType: "mixed",
          question: "연차 규정과 다음 휴가 일정을 알려줘.",
          answer: "연차 규정과 다음 휴가 일정은 다음과 같습니다.",
          sources: [],
          createdAt: timestamp,
        },
      ]),
    },
    error: {
      httpStatus: 401,
      errorCode: "INVALID_ACCESS_TOKEN",
      message: "로그인이 필요합니다.",
    },
  },
  "POST /api/v1/schedule-sources": {
    success: {
      httpStatus: 201,
      body: {
        sourceGroupKey: "schedule-source-20260727-01",
        status: "extracted",
        draftSchedules: [
          {
            ...schedule,
            status: "draft",
          },
        ],
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_SCHEDULE_SOURCE",
      message: "일정 원본문서 형식 또는 공개 범위가 올바르지 않습니다.",
    },
  },
  "GET /api/v1/schedules": {
    success: {
      httpStatus: 200,
      body: {
        items: [schedule],
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_SCHEDULE_RANGE",
      message: "일정 조회 기간이 올바르지 않습니다.",
    },
  },
  "POST /api/v1/schedules": {
    success: {
      httpStatus: 201,
      body: {
        ...schedule,
        visibilityType: "personal",
        departmentIds: [],
        ownerId: "1",
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_SCHEDULE",
      message: "일정 입력값을 확인해주세요.",
    },
  },
  "GET /api/v1/schedules/:scheduleId": {
    success: [
      {
        name: "200 OK - 사원 응답",
        httpStatus: 200,
        body: schedule,
      },
      {
        name: "200 OK - 관리자 응답",
        httpStatus: 200,
        body: {
          ...schedule,
          sourceDocument: {
            originalFileName: "8월일정.xlsx",
            sourceFileUrl: "/api/v1/schedules/31/source-file",
          },
        },
      },
    ],
    error: {
      httpStatus: 404,
      errorCode: "SCHEDULE_NOT_FOUND",
      message: "일정이 없거나 접근할 수 없습니다.",
    },
  },
  "GET /api/v1/inquiry-assignees": {
    success: {
      httpStatus: 200,
      body: {
        items: [inquiryAssignee],
      },
    },
    error: {
      httpStatus: 401,
      errorCode: "INVALID_ACCESS_TOKEN",
      message: "로그인이 필요합니다.",
    },
  },
  "POST /api/v1/inquiries": {
    success: {
      httpStatus: 201,
      body: inquiry,
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_INQUIRY",
      message: "문의 입력값 또는 이미지 파일을 확인해주세요.",
    },
  },
  "GET /api/v1/inquiries": {
    success: {
      httpStatus: 200,
      body: page([
        {
          inquiryId: inquiry.inquiryId,
          title: inquiry.title,
          author: inquiry.author,
          assignee: inquiry.assignee,
          priority: inquiry.priority,
          status: inquiry.status,
          createdAt: inquiry.createdAt,
        },
      ]),
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_INQUIRY_FILTER",
      message: "문의 조회 조건이 올바르지 않습니다.",
    },
  },
  "GET /api/v1/inquiries/:inquiryId": {
    success: {
      httpStatus: 200,
      body: inquiry,
    },
    error: {
      httpStatus: 404,
      errorCode: "INQUIRY_NOT_FOUND",
      message: "문의가 없거나 조회할 수 없습니다.",
    },
  },
  "POST /internal/v1/source-parses": {
    success: {
      httpStatus: 200,
      body: {
        requestId: "parse-request-1",
        sourceType: "wiki",
        sourceId: "15",
        parsedMarkdown: "# 취업 규칙\n본문...",
        warnings: [],
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_SOURCE_PARSE_REQUEST",
      message: "파싱 요청 파일 또는 메타데이터가 올바르지 않습니다.",
    },
  },
  "POST /internal/v1/wiki-context-selections": {
    success: {
      httpStatus: 200,
      body: {
        wikiIds: ["101", "108"],
        reason: "새 취업 규칙의 휴가·복무 항목과 관련된 현재 Wiki입니다.",
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_WIKI_CONTEXT_SELECTION_REQUEST",
      message: "Wiki 문맥 선택 요청 구조가 올바르지 않습니다.",
      failureStage: "context_load",
    },
  },
  "POST /internal/v1/wiki-transformations": {
    success: {
      httpStatus: 200,
      body: {
        summary: "휴가 규정 Wiki를 생성했습니다.",
        categoryChanges: [
          {
            action: "create",
            tempCategoryId: "category-temp-1",
            name: "휴가 및 근태",
          },
        ],
        wikiChanges: [
          {
            action: "create",
            tempWikiId: "wiki-temp-1",
            // 이 Wiki가 속할 카테고리. 같은 응답의 `categoryChanges[].tempCategoryId`
            // 또는 이미 존재하는 `wikiCategoryId`를 담는다. 이 필드가 없으면 백엔드는
            // 카테고리를 추정해야 하고, 한 응답이 카테고리를 둘 이상 만들면 실패한다.
            wikiCategoryRef: "category-temp-1",
            // `action`이 `create`일 때만 실린다. 신규 페이지의 파일명은 에이전트가 발급한
            // 값이라 `wikiId`에서 유도할 수 없다. 백엔드가 `wiki.wiki_path`(DR-016)를 채우고
            // 본문에 남은 신규 페이지 링크를 실제 ID로 치환하는 데 필요하다.
            wikiPath: "wiki/D1-D2/pages/a3f2c1d4.md",
            title: "휴가 규정",
            contentMarkdown: "# 휴가 규정\n...",
            evidence: [
              {
                documentId: "15",
                footnote: "1",
                location: "3장 휴가",
                quote: "연차는 15일을 부여한다",
              },
            ],
          },
        ],
        relationChanges: [
          {
            action: "add",
            type: "wiki_wiki",
            // 관계의 출발 Wiki. 같은 응답의 `wikiChanges[].tempWikiId` 또는 기존 `wikiId`를 담는다.
            sourceWikiRef: "wiki-temp-1",
            targetWikiRef: "101",
          },
        ],
        indexEntries: [
          {
            wikiRef: "wiki-temp-1",
            order: 1,
            title: "휴가 규정",
            summary: "연차와 반차 사용 기준",
          },
        ],
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_WIKI_TRANSFORMATION_REQUEST",
      message: "Wiki 변환 요청 구조가 올바르지 않습니다.",
      failureStage: "context_load",
    },
  },
  "POST /internal/v1/wiki-edits": {
    success: {
      httpStatus: 200,
      body: {
        agentMessage: "중복된 휴가 규정을 하나로 정리했습니다.",
        // 응답 구조는 Wiki 변환과 같다. `action`이 `create`인 항목에는 Wiki 변환과 동일하게
        // `wikiCategoryRef`와 `wikiPath`가 실린다.
        wikiChanges: [
          {
            action: "update",
            wikiId: "100",
            wikiCategoryRef: "9",
            title: "휴가 규정",
            contentMarkdown: "# 휴가 규정\n정리된 본문...",
          },
        ],
        categoryChanges: [],
        relationChanges: [
          {
            action: "add",
            type: "wiki_wiki",
            sourceWikiRef: "100",
            targetWikiRef: "101",
          },
        ],
        indexEntries: [
          {
            wikiRef: "100",
            order: 1,
            title: "휴가 규정",
            summary: "정리된 휴가 규정",
          },
        ],
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_WIKI_EDIT_REQUEST",
      message: "Wiki 수정 지시 또는 문맥이 올바르지 않습니다.",
    },
  },
  "POST /internal/v1/schedule-extractions": {
    success: {
      httpStatus: 200,
      body: {
        status: "extracted",
        schedules: [
          {
            order: 1,
            title: "8월 휴가 일정",
            content: "개발부 휴가 일정",
            targetText: "개발부",
            location: "본사",
            visibilityType: "department",
            departmentIds: ["1", "2"],
            startAt: "2026-08-03T01:00:00Z",
            endAt: "2026-08-03T03:00:00Z",
          },
        ],
        warnings: [],
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_SCHEDULE_EXTRACTION_REQUEST",
      message: "일정 추출 요청 구조가 올바르지 않습니다.",
    },
  },
  "POST /internal/v1/answer-context-selections": {
    success: {
      httpStatus: 200,
      body: {
        questionType: "mixed",
        wikiIds: ["101"],
        scheduleIds: ["31"],
        reason: "휴가 규정과 다음 일정을 모두 묻는 질문입니다.",
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_ANSWER_CONTEXT_REQUEST",
      message: "질문 또는 후보 자료 구조가 올바르지 않습니다.",
    },
  },
  "POST /internal/v1/answers": {
    success: {
      httpStatus: 200,
      body: {
        answer: "연차 규정과 다음 휴가 일정은 다음과 같습니다.",
        sources: [
          {
            type: "wiki",
            wikiId: "101",
            title: "휴가 규정",
          },
          {
            type: "schedule",
            scheduleId: "31",
            title: "8월 휴가 일정",
          },
        ],
      },
    },
    error: {
      httpStatus: 400,
      errorCode: "INVALID_ANSWER_GENERATION_REQUEST",
      message: "답변 생성 요청 문맥이 올바르지 않습니다.",
    },
  },

  // ---- Wiki 조회 창구 (FastAPI → Spring Boot) --------------------------------
  // FR-WIKI-002 가 요구하는 "백엔드가 검색·본문·관계 조회 수단을 제공하고 에이전트가
  // 필요한 Wiki 를 선택해 조회한다"의 창구다. 방향이 나머지와 반대다 — FastAPI 가
  // 호출자이고 Spring Boot 가 응답한다.
  //
  // 대표 오류를 404 로 둔 이유: 허가 범위 밖 조회를 403 이 아니라 404 로 돌려주는 것이
  // 이 창구의 핵심 규약이다 (NFR-SEC-003 · FR-ACL-006 존재 여부 비노출).
  "GET /internal/v1/wiki-search": {
    success: {
      httpStatus: 200,
      body: {
        scopeVersion: 47,
        items: [
          {
            wikiId: "101",
            title: "휴가 규정",
            breadcrumb: "휴가 규정 > 연차 > 이월",
            snippet: "연차는 다음 해 3월까지 이월할 수 있다...",
            chunkIndex: 3,
            contentHash:
              "9f2a1c8e4b7d0a35f6c9e2b8d1a4f7c0e3b6d9a2c5f8e1b4d7a0c3f6e9b2d5a8",
          },
        ],
      },
    },
    error: {
      httpStatus: 404,
      errorCode: "WIKI_SCOPE_NOT_FOUND",
      message: "요청한 자료를 찾을 수 없습니다.",
    },
  },
  "GET /internal/v1/wiki-pages": {
    success: {
      httpStatus: 200,
      body: {
        scopeVersion: 47,
        // 다음 페이지가 없으면 null. 정렬이 wikiId 오름차순이라 커서가 항목을
        // 건너뛰거나 겹치지 않는다.
        nextCursor: null,
        items: [
          {
            wikiId: "101",
            title: "휴가 규정",
            summary: "연차와 반차 사용 기준",
            wikiCategoryId: "9",
            categoryName: "휴가 및 근태",
            wikiPath: "wiki/D1-D2/pages/a3f2c1d4.md",
            contentHash:
              "9f2a1c8e4b7d0a35f6c9e2b8d1a4f7c0e3b6d9a2c5f8e1b4d7a0c3f6e9b2d5a8",
            updatedAt: timestamp,
          },
        ],
      },
    },
    error: {
      httpStatus: 404,
      errorCode: "WIKI_SCOPE_NOT_FOUND",
      message: "요청한 자료를 찾을 수 없습니다.",
    },
  },
  "GET /internal/v1/wikis/:wikiId/content": {
    success: {
      httpStatus: 200,
      body: {
        scopeVersion: 47,
        wikiId: "101",
        title: "휴가 규정",
        wikiPath: "wiki/D1-D2/pages/a3f2c1d4.md",
        contentMarkdown:
          "---\ntitle: 휴가 규정\ndescription: 연차와 반차 사용 기준\n---\n\n## 연차\n본문...",
        contentHash:
          "9f2a1c8e4b7d0a35f6c9e2b8d1a4f7c0e3b6d9a2c5f8e1b4d7a0c3f6e9b2d5a8",
      },
    },
    error: {
      httpStatus: 404,
      errorCode: "WIKI_NOT_FOUND",
      message: "요청한 자료를 찾을 수 없습니다.",
    },
  },
  "GET /internal/v1/wikis/:wikiId/relations": {
    success: {
      httpStatus: 200,
      body: {
        scopeVersion: 47,
        wikiId: "101",
        wikiRefs: ["102", "115"],
        documentRefs: ["15"],
        backlinks: ["108"],
      },
    },
    error: {
      httpStatus: 404,
      errorCode: "WIKI_NOT_FOUND",
      message: "요청한 자료를 찾을 수 없습니다.",
    },
  },
  "GET /internal/v1/wiki-spaces/:scopeKey/relations": {
    success: {
      httpStatus: 200,
      body: {
        scopeVersion: 47,
        items: [
          { wikiId: "101", wikiRefs: ["102", "115"], documentRefs: ["15"] },
          { wikiId: "102", wikiRefs: [], documentRefs: ["15", "16"] },
          { wikiId: "115", wikiRefs: ["101"], documentRefs: [] },
        ],
      },
    },
    error: {
      httpStatus: 404,
      errorCode: "WIKI_SCOPE_NOT_FOUND",
      message: "요청한 자료를 찾을 수 없습니다.",
    },
  },
  "GET /internal/v1/wiki-spaces/:scopeKey/index": {
    success: {
      httpStatus: 200,
      body: {
        scopeVersion: 47,
        scopeKey: "D1-D2",
        indexMarkdown:
          "# 목차\n\n## 휴가 및 근태\n- [휴가 규정](pages/a3f2c1d4.md) — 연차와 반차 사용 기준\n",
      },
    },
    error: {
      httpStatus: 404,
      errorCode: "WIKI_SCOPE_NOT_FOUND",
      message: "요청한 자료를 찾을 수 없습니다.",
    },
  },
  "GET /internal/v1/wiki-spaces/:scopeKey/categories": {
    success: {
      httpStatus: 200,
      body: {
        scopeVersion: 47,
        items: [
          { wikiCategoryId: "9", name: "휴가 및 근태", wikiCount: 12 },
          { wikiCategoryId: "10", name: "보안", wikiCount: 4 },
        ],
      },
    },
    error: {
      httpStatus: 404,
      errorCode: "WIKI_SCOPE_NOT_FOUND",
      message: "요청한 자료를 찾을 수 없습니다.",
    },
  },
  "GET /internal/v1/documents/:documentId/parsed": {
    success: {
      httpStatus: 200,
      body: {
        documentId: "15",
        originalFileName: "취업규칙.pdf",
        parsedMarkdown: "# 취업 규칙\n본문...",
      },
    },
    error: {
      httpStatus: 404,
      errorCode: "DOCUMENT_NOT_FOUND",
      message: "요청한 자료를 찾을 수 없습니다.",
    },
  },
};

const statusText = {
  200: "OK",
  201: "Created",
  202: "Accepted",
  400: "Bad Request",
  401: "Unauthorized",
  403: "Forbidden",
  404: "Not Found",
  409: "Conflict",
  500: "Internal Server Error",
};

const cloneRequest = (requestValue) =>
  JSON.parse(JSON.stringify(requestValue));

const savedExample = (definition, requestValue, defaultName) => ({
  name: definition.name ?? defaultName,
  originalRequest: cloneRequest(requestValue),
  status: statusText[definition.httpStatus],
  code: definition.httpStatus,
  _postman_previewlanguage: "json",
  header: [
    {
      key: "Content-Type",
      value: "application/json",
    },
    {
      key: "X-Request-Id",
      value: requestId,
    },
    ...(definition.headers ?? []),
  ],
  cookie: [],
  body: JSON.stringify(definition.body, null, 2),
});

export function buildSavedExamples(method, path, requestValue) {
  const contract = contracts[`${method} ${path}`];
  if (!contract) return [];

  const successDefinitions = Array.isArray(contract.success)
    ? contract.success
    : [contract.success];
  const successExamples = successDefinitions.map((definition) =>
    savedExample(
      definition,
      requestValue,
      `${definition.httpStatus} ${statusText[definition.httpStatus]} - 성공`,
    ),
  );

  const errorDefinition = {
    ...contract.error,
    body: {
      timestamp,
      status: contract.error.httpStatus,
      error: statusText[contract.error.httpStatus],
      code: contract.error.errorCode,
      message: contract.error.message,
      path,
      fieldErrors: contract.error.fieldErrors ?? [],
    },
  };

  return [
    ...successExamples,
    savedExample(
      errorDefinition,
      requestValue,
      `${errorDefinition.httpStatus} ${statusText[errorDefinition.httpStatus]} - 대표 오류`,
    ),
  ];
}
