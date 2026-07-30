package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.api.AiJobCancelResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiJobCancelService {

    private final CurrentMemberProvider currentMemberProvider;
    private final AiJobRepository aiJobRepository;
    private final DocumentRepository documentRepository;

    public AiJobCancelService(CurrentMemberProvider currentMemberProvider,
                              AiJobRepository aiJobRepository,
                              DocumentRepository documentRepository) {
        this.currentMemberProvider = currentMemberProvider;
        this.aiJobRepository = aiJobRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional
    public AiJobCancelResponse cancel(long jobId) {
        if (!currentMemberProvider.currentMember().isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        AiJob job = aiJobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_JOB_NOT_FOUND));
        if (job.status() != AiJobStatus.WAITING && job.status() != AiJobStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
        documentRepository.findAllById(job.documentIds()).stream()
                .filter(document -> document.status() == DocumentStatus.UPLOADED)
                .forEach(document -> document.cancel());
        job.cancel();
        return new AiJobCancelResponse(String.valueOf(job.id()), "cancelled");
    }
}
