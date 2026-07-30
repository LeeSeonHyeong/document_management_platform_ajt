package com.ajt.backend.domain.question;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnswerSourceRepository extends JpaRepository<AnswerSource, Long> {

    // 답변 목록의 출처를 한 번에 조회한다(배치 조회로 N+1 방지).
    List<AnswerSource> findByAnswer_IdIn(Collection<Long> answerIds);
}
