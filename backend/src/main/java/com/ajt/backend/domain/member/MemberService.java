package com.ajt.backend.domain.member;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.dto.SignupApprovalResponse;
import com.ajt.backend.domain.member.dto.SignupRejectionResponse;
import com.ajt.backend.domain.member.dto.SignupRequestListResponse;
import com.ajt.backend.domain.member.dto.SignupRequestSummaryResponse;
import com.ajt.backend.domain.member.dto.UserListResponse;
import com.ajt.backend.domain.member.dto.UserResponse;
import com.ajt.backend.domain.member.dto.UserSummaryResponse;
import com.ajt.backend.domain.member.dto.UserUpdateRequest;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final MemberRepository memberRepository;
    private final DepartmentRepository departmentRepository;
    private final Clock clock;

    public MemberService(
            MemberRepository memberRepository,
            DepartmentRepository departmentRepository,
            Clock clock
    ) {
        this.memberRepository = memberRepository;
        this.departmentRepository = departmentRepository;
        this.clock = clock;
    }

    /**
     * MEM-00 내 정보 조회 요구사항입니다.
     * 토큰의 회원 ID로 현재 사용자 정보를 다시 DB에서 읽어 최신 상태를 반환합니다.
     */
    @Transactional(readOnly = true)
    public UserResponse findMe(AuthenticatedMember loginMember) {
        Member member = findMember(loginMember.memberId());
        return UserResponse.from(member);
    }

    /**
     * MEM-01 관리자 사용자 목록 조회 요구사항입니다.
     * 관리자만 전체 사용자를 필터와 검색어로 조회할 수 있습니다.
     */
    @Transactional(readOnly = true)
    public UserListResponse findUsers(
            AuthenticatedMember loginMember,
            Integer page,
            Integer size,
            String status,
            String signupStatus,
            String role,
            Boolean managerAssignable,
            String keyword,
            String sort
    ) {
        requireAdmin(loginMember);
        Pageable pageable = createPageable(page, size, sort);
        Specification<Member> specification = userSpecification(status, signupStatus, role, managerAssignable, keyword);
        // 수정: 부서장 여부를 역할(ADMIN)이 아니라 department.manager_id 지정으로 판단하도록 변경(FR-USR-006).
        //       회원마다 개별 조회하면 N+1이 되므로, 부서장으로 지정된 회원 ID를 한 번에 모아 집합으로 비교한다.
        Set<Long> managerMemberIds = new HashSet<>(departmentRepository.findManagerMemberIds());
        Page<UserSummaryResponse> result = memberRepository.findAll(specification, pageable)
                // 수정: from(member) → from(member, 부서장여부)로 인자 추가.
                .map(member -> UserSummaryResponse.from(member, managerMemberIds.contains(member.getId())));
        return UserListResponse.from(result);
    }

    /**
     * MEM-02 관리자 사용자 수정 요구사항입니다.
     * 전달된 값만 수정하고, 전달되지 않은 값은 기존 정보를 그대로 둡니다.
     */
    @Transactional
    public UserResponse updateUser(AuthenticatedMember loginMember, Long userId, UserUpdateRequest request) {
        requireAdmin(loginMember);
        Member member = findMember(userId);

        // 수정: PATCH 부분 수정 규칙(§4.2). 이 4개 필드는 모두 필수라 비울 수 없으므로,
        //       "명시적 null"(키가 전달됐는데 값이 null)이면 400으로 거절한다. 키 생략은 그대로 둔다.
        rejectExplicitNull(request.namePresent(), request.name(), "name");
        rejectExplicitNull(request.rolePresent(), request.role(), "role");
        rejectExplicitNull(request.departmentIdPresent(), request.departmentId(), "departmentId");
        rejectExplicitNull(request.accountStatusPresent(), request.accountStatus(), "accountStatus");

        Department department = findDepartmentOrNull(request.departmentId());
        Role role = parseRoleOrNull(request.role());
        AccountStatus accountStatus = parseAccountStatusOrNull(request.accountStatus());

        // 수정(S15P11B106-69): 부서장 자동 해제 판단은 Member.updateByAdmin이 수행한다(FR-USR-008 v2.12).
        //   서비스는 이 회원이 부서장으로 지정된 부서(있으면)를 조회해 넘겨주는 역할만 한다.
        //   엔티티가 저장소에 접근하지 않으므로 담당 부서 조회는 서비스 책임이다. manager_id는 UNIQUE라
        //   대상 부서는 최대 1건이며, 부서장이 아니면 null을 넘긴다.
        //   ※ 부서 이동은 해제 대상이 아니다: 지정 로직(findAssignableManager)이 담당 부서 소속을 검사하지
        //     않으므로 부서 이동은 지정 자격을 깨지 않는다(자격 판정에 소속 부서가 없음).
        Department managedDepartment = departmentRepository.findByManager_Id(member.getId()).orElse(null);

        try {
            member.updateByAdmin(department, request.name(), role, accountStatus, managedDepartment);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, exception.getMessage());
        }
        return UserResponse.from(member);
    }

    /**
     * MEM-03 가입 신청 목록 조회 요구사항입니다.
     * 관리자는 pending/approved/rejected 상태별로 가입 신청을 확인할 수 있습니다.
     */
    @Transactional(readOnly = true)
    public SignupRequestListResponse findSignupRequests(
            AuthenticatedMember loginMember,
            Integer page,
            Integer size,
            String status,
            String keyword
    ) {
        requireAdmin(loginMember);
        Pageable pageable = createPageable(page, size, "createdAt,desc");
        Specification<Member> specification = signupRequestSpecification(status, keyword);
        Page<SignupRequestSummaryResponse> result = memberRepository.findAll(specification, pageable)
                .map(SignupRequestSummaryResponse::from);
        return SignupRequestListResponse.from(result);
    }

    /**
     * MEM-04 가입 신청 승인 요구사항입니다.
     * pending 회원에게 중복되지 않는 사번을 발급하고 approved/active 상태로 바꿉니다.
     */
    @Transactional
    public SignupApprovalResponse approveSignupRequest(AuthenticatedMember loginMember, Long userId) {
        requireAdmin(loginMember);
        Member member = findSignupRequest(userId);
        try {
            member.approveSignup(generateEmployeeNo());
        } catch (IllegalStateException exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_SIGNUP_STATUS,
                    "승인 대기 상태의 신청만 승인할 수 있습니다."
            );
        }
        return SignupApprovalResponse.from(member);
    }

    /**
     * MEM-05 가입 신청 거절 요구사항입니다.
     * pending 회원을 rejected/inactive 상태로 바꾸고 회원 기록은 보존합니다.
     */
    @Transactional
    public SignupRejectionResponse rejectSignupRequest(AuthenticatedMember loginMember, Long userId) {
        requireAdmin(loginMember);
        Member member = findSignupRequest(userId);
        try {
            member.rejectSignup();
        } catch (IllegalStateException exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_SIGNUP_STATUS,
                    "승인 대기 상태의 신청만 거절할 수 있습니다."
            );
        }
        return SignupRejectionResponse.from(member);
    }

    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    private Member findSignupRequest(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNUP_REQUEST_NOT_FOUND));
    }

    private void requireAdmin(AuthenticatedMember loginMember) {
        if (loginMember == null || !loginMember.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        }
    }

    // 수정: PATCH 필드가 전달됐는데(present) 값이 null이면, 비울 수 없는 필수 필드이므로 400으로 거절한다(§4.2).
    private void rejectExplicitNull(boolean present, Object value, String field) {
        if (present && value == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 필드는 null일 수 없습니다.");
        }
    }

    private Pageable createPageable(Integer page, Integer size, String sortValue) {
        int safePage = page == null ? DEFAULT_PAGE : page;
        int safeSize = size == null ? DEFAULT_SIZE : size;
        if (safePage < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 1 이상이어야 합니다.");
        }
        if (safeSize < 1 || safeSize > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "size는 1 이상 100 이하여야 합니다.");
        }
        return PageRequest.of(safePage - 1, safeSize, parseSort(sortValue));
    }

    private Sort parseSort(String sortValue) {
        if (sortValue == null || sortValue.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String[] values = sortValue.split(",", -1);
        String property = values[0].trim();
        if (!List.of("createdAt", "updatedAt", "name", "email").contains(property)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "허용되지 않은 정렬 기준입니다.");
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (values.length > 1 && !values[1].isBlank()) {
            try {
                direction = Sort.Direction.fromString(values[1]);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "정렬 방향은 asc 또는 desc만 사용할 수 있습니다.");
            }
        }
        return Sort.by(direction, property);
    }

    private Specification<Member> userSpecification(
            String status,
            String signupStatus,
            String role,
            Boolean managerAssignable,
            String keyword
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("accountStatus"), parseAccountStatus(status)));
            }
            if (signupStatus != null && !signupStatus.isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("signupStatus"), parseSignupStatus(signupStatus)));
            }
            if (role != null && !role.isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("role"), parseRole(role)));
            }
            if (Boolean.TRUE.equals(managerAssignable)) {
                predicates.add(criteriaBuilder.equal(root.get("role"), Role.ADMIN));
                predicates.add(criteriaBuilder.equal(root.get("signupStatus"), SignupStatus.APPROVED));
                predicates.add(criteriaBuilder.equal(root.get("accountStatus"), AccountStatus.ACTIVE));
            }
            addKeywordPredicate(keyword, root.get("name"), root.get("email"), criteriaBuilder, predicates);
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Specification<Member> signupRequestSpecification(String status, String keyword) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            SignupStatus targetStatus = status == null || status.isBlank()
                    ? SignupStatus.PENDING
                    : parseSignupStatus(status);
            predicates.add(criteriaBuilder.equal(root.get("signupStatus"), targetStatus));
            addKeywordPredicate(keyword, root.get("name"), root.get("email"), criteriaBuilder, predicates);
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void addKeywordPredicate(
            String keyword,
            jakarta.persistence.criteria.Path<String> namePath,
            jakarta.persistence.criteria.Path<String> emailPath,
            jakarta.persistence.criteria.CriteriaBuilder criteriaBuilder,
            List<Predicate> predicates
    ) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String likeKeyword = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        predicates.add(criteriaBuilder.or(
                criteriaBuilder.like(criteriaBuilder.lower(namePath), likeKeyword),
                criteriaBuilder.like(criteriaBuilder.lower(emailPath), likeKeyword)
        ));
    }

    private Department findDepartmentOrNull(String departmentId) {
        if (departmentId == null || departmentId.isBlank()) {
            return null;
        }
        Long id = parseId(departmentId, "부서 ID는 숫자 문자열이어야 합니다.");
        return departmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND));
    }

    private Role parseRoleOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseRole(value);
    }

    private AccountStatus parseAccountStatusOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseAccountStatus(value);
    }

    private Role parseRole(String value) {
        try {
            return Role.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, exception.getMessage());
        }
    }

    private SignupStatus parseSignupStatus(String value) {
        try {
            return SignupStatus.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, exception.getMessage());
        }
    }

    private AccountStatus parseAccountStatus(String value) {
        try {
            return AccountStatus.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, exception.getMessage());
        }
    }

    private Long parseId(String value, String errorMessage) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, errorMessage);
        }
    }

    private String generateEmployeeNo() {
        String prefix = "AJT-%d-".formatted(clock.instant().atZone(java.time.ZoneOffset.UTC).getYear());
        int sequence = 1;
        String employeeNo;
        do {
            employeeNo = prefix + "%04d".formatted(sequence++);
        } while (memberRepository.existsByEmployeeNo(employeeNo));
        return employeeNo;
    }
}
