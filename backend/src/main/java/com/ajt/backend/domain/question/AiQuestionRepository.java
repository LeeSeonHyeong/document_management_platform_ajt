package com.ajt.backend.domain.question;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 질문 이력 저장소입니다. 본인 질문 필터·정렬·페이지네이션에 Specification을 사용합니다.
 */
public interface AiQuestionRepository
        extends JpaRepository<AiQuestion, Long>, JpaSpecificationExecutor<AiQuestion> {
}
