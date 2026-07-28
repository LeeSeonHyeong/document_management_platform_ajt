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
}
