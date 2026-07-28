package com.ajt.backend.domain.department;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 부서 데이터를 조회하는 저장소입니다.
 * 현재 인증 범위에서는 회원가입용 부서 목록과 회원가입 시 부서 존재 여부 확인에 사용합니다.
 */
public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findAllByOrderByNameAsc();
}
