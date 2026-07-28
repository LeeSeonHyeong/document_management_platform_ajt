package com.ajt.backend.domain.member;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 회원 계정을 조회하는 저장소입니다.
 * 인증, 사용자 관리, 가입 신청 승인/거절 기능에서 같은 회원 데이터를 사용합니다.
 */
public interface MemberRepository extends JpaRepository<Member, Long>, JpaSpecificationExecutor<Member> {

    Optional<Member> findByEmail(String email);

    boolean existsByEmployeeNo(String employeeNo);

    boolean existsByDepartment_Id(Long departmentId);
}
