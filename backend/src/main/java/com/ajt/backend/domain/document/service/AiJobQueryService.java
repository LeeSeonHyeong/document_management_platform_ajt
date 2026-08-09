package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.api.AiJobListResponse;
import com.ajt.backend.domain.document.api.AiJobResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiJobQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentMemberProvider currentMemberProvider;
    private final AiJobRepository aiJobRepository;
    private final DocumentRepository documentRepository;

    public AiJobQueryService(
            CurrentMemberProvider currentMemberProvider,
            AiJobRepository aiJobRepository,
            DocumentRepository documentRepository
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.aiJobRepository = aiJobRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional(readOnly = true)
    public AiJobResponse getAiJob(long jobId) {
        requireAdmin();
        AiJob job = aiJobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_JOB_NOT_FOUND));
        return toResponse(job, documentsById(job.documentIds()));
    }

    /**
     * AI 작업 이력을 최신순으로 조회합니다(S15P11B106-192).
     *
     * <p>관리자 「요약 목록」 화면이 씁니다. 단건 조회는 jobId를 쥐고 있어야 열리므로,
     * 작업이 끝난 뒤 다시 찾아볼 길이 이 목록뿐입니다.
     *
     * <p>문서는 페이지 전체를 한 번에 읽습니다. 작업마다 조회하면 페이지 크기만큼
     * 질의가 늘어납니다.
     */
    @Transactional(readOnly = true)
    public AiJobListResponse listAiJobs(Integer page, Integer size) {
        requireAdmin();
        Page<AiJob> jobs = aiJobRepository.findAllByOrderByCreatedAtDescIdDesc(pageable(page, size));
        Map<Long, Document> documentsById = documentsById(
                jobs.getContent().stream().flatMap(job -> job.documentIds().stream()).distinct().toList());

        return AiJobListResponse.from(jobs.map(job -> toResponse(job, documentsById)));
    }

    private Pageable pageable(Integer page, Integer size) {
        int safePage = page == null ? 1 : page;
        int safeSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (safePage < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 1 이상이어야 합니다.");
        }
        if (safeSize < 1 || safeSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "size는 1 이상 100 이하여야 합니다.");
        }
        // 정렬은 리포지토리 메서드 이름이 정한다. 여기서 Sort 를 주면 둘이 겹쳐 어긋난다.
        return PageRequest.of(safePage - 1, safeSize);
    }

    private AiJobResponse toResponse(AiJob job, Map<Long, Document> documentsById) {
        return new AiJobResponse(
                String.valueOf(job.id()),
                job.status().name().toLowerCase(),
                documentResults(job, documentsById, recordedResultsById(job)),
                job.createdAt(),
                job.startedAt(),
                job.finishedAt(),
                job.failureReason()
        );
    }

    private void requireAdmin() {
        CurrentMember currentMember = currentMemberProvider.currentMember();
        if (!currentMember.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private Map<Long, Document> documentsById(List<Long> documentIds) {
        Map<Long, Document> documents = new HashMap<>();
        documentRepository.findAllById(documentIds)
                .forEach(document -> documents.put(document.id(), document));
        return documents;
    }

    /**
     * 작업에 기록된 문서별 결과입니다. 아직 진행 중인 작업은 비어 있습니다.
     */
    private Map<Long, AiJob.DocumentParseResult> recordedResultsById(AiJob job) {
        Map<Long, AiJob.DocumentParseResult> results = new HashMap<>();
        job.documentResults().forEach(result -> results.put(result.documentId(), result));
        return results;
    }

    private List<AiJobResponse.DocumentResultResponse> documentResults(
            AiJob job,
            Map<Long, Document> documentsById,
            Map<Long, AiJob.DocumentParseResult> recordedResultsById
    ) {
        List<Long> documentIds = job.documentIds();
        return documentIds.stream()
                .map(documentId -> toDocumentResult(
                        job,
                        documentId,
                        documentsById.get(documentId),
                        recordedResultsById.get(documentId),
                        documentIds
                ))
                .sorted(Comparator.comparingInt(AiJobResponse.DocumentResultResponse::order))
                .toList();
    }

    private AiJobResponse.DocumentResultResponse toDocumentResult(
            AiJob job,
            long documentId,
            Document document,
            AiJob.DocumentParseResult recordedResult,
            List<Long> orderedDocumentIds
    ) {
        AiJobStatus jobStatus = job.status();
        DocumentStatus status = statusOf(jobStatus, document, recordedResult);
        return new AiJobResponse.DocumentResultResponse(
                String.valueOf(documentId),
                fileNameOf(document, recordedResult),
                job.changeTypeOf(documentId).orElse("document_added"),
                orderedDocumentIds.indexOf(documentId) + 1,
                responseStatus(status),
                currentStage(status),
                recordedResult == null ? null : recordedResult.summary(),
                failureReasonOf(jobStatus, document, recordedResult),
                recordedResult == null ? null : recordedResult.failureStage(),
                recordedResult == null
                        ? List.of()
                        : recordedResult.affectedWikis().stream()
                                .map(wiki -> new AiJobResponse.AffectedWikiResponse(
                                        String.valueOf(wiki.wikiId()),
                                        wiki.title(),
                                        wiki.deleted()
                                ))
                                .toList()
        );
    }

    /**
     * 이력에 그릴 문서 상태입니다.
     *
     * <p><b>끝난 작업은 그때 기록한 결과를 따른다.</b> 문서의 현재 상태로 그리면 같은 문서를
     * 처리한 과거 작업들의 상태가 한꺼번에 바뀐다 — 문서를 다시 지우는 중이면 지난달 업로드
     * 작업까지 「처리 중」이 된다. 이력은 그 회차에 무엇이 일어났는지를 가리켜야 한다.
     *
     * <p>진행 중인 작업만 문서의 현재 상태로 그린다. 아직 기록이 없어 그것이 유일한 근거이고,
     * 화면도 그 값으로 진행 단계를 보여준다.
     *
     * <p>기록이 없는 옛 작업은 종전대로 문서의 현재 상태를 쓴다(판단 근거가 없다).
     */
    private DocumentStatus statusOf(
            AiJobStatus jobStatus,
            Document document,
            AiJob.DocumentParseResult recordedResult
    ) {
        if (document == null) {
            return statusOfMissingDocument(recordedResult);
        }
        if (recordedResult == null || !isSettled(jobStatus)) {
            return document.status();
        }
        if (recordedResult.success()) {
            return DocumentStatus.COMPLETED;
        }
        return jobStatus == AiJobStatus.CANCELLED ? DocumentStatus.CANCELLED : DocumentStatus.FAILED;
    }

    private boolean isSettled(AiJobStatus jobStatus) {
        return jobStatus == AiJobStatus.COMPLETED
                || jobStatus == AiJobStatus.FAILED
                || jobStatus == AiJobStatus.CANCELLED;
    }

    /**
     * 이력에 남길 파일 이름(S15P11B106-202).
     *
     * <p><b>기록된 스냅샷이 우선이다.</b> 그것이 이 작업이 실제로 처리한 파일의 이름이고,
     * 문서가 그 뒤에 교체·삭제됐어도 이력은 그때를 가리켜야 한다. 스냅샷이 없는 옛 작업만
     * 살아 있는 문서의 현재 이름으로 메운다 — 그마저 없으면 {@code null}이고 화면이
     * 「삭제된 문서」로 표시한다.
     */
    private String fileNameOf(Document document, AiJob.DocumentParseResult recordedResult) {
        if (recordedResult != null && recordedResult.originalFileName() != null) {
            return recordedResult.originalFileName();
        }
        return document == null ? null : document.originalFileName();
    }

    /**
     * 문서 행이 없는 결과의 상태입니다(S15P11B106-209).
     *
     * <p>행이 없다는 것만으로 실패라고 볼 수 없다. S15P11B106-195 부터 <b>삭제 성공이 문서 행을
     * 지운다</b> — 성공한 삭제와 걷어내기 실패가 똑같이 여기로 온다. 그러므로 이 작업이 그때
     * 기록한 결과를 따른다. 기록이 아예 없는 옛 작업만 실패로 남긴다(판단 근거가 없다).
     */
    private DocumentStatus statusOfMissingDocument(AiJob.DocumentParseResult recordedResult) {
        if (recordedResult == null) {
            return DocumentStatus.FAILED;
        }
        return recordedResult.success() ? DocumentStatus.COMPLETED : DocumentStatus.FAILED;
    }

    /**
     * 이력에 남길 실패 사유입니다.
     *
     * <p>문서 행이 없으면 <b>이 작업이 기록한 사유가 유일한 근거다</b>(S15P11B106-209).
     * 예전에는 곧바로 「문서를 찾을 수 없습니다」로 덮어써서, 걷어내기가 실제로 왜 실패했는지
     * 화면에서 볼 수 없었다. 기록도 문서도 없을 때만 그 문구로 남긴다.
     *
     * <p>끝난 작업은 상태와 같은 근거를 쓴다 — 그때 기록한 사유다. 문서의 현재 사유를 얹으면
     * 성공했던 과거 회차에 나중에 난 오류가 붙는다.
     *
     * <p>진행 중인 작업만 문서의 현재 실패 사유를 먼저 본다(아직 기록이 없다).
     */
    private String failureReasonOf(
            AiJobStatus jobStatus,
            Document document,
            AiJob.DocumentParseResult recordedResult
    ) {
        if (document == null) {
            if (recordedResult != null) {
                return recordedResult.failureReason();
            }
            return "문서를 찾을 수 없습니다.";
        }
        if (recordedResult != null && isSettled(jobStatus)) {
            return recordedResult.failureReason();
        }
        if (document.failureReason() != null) {
            return document.failureReason();
        }
        return recordedResult == null ? null : recordedResult.failureReason();
    }

    private String responseStatus(DocumentStatus status) {
        return switch (status) {
            case UPLOADED -> "waiting";
            case PARSING -> "processing";
            case PROCESSING -> "processing";
            // 삭제 대기도 처리 중이다 — 걷어내기 작업이 돌고 있다 (S15P11B106-195).
            case DELETING -> "processing";
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
        };
    }

    /**
     * 지금 어디까지 왔는지. 문서 상태에서 역산하므로 <b>실패 지점을 알려주지 않는다</b> —
     * Wiki 변환 중 실패한 문서도 {@code parsing}이 된다. 실패 지점은 기록된
     * {@code DocumentParseResult.failureStage}가 알려준다.
     */
    private String currentStage(DocumentStatus status) {
        return switch (status) {
            case UPLOADED -> "waiting";
            case PARSING, FAILED -> "parsing";
            case PROCESSING -> "wiki_pending";
            case DELETING -> "wiki_pending";
            case COMPLETED -> "wiki_applied";
            case CANCELLED -> "cancelled";
        };
    }
}
