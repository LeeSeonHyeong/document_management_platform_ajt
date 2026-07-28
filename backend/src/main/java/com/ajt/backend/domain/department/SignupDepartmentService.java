package com.ajt.backend.domain.department;

import com.ajt.backend.domain.department.dto.SignupDepartmentListResponse;
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
     */
    @Transactional(readOnly = true)
    public SignupDepartmentListResponse findSignupDepartments() {
        return SignupDepartmentListResponse.from(departmentRepository.findAllByOrderByNameAsc());
    }
}
