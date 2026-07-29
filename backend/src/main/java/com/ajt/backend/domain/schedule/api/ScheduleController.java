package com.ajt.backend.domain.schedule.api;

import com.ajt.backend.domain.schedule.service.ScheduleService;
import com.ajt.backend.domain.schedule.service.ScheduleSourceFile;
import com.ajt.backend.global.auth.AuthenticatedMember;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    /**
     * GET /api/v1/schedules
     * 기간과 공개 범위 권한에 맞는 일정 목록을 조회합니다. (달력/관리자 검수 화면)
     */
    @GetMapping("/api/v1/schedules")
    public ScheduleListResponse listSchedules(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String visibilityType,
            @RequestParam(required = false) String departmentId
    ) {
        return scheduleService.list(loginMember, startDate, endDate, status, visibilityType, departmentId);
    }

    /**
     * POST /api/v1/schedules
     * 관리자가 전체·부서 일정을, 사원이 개인 일정을 직접 생성합니다.
     */
    @PostMapping("/api/v1/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleCreateResponse createSchedule(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @Valid @RequestBody ScheduleCreateRequest request
    ) {
        return scheduleService.create(loginMember, request);
    }

    /**
     * GET /api/v1/schedules/{scheduleId}
     * 공개 범위 권한을 검증한 뒤 일정 상세를 반환합니다.
     */
    @GetMapping("/api/v1/schedules/{scheduleId}")
    public ScheduleDetailResponse getSchedule(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable long scheduleId
    ) {
        return scheduleService.getDetail(loginMember, scheduleId);
    }

    /**
     * PATCH /api/v1/schedules/{scheduleId}
     * 전달한 필드만 반영해 일정을 수정합니다. (사원은 본인 개인 일정만)
     */
    @PatchMapping("/api/v1/schedules/{scheduleId}")
    public ScheduleDetailResponse updateSchedule(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable long scheduleId,
            @Valid @RequestBody ScheduleUpdateRequest request
    ) {
        return scheduleService.update(loginMember, scheduleId, request);
    }

    /**
     * POST /api/v1/schedules/{scheduleId}/approve
     * 관리자가 AI가 추출한 draft 일정을 승인합니다.
     */
    @PostMapping("/api/v1/schedules/{scheduleId}/approve")
    public ScheduleDetailResponse approveSchedule(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable long scheduleId
    ) {
        return scheduleService.approve(loginMember, scheduleId);
    }

    /**
     * DELETE /api/v1/schedules/{scheduleId}
     * 일정을 하드 삭제합니다. draft 삭제는 거부로 처리합니다.
     */
    @DeleteMapping("/api/v1/schedules/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSchedule(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable long scheduleId
    ) {
        scheduleService.delete(loginMember, scheduleId);
    }

    /**
     * GET /api/v1/schedules/{scheduleId}/source-file
     * 관리자가 문서에서 추출된 일정의 원본문서를 다운로드합니다.
     */
    @GetMapping("/api/v1/schedules/{scheduleId}/source-file")
    public ResponseEntity<Resource> getScheduleSourceFile(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable long scheduleId
    ) {
        ScheduleSourceFile sourceFile = scheduleService.getSourceFile(loginMember, scheduleId);
        String contentDisposition = ContentDisposition.attachment()
                .filename(sourceFile.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(sourceFile.resource());
    }
}
