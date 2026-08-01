package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
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
import org.springframework.stereotype.Component;

@Component
public class DocumentParseWorker {

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
                    ? removeDocument(job, documentId, removedParsedMarkdown)
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
                    failureReason(exception),
                    exception.failureStage()
            );
        } catch (RuntimeException exception) {
            document.failParsing(exception.getMessage());
            documentRepository.save(document);
            return AiJob.DocumentParseResult.failed(document.id(), exception.getMessage(), null);
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
            String removedParsedMarkdown
    ) {
        String scopeKey = job.scopeKey();
        try {
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
            return AiJob.DocumentParseResult.succeeded(documentId, result.summary());
        } catch (AiClientException exception) {
            return AiJob.DocumentParseResult.failed(
                    documentId,
                    failureReason(exception),
                    exception.failureStage()
            );
        } catch (RuntimeException exception) {
            return AiJob.DocumentParseResult.failed(documentId, exception.getMessage(), null);
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
            return AiJob.DocumentParseResult.succeeded(document.id(), result.summary());
        } catch (AiClientException exception) {
            document.failProcessing(failureReason(exception));
            documentRepository.save(document);
            return AiJob.DocumentParseResult.failed(
                    document.id(),
                    failureReason(exception),
                    exception.failureStage()
            );
        } catch (RuntimeException exception) {
            document.failProcessing(exception.getMessage());
            documentRepository.save(document);
            return AiJob.DocumentParseResult.failed(document.id(), exception.getMessage(), null);
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
