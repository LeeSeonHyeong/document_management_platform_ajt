package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
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
    private final WikiRepository wikiRepository = mock(WikiRepository.class);

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
    @DisplayName("문서 삭제로 수정된 살아 있는 Wiki도 ID·제목 스냅샷을 남긴다")
    void snapshotsSurvivingWikisChangedByRemoval() throws Exception {
        Wiki surviving = Wiki.create("ALL", 7L, "취업규칙");
        assignId(surviving, 101L);
        WikiTransformationResponse response = response("인용 근거를 걷어냈습니다.");
        given(applier.applyRemovedDocument("ALL", 15L, response))
                .willReturn(new WikiTransformationApplier.RemovedDocumentResult(List.of(101L), 1));
        given(wikiRepository.findAllById(List.of(101L))).willReturn(List.of(surviving));
        DocumentWikiTransformationTransactionService service =
                new DocumentWikiTransformationTransactionService(documentRepository, applier, wikiRepository);

        DocumentWikiTransformationTransactionService.WikiTransformationResult result =
                service.applyRemovedDocument(15L, "ALL", response);

        assertThat(result.affectedWikis())
                .containsExactly(new AiJob.AffectedWiki(101L, "취업규칙"));
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

    private void assignId(Wiki wiki, long id) throws ReflectiveOperationException {
        Field idField = Wiki.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(wiki, id);
    }
}
