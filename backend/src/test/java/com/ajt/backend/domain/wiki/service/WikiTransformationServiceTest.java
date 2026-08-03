package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.global.ai.capability.WikiCapabilityService;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.WikiDocumentChangeType;
import com.ajt.backend.global.ai.client.WikiTransformationRequest;
import com.ajt.backend.global.ai.client.WikiTransformationResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

@DisplayName("FastAPI Wiki 변환 호출 — 문맥을 밀어 보내지 않는다")
class WikiTransformationServiceTest {

    private static final String SCOPE_KEY = "ALL";

    private final AiClient aiClient = mock(AiClient.class);
    private final WikiScopeRepository wikiScopeRepository = mock(WikiScopeRepository.class);
    private final WikiCapabilityService wikiCapabilityService = mock(WikiCapabilityService.class);
    private final WikiTransformationApplier applier = mock(WikiTransformationApplier.class);
    private final WikiTransformationService service = new WikiTransformationService(
            aiClient,
            wikiScopeRepository,
            wikiCapabilityService
    );

    @BeforeEach
    void setUpCapability() {
        given(wikiScopeRepository.findById(SCOPE_KEY)).willReturn(Optional.of(WikiScope.all()));
        given(wikiCapabilityService.issue(eq(SCOPE_KEY), eq(0L), any())).willReturn("capability");
    }

    @Test
    @DisplayName("변환 요청은 AI 응답만 반환하고 Wiki 반영을 수행하지 않는다")
    void requestsTransformationWithoutApplyingIt() {
        WikiTransformationResponse response = emptyResponse();
        given(aiClient.transformWiki(any(WikiTransformationRequest.class))).willReturn(response);

        assertThat(service.requestForAddedDocument(42L, 15L, SCOPE_KEY, "# 취업 규칙"))
                .isSameAs(response);

        Mockito.verifyNoInteractions(applier);
    }

    @Test
    @DisplayName("요청은 작업 번호·파싱 본문과 조회 권한으로 끝난다")
    void sendsOnlyIdentifiersAndQueryPermission() {
        given(aiClient.transformWiki(any(WikiTransformationRequest.class))).willReturn(emptyResponse());

        service.requestForAddedDocument(42L, 15L, SCOPE_KEY, "# 취업 규칙");

        WikiTransformationRequest request = capturedRequest();
        assertThat(request.jobId()).isEqualTo("42");
        assertThat(request.documentId()).isEqualTo("15");
        assertThat(request.scopeKey()).isEqualTo(SCOPE_KEY);
        assertThat(request.changeType()).isEqualTo(WikiDocumentChangeType.DOCUMENT_ADDED);
        assertThat(request.parsedMarkdown()).isEqualTo("# 취업 규칙");
        assertThat(request.removedParsedMarkdown()).isNull();
        assertThat(request.wikiCapability()).isEqualTo("capability");
        assertThat(request.scopeVersion()).isZero();
    }

    @Test
    @DisplayName("목차·카테고리·Wiki 본문을 읽지 않는다 — 에이전트가 조회 API로 읽는다")
    void readsNothingToBuildTheRequest() {
        given(aiClient.transformWiki(any(WikiTransformationRequest.class))).willReturn(emptyResponse());

        service.requestForAddedDocument(42L, 15L, SCOPE_KEY, "# 취업 규칙");

        // 조회에 쓰던 협력자가 생성자에서 아예 빠졌으므로, 남은 것은 범위 버전 조회뿐이다.
        Mockito.verify(wikiScopeRepository).findById(SCOPE_KEY);
        Mockito.verifyNoMoreInteractions(wikiScopeRepository);
    }

    @Test
    @DisplayName("허가값을 발급해 보내고 호출이 끝나면 회수한다")
    void issuesAndRevokesTheCapability() {
        given(aiClient.transformWiki(any(WikiTransformationRequest.class))).willReturn(emptyResponse());

        service.requestForAddedDocument(42L, 15L, SCOPE_KEY, "# 취업 규칙");

        InOrder inOrder = Mockito.inOrder(wikiCapabilityService, aiClient);
        inOrder.verify(wikiCapabilityService).issue(eq(SCOPE_KEY), eq(0L), any(Duration.class));
        inOrder.verify(aiClient).transformWiki(any(WikiTransformationRequest.class));
        inOrder.verify(wikiCapabilityService).revoke("capability");
    }

    @Test
    @DisplayName("AI 호출이 실패해도 허가값을 회수한다 — 남겨 두면 만료까지 살아 있다")
    void revokesTheCapabilityEvenWhenTheCallFails() {
        given(aiClient.transformWiki(any(WikiTransformationRequest.class)))
                .willThrow(new IllegalStateException("boom"));

        try {
            service.requestForAddedDocument(42L, 15L, SCOPE_KEY, "# 취업 규칙");
        } catch (IllegalStateException ignored) {
            // 이 테스트가 보는 것은 회수 여부다.
        }

        Mockito.verify(wikiCapabilityService).revoke("capability");
    }

    @Test
    @DisplayName("걷어내기는 제거 전 본문을 실어 보낸다 — 새 파싱 본문 자리는 빈 문자열이다")
    void removedDocumentCarriesTheRemovedBody() {
        given(aiClient.transformWiki(any(WikiTransformationRequest.class))).willReturn(emptyResponse());

        service.requestForDocumentChange(42L, 15L, SCOPE_KEY,
                WikiDocumentChangeType.DOCUMENT_REMOVED, null, "# 옛 취업규칙");

        WikiTransformationRequest request = capturedRequest();
        assertThat(request.changeType()).isEqualTo(WikiDocumentChangeType.DOCUMENT_REMOVED);
        // 이 단언이 예전에는 null 이었고, 그것이 실서버 삭제를 전부 400 으로 죽였다
        // (S15P11B106-194). 계약이 이 자리를 문자열로 정의하므로 null 을 실어 보내면
        // FastAPI 가 요청 전체를 거부한다 — 잡았어야 할 테스트가 버그를 고정하고 있었다.
        assertThat(request.parsedMarkdown()).isEmpty();
        assertThat(request.removedParsedMarkdown()).isEqualTo("# 옛 취업규칙");
    }

    private WikiTransformationRequest capturedRequest() {
        ArgumentCaptor<WikiTransformationRequest> captor =
                ArgumentCaptor.forClass(WikiTransformationRequest.class);
        Mockito.verify(aiClient).transformWiki(captor.capture());
        return captor.getValue();
    }

    private WikiTransformationResponse emptyResponse() {
        return new WikiTransformationResponse("요약", List.of(), List.of(), List.of(), List.of());
    }
}
