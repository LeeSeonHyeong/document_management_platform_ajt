package com.ajt.backend.domain.inquiry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 문의를 조회하는 저장소입니다.
 * 목록은 등록자/담당자 범위와 상태·우선순위·기간 필터를 동적으로 조합하므로
 * JpaSpecificationExecutor를 함께 사용합니다.
 */
public interface InquiryRepository extends JpaRepository<Inquiry, Long>, JpaSpecificationExecutor<Inquiry> {
}
