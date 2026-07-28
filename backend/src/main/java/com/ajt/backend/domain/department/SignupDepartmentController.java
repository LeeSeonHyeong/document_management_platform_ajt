package com.ajt.backend.domain.department;

import com.ajt.backend.domain.department.dto.SignupDepartmentListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/signup-departments")
public class SignupDepartmentController {

    private final SignupDepartmentService signupDepartmentService;

    public SignupDepartmentController(SignupDepartmentService signupDepartmentService) {
        this.signupDepartmentService = signupDepartmentService;
    }

    /**
     * AUTH-01 보조 API입니다.
     * 회원가입 화면에 필요한 부서 ID와 이름만 내려줍니다.
     */
    @GetMapping
    public SignupDepartmentListResponse findSignupDepartments() {
        return signupDepartmentService.findSignupDepartments();
    }
}
