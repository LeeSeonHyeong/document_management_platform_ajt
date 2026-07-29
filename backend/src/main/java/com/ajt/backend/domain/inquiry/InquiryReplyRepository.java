package com.ajt.backend.domain.inquiry;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 문의 답변을 조회하는 저장소입니다.
 * 문의당 답변은 한 건이므로 inquiryId로 단건 조회합니다.
 */
public interface InquiryReplyRepository extends JpaRepository<InquiryReply, Long> {

    Optional<InquiryReply> findByInquiryId(Long inquiryId);

    void deleteByInquiryId(Long inquiryId);
}
