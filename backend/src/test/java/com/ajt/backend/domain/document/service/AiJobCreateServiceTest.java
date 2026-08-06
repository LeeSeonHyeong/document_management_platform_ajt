package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.document.api.AiJobCreateResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
import com.ajt.backend.domain.member.ScopeAccess;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 작업 생성 서비스(S15P11B106-276)")
class AiJobCreateServiceTest {

    private final CurrentMemberProvider currentMemberProvider = mock(CurrentMemberProvider.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final AiJobRepository aiJobRepository = mock(AiJobRepository.class);
    private final DocumentParseJobLauncher parseJobLauncher = mock(DocumentParseJobLauncher.class);
    private final DepartmentScopePolicy departmentScopePolicy = superAdminScopePolicy();
    private final AiJobCreateService service = new AiJobCreateService(
            currentMemberProvider,
            documentRepository,
            aiJobRepository,
            parseJobLauncher,
            departmentScopePolicy
    );

    private static DepartmentScopePolicy superAdminScopePolicy() {
        DepartmentScopePolicy policy = mock(DepartmentScopePolicy.class);
        given(policy.resolve(anyLong())).willReturn(ScopeAccess.superAdmin());
        return policy;
    }

    @Test
    @DisplayName("확정된 문서들로 작업을 만들고 바로 시작한다")
    void createsAndStartsJob() throws Exception {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        Document first = classifiedDocument(15L, "D1");
        Document second = classifiedDocument(16L, "D1");
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(first, second));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            if (job.id() == null) {
                assignId(job, 42L);
            }
            return job;
        });

        AiJobCreateResponse response = service.create(List.of(15L, 16L));

        assertThat(response.jobs()).hasSize(1);
        assertThat(response.jobs().getFirst().jobId()).isEqualTo("42");
        assertThat(response.jobs().getFirst().scopeKey()).isEqualTo("D1");
        assertThat(response.jobs().getFirst().status()).isEqualTo("processing");
        assertThat(response.jobs().getFirst().documentIds()).containsExactly("15", "16");
        verify(parseJobLauncher).launch(any(AiJob.class), any(DocumentReprocessPlan.class));
    }

    @Test
    @DisplayName("공개 범위가 섞여 있으면 범위별로 작업을 나눠 만든다")
    void splitsJobsByScope() throws Exception {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findAllById(List.of(15L, 16L)))
                .willReturn(List.of(classifiedDocument(15L, "D1"), classifiedDocument(16L, "ALL")));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> invocation.getArgument(0));

        AiJobCreateResponse response = service.create(List.of(15L, 16L));

        assertThat(response.jobs()).hasSize(2);
        assertThat(response.jobs()).extracting("scopeKey").containsExactly("D1", "ALL");
        verify(parseJobLauncher, times(2)).launch(any(AiJob.class), any(DocumentReprocessPlan.class));
    }

    @Test
    @DisplayName("카테고리가 없는 문서가 섞여 있으면 작업을 만들지 않는다")
    void rejectsUnclassifiedDocument() throws Exception {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        Document unclassified = document(15L, null, "ALL");
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(unclassified));

        assertThatThrownBy(() -> service.create(List.of(15L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(aiJobRepository, never()).save(any(AiJob.class));
        verify(parseJobLauncher, never()).launch(any(AiJob.class), any(DocumentReprocessPlan.class));
    }

    @Test
    @DisplayName("이미 처리된 문서는 다시 넣을 수 없다")
    void rejectsAlreadyProcessedDocument() throws Exception {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        Document document = classifiedDocument(15L, "ALL");
        document.startParsing();
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(document));

        assertThatThrownBy(() -> service.create(List.of(15L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    @DisplayName("없는 문서 ID가 섞여 있으면 404다")
    void rejectsMissingDocument() throws Exception {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findAllById(List.of(15L, 99L)))
                .willReturn(List.of(classifiedDocument(15L, "ALL")));

        assertThatThrownBy(() -> service.create(List.of(15L, 99L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("부서관리자는 담당 부서 밖 문서로 작업을 만들 수 없다(존재를 숨겨 404)")
    void departmentManagerCannotUseOtherScope() throws Exception {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(departmentScopePolicy.resolve(10L)).willReturn(ScopeAccess.departmentManager(2L));
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(classifiedDocument(15L, "D1")));

        assertThatThrownBy(() -> service.create(List.of(15L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("관리자가 아니면 작업을 만들 수 없다")
    void rejectsNonAdmin() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.EMPLOYEE));

        assertThatThrownBy(() -> service.create(List.of(15L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("documentIds가 비어 있으면 400이다")
    void rejectsEmptyDocumentIds() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));

        assertThatThrownBy(() -> service.create(List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    private Document classifiedDocument(long id, String scopeKey) throws ReflectiveOperationException {
        return document(id, 7L, scopeKey);
    }

    private Document document(long id, Long categoryId, String scopeKey) throws ReflectiveOperationException {
        Document document = Document.uploaded(
                10L,
                categoryId,
                scopeKey,
                "rule.md",
                "wiki/" + scopeKey + "/sources/" + id + "/original.md",
                "text/markdown",
                1024L
        );
        assignId(document, id);
        return document;
    }

    private void assignId(Object target, long id) throws ReflectiveOperationException {
        Field idField = target.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
    }

    @Test
    @DisplayName("만들어진 작업은 WAITING으로 저장된 뒤 PROCESSING으로 시작된다")
    void startsCreatedJob() throws Exception {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(classifiedDocument(15L, "ALL")));
        List<AiJobStatus> savedStatuses = new ArrayList<>();
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            savedStatuses.add(job.status());
            return job;
        });

        service.create(List.of(15L));

        // 생성(WAITING) → 시작(PROCESSING) 두 번 저장된다. AiJobStartService와 같은 전이를 탄다.
        assertThat(savedStatuses).containsExactly(AiJobStatus.WAITING, AiJobStatus.PROCESSING);
    }
}
