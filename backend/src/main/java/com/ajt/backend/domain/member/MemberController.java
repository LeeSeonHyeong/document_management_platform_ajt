package com.ajt.backend.domain.member;

import com.ajt.backend.domain.member.dto.SignupApprovalResponse;
import com.ajt.backend.domain.member.dto.SignupRejectionResponse;
import com.ajt.backend.domain.member.dto.SignupRequestListResponse;
import com.ajt.backend.domain.member.dto.UserListResponse;
import com.ajt.backend.domain.member.dto.UserResponse;
import com.ajt.backend.domain.member.dto.UserUpdateRequest;
import com.ajt.backend.global.auth.AuthenticatedMember;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * GET /api/v1/me
     * 로그인한 사용자가 마이페이지에서 자신의 계정과 부서 정보를 확인합니다.
     */
    @GetMapping("/api/v1/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedMember loginMember) {
        return memberService.findMe(loginMember);
    }

    /**
     * GET /api/v1/users
     * 관리자가 사용자 목록을 검색하고 상태별로 확인합니다.
     */
    @GetMapping("/api/v1/users")
    public UserListResponse users(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String signupStatus,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean managerAssignable,
            @RequestParam(required = false) String departmentId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort
    ) {
        return memberService.findUsers(
                loginMember,
                page,
                size,
                status,
                signupStatus,
                role,
                managerAssignable,
                departmentId,
                keyword,
                sort
        );
    }

    /**
     * GET /api/v1/users/{userId}
     * 최고관리자가 사용자 상세/수정 화면에서 특정 사용자 한 명의 최신 정보를 조회합니다(S15P11B106-78).
     * 부서관리자는 목록만 볼 수 있고 상세 조회는 최고관리자 전용이다(S15P11B106-222).
     */
    @GetMapping("/api/v1/users/{userId}")
    public UserResponse user(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable Long userId
    ) {
        return memberService.findUser(loginMember, userId);
    }

    /**
     * PATCH /api/v1/users/{userId}
     * 최고관리자가 사용자 이름, 역할, 부서, 계정 상태를 필요한 항목만 수정합니다.
     * 부서관리자는 수정할 수 없다(최고관리자 전용, S15P11B106-222).
     */
    @PatchMapping("/api/v1/users/{userId}")
    public UserResponse updateUser(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable Long userId,
            @Valid @RequestBody UserUpdateRequest request
    ) {
        return memberService.updateUser(loginMember, userId, request);
    }

    /**
     * GET /api/v1/signup-requests
     * 관리자가 가입 신청 목록을 상태별로 확인합니다.
     */
    @GetMapping("/api/v1/signup-requests")
    public SignupRequestListResponse signupRequests(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword
    ) {
        return memberService.findSignupRequests(loginMember, page, size, status, keyword);
    }

    /**
     * POST /api/v1/signup-requests/{userId}/approve
     * 관리자가 가입 신청을 승인하고 사번을 발급합니다.
     */
    @PostMapping("/api/v1/signup-requests/{userId}/approve")
    public SignupApprovalResponse approveSignupRequest(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable Long userId
    ) {
        return memberService.approveSignupRequest(loginMember, userId);
    }

    /**
     * POST /api/v1/signup-requests/{userId}/reject
     * 관리자가 가입 신청을 거절하고 계정을 비활성 상태로 유지합니다.
     */
    @PostMapping("/api/v1/signup-requests/{userId}/reject")
    public SignupRejectionResponse rejectSignupRequest(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable Long userId
    ) {
        return memberService.rejectSignupRequest(loginMember, userId);
    }
}
