package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.wiki.service.WikiTransformationApplier;
import com.ajt.backend.domain.wiki.service.WikiTransformationService;
import com.ajt.backend.domain.document.service.DocumentWikiTransformationTransactionService.WikiTransformationResult;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.SourceParseRequest;
import com.ajt.backend.global.ai.client.SourceParseResponse;
import com.ajt.backend.global.ai.client.SourceType;
import com.ajt.backend.global.ai.client.WikiDocumentChangeType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DocumentParseWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseWorker.class);

    /** 걷어낼 근거가 있었는데 AI 가 Wiki 변경을 주지 않았을 때의 실패 사유입니다(S15P11B106-225). */
    private static final String EMPTY_REMOVAL_REASON =
            "AI가 Wiki에서 걷어낼 내용을 찾지 못했습니다. 원본문서는 지우지 않았습니다 — 재처리해 주세요.";

    private final DocumentRepository documentRepository;
    private final AiJobRepository aiJobRepository;
    private final DocumentFileStorage fileStorage;
    private final AiClient aiClient;
    private final WikiTransformationService wikiTransformationService;
    private final DocumentWikiTransformationTransactionService transactionService;

    public DocumentParseWorker(
            DocumentRepository documentRepository,
            AiJobRepository aiJobRepository,
            DocumentFileStorage fileStorage,
            AiClient aiClient,
            WikiTransformationService wikiTransformationService,
            DocumentWikiTransformationTransactionService transactionService
    ) {
        this.documentRepository = documentRepository;
        this.aiJobRepository = aiJobRepository;
        this.fileStorage = fileStorage;
        this.aiClient = aiClient;
        this.wikiTransformationService = wikiTransformationService;
        this.transactionService = transactionService;
    }

    /** 문서를 새로 반영하는 기본 실행입니다. (업로드·재시도) */
    public void parse(AiJob job) {
        parse(job, DocumentReprocessPlan.added());
    }

    public void parse(AiJob job, DocumentReprocessPlan plan) {
        // 관리자가 시작한 작업(AiJobStartService)은 중복 시작을 막기 위해 요청 트랜잭션에서 이미
        // PROCESSING으로 넘어와 있다. 재처리·교체·삭제처럼 워커가 직접 여는 작업만 여기서 시작한다.
        if (job.status() == com.ajt.backend.domain.document.model.AiJobStatus.WAITING) {
            job.start();
            aiJobRepository.save(job);
        }
        Map<Long, Document> documentsById;
        try {
            documentsById = documentsById(job.documentIds());
        } catch (RuntimeException exception) {
            job.fail("작업 대상 문서를 읽지 못했습니다: " + exception.getMessage());
            aiJobRepository.save(job);
            return;
        }
        List<AiJob.DocumentParseResult> documentResults = new ArrayList<>();
        for (Long documentId : job.documentIds()) {
            AiJob currentJob = aiJobRepository.findById(job.id()).orElse(job);
            if (currentJob.status() == com.ajt.backend.domain.document.model.AiJobStatus.CANCELLED) {
                break;
            }
            WikiDocumentChangeType changeType = plan.changeTypeOf(documentId);
            String removedParsedMarkdown = plan.removedParsedMarkdownOf(documentId);
            boolean removing = changeType == WikiDocumentChangeType.DOCUMENT_REMOVED;
            Document document = documentsById.get(documentId);
            if (document == null && !removing) {
                // 업로드 후 삭제된 문서다. 나머지 문서는 계속 처리하고 이 문서만 실패로 남긴다.
                AiJob.DocumentParseResult result = AiJob.DocumentParseResult.failed(
                        documentId,
                        null,  // 문서가 없어 이름을 알 수 없다
                        "문서를 찾을 수 없습니다.",
                        null
                );
                documentResults.add(result);
                currentJob.recordResult(result);
                aiJobRepository.save(currentJob);
                continue;
            }
            // 걷어내기는 문서 행이 하드 삭제된 뒤에도 실행된다(DR-014). 문서 엔티티를 읽지 않는다.
            AiJob.DocumentParseResult result = removing
                    ? removeDocument(job, documentId, document, removedParsedMarkdown)
                    : parseDocument(job, document, changeType, removedParsedMarkdown);
            documentResults.add(result);
            AiJob resultJob = aiJobRepository.findById(job.id()).orElse(job);
            resultJob.recordResult(result);
            aiJobRepository.save(resultJob);
        }
        if (aiJobRepository.findById(job.id()).orElse(job).status()
                == com.ajt.backend.domain.document.model.AiJobStatus.CANCELLED) {
            return;
        }
        job.finish(documentResults);
        aiJobRepository.save(job);
    }

    /**
     * 업로드 순서대로 처리하기 위해 문서를 ID로 찾을 수 있게 모읍니다.
     */
    private Map<Long, Document> documentsById(List<Long> documentIds) {
        return documentRepository.findAllById(documentIds)
                .stream()
                .collect(Collectors.toMap(Document::id, Function.identity()));
    }

    /**
     * 원본문서를 파싱해 Wiki에 반영합니다. 추가({@code DOCUMENT_ADDED})와 교체
     * ({@code DOCUMENT_REPLACED})가 이 경로를 함께 씁니다.
     *
     * <p>교체는 새 파일을 파싱해 보내면서 교체 전 본문({@code removedParsedMarkdown})도 함께
     * 실어, 옛 내용을 근거로 쓴 문단·각주를 새 내용에 맞게 고치도록 한다. 옛 본문은 파일을
     * 덮어쓰기 전에 읽어 둔 것이며 계획이 실어 온다.
     */
    private AiJob.DocumentParseResult parseDocument(
            AiJob job,
            Document document,
            WikiDocumentChangeType changeType,
            String removedParsedMarkdown
    ) {
        document.startParsing();
        documentRepository.save(document);
        String scopeKey = job.scopeKey();
        String parsedMarkdown;
        try {
            SourceParseResponse response = aiClient.parseSource(new SourceParseRequest(
                    UUID.randomUUID().toString(),
                    SourceType.WIKI,
                    String.valueOf(document.id()),
                    fileStorage.load(document.originalPath()),
                    document.originalFileName(),
                    document.mimeType()
            ));
            parsedMarkdown = response.parsedMarkdown();
            String parsedPath = storeParsedMarkdown(document, parsedMarkdown);
            // 수정(S15P11B106-174): 자료 선택 단계가 없어졌다. 참조 목록은 변환이 끝난 뒤
            //   completeProcessing 이 실제 영향받은 Wiki 로 채운다.
            document.completeParsing(parsedPath);
            documentRepository.save(document);
        } catch (AiClientException exception) {
            document.failParsing(failureReason(exception));
            documentRepository.save(document);
            return AiJob.DocumentParseResult.failed(
                    document.id(),
                    document.originalFileName(),
                    failureReason(exception),
                    exception.failureStage()
            );
        } catch (RuntimeException exception) {
            document.failParsing(exception.getMessage());
            documentRepository.save(document);
            return AiJob.DocumentParseResult.failed(document.id(), document.originalFileName(), exception.getMessage(), null);
        }
        return transformWiki(job, document, changeType, parsedMarkdown, removedParsedMarkdown);
    }

    /**
     * 문서가 이 범위에서 빠진 것을 Wiki에 반영합니다.
     * (FR-DOC-008 공개 범위 변경, DR-014 하드 삭제)
     *
     * <p>파싱하지 않고 <b>문서 엔티티도 읽지 않는다</b> — 필요한 것은 문서 ID와 계획이 실어 온
     * 옛 파싱 본문뿐이다. 범위 변경에서는 원본이 이미 새 범위로 옮겨졌고, 삭제에서는 문서 행과
     * 파일이 이미 사라졌다.
     *
     * <p>범위 변경일 때 문서의 처리 상태를 건드리지 않는 것도 같은 이유다: 같은 문서를 새 범위
     * 작업이 이어서 처리하므로 여기서 상태를 옮기면 두 작업이 한 문서 상태를 두고 다툰다.
     *
     * <p>변환 대상 범위는 문서의 현재 {@code scopeKey}가 아니라 작업의 {@code scopeKey}다.
     * 범위 변경 시점에 문서 행은 이미 새 범위를 가리키고 있다.
     */
    private AiJob.DocumentParseResult removeDocument(
            AiJob job,
            long documentId,
            Document document,
            String removedParsedMarkdown
    ) {
        String scopeKey = job.scopeKey();
        // 지우기 전에 이름을 잡아 둔다 — 결과에 남길 스냅샷이다 (S15P11B106-202).
        String fileName = document == null ? null : document.originalFileName();
        try {
            // 결정적 프리패스: 근거가 이 문서뿐인 Wiki 는 판단이 필요 없으므로 AI 전에 지운다.
            // 링크받는 페이지의 삭제를 에이전트에게 맡기면 지시 규칙이 충돌해 같은 read 를
            // 반복하다 호출 상한에서 죽는 데드락이 있었다 (2026-08-07 LangSmith 실측).
            var prune = transactionService.pruneFullyDependentWikis(documentId, scopeKey, fileName);
            if (prune.remainingReferencingWikis() == 0) {
                // 걷어낼 것이 남지 않았다 — AI 를 부르지 않고 끝낸다 (LLM 비용 0).
                finishDeletion(documentId);
                return AiJob.DocumentParseResult.succeeded(documentId, fileName, pruneSummary(prune));
            }
            var response = wikiTransformationService.requestForDocumentChange(
                    job.id(),
                    documentId,
                    scopeKey,
                    WikiDocumentChangeType.DOCUMENT_REMOVED,
                    null,
                    removedParsedMarkdown
            );
            WikiTransformationResult result = transactionService.applyRemovedDocument(
                    documentId, scopeKey, response);

            // 수정(S15P11B106-225): 예외가 없다고 걷어낸 것은 아니다. AI 가 Wiki 변경을 하나도
            //   주지 않으면 아무것도 반영되지 않는데, 그대로 두면 원본만 사라지고 Wiki 에 근거가
            //   남는다 — 원본이 없어 다시 걷어낼 수도, 무엇이 근거였는지 볼 수도 없는 상태다.
            //   근거로 삼던 Wiki 가 있었는데 변경이 0건이면 지우지 않고 실패로 남긴다.
            //   참조가 애초에 없었으면(파싱은 됐지만 Wiki 에 반영된 적 없는 문서) 걷어낼 것이
            //   없었던 것이므로 지금처럼 성공이다.
            if (result.referencingWikiCount() > 0 && response.wikiChanges().isEmpty()) {
                log.warn("걷어내기 변경이 비어 문서를 지우지 않습니다: documentId={}, scopeKey={}, 참조 Wiki={}건",
                        documentId, scopeKey, result.referencingWikiCount());
                markDeletionFailed(documentId, EMPTY_REMOVAL_REASON);
                return AiJob.DocumentParseResult.failed(
                        documentId, fileName, EMPTY_REMOVAL_REASON, null);
            }

            // 여기까지 왔으면 Wiki 에서 이 문서의 근거가 걷혔다. 이제 지운다.
            finishDeletion(documentId);
            String summary = prune.deletedWikiTitles().isEmpty()
                    ? result.summary()
                    : pruneSummary(prune) + "\n\n" + result.summary();
            return AiJob.DocumentParseResult.succeeded(documentId, fileName, summary);
        } catch (AiClientException exception) {
            markDeletionFailed(documentId, failureReason(exception));
            return AiJob.DocumentParseResult.failed(
                    documentId,
                    fileName,
                    failureReason(exception),
                    exception.failureStage()
            );
        } catch (RuntimeException exception) {
            markDeletionFailed(documentId, exception.getMessage());
            return AiJob.DocumentParseResult.failed(documentId, fileName, exception.getMessage(), null);
        }
    }

    /**
     * 걷어내기가 끝난 문서의 행과 파일을 지웁니다(S15P11B106-195, DR-014 하드 삭제).
     *
     * <p>범위 변경 재처리도 이 경로를 쓰지만 그때 문서는 {@code DELETING}이 아니다 — 이미 새 범위로
     * 옮겨져 그쪽 작업이 상태를 관리한다. 그래서 {@code DELETING}일 때만 지운다.
     */
    /**
     * 결정적 프리패스 결과를 관리자용 문장으로 만듭니다. AI 요약과 같은 자리(작업 요약)에 실립니다.
     */
    private static String pruneSummary(WikiTransformationApplier.PruneResult prune) {
        if (prune.deletedWikiTitles().isEmpty()) {
            if (prune.detachedStaleRefWikis() > 0) {
                return "이 문서를 본문에서 인용하는 위키가 없어 위키 "
                        + prune.detachedStaleRefWikis()
                        + "건의 근거 기록만 정리하고 문서를 삭제했습니다.";
            }
            return "이 문서를 근거로 삼는 위키가 없어 위키 변경 없이 문서를 삭제했습니다.";
        }
        StringBuilder summary = new StringBuilder()
                .append("근거가 이 문서뿐인 위키 ")
                .append(prune.deletedWikiTitles().size())
                .append("건을 삭제했습니다: ")
                .append(String.join(", ", prune.deletedWikiTitles()))
                .append(".");
        if (prune.flattenedLinkPages() > 0) {
            summary.append(" 삭제된 페이지로 향하는 링크가 있던 위키 ")
                    .append(prune.flattenedLinkPages())
                    .append("건에서 링크 표기를 정리했습니다(내용은 유지).");
        }
        if (prune.detachedStaleRefWikis() > 0) {
            summary.append(" 근거 기록만 있고 본문 인용이 없던 위키 ")
                    .append(prune.detachedStaleRefWikis())
                    .append("건의 참조를 정리했습니다.");
        }
        return summary.toString();
    }

    private void finishDeletion(long documentId) {
        documentRepository.findById(documentId).ifPresent(document -> {
            if (document.status() != DocumentStatus.DELETING) {
                return;
            }
            String originalPath = document.originalPath();
            String parsedPath = document.parsedPath();
            documentRepository.delete(document);
            documentRepository.flush();
            deleteQuietly(originalPath);
            deleteQuietly(parsedPath);
        });
    }

    /**
     * 걷어내기에 실패한 삭제를 실패로 남깁니다. 행·파일은 그대로 두어 관리자가 다시 삭제할 수 있다.
     */
    private void markDeletionFailed(long documentId, String failureReason) {
        documentRepository.findById(documentId).ifPresent(document -> {
            if (document.status() != DocumentStatus.DELETING) {
                return;
            }
            document.failDeleting(failureReason);
            documentRepository.save(document);
        });
    }

    private void deleteQuietly(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        try {
            fileStorage.delete(storedPath);
        } catch (IOException exception) {
            log.warn("원본문서 파일 삭제 실패(무시하고 진행): {}", storedPath);
        }
    }

    /**
     * 파싱된 문서를 Wiki로 변환하고 결과를 반영합니다.
     * 변환이 실패해도 이 문서만 실패로 남기고 작업의 다음 문서는 계속 처리합니다.
     */
    private AiJob.DocumentParseResult transformWiki(
            AiJob job,
            Document document,
            WikiDocumentChangeType changeType,
            String parsedMarkdown,
            String removedParsedMarkdown
    ) {
        String scopeKey = job.scopeKey();
        try {
            var response = wikiTransformationService.requestForDocumentChange(
                    job.id(),
                    document.id(),
                    scopeKey,
                    changeType,
                    parsedMarkdown,
                    removedParsedMarkdown
            );
            // 교체는 문서가 그대로 남으므로 추가와 같은 반영 경로를 쓴다 — 새 근거를 documentRefs에 더한다.
            WikiTransformationResult result = transactionService.applyAddedDocument(
                    document.id(), scopeKey, response);
            // 반영 트랜잭션은 별도로 조회한 엔티티를 완료 처리한다. 이 인스턴스도 작업 결과를
            // 조립할 때 일관된 상태를 보도록만 맞추며, 여기서 다시 저장하지는 않는다.
            document.completeProcessing(result.affectedWikiIds());
            return AiJob.DocumentParseResult.succeeded(document.id(), document.originalFileName(), result.summary());
        } catch (AiClientException exception) {
            document.failProcessing(failureReason(exception));
            documentRepository.save(document);
            return AiJob.DocumentParseResult.failed(
                    document.id(),
                    document.originalFileName(),
                    failureReason(exception),
                    exception.failureStage()
            );
        } catch (RuntimeException exception) {
            document.failProcessing(exception.getMessage());
            documentRepository.save(document);
            return AiJob.DocumentParseResult.failed(document.id(), document.originalFileName(), exception.getMessage(), null);
        }
    }

    private String storeParsedMarkdown(Document document, String parsedMarkdown) {
        try {
            return fileStorage.storeParsedMarkdown(document.scopeKey(), document.id(), parsedMarkdown);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String failureReason(AiClientException exception) {
        if (exception.upstreamMessage() != null && !exception.upstreamMessage().isBlank()) {
            return exception.upstreamMessage();
        }
        return exception.failureType().name();
    }
}
