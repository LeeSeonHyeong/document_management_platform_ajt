package com.ajt.backend.domain.document.api;

import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.document.service.DocumentUploadService;
import com.ajt.backend.global.error.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Wiki 원본문서 업로드 API")
class DocumentUploadControllerTest {

    private final DocumentUploadService documentUploadService = org.mockito.Mockito.mock(DocumentUploadService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentUploadController(documentUploadService))
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

    private MockMultipartFile markdownFile(String name) {
        return new MockMultipartFile("files", name, "text/markdown", "# 문서".getBytes());
    }
}
