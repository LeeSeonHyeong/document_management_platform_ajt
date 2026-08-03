package com.ajt.backend.domain.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ajt.backend.domain.document.model.AiJob;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 이력 목록의 정렬을 실제 쿼리로 검증합니다(S15P11B106-192).
 *
 * <p>서비스 단위 테스트는 리포지터리를 목으로 대체해 파생 메서드 이름이 실제로 파싱되는지,
 * {@code created_at}이 같을 때 {@code job_id}가 정말 순서를 갈라 주는지 확인할 수 없습니다.
 * 이름이 틀리면 기동 시점에야 터지고, 동시 생성 정렬은 실 데이터로만 드러납니다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@Transactional
@DisplayName("AI 작업 이력 정렬 통합테스트")
class AiJobRepositoryOrderTest {

    @Autowired
    private AiJobRepository aiJobRepository;

    @Test
    @DisplayName("최신순으로 정렬하고, 생성 시각이 같으면 나중에 만들어진 작업을 앞에 둔다")
    void ordersByCreatedAtThenId() {
        // 같은 트랜잭션에서 연달아 저장하면 created_at 이 같은 밀리초로 찍힌다 —
        // 화면이 회차별로 묶어 보여주므로 그때 순서가 흔들리면 안 된다.
        AiJob first = aiJobRepository.save(AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(15L)));
        AiJob second = aiJobRepository.save(AiJob.waiting(10L, "ALL", "ALL/jobs/2", List.of(16L)));

        Page<AiJob> page = aiJobRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AiJob::id)
                .containsExactly(second.id(), first.id());
    }

    @Test
    @DisplayName("페이지 크기를 넘으면 나눠 담고 총 건수를 함께 알려준다")
    void paginates() {
        for (int index = 0; index < 3; index++) {
            aiJobRepository.save(AiJob.waiting(10L, "ALL", "ALL/jobs/%d".formatted(index), List.of(15L)));
        }

        Page<AiJob> page = aiJobRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(1, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
    }
}
