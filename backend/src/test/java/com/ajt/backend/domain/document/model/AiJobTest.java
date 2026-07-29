package com.ajt.backend.domain.document.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 원본문서 처리 작업")
class AiJobTest {

    @Test
    @DisplayName("업로드 작업은 대기 상태와 문서 ID 목록을 가진다")
    void createsWaitingJob() {
        AiJob job = AiJob.waiting(10L, "D1-D2", "D1-D2/jobs/1", List.of(3L, 4L));

        assertThat(job.requesterId()).isEqualTo(10L);
        assertThat(job.scopeKey()).isEqualTo("D1-D2");
        assertThat(job.workspacePath()).isEqualTo("D1-D2/jobs/1");
        assertThat(job.status()).isEqualTo(AiJobStatus.WAITING);
        assertThat(job.documentIds()).containsExactly(3L, 4L);
    }

    @Test
    @DisplayName("대기 작업은 처리 상태로 시작할 수 있다")
    void startsWaitingJob() {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(3L));

        job.start();

        assertThat(job.status()).isEqualTo(AiJobStatus.PROCESSING);
        assertThat(job.startedAt()).isNotNull();
    }

    @Test
    @DisplayName("대기 상태가 아니면 작업을 시작할 수 없다")
    void rejectsStartOutsideWaitingState() {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(3L));
        job.start();

        assertThatThrownBy(job::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WAITING 상태의 작업만 시작할 수 있습니다.");
    }

    @Test
    @DisplayName("문서가 하나라도 성공하면 완료로 끝난다")
    void completesWhenAnyDocumentSucceeds() {
        AiJob job = processingJob();

        job.finish(List.of(
                AiJob.DocumentParseResult.succeeded(3L, "휴가 규정을 Wiki에 반영했습니다."),
                AiJob.DocumentParseResult.failed(4L, "FastAPI 응답 시간이 초과되었습니다.", "agent_timeout")
        ));

        assertThat(job.status()).isEqualTo(AiJobStatus.COMPLETED);
        assertThat(job.failureReason()).isNull();
        assertThat(job.finishedAt()).isNotNull();
        assertThat(job.documentResults())
                .extracting(
                        AiJob.DocumentParseResult::documentId,
                        AiJob.DocumentParseResult::success,
                        AiJob.DocumentParseResult::summary,
                        AiJob.DocumentParseResult::failureStage
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3L, true, "휴가 규정을 Wiki에 반영했습니다.", null),
                        org.assertj.core.groups.Tuple.tuple(4L, false, null, "agent_timeout")
                );
    }

    @Test
    @DisplayName("문서가 전부 실패하면 실패로 끝나고 첫 실패 사유를 남긴다")
    void failsWhenEveryDocumentFails() {
        AiJob job = processingJob();

        job.finish(List.of(
                AiJob.DocumentParseResult.failed(3L, "파싱에 실패했습니다.", "context_load"),
                AiJob.DocumentParseResult.failed(4L, "변환에 실패했습니다.", "agent_error")
        ));

        assertThat(job.status()).isEqualTo(AiJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("파싱에 실패했습니다.");
        assertThat(job.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("실패 사유가 비어 있으면 기본 문구를 남긴다")
    void usesDefaultFailureReason() {
        AiJob job = processingJob();

        job.finish(List.of(AiJob.DocumentParseResult.failed(3L, null, null)));

        assertThat(job.failureReason()).isEqualTo("문서를 Wiki로 변환하지 못했습니다.");
    }

    @Test
    @DisplayName("문서 목록을 읽지 못하면 작업 자체를 실패로 끝낸다")
    void failsWholeJob() {
        AiJob job = processingJob();

        job.fail("작업 대상 문서를 읽지 못했습니다.");

        assertThat(job.status()).isEqualTo(AiJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("작업 대상 문서를 읽지 못했습니다.");
        assertThat(job.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("처리 중이 아닌 작업은 종료할 수 없다")
    void rejectsFinishOutsideProcessingState() {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(3L));

        assertThatThrownBy(() -> job.finish(List.of(AiJob.DocumentParseResult.succeeded(3L, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PROCESSING 상태의 작업만 종료할 수 있습니다.");
        assertThatThrownBy(() -> job.fail("사유"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PROCESSING 상태의 작업만 종료할 수 있습니다.");
    }

    @Test
    @DisplayName("아직 결과가 기록되지 않은 작업은 빈 목록을 돌려준다")
    void returnsEmptyResultsBeforeFinish() {
        assertThat(processingJob().documentResults()).isEmpty();
    }

    private AiJob processingJob() {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(3L, 4L));
        job.start();
        return job;
    }
}
