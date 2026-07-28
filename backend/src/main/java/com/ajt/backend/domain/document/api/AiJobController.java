package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.service.AiJobQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiJobController {

    private final AiJobQueryService aiJobQueryService;

    public AiJobController(AiJobQueryService aiJobQueryService) {
        this.aiJobQueryService = aiJobQueryService;
    }

    @GetMapping("/api/v1/ai-jobs/{jobId}")
    public AiJobResponse getAiJob(@PathVariable long jobId) {
        return aiJobQueryService.getAiJob(jobId);
    }
}
