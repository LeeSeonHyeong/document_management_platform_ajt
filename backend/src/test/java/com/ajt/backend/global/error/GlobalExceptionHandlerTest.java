package com.ajt.backend.global.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void validationExceptionReturnsCommonErrorResponse() throws Exception {
        mockMvc.perform(post("/test/errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청값을 확인해주세요."))
                .andExpect(jsonPath("$.path").value("/test/errors/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("제목은 비어 있을 수 없습니다."));
    }

    @Test
    void businessExceptionReturnsConfiguredErrorResponse() throws Exception {
        mockMvc.perform(get("/test/errors/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("테스트 리소스를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.path").value("/test/errors/business"))
                .andExpect(jsonPath("$.fieldErrors", empty()));
    }

    @Test
    void unsupportedHttpMethodReturnsMethodNotAllowed() throws Exception {
        // GET 전용 엔드포인트에 POST로 요청하면, 캐치올(500)이 아니라 405로 응답해야 한다.
        mockMvc.perform(post("/test/errors/business")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("허용되지 않은 요청 메서드입니다."))
                .andExpect(jsonPath("$.path").value("/test/errors/business"))
                .andExpect(jsonPath("$.fieldErrors", empty()))
                // REST 규격상 405는 허용 메서드를 Allow 헤더로 안내해야 한다.
                .andExpect(header().string("Allow", containsString("GET")));
    }

    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        // JSON을 받는 엔드포인트에 text/plain으로 보내면 캐치올(500)이 아니라 415로 응답해야 한다.
        mockMvc.perform(post("/test/errors/validation")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.error").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.path").value("/test/errors/validation"))
                .andExpect(jsonPath("$.fieldErrors", empty()));
    }

    @Test
    void maxUploadSizeExceededMapsTo413() {
        // 업로드 파일이 최대 크기를 초과하면(MaxUploadSizeExceededException, base가 413으로 매핑)
        // 공통 envelope로 413을 반환해야 한다. 최대 크기 초과는 서블릿 멀티파트 리졸버가 판정하므로
        // standalone MockMvc로는 재현되지 않아, base가 위임하는 handleExceptionInternal을 직접 호출해
        // 매핑을 검증한다(실제 초과 업로드 시 413 JSON은 라이브로 확인).
        ServletWebRequest webRequest =
                new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/documents"));
        ResponseEntity<Object> response = new GlobalExceptionHandler().handleExceptionInternal(
                new MaxUploadSizeExceededException(20L * 1024 * 1024),
                null,
                new HttpHeaders(),
                HttpStatus.PAYLOAD_TOO_LARGE,
                webRequest);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        ErrorResponse errorBody = (ErrorResponse) response.getBody();
        assertEquals(413, errorBody.status());
        assertEquals("PAYLOAD_TOO_LARGE", errorBody.code());
        assertEquals("/api/v1/documents", errorBody.path());
    }

    @Test
    void missingRequiredPartReturns400() throws Exception {
        // 필수 multipart 파트를 빠뜨리고 요청하면 캐치올(500)이 아니라 400으로 응답해야 한다.
        mockMvc.perform(multipart("/test/errors/part"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.path").value("/test/errors/part"))
                .andExpect(jsonPath("$.fieldErrors", empty()));
    }

    @Test
    void unhandledExceptionReturns500() throws Exception {
        // 매핑되지 않은 예외는 공통 500 응답으로 변환된다(핸들러 내부에서 ERROR 로깅 수행).
        mockMvc.perform(get("/test/errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.path").value("/test/errors/boom"))
                .andExpect(jsonPath("$.fieldErrors", empty()));
    }

    @RestController
    @RequestMapping("/test/errors")
    static class TestController {

        @PostMapping("/validation")
        void validation(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.NOT_FOUND, "테스트 리소스를 찾을 수 없습니다.");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("의도적으로 발생시킨 처리되지 않은 예외");
        }

        @PostMapping("/part")
        void part(@RequestPart("file") MultipartFile file) {
        }
    }

    record TestRequest(
            @NotBlank(message = "제목은 비어 있을 수 없습니다.") String title
    ) {
    }
}