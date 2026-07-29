package com.ajt.backend.domain.schedule.api;

import com.ajt.backend.domain.schedule.service.ScheduleSourceService;
import com.ajt.backend.global.auth.AuthenticatedMember;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class ScheduleSourceController {

    private final ScheduleSourceService scheduleSourceService;

    /**
     * POST /api/v1/schedule-sources
     * 관리자가 일정 추출용 원본문서를 업로드합니다.
     * 일정을 추출하면 201, 추출된 일정이 없으면 200 no_schedule로 응답합니다.
     */
    @PostMapping("/api/v1/schedule-sources")
    public ResponseEntity<ScheduleSourceUploadResponse> uploadScheduleSource(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String visibilityType,
            @RequestParam(required = false) List<String> departmentIds
    ) {
        ScheduleSourceUploadResponse response =
                scheduleSourceService.upload(loginMember, file, visibilityType, departmentIds);
        HttpStatus status = response.hasSchedules() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
