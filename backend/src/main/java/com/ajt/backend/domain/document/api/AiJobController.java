package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.service.AiJobQueryService;
import com.ajt.backend.domain.document.service.AiJobCancelService;
import com.ajt.backend.domain.document.service.AiJobStartService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiJobController {

    private final AiJobQueryService aiJobQueryService;
    private final AiJobCancelService aiJobCancelService;
    private final AiJobStartService aiJobStartService;

    public AiJobController(
            AiJobQueryService aiJobQueryService,
            AiJobCancelService aiJobCancelService,
            AiJobStartService aiJobStartService
    ) {
        this.aiJobQueryService = aiJobQueryService;
        this.aiJobCancelService = aiJobCancelService;
        this.aiJobStartService = aiJobStartService;
    }

    // 작업 이력 목록(S15P11B106-192). 관리자 「요약 목록」이 회차별로 묶어 보여준다.
    @GetMapping("/api/v1/ai-jobs")
    public AiJobListResponse listAiJobs(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        return aiJobQueryService.listAiJobs(page, size);
    }

    @GetMapping("/api/v1/ai-jobs/{jobId}")
    public AiJobResponse getAiJob(@PathVariable long jobId) {
        return aiJobQueryService.getAiJob(jobId);
    }

    // 업로드로 생성된 대기 작업을 관리자가 시작한다. 업로드만으로는 파싱·Wiki 변환이 돌지 않는다.
    @PostMapping("/api/v1/ai-jobs/{jobId}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiJobStartResponse start(@PathVariable long jobId) {
        return aiJobStartService.start(jobId);
    }

    @PostMapping("/api/v1/ai-jobs/{jobId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiJobCancelResponse cancel(@PathVariable long jobId) {
        return aiJobCancelService.cancel(jobId);
    }
}
