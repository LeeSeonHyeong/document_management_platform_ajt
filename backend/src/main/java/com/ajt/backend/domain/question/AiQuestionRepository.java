package com.ajt.backend.domain.question;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 질문 이력 저장소입니다. 본인 질문 필터·정렬·페이지네이션에 Specification을 사용합니다.
 */
public interface AiQuestionRepository
        extends JpaRepository<AiQuestion, Long>, JpaSpecificationExecutor<AiQuestion> {

    /** 후속 질문이 본인 대화인지 확인합니다. 다른 사용자의 대화는 존재를 숨긴다(계약의 404). */
    boolean existsByMember_IdAndConversationKey(long memberId, String conversationKey);

    /** 멀티턴 문맥으로 쓸 같은 대화의 질문들입니다. */
    java.util.List<AiQuestion> findByMember_IdAndConversationKeyOrderByCreatedAtAsc(
            long memberId, String conversationKey);
}
