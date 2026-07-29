package com.ajt.backend.domain.schedule.service;

import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.schedule.api.ScheduleSourceUploadResponse;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.domain.schedule.storage.ScheduleSourceFileStorage;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.ScheduleExtractionRequest;
import com.ajt.backend.global.ai.client.ScheduleExtractionResponse;
import com.ajt.backend.global.ai.client.SourceParseRequest;
import com.ajt.backend.global.ai.client.SourceParseResponse;
import com.ajt.backend.global.ai.client.SourceType;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * SCH-SOURCE-UPLOAD 일정 원본문서 업로드와 일정 추출입니다. (관리자 전용)
 * 원본을 저장한 뒤 FastAPI 파싱과 일정 추출을 순서대로 호출하고, 추출된 일정을 draft로 저장합니다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleSourceService {

    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final Map<String, Set<String>> ALLOWED_MIME_TYPES = Map.of(
            "txt", Set.of("text/plain"),
            "md", Set.of("text/markdown", "text/plain"),
            "pdf", Set.of("application/pdf"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "csv", Set.of("text/csv", "text/plain", "application/vnd.ms-excel"),
            "xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    );
    private static final Set<ScheduleVisibility> ALLOWED_VISIBILITIES =
            Set.of(ScheduleVisibility.ALL, ScheduleVisibility.DEPARTMENT);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ScheduleRepository scheduleRepository;
    private final DepartmentRepository departmentRepository;
    private final ScheduleSourceFileStorage sourceFileStorage;
    private final AiClient aiClient;

    /**
     * 원본문서를 저장하고 파싱·추출을 동기로 처리합니다.
     * 외부 호출이 최대 180초까지 걸릴 수 있어 트랜잭션 밖에서 수행하고, 저장은 draft 일괄 저장 한 번으로 끝냅니다.
     * 추출된 일정이 없으면 원본·파싱 파일을 지우고 no_schedule을 반환합니다.
     */
    public ScheduleSourceUploadResponse upload(
            AuthenticatedMember loginMember,
            MultipartFile file,
            String visibilityType,
            List<String> rawDepartmentIds
    ) {
        requireAdmin(loginMember);
        validateFile(file);
        ScheduleVisibility visibility = parseVisibility(visibilityType);
        List<Long> departmentIds = resolveDepartmentIds(visibility, rawDepartmentIds);

        String sourceGroupKey = generateSourceGroupKey();
        String originalPath = storeOriginal(sourceGroupKey, file);

        try {
            String parsedMarkdown = parseSource(sourceGroupKey, originalPath, file);
            String parsedPath = storeParsedMarkdown(sourceGroupKey, parsedMarkdown);
            List<ScheduleExtractionResponse.ExtractedSchedule> extracted =
                    extractSchedules(sourceGroupKey, parsedMarkdown, visibility, departmentIds);

            if (extracted.isEmpty()) {
                deleteSourceGroupQuietly(sourceGroupKey);
                return ScheduleSourceUploadResponse.noSchedule(sourceGroupKey);
            }

            List<Schedule> drafts = toDrafts(
                    loginMember, extracted, visibility, departmentIds,
                    sourceGroupKey, originalPath, file.getOriginalFilename(), parsedPath);
            return ScheduleSourceUploadResponse.extracted(sourceGroupKey, scheduleRepository.saveAll(drafts));
        } catch (RuntimeException exception) {
            // 파싱·추출·저장 중 실패하면 참조되지 않는 원본·파싱 파일이 남으므로 함께 정리한다.
            deleteSourceGroupQuietly(sourceGroupKey);
            throw exception;
        }
    }

    private String parseSource(String sourceGroupKey, String originalPath, MultipartFile file) {
        SourceParseResponse response = aiClient.parseSource(new SourceParseRequest(
                UUID.randomUUID().toString(),
                SourceType.SCHEDULE,
                sourceGroupKey,
                sourceFileStorage.load(originalPath),
                file.getOriginalFilename(),
                file.getContentType()
        ));
        return response.parsedMarkdown();
    }

    private List<ScheduleExtractionResponse.ExtractedSchedule> extractSchedules(
            String sourceGroupKey,
            String parsedMarkdown,
            ScheduleVisibility visibility,
            List<Long> departmentIds
    ) {
        ScheduleExtractionResponse response = aiClient.extractSchedules(new ScheduleExtractionRequest(
                sourceGroupKey,
                parsedMarkdown,
                visibility.apiValue(),
                departmentIds.stream().map(String::valueOf).toList()
        ));
        return response.schedules();
    }

    /**
     * 추출 결과를 draft 일정으로 만듭니다.
     * 공개 범위와 부서는 업로드 요청에서 검증한 값을 사용하고 추출 응답의 값은 신뢰하지 않습니다.
     */
    private List<Schedule> toDrafts(
            AuthenticatedMember loginMember,
            List<ScheduleExtractionResponse.ExtractedSchedule> extracted,
            ScheduleVisibility visibility,
            List<Long> departmentIds,
            String sourceGroupKey,
            String originalPath,
            String originalFileName,
            String parsedPath
    ) {
        List<Schedule> drafts = new ArrayList<>();
        for (ScheduleExtractionResponse.ExtractedSchedule item : extracted) {
            Schedule draft;
            try {
                draft = Schedule.draft(
                        loginMember.memberId(),
                        item.title(),
                        item.content(),
                        item.targetText(),
                        item.location(),
                        visibility,
                        item.startAtInstant(),
                        item.endAtInstant()
                );
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.INVALID_SCHEDULE_SOURCE, exception.getMessage());
            }
            draft.replaceDepartments(departmentIds);
            draft.linkSource(sourceGroupKey, originalPath, originalFileName, parsedPath);
            drafts.add(draft);
        }
        return drafts;
    }

    private String storeOriginal(String sourceGroupKey, MultipartFile file) {
        try {
            return sourceFileStorage.storeOriginal(sourceGroupKey, file);
        } catch (IOException exception) {
            throw new IllegalStateException("일정 원본문서를 저장하지 못했습니다.", exception);
        }
    }

    private String storeParsedMarkdown(String sourceGroupKey, String parsedMarkdown) {
        try {
            return sourceFileStorage.storeParsedMarkdown(sourceGroupKey, parsedMarkdown);
        } catch (IOException exception) {
            throw new IllegalStateException("일정 원본문서 파싱 결과를 저장하지 못했습니다.", exception);
        }
    }

    private void deleteSourceGroupQuietly(String sourceGroupKey) {
        try {
            sourceFileStorage.deleteSourceGroup(sourceGroupKey);
        } catch (IOException ignored) {
            // 파일 정리 실패는 업로드 결과를 바꾸지 않는다.
        }
    }

    /**
     * 같은 날 업로드가 겹쳐도 충돌하지 않도록 날짜와 임의 값을 조합합니다.
     */
    private String generateSourceGroupKey() {
        byte[] suffix = new byte[4];
        RANDOM.nextBytes(suffix);
        return "schedule-source-%s-%s".formatted(
                LocalDate.now(ZoneOffset.UTC).toString().replace("-", ""),
                HexFormat.of().formatHex(suffix)
        );
    }

    private void requireAdmin(AuthenticatedMember loginMember) {
        if (loginMember == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!loginMember.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE_SOURCE, "원본문서 파일을 첨부해야 합니다.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE_SOURCE, "파일당 최대 크기는 20MB입니다.");
        }
        String extension = extensionOf(file.getOriginalFilename());
        String contentType = file.getContentType();
        if (extension == null || contentType == null || !ALLOWED_MIME_TYPES
                .getOrDefault(extension, Set.of())
                .contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(
                    ErrorCode.INVALID_SCHEDULE_SOURCE,
                    "TXT, MD, DOCX, PDF, CSV, XLSX 파일만 업로드할 수 있습니다.");
        }
    }

    private String extensionOf(String originalFileName) {
        if (originalFileName == null) {
            return null;
        }
        int extensionStart = originalFileName.lastIndexOf('.');
        if (extensionStart < 0 || extensionStart == originalFileName.length() - 1) {
            return null;
        }
        return originalFileName.substring(extensionStart + 1).toLowerCase(Locale.ROOT);
    }

    private ScheduleVisibility parseVisibility(String visibilityType) {
        ScheduleVisibility visibility;
        try {
            visibility = ScheduleVisibility.fromApiValue(visibilityType);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE_SOURCE, exception.getMessage());
        }
        if (!ALLOWED_VISIBILITIES.contains(visibility)) {
            throw new BusinessException(
                    ErrorCode.INVALID_SCHEDULE_SOURCE,
                    "일정 원본문서는 all 또는 department 공개 범위만 지정할 수 있습니다.");
        }
        return visibility;
    }

    private List<Long> resolveDepartmentIds(ScheduleVisibility visibility, List<String> rawDepartmentIds) {
        boolean hasValues = rawDepartmentIds != null && !rawDepartmentIds.isEmpty();
        if (visibility != ScheduleVisibility.DEPARTMENT) {
            if (hasValues) {
                throw new BusinessException(
                        ErrorCode.INVALID_SCHEDULE_SOURCE, "부서 공개 범위가 아닐 때는 부서를 지정할 수 없습니다.");
            }
            return List.of();
        }
        if (!hasValues) {
            throw new BusinessException(
                    ErrorCode.INVALID_SCHEDULE_SOURCE, "부서 공개 일정은 부서를 하나 이상 지정해야 합니다.");
        }

        List<Long> departmentIds = new ArrayList<>();
        for (String rawId : rawDepartmentIds) {
            departmentIds.add(parseDepartmentId(rawId));
        }
        List<Long> distinctIds = departmentIds.stream().distinct().toList();
        if (departmentRepository.findAllById(distinctIds).size() != distinctIds.size()) {
            throw new BusinessException(
                    ErrorCode.INVALID_SCHEDULE_SOURCE, "존재하지 않는 부서가 포함되어 있습니다.");
        }
        return distinctIds;
    }

    private long parseDepartmentId(String rawId) {
        try {
            return Long.parseLong(rawId.trim());
        } catch (NumberFormatException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE_SOURCE, "부서 ID 형식이 올바르지 않습니다.");
        }
    }
}
