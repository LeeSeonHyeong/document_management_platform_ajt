package com.ajt.backend.domain.question;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiAnswerRepository extends JpaRepository<AiAnswer, Long> {

    // 질문 목록의 답변을 한 번에 조회한다(질문마다 개별 조회하면 N+1이 되므로 배치 조회).
    List<AiAnswer> findByQuestion_IdIn(Collection<Long> questionIds);
}
