package com.ajt.backend.domain.inquiry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 문의를 조회하는 저장소입니다.
 * 목록은 등록자/담당자 범위와 상태·우선순위·기간 필터를 동적으로 조합하므로
 * JpaSpecificationExecutor를 함께 사용합니다.
 */
public interface InquiryRepository extends JpaRepository<Inquiry, Long>, JpaSpecificationExecutor<Inquiry> {

    /**
     * 특정 담당자에게 배정된 문의 중 아직 처리되지 않은(status) 건이 하나라도 있는지 확인합니다.
     * 사용자 수정에서 담당자를 사원으로 강등하거나 비활성화하기 전에 미처리 문의를 검사할 때 사용합니다(DR-027).
     */
    boolean existsByAssignee_IdAndStatus(Long assigneeId, InquiryStatus status);
}
