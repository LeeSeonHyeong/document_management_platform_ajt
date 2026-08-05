package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("관리자 지시 근거 문서 생성")
class AdminInstructionDocumentServiceTest {

    private static final long WIKI_ID = 41L;
    private static final long ADMIN_MEMBER_ID = 73L;
    private static final long CATEGORY_ID = 7L;
    private static final long DOCUMENT_ID = 817L;

    @Mock
    private WikiRepository wikiRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentCategoryRepository documentCategoryRepository;
    @Mock
    private DocumentFileStorage fileStorage;

    private AdminInstructionDocumentService service;
    private Wiki wiki;

    @BeforeEach
    void setUp() throws Exception {
        service = new AdminInstructionDocumentService(
                wikiRepository,
                documentRepository,
                documentCategoryRepository,
                fileStorage
        );
        wiki = Wiki.create("ALL", 3L, "휴가 정책");
        assignId(wiki, WIKI_ID);
        given(wikiRepository.findById(WIKI_ID)).willReturn(Optional.of(wiki));
    }

    @Test
    @DisplayName("기존 관리자 지시 카테고리를 재사용해 완료된 Markdown 근거 문서를 연결한다")
    void createsCompletedMarkdownDocumentUsingExistingCategory() throws Exception {
        DocumentCategory category = DocumentCategory.create("ALL", "관리자 지시", null);
        assignId(category, CATEGORY_ID);
        given(documentCategoryRepository.findByScopeKeyAndName("ALL", "관리자 지시"))
                .willReturn(Optional.of(category));
        given(documentRepository.save(any(Document.class))).willAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            assignId(document, DOCUMENT_ID);
            return document;
        });
        given(fileStorage.storeSynthesizedOriginal(eq("ALL"), eq(DOCUMENT_ID), anyString()))
                .willReturn("wiki/ALL/sources/817/original.md");
        given(fileStorage.storeParsedMarkdown(eq("ALL"), eq(DOCUMENT_ID), anyString()))
                .willReturn("wiki/ALL/sources/817/parsed.md");
        String instruction = "휴가 일수를 4일로 변경\n둘째 줄은 그대로";

        long createdId = service.create(WIKI_ID, ADMIN_MEMBER_ID, instruction);

        assertThat(createdId).isEqualTo(DOCUMENT_ID);
        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(documentCaptor.capture());
        Document document = documentCaptor.getValue();
        assertThat(document.documentCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(document.scopeKey()).isEqualTo("ALL");
        assertThat(document.mimeType()).isEqualTo("text/markdown");
        assertThat(document.originalPath()).isEqualTo("wiki/ALL/sources/817/original.md");
        assertThat(document.parsedPath()).isEqualTo("wiki/ALL/sources/817/parsed.md");
        assertThat(document.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(document.documentWikiRefs()).containsExactly(WIKI_ID);
        assertThat(wiki.documentRefs()).containsExactly(DOCUMENT_ID);
        assertThat(document.fileSize()).isPositive();

        Pattern fileNamePattern = Pattern.compile("관리자지시-(\\d{8}-\\d{4})-w41\\.md");
        Matcher fileNameMatcher = fileNamePattern.matcher(document.originalFileName());
        assertThat(fileNameMatcher.matches()).isTrue();

        ArgumentCaptor<String> originalMarkdownCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> parsedMarkdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileStorage).storeSynthesizedOriginal(
                eq("ALL"),
                eq(DOCUMENT_ID),
                originalMarkdownCaptor.capture()
        );
        verify(fileStorage).storeParsedMarkdown(
                eq("ALL"),
                eq(DOCUMENT_ID),
                parsedMarkdownCaptor.capture()
        );
        String markdown = originalMarkdownCaptor.getValue();
        assertThat(parsedMarkdownCaptor.getValue()).isEqualTo(markdown);

        Matcher markdownTimestampMatcher = Pattern.compile(
                "- 일시: (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}) \\(KST\\)"
        ).matcher(markdown);
        assertThat(markdownTimestampMatcher.find()).isTrue();
        String markdownTimestamp = markdownTimestampMatcher.group(1);
        assertThat(fileNameMatcher.group(1))
                .isEqualTo(markdownTimestamp.replace("-", "").replace(" ", "-").replace(":", ""));
        assertThat(markdown).isEqualTo("""
                # 관리자 지시

                - 일시: %s (KST)
                - 지시자: member_id=73
                - 대상 위키: 휴가 정책 (wiki_id=41)

                ## 지시 내용

                > 휴가 일수를 4일로 변경
                둘째 줄은 그대로
                """.formatted(markdownTimestamp));
        assertThat(document.fileSize()).isEqualTo(markdown.getBytes(StandardCharsets.UTF_8).length);
        verify(documentCategoryRepository, never()).save(any(DocumentCategory.class));
    }

    @Test
    @DisplayName("관리자 지시 카테고리가 없으면 런타임 생성 없이 실패한다")
    void failsWithoutCreatingMissingCategoryAtRuntime() {
        given(documentCategoryRepository.findByScopeKeyAndName("ALL", "관리자 지시"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(WIKI_ID, ADMIN_MEMBER_ID, "휴가 일수를 4일로 변경"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(documentCategoryRepository, never()).save(any(DocumentCategory.class));
        verify(documentRepository, never()).save(any(Document.class));
        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("파싱 파일 저장이 실패하면 부분 저장된 합성 원문과 파싱 경로를 정리한다")
    void cleansPartiallyStoredFilesWhenParsedWriteFails() throws Exception {
        prepareSuccessfulPersistence();
        given(fileStorage.storeSynthesizedOriginal(eq("ALL"), eq(DOCUMENT_ID), anyString()))
                .willReturn("wiki/ALL/sources/817/original.md");
        given(fileStorage.storeParsedMarkdown(eq("ALL"), eq(DOCUMENT_ID), anyString()))
                .willThrow(new IOException("parsed write failed"));

        assertThatThrownBy(() -> service.create(WIKI_ID, ADMIN_MEMBER_ID, "휴가 일수를 4일로 변경"))
                .isInstanceOf(UncheckedIOException.class);

        verify(fileStorage).delete("wiki/ALL/sources/817/original.md");
        verify(fileStorage).delete("wiki/ALL/sources/817/parsed.md");
    }

    @Test
    @DisplayName("DB 트랜잭션이 롤백되면 먼저 쓴 합성 원문과 파싱 파일을 정리한다")
    void cleansStoredFilesWhenTransactionRollsBack() throws Exception {
        prepareSuccessfulPersistence();
        given(fileStorage.storeSynthesizedOriginal(eq("ALL"), eq(DOCUMENT_ID), anyString()))
                .willReturn("wiki/ALL/sources/817/original.md");
        given(fileStorage.storeParsedMarkdown(eq("ALL"), eq(DOCUMENT_ID), anyString()))
                .willReturn("wiki/ALL/sources/817/parsed.md");

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.create(WIKI_ID, ADMIN_MEMBER_ID, "휴가 일수를 4일로 변경");
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(fileStorage).delete("wiki/ALL/sources/817/original.md");
        verify(fileStorage).delete("wiki/ALL/sources/817/parsed.md");
    }

    private void prepareSuccessfulPersistence() throws Exception {
        DocumentCategory category = DocumentCategory.create("ALL", "관리자 지시", null);
        assignId(category, CATEGORY_ID);
        given(documentCategoryRepository.findByScopeKeyAndName("ALL", "관리자 지시"))
                .willReturn(Optional.of(category));
        given(documentRepository.save(any(Document.class))).willAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            assignId(document, DOCUMENT_ID);
            return document;
        });
    }

    private static void assignId(Object target, long id) throws ReflectiveOperationException {
        Field idField = target.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
    }
}
