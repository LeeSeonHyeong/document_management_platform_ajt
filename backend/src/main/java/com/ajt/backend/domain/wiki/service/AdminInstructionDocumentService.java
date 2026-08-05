package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AdminInstructionDocumentService {

    private static final Logger log = LoggerFactory.getLogger(AdminInstructionDocumentService.class);
    private static final String CATEGORY_NAME = "관리자 지시";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter MARKDOWN_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WikiRepository wikiRepository;
    private final DocumentRepository documentRepository;
    private final DocumentCategoryRepository documentCategoryRepository;
    private final DocumentFileStorage fileStorage;

    public AdminInstructionDocumentService(
            WikiRepository wikiRepository,
            DocumentRepository documentRepository,
            DocumentCategoryRepository documentCategoryRepository,
            DocumentFileStorage fileStorage
    ) {
        this.wikiRepository = wikiRepository;
        this.documentRepository = documentRepository;
        this.documentCategoryRepository = documentCategoryRepository;
        this.fileStorage = fileStorage;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long create(long wikiId, long adminMemberId, String instruction) {
        Wiki wiki = wikiRepository.findById(wikiId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        DocumentCategory category = documentCategoryRepository
                .findByScopeKeyAndName(wiki.scopeKey(), CATEGORY_NAME)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        var timestamp = Instant.now().atZone(KST);
        String markdownTimestamp = MARKDOWN_TIMESTAMP_FORMATTER.format(timestamp);
        String fileName = "관리자지시-" + FILE_TIMESTAMP_FORMATTER.format(timestamp) + "-w" + wikiId + ".md";
        String markdown = """
                # 관리자 지시

                - 일시: %s (KST)
                - 지시자: member_id=%d
                - 대상 위키: %s (wiki_id=%d)

                ## 지시 내용

                > %s
                """.formatted(markdownTimestamp, adminMemberId, wiki.title(), wikiId, instruction);

        Document document = documentRepository.save(Document.uploaded(
                adminMemberId,
                category.id(),
                wiki.scopeKey(),
                fileName,
                "pending",
                "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8).length
        ));

        List<String> storedPaths = List.of(
                "wiki/" + wiki.scopeKey() + "/sources/" + document.id() + "/original.md",
                "wiki/" + wiki.scopeKey() + "/sources/" + document.id() + "/parsed.md"
        );
        boolean rollbackCleanupRegistered = registerRollbackCleanup(storedPaths);

        try {
            String originalPath = fileStorage.storeSynthesizedOriginal(wiki.scopeKey(), document.id(), markdown);
            String parsedPath = fileStorage.storeParsedMarkdown(wiki.scopeKey(), document.id(), markdown);
            document.changeOriginalPath(originalPath);
            document.startParsing();
            document.completeParsing(parsedPath);
            document.completeProcessing(List.of(wiki.id()));
            wiki.addDocumentRefs(List.of(document.id()));
            return document.id();
        } catch (IOException exception) {
            if (!rollbackCleanupRegistered) {
                deleteQuietly(storedPaths);
            }
            throw new UncheckedIOException(exception);
        } catch (RuntimeException exception) {
            if (!rollbackCleanupRegistered) {
                deleteQuietly(storedPaths);
            }
            throw exception;
        }
    }

    private boolean registerRollbackCleanup(List<String> storedPaths) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(storedPaths);
                }
            }
        });
        return true;
    }

    private void deleteQuietly(List<String> storedPaths) {
        for (String storedPath : storedPaths) {
            try {
                fileStorage.delete(storedPath);
            } catch (IOException cleanupException) {
                log.warn("롤백된 관리자 지시 파일을 정리하지 못했습니다: {}", storedPath, cleanupException);
            }
        }
    }
}
