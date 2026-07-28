package com.ajt.backend.domain.department;

import com.ajt.backend.domain.department.dto.DepartmentCreateRequest;
import com.ajt.backend.domain.department.dto.DepartmentListResponse;
import com.ajt.backend.domain.department.dto.DepartmentResponse;
import com.ajt.backend.domain.department.dto.DepartmentUpdateRequest;
import com.ajt.backend.global.auth.AuthenticatedMember;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    /**
     * GET /api/v1/departments
     * 로그인한 사용자가 사용자 관리와 공개 범위 선택에 필요한 부서 목록을 확인합니다.
     */
    @GetMapping("/api/v1/departments")
    public DepartmentListResponse departments() {
        return departmentService.findDepartments();
    }

    /**
     * POST /api/v1/departments
     * 관리자가 새 부서를 생성하고 필요한 경우 부서 관리자를 지정합니다.
     */
    @PostMapping("/api/v1/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentResponse createDepartment(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @Valid @RequestBody DepartmentCreateRequest request
    ) {
        return departmentService.createDepartment(loginMember, request);
    }

    /**
     * PATCH /api/v1/departments/{departmentId}
     * 관리자가 부서명과 부서 관리자를 수정하거나 관리자 지정을 해제합니다.
     */
    @PatchMapping("/api/v1/departments/{departmentId}")
    public DepartmentResponse updateDepartment(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable Long departmentId,
            @Valid @RequestBody DepartmentUpdateRequest request
    ) {
        return departmentService.updateDepartment(loginMember, departmentId, request);
    }

    /**
     * DELETE /api/v1/departments/{departmentId}
     * 관리자가 아무 회원도 소속되지 않은 부서를 삭제합니다.
     */
    @DeleteMapping("/api/v1/departments/{departmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDepartment(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable Long departmentId
    ) {
        departmentService.deleteDepartment(loginMember, departmentId);
    }
}
