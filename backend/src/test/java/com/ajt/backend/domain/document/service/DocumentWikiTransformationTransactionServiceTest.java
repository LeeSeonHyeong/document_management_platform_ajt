package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.wiki.service.WikiTransformationApplier;
import com.ajt.backend.global.ai.client.WikiTransformationResponse;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("문서별 Wiki 변환 반영 트랜잭션")
class DocumentWikiTransformationTransactionServiceTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final WikiTransformationApplier applier = mock(WikiTransformationApplier.class);

    @Test
    @DisplayName("Wiki 변경을 반영한 뒤 같은 문서를 완료 처리한다")
    void appliesWikiChangesThenCompletesTheDocument() throws Exception {
        Document document = processingDocument(15L);
        WikiTransformationResponse response = response("반영 완료");
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(applier.apply("ALL", 15L, response)).willReturn(List.of(101L));
        DocumentWikiTransformationTransactionService service =
                new DocumentWikiTransformationTransactionService(documentRepository, applier);

        DocumentWikiTransformationTransactionService.WikiTransformationResult result =
                service.applyAddedDocument(15L, "ALL", response);

        assertThat(document.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(document.documentWikiRefs()).containsExactly(101L);
        assertThat(result.affectedWikiIds()).containsExactly(101L);
        assertThat(result.summary()).isEqualTo("반영 완료");
    }

    @Test
    @DisplayName("문서 반영은 새 트랜잭션에서 수행한다")
    void appliesInNewTransaction() throws Exception {
        Method method = DocumentWikiTransformationTransactionService.class.getMethod(
                "applyAddedDocument", long.class, String.class, WikiTransformationResponse.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private Document processingDocument(long id) throws ReflectiveOperationException {
        Document document = Document.uploaded(
                10L, 7L, "ALL", "source.md", "wiki/ALL/sources/15/original.md", "text/markdown", 100L);
        assignId(document, id);
        document.startParsing();
        document.completeParsing("wiki/ALL/sources/15/parsed.md", List.of());
        return document;
    }

    private WikiTransformationResponse response(String summary) {
        return new WikiTransformationResponse(summary, List.of(), List.of(), List.of(), List.of());
    }

    private void assignId(Document document, long id) throws ReflectiveOperationException {
        Field idField = Document.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(document, id);
    }
}
