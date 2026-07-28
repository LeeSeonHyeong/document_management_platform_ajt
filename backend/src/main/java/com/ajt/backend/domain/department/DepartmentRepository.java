package com.ajt.backend.domain.department;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 부서 데이터를 조회하는 저장소입니다.
 * 회원가입용 부서 목록과 관리자 부서 관리 API가 같은 부서 테이블을 사용합니다.
 */
public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findAllByOrderByNameAsc();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    boolean existsByManager_Id(Long managerId);

    boolean existsByManager_IdAndIdNot(Long managerId, Long departmentId);
}
