package com.ajt.backend.domain.department;

import com.ajt.backend.domain.department.dto.SignupDepartmentListResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignupDepartmentService {

    private final DepartmentRepository departmentRepository;

    public SignupDepartmentService(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    /**
     * AUTH-01 보조 API입니다.
     * 회원가입 화면에서 선택 가능한 부서 ID와 이름만 공개합니다.
     * 수정(S15P11B106-183): 명목상 부서('최고관리자')는 가입 대상이 아니므로 응답에서 제외한다.
     */
    @Transactional(readOnly = true)
    public SignupDepartmentListResponse findSignupDepartments() {
        List<Department> selectableDepartments = departmentRepository.findAllByOrderByNameAsc().stream()
                .filter(department -> !department.isNominal())
                .toList();
        return SignupDepartmentListResponse.from(selectableDepartments);
    }
}
