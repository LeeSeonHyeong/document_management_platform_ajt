package com.ajt.backend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.schedule.api.ScheduleSourceUploadResponse;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.domain.schedule.storage.LocalScheduleSourceFileStorage;
import com.ajt.backend.domain.schedule.storage.ScheduleSourceFileStorage;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.AiClientFailureType;
import com.ajt.backend.global.ai.client.ScheduleExtractionRequest;
import com.ajt.backend.global.ai.client.ScheduleExtractionResponse;
import com.ajt.backend.global.ai.client.SourceParseRequest;
import com.ajt.backend.global.ai.client.SourceParseResponse;
import com.ajt.backend.global.ai.client.SourceType;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@DisplayName("일정 원본문서 업로드 서비스")
class ScheduleSourceServiceTest {

    @TempDir
    Path storageRoot;

    private final ScheduleRepository scheduleRepository = mock(ScheduleRepository.class);
    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    private final AiClient aiClient = mock(AiClient.class);

    private ScheduleSourceFileStorage storage;
    private ScheduleSourceService service;

    @BeforeEach
    void setUp() {
        storage = new LocalScheduleSourceFileStorage(storageRoot);
        service = new ScheduleSourceService(scheduleRepository, departmentRepository, storage, aiClient);

        given(aiClient.parseSource(any(SourceParseRequest.class))).willAnswer(invocation -> {
            SourceParseRequest request = invocation.getArgument(0);
            return new SourceParseResponse(
                    request.requestId(), request.sourceType(), request.sourceId(), "# 8월 일정", List.of());
        });
        // 저장 시 DB가 부여하는 ID를 대신 채워 응답의 scheduleId를 검증할 수 있게 한다.
        given(scheduleRepository.saveAll(anyList())).willAnswer(invocation -> {
            List<Schedule> schedules = invocation.getArgument(0);
            long nextId = 31L;
            for (Schedule schedule : schedules) {
                assignId(schedule, nextId++);
            }
            return schedules;
        });
    }

    private void givenDepartments(Long... ids) {
        given(departmentRepository.findAllById(anyList()))
                .willReturn(List.of(ids).stream().map(id -> new Department("부서" + id)).toList());
    }

    private void givenExtraction(int count) {
        given(aiClient.extractSchedules(any(ScheduleExtractionRequest.class)))
                .willReturn(extractionWith(count));
    }

