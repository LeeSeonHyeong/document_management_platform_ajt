package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.document.api.AiJobStartResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 작업 시작 서비스")
class AiJobStartServiceTest {

    private final CurrentMemberProvider currentMemberProvider = mock(CurrentMemberProvider.class);
    private final AiJobRepository aiJobRepository = mock(AiJobRepository.class);
    private final DocumentParseJobLauncher parseJobLauncher = mock(DocumentParseJobLauncher.class);
    private final AiJobStartService service = new AiJobStartService(
            currentMemberProvider, aiJobRepository, parseJobLauncher);

    @Test
    @DisplayName("관리자가 대기 작업을 시작하면 처리 중으로 전환하고 워커를 띄운다")
    void startsWaitingJob() throws Exception {
        givenAdmin();
        AiJob job = waitingJob(42L);
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));

        AiJobStartResponse response = service.start(42L);

        assertThat(response.jobId()).isEqualTo("42");
        assertThat(response.status()).isEqualTo("processing");
        assertThat(job.status()).isEqualTo(AiJobStatus.PROCESSING);
        assertThat(job.startedAt()).isNotNull();
        verify(aiJobRepository).save(job);
        verify(parseJobLauncher).launch(any(AiJob.class), any(DocumentReprocessPlan.class));
    }

    @Test
    @DisplayName("이미 시작된 작업은 다시 시작할 수 없다")
    void rejectsAlreadyStartedJob() throws Exception {
        givenAdmin();
        AiJob job = waitingJob(42L);
        job.start();
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));

        assertThatThrownBy(() -> service.start(42L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        verify(parseJobLauncher, never()).launch(any(AiJob.class), any(DocumentReprocessPlan.class));
    }

    @Test
    @DisplayName("존재하지 않는 작업은 시작할 수 없다")
    void rejectsUnknownJob() {
        givenAdmin();
        given(aiJobRepository.findById(42L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(42L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_JOB_NOT_FOUND);
    }

    @Test
    @DisplayName("관리자가 아니면 작업을 시작할 수 없다")
    void rejectsNonAdmin() {
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(10L, CurrentMemberRole.EMPLOYEE));

        assertThatThrownBy(() -> service.start(42L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(parseJobLauncher, never()).launch(any(AiJob.class), any(DocumentReprocessPlan.class));
    }

    private void givenAdmin() {
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
    }

    private AiJob waitingJob(long jobId) throws ReflectiveOperationException {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(15L, 16L));
        Field idField = AiJob.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(job, jobId);
        return job;
    }
}
