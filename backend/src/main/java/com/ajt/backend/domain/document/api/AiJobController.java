package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.service.AiJobQueryService;
import com.ajt.backend.domain.document.service.AiJobCancelService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiJobController {

    private final AiJobQueryService aiJobQueryService;
    private final AiJobCancelService aiJobCancelService;

    public AiJobController(AiJobQueryService aiJobQueryService, AiJobCancelService aiJobCancelService) {
        this.aiJobQueryService = aiJobQueryService;
        this.aiJobCancelService = aiJobCancelService;
    }

    @GetMapping("/api/v1/ai-jobs/{jobId}")
    public AiJobResponse getAiJob(@PathVariable long jobId) {
        return aiJobQueryService.getAiJob(jobId);
    }

    @PostMapping("/api/v1/ai-jobs/{jobId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiJobCancelResponse cancel(@PathVariable long jobId) {
        return aiJobCancelService.cancel(jobId);
    }
}
