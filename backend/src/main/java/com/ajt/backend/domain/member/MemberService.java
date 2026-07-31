package com.ajt.backend.domain.member;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.inquiry.InquiryRepository;
import com.ajt.backend.domain.inquiry.InquiryStatus;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
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

    // 수정(S15P11B106-72): 사번 UNIQUE 충돌 시 새 트랜잭션으로 재시도하기 위한 최대 시도 횟수.
    //   동시 승인 경쟁은 매우 드물어 소수의 재시도로 충분하다.
    private static final int MAX_EMPLOYEE_NO_ATTEMPTS = 5;

    private final MemberRepository memberRepository;
    private final DepartmentRepository departmentRepository;
    private final InquiryRepository inquiryRepository;
    private final SuperAdminChecker superAdminChecker;
    private final Clock clock;
    // 수정(S15P11B106-72): 사번 재시도는 승인 1회(approveSignupOnce)를 트랜잭션 단위로 실행하는데, 같은 빈의
    //   @Transactional 메서드를 this로 호출하면 프록시를 거치지 않아 트랜잭션 경계가 적용되지 않는다. 프록시
    //   인스턴스를 ObjectProvider로 지연 조회해 self 호출에 사용한다(생성자 순환 주입 방지).
    private final ObjectProvider<MemberService> selfProvider;

    public MemberService(
            MemberRepository memberRepository,
            DepartmentRepository departmentRepository,
            InquiryRepository inquiryRepository,
            SuperAdminChecker superAdminChecker,
            Clock clock,
            ObjectProvider<MemberService> selfProvider
    ) {
        this.memberRepository = memberRepository;
        this.departmentRepository = departmentRepository;
        this.inquiryRepository = inquiryRepository;
        this.superAdminChecker = superAdminChecker;
        this.clock = clock;
        this.selfProvider = selfProvider;
    }

    /**
     * MEM-00 내 정보 조회 요구사항입니다.
     * 토큰의 회원 ID로 현재 사용자 정보를 다시 DB에서 읽어 최신 상태를 반환합니다.
     */
    @Transactional(readOnly = true)
    public UserResponse findMe(AuthenticatedMember loginMember) {
        Member member = findMember(loginMember.memberId());
        return UserResponse.from(member, superAdminChecker.isSuperAdmin(member));
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
        // 수정(S15P11B106-104): 사용자 목록 조회는 최고관리자·부서관리자 모두 접근 가능(requireAdmin으로 완화).
        //   가입 신청 조회/승인/거절만 최고관리자 전용(requireSuperAdmin) 유지. MVP에서는 부서관리자도 전체
        //   승인 사용자를 조회할 수 있다(부서 스코프 제한은 후속 과제).
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
     * MEM-01b 관리자 사용자 단건 조회 요구사항입니다(S15P11B106-78).
     * 관리자 상세/수정 화면에서 특정 사용자 한 명의 최신 정보를 반환합니다. 목록을 훑어 찾지 않아도 되도록,
     * userId로 직접 조회한다. 존재하지 않으면 404(MEMBER_NOT_FOUND).
     */
    @Transactional(readOnly = true)
    public UserResponse findUser(AuthenticatedMember loginMember, Long userId) {
        // 수정(S15P11B106-104): 사용자 단건 조회도 부서관리자 허용(requireAdmin으로 완화).
        requireAdmin(loginMember);
        Member member = findMember(userId);
        return UserResponse.from(member, superAdminChecker.isSuperAdmin(member));
    }

    /**
     * MEM-02 관리자 사용자 수정 요구사항입니다.
     * 전달된 값만 수정하고, 전달되지 않은 값은 기존 정보를 그대로 둡니다.
     */
    @Transactional
    public UserResponse updateUser(AuthenticatedMember loginMember, Long userId, UserUpdateRequest request) {
        // 수정(S15P11B106-104): 사용자 수정 진입은 부서관리자 포함 모든 ADMIN 허용(requireAdmin으로 완화).
        //   대상 제한은 아래 세부 가드로 처리한다.
        requireAdmin(loginMember);
        Member member = findMember(userId);

        // 수정(S15P11B106-104): 가드 0 — 부서관리자(최고관리자 아님)는 사원 계정만 관리할 수 있다. 다른 관리자
        //   계정(다른 부서관리자·최고관리자) 수정은 403으로 막는다. 자기 자신(ADMIN)은 이 가드에서 제외하고
        //   아래 자기보호 가드(가드 3, 409)로 처리해, "부서관리자 자기 강등/비활성화"는 409로 응답한다.
        rejectNonSuperAdminModifyingAnotherAdmin(loginMember, member);

        // 수정(S15P11B106-71): 가드 1 — 가입 승인(APPROVED)된 사용자만 이 API로 수정할 수 있다(FR-USR-007:
        //   "관리자는 승인된 사용자 계정을 조회·수정"). PENDING/REJECTED 계정을 여기서 ACTIVE·ADMIN으로 바꾸면
        //   가입 승인 절차를 우회한 무효 데이터가 되므로, 승인·거부 전용 API로만 상태를 바꾸도록 409로 막는다.
        if (member.getSignupStatus() != SignupStatus.APPROVED) {
            throw new BusinessException(ErrorCode.USER_NOT_MODIFIABLE);
        }

        // 수정: PATCH 부분 수정 규칙(§4.2). 이 4개 필드는 모두 필수라 비울 수 없으므로,
        //       "명시적 null"(키가 전달됐는데 값이 null)이면 400으로 거절한다. 키 생략은 그대로 둔다.
        rejectExplicitNull(request.namePresent(), request.name(), "name");
        rejectExplicitNull(request.rolePresent(), request.role(), "role");
        rejectExplicitNull(request.departmentIdPresent(), request.departmentId(), "departmentId");
        rejectExplicitNull(request.accountStatusPresent(), request.accountStatus(), "accountStatus");

        Department department = findDepartmentOrNull(request.departmentId());
        Role role = parseRoleOrNull(request.role());
        AccountStatus accountStatus = parseAccountStatusOrNull(request.accountStatus());

        // 수정(S15P11B106-86): 가드 0-b — 부서관리자(최고관리자 아님)는 사원 계정의 이름·부서만 수정할 수 있고,
        //   역할(role)·계정 상태(accountStatus) 변경은 최고관리자만 가능하다. 프론트는 화면에서 막지만 Postman·
        //   개발자도구로 직접 호출하면 뚫릴 수 있어 백엔드에서 막는다. 다른 관리자 대상은 위 가드 0에서 이미 403,
        //   자기 자신은 자기보호 가드(409)가 처리하므로 여기선 제외한다. 프론트가 기존 값을 그대로 보낼 수 있으므로
        //   "요청 값이 현재 값과 실제로 달라질 때"만 막는다(같은 값 재전송은 허용).
        rejectNonSuperAdminChangingRoleOrStatus(loginMember, member, role, accountStatus);

        // 수정(S15P11B106-78): 가드 3 — 관리자가 자기 자신을 사원으로 강등(EMPLOYEE)하거나 비활성화(INACTIVE)하면
        //   본인이 관리자 권한을 잃어 관리자 화면에 못 들어가는 운영 사고가 나므로 409로 거절한다. 이름·부서 등
        //   권한과 무관한 필드의 본인 수정은 허용한다.
        rejectSelfPrivilegeRemoval(loginMember, member, role, accountStatus);

        // 수정(S15P11B106-71): 가드 2 — 이번 수정으로 사원 강등(ADMIN→EMPLOYEE) 또는 비활성화(ACTIVE→INACTIVE)되는데
        //   대상이 미처리(PENDING) 문의 담당자이면, 문의가 담당자 없이 붕 뜨므로 409로 거절한다(DR-027).
        rejectDemotionOfPendingAssignee(member, role, accountStatus);

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
        return UserResponse.from(member, superAdminChecker.isSuperAdmin(member));
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
        requireSuperAdmin(loginMember);
        Pageable pageable = createPageable(page, size, "createdAt,desc");
        Specification<Member> specification = signupRequestSpecification(status, keyword);
        Page<SignupRequestSummaryResponse> result = memberRepository.findAll(specification, pageable)
                .map(SignupRequestSummaryResponse::from);
        return SignupRequestListResponse.from(result);
    }

    /**
     * MEM-04 가입 신청 승인 요구사항입니다.
     * pending 회원에게 중복되지 않는 사번을 발급하고 approved/active 상태로 바꿉니다.
     *
     * <p>수정(S15P11B106-72): 사번은 "비어 있는 번호 찾기 → 저장"(check-then-act)이라 동시 승인 시 서로 같은
     * 번호를 고를 수 있고, 그때 뒤늦게 저장하는 쪽이 employee_no UNIQUE 제약에 걸려 500으로 실패했다.
     * 승인 1회(approveSignupOnce)를 트랜잭션 단위로 실행하고, 사번 충돌(DataIntegrityViolationException)이 나면
     * 오염된 트랜잭션을 롤백한 뒤 다음 번호로 다시 발급해 재시도한다. 이 메서드 자체는 트랜잭션이 아니며 self
     * 프록시로 호출하므로, 운영에서는 시도마다 새 트랜잭션이 열린다(자세한 전파 설명은 approveSignupOnce 참고).
     * 권한 검사는 트랜잭션 밖에서 먼저 한다.
     */
    public SignupApprovalResponse approveSignupRequest(AuthenticatedMember loginMember, Long userId) {
        requireSuperAdmin(loginMember);
        MemberService self = selfProvider.getObject();
        for (int attempt = 1; attempt <= MAX_EMPLOYEE_NO_ATTEMPTS; attempt++) {
            try {
                return self.approveSignupOnce(userId);
            } catch (DataIntegrityViolationException conflict) {
                // 사번 UNIQUE 충돌: 동시 승인으로 같은 번호가 먼저 저장된 경우. 실패한 시도의 트랜잭션은 롤백됐으니
                // 다음 시도에서 회원을 다시 읽어 새 번호(직전 저장분이 반영된 다음 값)로 재발급한다.
                if (attempt == MAX_EMPLOYEE_NO_ATTEMPTS) {
                    throw new BusinessException(
                            ErrorCode.RESOURCE_CONFLICT,
                            "사번 발급이 반복적으로 충돌했습니다. 잠시 후 다시 시도해주세요."
                    );
                }
            }
        }
        // 도달 불가(루프에서 반환하거나 마지막 시도에서 예외를 던진다).
        throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    /**
     * 수정(S15P11B106-72): 가입 승인 1회를 트랜잭션 단위로 수행한다. 사번을 발급·저장한 뒤 즉시 flush 하여
     * employee_no UNIQUE 위반을 이 메서드 안에서 확정적으로 드러낸다(상위 재시도 루프가 잡을 수 있도록).
     *
     * <p>전파는 기본값(REQUIRED)이다. 운영에서는 상위 approveSignupRequest가 트랜잭션 없이(컨트롤러 직접 호출,
     * open-in-view=false) self 프록시로 호출하므로 이 메서드가 매 시도마다 새 트랜잭션·새 영속성 컨텍스트를 열고,
     * 충돌 시 그 트랜잭션만 롤백돼 재시도가 깨끗한 상태에서 진행된다. REQUIRES_NEW를 쓰지 않는 이유는, 통합테스트의
     * @Transactional 롤백 트랜잭션에 합류해 아직 커밋되지 않은 대기 회원을 볼 수 있게 하기 위함이다(테스트에서는
     * 실제 동시성 충돌이 없으므로 합류로 충분하다).
     */
    @Transactional
    public SignupApprovalResponse approveSignupOnce(Long userId) {
        Member member = findSignupRequest(userId);
        try {
            member.approveSignup(generateEmployeeNo());
        } catch (IllegalStateException exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_SIGNUP_STATUS,
                    "승인 대기 상태의 신청만 승인할 수 있습니다."
            );
        }
        memberRepository.flush();
        return SignupApprovalResponse.from(member);
    }

    /**
     * MEM-05 가입 신청 거절 요구사항입니다.
     * pending 회원을 rejected/inactive 상태로 바꾸고 회원 기록은 보존합니다.
     */
    @Transactional
    public SignupRejectionResponse rejectSignupRequest(AuthenticatedMember loginMember, Long userId) {
        requireSuperAdmin(loginMember);
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

    // 수정(S15P11B106-104): 최고관리자 전용 작업(가입 신청 조회/승인/거절)에만 적용한다. 별도 SUPER_ADMIN role은
    //   두지 않고, 부서관리자도 Role.ADMIN이다. 최고관리자 = "ADMIN이면서 어느 부서의 부서장으로도 지정되지 않은
    //   사용자". 먼저 ADMIN 여부를 검사(비ADMIN·미인증은 403 ADMIN_PERMISSION_REQUIRED)하고, ADMIN이지만
    //   부서관리자면 같은 403 코드로 거절하되 메시지로 구분한다(에러 코드·상태는 유지해 프론트/계약 충돌을 피한다).
    //   (사용자 목록/상세/수정은 -104에서 requireAdmin으로 완화됐고, 이 게이트는 가입 신청 API에만 남는다.)
    private void requireSuperAdmin(AuthenticatedMember loginMember) {
        requireAdmin(loginMember);
        if (!superAdminChecker.isSuperAdmin(loginMember.memberId(), loginMember.isAdmin())) {
            throw new BusinessException(
                    ErrorCode.ADMIN_PERMISSION_REQUIRED,
                    "가입 신청 관리는 최고관리자만 사용할 수 있습니다."
            );
        }
    }

    // 수정(S15P11B106-104): 부서관리자(최고관리자가 아닌 ADMIN)는 사원 계정만 관리할 수 있다. 수정 대상이 다른
    //   관리자 계정(다른 부서관리자·최고관리자)이면 403으로 막는다. 자기 자신은 여기서 제외해, 부서관리자의 자기
    //   강등/비활성화는 자기보호 가드(rejectSelfPrivilegeRemoval, 409)로 처리되게 한다. 최고관리자 actor는 이
    //   제한을 받지 않는다(부서관리자 강등/비활성화 가능).
    private void rejectNonSuperAdminModifyingAnotherAdmin(AuthenticatedMember loginMember, Member target) {
        boolean actorIsSuperAdmin =
                superAdminChecker.isSuperAdmin(loginMember.memberId(), loginMember.isAdmin());
        boolean targetIsSelf = target.getId().equals(loginMember.memberId());
        if (!actorIsSuperAdmin && !targetIsSelf && target.getRole() == Role.ADMIN) {
            throw new BusinessException(ErrorCode.DEPARTMENT_MANAGER_CANNOT_MANAGE_ADMIN);
        }
    }

    // 수정(S15P11B106-86): 부서관리자(최고관리자 아님)는 사원의 이름·부서만 수정할 수 있다. 역할(role)·계정
    //   상태(accountStatus)를 실제로 바꾸려 하면 403으로 막는다(최고관리자 전용). 최고관리자 actor·자기 자신은
    //   제외한다(자기 강등/비활성화는 자기보호 가드 409). 프론트가 기존 값을 그대로 재전송하는 것은 변경이 아니므로
    //   허용하고, "요청 값이 대상의 현재 값과 실제로 달라질 때"만 거절한다(전달되지 않은 필드 null은 변경 아님).
    private void rejectNonSuperAdminChangingRoleOrStatus(
            AuthenticatedMember loginMember, Member target, Role newRole, AccountStatus newAccountStatus) {
        boolean actorIsSuperAdmin =
                superAdminChecker.isSuperAdmin(loginMember.memberId(), loginMember.isAdmin());
        if (actorIsSuperAdmin) {
            return;
        }
        boolean targetIsSelf = target.getId().equals(loginMember.memberId());
        if (targetIsSelf) {
            return;
        }
        boolean roleChanges = newRole != null && newRole != target.getRole();
        boolean statusChanges = newAccountStatus != null && newAccountStatus != target.getAccountStatus();
        if (roleChanges || statusChanges) {
            throw new BusinessException(
                    ErrorCode.DEPARTMENT_MANAGER_CANNOT_MANAGE_ADMIN,
                    "부서관리자는 사원의 이름·부서만 수정할 수 있습니다. 역할·계정 상태 변경은 최고관리자만 가능합니다."
            );
        }
    }

    // 수정: PATCH 필드가 전달됐는데(present) 값이 null이면, 비울 수 없는 필수 필드이므로 400으로 거절한다(§4.2).
    private void rejectExplicitNull(boolean present, Object value, String field) {
        if (present && value == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 필드는 null일 수 없습니다.");
        }
    }

    // 수정(S15P11B106-78): 수정 대상이 로그인한 관리자 본인이고, 이번 수정으로 자기 역할을 EMPLOYEE로 바꾸거나 계정을
    //   INACTIVE로 바꾸려 하면 409로 거절한다. 본인이 관리자 권한을 스스로 잃어 관리자 기능에 접근하지 못하는 사고를
    //   막는다. 전달되지 않은 역할·계정 상태(null)나 다른 사용자 수정은 이 검사에 걸리지 않는다.
    private void rejectSelfPrivilegeRemoval(
            AuthenticatedMember loginMember, Member target, Role newRole, AccountStatus newAccountStatus) {
        boolean isSelf = loginMember != null && target.getId().equals(loginMember.memberId());
        if (!isSelf) {
            return;
        }
        boolean demotesSelf = newRole == Role.EMPLOYEE;
        boolean deactivatesSelf = newAccountStatus == AccountStatus.INACTIVE;
        if (demotesSelf || deactivatesSelf) {
            throw new BusinessException(ErrorCode.SELF_PRIVILEGE_REMOVAL_FORBIDDEN);
        }
    }

    // 수정(S15P11B106-71): 이번 수정으로 사원으로 강등(ADMIN→EMPLOYEE)되거나 비활성화(ACTIVE→INACTIVE)되는 경우에만,
    //   대상이 미처리(PENDING) 문의 담당자인지 확인해 하나라도 있으면 409로 거절한다(DR-027). 전달되지 않은 역할·계정
    //   상태(null)는 변경이 아니므로 검사 대상이 아니며, 이미 EMPLOYEE·INACTIVE인 값을 그대로 두는 경우도 '전이'가
    //   아니라 통과한다(강등·비활성화는 담당자 자격을 잃게 만드는 '변화'일 때만 문제가 된다).
    private void rejectDemotionOfPendingAssignee(Member member, Role newRole, AccountStatus newAccountStatus) {
        boolean demotedToEmployee = newRole == Role.EMPLOYEE && member.getRole() != Role.EMPLOYEE;
        boolean deactivated = newAccountStatus == AccountStatus.INACTIVE
                && member.getAccountStatus() != AccountStatus.INACTIVE;
        if (!demotedToEmployee && !deactivated) {
            return;
        }
        if (inquiryRepository.existsByAssignee_IdAndStatus(member.getId(), InquiryStatus.PENDING)) {
            throw new BusinessException(ErrorCode.INQUIRY_ASSIGNEE_HAS_PENDING);
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
