package com.ajt.backend.domain.department;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    // 수정: 신규 추가한 쿼리. 부서장(department.manager_id)으로 지정된 회원 ID 목록을 한 번에 조회한다.
    //       사용자 목록에서 "이 회원이 부서장인가"를 회원 한 명씩 조회(N+1)하지 않고 판단하기 위함.
    @Query("select d.manager.id from Department d where d.manager is not null")
    List<Long> findManagerMemberIds();
}
