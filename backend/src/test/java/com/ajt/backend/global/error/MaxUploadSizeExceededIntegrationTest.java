package com.ajt.backend.global.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 업로드 파일 크기 초과(413)가 실제 서블릿 스택에서 공통 JSON 오류 envelope로 반환되는지 검증하는 통합 테스트.
 *
 * <p>413 수정은 두 부분(핸들러 매핑 + {@code server.tomcat.max-swallow-size} 설정)으로 이뤄지는데,
 * 설정이 빠지면 Tomcat이 미판독 본문을 못 삼켜 연결을 끊고 기본 HTML 오류를 반환한다(회귀).
 * 이 테스트는 실제 커넥터(RANDOM_PORT) + 인증/CSRF 흐름을 태워 그 회귀를 잡는다.
 * (인증되지 않은 요청은 다른 경로로 HTML 413이 나므로, 로그인·CSRF를 반드시 거친다.)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ajt.local-data.enabled=true"
)
class MaxUploadSizeExceededIntegrationTest {

    private static final String BOUNDARY = "----ajtTestBoundary";

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("최대 크기를 초과한 업로드는 실제 스택에서 413과 공통 JSON envelope를 반환한다")
    void oversizedUploadReturnsJson413() throws Exception {
        // 1) 로컬 시드 관리자 계정으로 로그인해 인증 쿠키 확보
        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(
                                "{\"email\":\"admin@ajt.com\",\"password\":\"password123!\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), "로그인 성공해야 함");
        String accessCookie = cookiePair(login, "AJT_ACCESS_TOKEN");
        assertNotNull(accessCookie, "인증 쿠키가 발급되어야 함");

        // 2) CSRF 토큰 발급(상태 변경 요청에 필요)
        HttpResponse<String> csrf = client.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                        .header("Cookie", accessCookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String csrfCookie = cookiePair(csrf, "XSRF-TOKEN");
        assertNotNull(csrfCookie, "CSRF 쿠키가 발급되어야 함");
        String csrfToken = csrfCookie.substring("XSRF-TOKEN=".length());

        // 3) max-file-size(20MB)를 크게 넘는 40MB 파일 업로드.
        //    초과분(미판독 잔여 본문 ~20MB)이 Tomcat 기본 max-swallow-size(2MB)를 넘어야
        //    설정 누락 시 연결이 끊기고 HTML로 회귀하므로, 그 조건을 만들기 위해 넉넉히 키운다.
        byte[] body = multipartBody(40 * 1024 * 1024);
        HttpResponse<String> upload = client.send(
                HttpRequest.newBuilder(uri("/api/v1/documents"))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .header("Cookie", accessCookie + "; " + csrfCookie)
                        .header("X-XSRF-TOKEN", csrfToken)
                        .POST(BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // 상태코드 413 + Tomcat HTML이 아니라 공통 JSON envelope여야 한다.
        assertEquals(413, upload.statusCode(), "업로드 크기 초과는 413이어야 함");
        String contentType = upload.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("application/json"),
                "JSON envelope여야 함(설정 누락 시 text/html로 회귀). 실제=" + contentType);
        assertTrue(upload.body().contains("\"code\":\"PAYLOAD_TOO_LARGE\""),
                "본문에 PAYLOAD_TOO_LARGE 코드가 있어야 함. 실제=" + upload.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    // Set-Cookie 헤더들에서 지정한 쿠키의 "이름=값" 부분(첫 세그먼트)만 추출한다.
    private String cookiePair(HttpResponse<?> response, String name) {
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        for (String setCookie : setCookies) {
            if (setCookie.startsWith(name + "=")) {
                int semicolon = setCookie.indexOf(';');
                return semicolon >= 0 ? setCookie.substring(0, semicolon) : setCookie;
            }
        }
        return null;
    }

    private byte[] multipartBody(int fileSize) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"files\"; filename=\"big.pdf\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/pdf\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(new byte[fileSize]);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Disposition: form-data; name=\"visibilityType\"\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
        out.write("ALL\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
