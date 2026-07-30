package com.ajt.backend.domain.document.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.document.service.DocumentFileDownload;
import com.ajt.backend.domain.document.service.DocumentManagementService;
import com.ajt.backend.domain.document.service.DocumentUploadService;
import com.ajt.backend.global.error.GlobalExceptionHandler;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Wiki 원본문서 업로드 API")
class DocumentUploadControllerTest {

    private final DocumentUploadService documentUploadService = org.mockito.Mockito.mock(DocumentUploadService.class);
    private final DocumentManagementService documentManagementService =
            org.mockito.Mockito.mock(DocumentManagementService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentUploadController(documentUploadService, documentManagementService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("multipart 업로드 성공 시 202와 AI 작업 정보를 반환한다")
    void uploadsDocuments() throws Exception {
        given(documentUploadService.upload(any(DocumentUploadRequest.class))).willReturn(new DocumentUploadResponse(
                "42",
                List.of("15", "16"),
                "D1-D2",
                "waiting",
                LocalDateTime.parse("2026-07-28T13:00:00")
        ));

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(markdownFile("one.md"))
                        .file(markdownFile("two.md"))
                        .param("documentCategoryId", "7")
                        .param("visibilityType", "department")
                        .param("departmentIds", "1", "2"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("42"))
                .andExpect(jsonPath("$.documentIds[0]").value("15"))
                .andExpect(jsonPath("$.documentIds[1]").value("16"))
                .andExpect(jsonPath("$.scopeKey").value("D1-D2"))
                .andExpect(jsonPath("$.status").value("waiting"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-28T13:00:00"));
    }

    @Test
    @DisplayName("업로드 검증 실패 시 INVALID_DOCUMENT_UPLOAD 오류를 반환한다")
    void returnsInvalidDocumentUpload() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .param("documentCategoryId", "7")
                        .param("visibilityType", "all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_UPLOAD"))
                .andExpect(jsonPath("$.message").value("파일 형식, 개수 또는 용량 제한을 확인해주세요."))
                .andExpect(jsonPath("$.path").value("/api/v1/documents"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("files"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("파일을 하나 이상 업로드해야 합니다."));
    }

    @Test
    @DisplayName("카테고리나 공개 범위 파라미터가 잘못되면 업로드 검증 오류를 반환한다")
    void returnsInvalidDocumentUploadForInvalidParameters() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(markdownFile("one.md"))
                        .param("documentCategoryId", "0")
                        .param("visibilityType", "all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_UPLOAD"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("documentCategoryId"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("문서 카테고리를 지정해야 합니다."))
                .andExpect(jsonPath("$.fieldErrors[1:]", empty()));
    }

    @Test
    @DisplayName("문서 상세 조회 성공 시 문서 처리 상태와 메타데이터를 반환한다")
    void getsDocumentDetail() throws Exception {
        given(documentManagementService.getDocument(15L)).willReturn(new DocumentDetailResponse(
                "15",
                "rule.md",
                "text/markdown",
                2048L,
                "7",
                "취업규칙",
                "ALL",
                "all",
                List.of(),
                "failed",
                "파싱 실패",
                new DocumentUploaderResponse("10", "김관리"),
                Instant.parse("2026-07-28T05:00:00Z"),
                "/api/v1/documents/15/file",
                List.of()
        ));

        mockMvc.perform(get("/api/v1/documents/{documentId}", 15L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("15"))
                .andExpect(jsonPath("$.originalFileName").value("rule.md"))
                .andExpect(jsonPath("$.mimeType").value("text/markdown"))
                .andExpect(jsonPath("$.fileSize").value(2048))
                .andExpect(jsonPath("$.documentCategoryId").value("7"))
                .andExpect(jsonPath("$.documentCategoryName").value("취업규칙"))
                .andExpect(jsonPath("$.scopeKey").value("ALL"))
                .andExpect(jsonPath("$.visibilityType").value("all"))
                .andExpect(jsonPath("$.departments", empty()))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failureReason").value("파싱 실패"))
                .andExpect(jsonPath("$.uploadedBy.userId").value("10"))
                .andExpect(jsonPath("$.uploadedBy.name").value("김관리"))
                .andExpect(jsonPath("$.uploadedAt").value("2026-07-28T05:00:00Z"))
                .andExpect(jsonPath("$.downloadUrl").value("/api/v1/documents/15/file"))
                .andExpect(jsonPath("$.relatedWikis", empty()));
    }

    @Test
    @DisplayName("문서 목록 조회 성공 시 200과 목록·페이지 정보를 반환한다")
    void listsDocuments() throws Exception {
        given(documentManagementService.findDocuments(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new DocumentListResponse(
                        List.of(new DocumentSummaryResponse(
                                "15",
                                "rule.md",
                                "text/markdown",
                                2048L,
                                "7",
                                "취업규칙",
                                "ALL",
                                "all",
                                List.of(),
                                "uploaded",
                                null,
                                new DocumentUploaderResponse("10", "김관리"),
                                Instant.parse("2026-07-28T05:00:00Z")
                        )),
                        1,
                        20,
                        1,
                        1
                ));

        mockMvc.perform(get("/api/v1/documents")
                        .param("scopeKey", "ALL")
                        .param("status", "uploaded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].documentId").value("15"))
                .andExpect(jsonPath("$.items[0].originalFileName").value("rule.md"))
                .andExpect(jsonPath("$.items[0].scopeKey").value("ALL"))
                .andExpect(jsonPath("$.items[0].documentCategoryName").value("취업규칙"))
                .andExpect(jsonPath("$.items[0].uploadedBy.name").value("김관리"))
                .andExpect(jsonPath("$.items[0].status").value("uploaded"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("실패 문서 재시도 성공 시 202와 새 AI 작업 정보를 반환한다")
    void retriesFailedDocument() throws Exception {
        given(documentManagementService.retry(15L)).willReturn(new DocumentRetryResponse(
                "42",
                "15",
                "waiting",
                LocalDateTime.parse("2026-07-28T14:00:00")
        ));

        mockMvc.perform(post("/api/v1/documents/{documentId}/retry", 15L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("42"))
                .andExpect(jsonPath("$.documentId").value("15"))
                .andExpect(jsonPath("$.status").value("waiting"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-28T14:00:00"));

        verify(documentManagementService).retry(15L);
    }

    @Test
    @DisplayName("재시도할 수 없는 상태면 409 오류를 반환한다")
    void returnsConflictWhenRetryIsNotAllowed() throws Exception {
        given(documentManagementService.retry(15L))
                .willThrow(new BusinessException(ErrorCode.INVALID_DOCUMENT_STATUS));

        mockMvc.perform(post("/api/v1/documents/{documentId}/retry", 15L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_STATUS"))
                .andExpect(jsonPath("$.message").value("문서 처리 상태를 확인해주세요."))
                .andExpect(jsonPath("$.path").value("/api/v1/documents/15/retry"));
    }

    @Test
    @DisplayName("파일 교체 성공 시 202와 재처리 AI 작업 정보를 반환한다")
    void replacesDocumentFile() throws Exception {
        given(documentManagementService.replaceFile(eq(15L), any()))
                .willReturn(new DocumentFileReplaceResponse("42", "15", "waiting"));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/documents/{documentId}/file", 15L)
                        .file(new MockMultipartFile("file", "new.md", "text/markdown", "# 새 문서".getBytes())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("42"))
                .andExpect(jsonPath("$.documentId").value("15"))
                .andExpect(jsonPath("$.status").value("waiting"));

        verify(documentManagementService).replaceFile(eq(15L), any());
    }

    @Test
    @DisplayName("파일 교체 대상이 처리 중이면 409 오류를 반환한다")
    void returnsConflictWhenReplaceTargetInProgress() throws Exception {
        given(documentManagementService.replaceFile(eq(15L), any()))
                .willThrow(new BusinessException(ErrorCode.INVALID_DOCUMENT_STATUS));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/documents/{documentId}/file", 15L)
                        .file(new MockMultipartFile("file", "new.md", "text/markdown", "# 새 문서".getBytes())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_STATUS"))
                .andExpect(jsonPath("$.path").value("/api/v1/documents/15/file"));
    }

    @Test
    @DisplayName("문서 파일 다운로드 성공 시 200과 파일명·타입 헤더로 파일을 내려준다")
    void downloadsDocumentFile() throws Exception {
        Resource resource = new ByteArrayResource("hello".getBytes(StandardCharsets.UTF_8));
        given(documentManagementService.downloadFile(15L))
                .willReturn(new DocumentFileDownload(resource, "취업규칙.pdf", "application/pdf"));

        mockMvc.perform(get("/api/v1/documents/{documentId}/file", 15L))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/pdf")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("filename")))
                .andExpect(content().bytes("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("문서 파일이 없으면 404를 반환한다")
    void returnsNotFoundWhenFileMissing() throws Exception {
        given(documentManagementService.downloadFile(15L))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/v1/documents/{documentId}/file", 15L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("문서 메타데이터 수정 성공 시 202와 재처리 작업·수정된 문서를 반환한다")
    void updatesDocumentMetadata() throws Exception {
        given(documentManagementService.update(eq(15L), any(DocumentMetadataUpdateRequest.class)))
                .willReturn(new DocumentUpdateResponse("42", "waiting", List.of(
                        new ReprocessJobResponse("ALL", "43"),
                        new ReprocessJobResponse("D1-D3", "42")
                ), new DocumentDetailResponse(
                        "15",
                        "rule.md",
                        "text/markdown",
                        2048L,
                        "4",
                        "사규",
                        "D1-D3",
                        "department",
                        List.of(),
                        "uploaded",
                        null,
                        new DocumentUploaderResponse("10", "김관리"),
                        Instant.parse("2026-07-28T05:00:00Z"),
                        "/api/v1/documents/15/file",
                        List.of()
                )));

        mockMvc.perform(patch("/api/v1/documents/{documentId}", 15L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentCategoryId\":4,\"visibilityType\":\"department\",\"departmentIds\":[1,3]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("42"))
                .andExpect(jsonPath("$.status").value("waiting"))
                .andExpect(jsonPath("$.reprocessJobs[0].scopeKey").value("ALL"))
                .andExpect(jsonPath("$.reprocessJobs[0].jobId").value("43"))
                .andExpect(jsonPath("$.reprocessJobs[1].scopeKey").value("D1-D3"))
                .andExpect(jsonPath("$.reprocessJobs[1].jobId").value("42"))
                .andExpect(jsonPath("$.document.documentId").value("15"))
                .andExpect(jsonPath("$.document.scopeKey").value("D1-D3"))
                .andExpect(jsonPath("$.document.documentCategoryName").value("사규"));
    }

    @Test
    @DisplayName("문서 삭제 성공 시 202와 Wiki 재처리 작업 정보를 반환한다")
    void deletesDocument() throws Exception {
        given(documentManagementService.delete(15L))
                .willReturn(new DocumentDeleteResponse("42", "ALL", "waiting"));

        mockMvc.perform(delete("/api/v1/documents/{documentId}", 15L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("42"))
                .andExpect(jsonPath("$.scopeKey").value("ALL"))
                .andExpect(jsonPath("$.status").value("waiting"));

        verify(documentManagementService).delete(15L);
    }

    private MockMultipartFile markdownFile(String name) {
        return new MockMultipartFile("files", name, "text/markdown", "# 문서".getBytes());
    }
}