    private MultipartFile xlsx() {
        return new MockMultipartFile(
                "file",
                "8월일정.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "일정".getBytes(StandardCharsets.UTF_8));
    }

    private AuthenticatedMember admin() {
        return new AuthenticatedMember(7L, "admin@ajt.com", Role.ADMIN);
    }

    @Test
    @DisplayName("원본을 저장하고 파싱·추출을 거쳐 draft 일정을 만든다")
    void uploadsAndCreatesDraftSchedules() {
        givenDepartments(1L, 2L);
        givenExtraction(2);

        ScheduleSourceUploadResponse response =
                service.upload(admin(), xlsx(), "department", List.of("1", "2"));

        assertThat(response.status()).isEqualTo("extracted");
        assertThat(response.sourceGroupKey()).startsWith("schedule-source-");
        assertThat(response.draftSchedules()).hasSize(2);
        assertThat(response.draftSchedules()).allSatisfy(draft -> {
            assertThat(draft.status()).isEqualTo("draft");
            assertThat(draft.visibilityType()).isEqualTo("department");
            assertThat(draft.departmentIds()).containsExactly("1", "2");
        });
        assertThat(response.draftSchedules().getFirst().scheduleId()).isEqualTo("31");
    }

    @Test
    @DisplayName("파싱과 추출을 계약대로 호출하고 원본·파싱 파일을 남긴다")
    void callsInternalApisWithContractValues() throws IOException {
        givenExtraction(1);

        ScheduleSourceUploadResponse response = service.upload(admin(), xlsx(), "all", null);
        String key = response.sourceGroupKey();

        ArgumentCaptor<SourceParseRequest> parse = ArgumentCaptor.forClass(SourceParseRequest.class);
        verify(aiClient).parseSource(parse.capture());
        assertThat(parse.getValue().sourceType()).isEqualTo(SourceType.SCHEDULE);
        assertThat(parse.getValue().sourceId()).isEqualTo(key);
        assertThat(parse.getValue().originalFileName()).isEqualTo("8월일정.xlsx");

        ArgumentCaptor<ScheduleExtractionRequest> extraction =
                ArgumentCaptor.forClass(ScheduleExtractionRequest.class);
        verify(aiClient).extractSchedules(extraction.capture());
        assertThat(extraction.getValue().sourceGroupKey()).isEqualTo(key);
        assertThat(extraction.getValue().parsedMarkdown()).isEqualTo("# 8월 일정");
        assertThat(extraction.getValue().visibilityType()).isEqualTo("all");
        assertThat(extraction.getValue().departmentIds()).isEmpty();

        assertThat(Files.exists(storageRoot.resolve(
                "schedule-sources/" + key + "/original/source.xlsx"))).isTrue();
        assertThat(Files.readString(storageRoot.resolve(
                "schedule-sources/" + key + "/parsed/content.md"))).isEqualTo("# 8월 일정");
    }

    @Test
    @DisplayName("저장한 draft에 원본 경로와 원본 파일명이 함께 기록된다")
    void linksSourceMetadataToDrafts() {
        givenExtraction(1);

        ScheduleSourceUploadResponse response = service.upload(admin(), xlsx(), "all", null);

        ArgumentCaptor<List<Schedule>> saved = ArgumentCaptor.captor();
        verify(scheduleRepository).saveAll(saved.capture());
        Schedule draft = saved.getValue().getFirst();
        assertThat(draft.sourceGroupKey()).isEqualTo(response.sourceGroupKey());
        assertThat(draft.sourceOriginalFileName()).isEqualTo("8월일정.xlsx");
        assertThat(draft.sourceOriginalPath())
                .isEqualTo("schedule-sources/" + response.sourceGroupKey() + "/original/source.xlsx");
        assertThat(draft.sourceParsedPath())
                .isEqualTo("schedule-sources/" + response.sourceGroupKey() + "/parsed/content.md");
    }

    @Test
    @DisplayName("추출된 일정이 없으면 원본·파싱 파일을 지우고 no_schedule을 반환한다")
    void deletesFilesWhenNoScheduleExtracted() {
        given(aiClient.extractSchedules(any(ScheduleExtractionRequest.class)))
                .willReturn(new ScheduleExtractionResponse("no_schedule", List.of(), List.of()));

        ScheduleSourceUploadResponse response = service.upload(admin(), xlsx(), "all", null);

        assertThat(response.status()).isEqualTo("no_schedule");
        assertThat(response.draftSchedules()).isEmpty();
        assertThat(response.hasSchedules()).isFalse();
        assertThat(Files.exists(storageRoot.resolve("schedule-sources/" + response.sourceGroupKey())))
                .isFalse();
        verify(scheduleRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("추출이 실패하면 원본·파싱 파일을 남기지 않는다")
    void deletesFilesWhenExtractionFails() {
        given(aiClient.extractSchedules(any(ScheduleExtractionRequest.class)))
                .willThrow(new IllegalStateException("추출 실패"));

        assertThatThrownBy(() -> service.upload(admin(), xlsx(), "all", null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(storageRoot.resolve("schedule-sources").toFile().list()).isEmpty();
    }

    @Test
    @DisplayName("AI 연결 실패는 503(AI_SERVER_UNAVAILABLE)으로 내리고 파일을 남기지 않는다")
    void mapsAiConnectionFailureToServiceUnavailable() {
        given(aiClient.extractSchedules(any(ScheduleExtractionRequest.class)))
                .willThrow(new AiClientException(
                        AiClientFailureType.CONNECTION_FAILED, null, null,
                        "FastAPI에 연결하지 못했습니다.", List.of(), null));

        assertThatThrownBy(() -> service.upload(admin(), xlsx(), "all", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_SERVER_UNAVAILABLE);

        assertThat(storageRoot.resolve("schedule-sources").toFile().list()).isEmpty();
    }

    @Test
    @DisplayName("AI 처리 실패(응답 5xx)는 503이 아니라 기존 서버 오류로 전파한다")
    void keepsAiProcessingFailureAsServerError() {
        given(aiClient.extractSchedules(any(ScheduleExtractionRequest.class)))
                .willThrow(new AiClientException(
                        AiClientFailureType.SERVER_ERROR, 500, "EXTRACTION_FAILED",
                        "추출에 실패했습니다.", List.of(), null));

        // 연결 불가가 아니므로 AI_SERVER_UNAVAILABLE로 매핑하지 않고 원래 예외를 그대로 둔다.
        assertThatThrownBy(() -> service.upload(admin(), xlsx(), "all", null))
                .isInstanceOf(AiClientException.class);

        assertThat(storageRoot.resolve("schedule-sources").toFile().list()).isEmpty();
    }

    @Test
    @DisplayName("관리자가 아니면 업로드할 수 없다")
    void rejectsNonAdmin() {
        AuthenticatedMember employee = new AuthenticatedMember(8L, "user@ajt.com", Role.EMPLOYEE);

        assertThatThrownBy(() -> service.upload(employee, xlsx(), "all", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("허용하지 않는 파일 형식은 거절한다")
    void rejectsUnsupportedFileType() {
        MultipartFile png = new MockMultipartFile("file", "일정.png", "image/png", "x".getBytes());

        assertThatThrownBy(() -> service.upload(admin(), png, "all", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCHEDULE_SOURCE);
    }

    @Test
    @DisplayName("personal 공개 범위는 거절한다")
    void rejectsPersonalVisibility() {
        assertThatThrownBy(() -> service.upload(admin(), xlsx(), "personal", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCHEDULE_SOURCE);
    }

    @Test
    @DisplayName("부서 공개인데 부서를 지정하지 않으면 거절한다")
    void rejectsDepartmentVisibilityWithoutDepartments() {
        assertThatThrownBy(() -> service.upload(admin(), xlsx(), "department", List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCHEDULE_SOURCE);
    }

    @Test
    @DisplayName("존재하지 않는 부서가 있으면 거절한다")
    void rejectsUnknownDepartment() {
        given(departmentRepository.findAllById(anyList())).willReturn(List.of(new Department("개발부")));

        assertThatThrownBy(() -> service.upload(admin(), xlsx(), "department", List.of("1", "2")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCHEDULE_SOURCE);
    }

    @Test
    @DisplayName("검증 실패 시 원본문서를 저장하지 않는다")
    void doesNotStoreFileWhenValidationFails() {
        assertThatThrownBy(() -> service.upload(admin(), xlsx(), "personal", null))
                .isInstanceOf(BusinessException.class);

        assertThat(Files.exists(storageRoot.resolve("schedule-sources"))).isFalse();
    }

    private ScheduleExtractionResponse extractionWith(int count) {
        List<ScheduleExtractionResponse.ExtractedSchedule> schedules = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            schedules.add(new ScheduleExtractionResponse.ExtractedSchedule(
                    index,
                    "8월 일정 " + index,
                    "내용",
                    "개발부",
                    "본사",
                    "department",
                    List.of("1", "2"),
                    "2026-08-0" + index + "T01:00:00Z",
                    "2026-08-0" + index + "T03:00:00Z"
            ));
        }
        return new ScheduleExtractionResponse("extracted", schedules, List.of());
    }

    private void assignId(Object target, long id) throws ReflectiveOperationException {
        Field idField = target.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
    }
}
