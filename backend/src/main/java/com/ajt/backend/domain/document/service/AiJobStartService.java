package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.api.AiJobStartResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업로드로 만들어진 대기 작업을 관리자가 시작하는 서비스입니다.
 *
 * <p>업로드는 작업을 WAITING으로만 만든다(DocumentUploadService). 관리자가 대기 화면에서
 * 문서별 공개 범위를 확정한 뒤 이 API를 호출해야 파싱·Wiki 변환이 돌아간다.
 *
 * <p>중복 시작은 상태 전이로 막는다. 요청 트랜잭션에서 WAITING → PROCESSING으로 먼저 넘기고
 * 커밋 후에 워커를 띄우므로, 같은 작업에 두 번째 요청이 오면 WAITING이 아니라 409가 된다.
 */
@Service
public class AiJobStartService {

    private final CurrentMemberProvider currentMemberProvider;
    private final AiJobRepository aiJobRepository;
    private final DocumentParseJobLauncher parseJobLauncher;

    public AiJobStartService(
            CurrentMemberProvider currentMemberProvider,
            AiJobRepository aiJobRepository,
            DocumentParseJobLauncher parseJobLauncher
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.aiJobRepository = aiJobRepository;
        this.parseJobLauncher = parseJobLauncher;
    }

    @Transactional
    public AiJobStartResponse start(long jobId) {
        if (!currentMemberProvider.currentMember().isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        AiJob job = aiJobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_JOB_NOT_FOUND));
        if (job.status() != AiJobStatus.WAITING) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }

        job.start();
        aiJobRepository.save(job);
        // 커밋 후에 워커가 뜬다(AsyncDocumentParseJobLauncher). 워커는 PROCESSING 상태를 보고
        // 다시 start()하지 않는다.
        parseJobLauncher.launch(job, DocumentReprocessPlan.added());

        return new AiJobStartResponse(String.valueOf(job.id()), job.status().name().toLowerCase());
    }
}
