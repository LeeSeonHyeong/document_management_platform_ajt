package com.ajt.backend.domain.schedule.service;

import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.schedule.storage.ScheduleSourceFileStorage;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.ScopeAccess;
import com.ajt.backend.domain.schedule.api.ScheduleCreateRequest;
import com.ajt.backend.domain.schedule.api.ScheduleCreateResponse;
import com.ajt.backend.domain.schedule.api.ScheduleDetailResponse;
import com.ajt.backend.domain.schedule.api.ScheduleListResponse;
import com.ajt.backend.domain.schedule.api.ScheduleUpdateRequest;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleStatus;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SCH 일정 생성/조회 서비스입니다.
 * 관리자는 all/department 일정을, 사원은 personal 일정을 생성하며, 조회는 공개 범위 권한을 검증합니다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final MemberRepository memberRepository;
    private final DepartmentRepository departmentRepository;
    private final ScheduleSourceFileStorage scheduleSourceFileStorage;
    private final DepartmentScopePolicy departmentScopePolicy;

    /**
     * SCH-CREATE 수동/개인 일정 생성입니다.
     * all/department는 관리자만, personal은 로그인 사용자가 본인 일정으로 생성하며 즉시 approved 상태가 됩니다.
     */
    @Transactional
    public ScheduleCreateResponse create(AuthenticatedMember loginMember, ScheduleCreateRequest request) {
        requireAuthenticated(loginMember);
        ScheduleVisibility visibility = parseVisibility(request.visibilityType());
        if (visibility != ScheduleVisibility.PERSONAL && !loginMember.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        }

        List<Long> departmentIds = resolveDepartmentIds(visibility, request.departmentIds());
        // 수정(S15P11B106-199): 부서관리자는 담당 부서(DEPARTMENT) 단독 일정만 생성할 수 있다. 전체(ALL)·타부서·복수부서 차단.
        requireManageableScope(loginMember, visibility, departmentIds);
        validatePeriod(request.startAt(), request.endAt());

        Schedule schedule = Schedule.create(
                loginMember.memberId(),
                requireTitle(request.title()),
                request.content(),
                request.targetText(),
                request.location(),
                visibility,
                request.startAt(),
                request.endAt()
        );
        schedule.replaceDepartments(departmentIds);

        return ScheduleCreateResponse.from(scheduleRepository.save(schedule));
    }

    /**
     * SCH-DETAIL 일정 상세 조회입니다.
     * 존재하지 않거나 접근 권한이 없는 일정은 존재 여부를 숨기기 위해 동일하게 404로 처리합니다.
     */
    @Transactional(readOnly = true)
    public ScheduleDetailResponse getDetail(AuthenticatedMember loginMember, long scheduleId) {
        requireAuthenticated(loginMember);
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        ScopeAccess adminScope = loginMember.isAdmin() ? departmentScopePolicy.resolve(loginMember.memberId()) : null;
        Long memberDepartmentId = loginMember.isAdmin() ? null : currentDepartmentId(loginMember);
        if (!canAccess(loginMember, schedule, adminScope, memberDepartmentId)) {
            throw new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND);
        }
        // 수정: 사원에게는 원본문서(파일명·다운로드 URL)를 노출하지 않도록 관리자 여부를 전달한다(FR-SCH-009).
        return ScheduleDetailResponse.from(schedule, loginMember.isAdmin());
    }

    /**
     * SCH-LIST 일정 목록 조회입니다.
     * 기간·상태·공개유형은 쿼리로 거르고, 사원 가시성과 부서 포함 여부는 조회 후 필터링합니다.
     * 사원은 승인된 전체·소속 부서 일정과 본인 개인 일정만, 관리자는 draft 포함 전체를 조회합니다.
     */
    @Transactional(readOnly = true)
    public ScheduleListResponse list(
            AuthenticatedMember loginMember,
            String startDate,
            String endDate,
            String status,
            String visibilityType,
            String departmentId
    ) {
        // TODO(스케일 검토): 계약상 목록은 페이지네이션이 없어 최대 1년 기간 내 전체를 반환한다.
        //  또한 사원 가시성/부서 필터는 조회 후 인메모리로 거른다(부서 목록은 BatchSize로 모아 읽는다).
        //  기간 내 일정이 대량이면 성능 이슈 가능 → 데이터 증가 시 페이지네이션/DB 필터 도입 검토.
        requireAuthenticated(loginMember);

        // 수정(S15P11B106-146): status를 기간보다 먼저 파싱한다. draft 목록은 관리자 검수 대기 목록 성격이라
        //   기간 없이도 조회할 수 있어야 하므로, 기간 필수 검사보다 status 판단이 앞서야 한다.
        ScheduleStatus statusFilter = parseStatusFilter(status);
        ScheduleVisibility visibilityFilter = parseVisibilityFilter(visibilityType);
        Long departmentFilter = parseDepartmentFilter(departmentId);

        boolean admin = loginMember.isAdmin();
        ScopeAccess adminScope = admin ? departmentScopePolicy.resolve(loginMember.memberId()) : null;
        Long memberDepartmentId = admin ? null : currentDepartmentId(loginMember);

        // 수정(S15P11B106-146): status=draft이면서 startDate/endDate가 둘 다 비어 있으면 기간 필터를 생략하고
        //   전체 draft를 조회한다. 저장 정책(start_at/end_at 필수, 엔티티 nullable=false)은 그대로이며 조회 조건만 완화한다.
        //   기간이 주어졌거나 status가 draft가 아니면(없음/approved 포함) 기존처럼 기간을 필수로 검사한다.
        //   draft 노출은 아래 canAccess가 관리자에게만 허용하므로(일반 사용자는 걸러져 빈 목록) 권한 정책은 유지된다.
        boolean draftListWithoutRange =
                statusFilter == ScheduleStatus.DRAFT && isBlank(startDate) && isBlank(endDate);

        Specification<Schedule> specification;
        if (draftListWithoutRange) {
            specification = hasStatus(statusFilter, admin).and(hasVisibility(visibilityFilter));
        } else {
            LocalDate from = parseDate(startDate);
            LocalDate to = parseDate(endDate);
            if (from.isAfter(to)) {
                throw new BusinessException(ErrorCode.INVALID_SCHEDULE_RANGE, "조회 시작일이 종료일보다 늦습니다.");
            }
            if (to.isAfter(from.plusYears(1))) {
                throw new BusinessException(ErrorCode.INVALID_SCHEDULE_RANGE, "일정 조회 기간은 최대 1년입니다.");
            }
            Instant windowStart = from.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant windowEndExclusive = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            specification = withinRange(windowStart, windowEndExclusive)
                    .and(hasStatus(statusFilter, admin))
                    .and(hasVisibility(visibilityFilter));
        }

        List<Schedule> schedules = scheduleRepository.findAll(
                specification, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id")));

        List<Schedule> visible = schedules.stream()
                .filter(schedule -> canAccess(loginMember, schedule, adminScope, memberDepartmentId))
                .filter(schedule -> departmentFilter == null || schedule.departmentIds().contains(departmentFilter))
                .toList();

        return ScheduleListResponse.from(visible);
    }

    /**
     * SCH-UPDATE 일정 수정입니다.
     * 전달한 필드만 반영하고, 사원은 본인 personal 일정만 수정할 수 있습니다.
     */
    @Transactional
    public ScheduleDetailResponse update(AuthenticatedMember loginMember, long scheduleId, ScheduleUpdateRequest request) {
        requireAuthenticated(loginMember);
        // 수정(S15P11B106-87): 저장 직전 짧은 쓰기 락으로 읽어 동시 수정을 직렬화한다(화면 진입부터 잡지 않음).
        Schedule schedule = scheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        requireCanModify(loginMember, schedule);

        // 수정(S15P11B106-87): 클라이언트가 마지막으로 조회한 updatedAt(expectedUpdatedAt)을 보냈으면, 락으로 읽은
        //   현재 값과 비교해 다르면 409로 거절한다(먼저 저장한 요청이 이기고 오래된 화면의 덮어쓰기를 막음).
        //   토큰을 보내지 않은 요청은 기존과 동일하게 처리한다(프론트 배포 지연 대비 하위 호환).
        if (request.expectedUpdatedAt() != null
                && !request.expectedUpdatedAt().equals(schedule.updatedAt())) {
            throw new BusinessException(ErrorCode.SCHEDULE_VERSION_CONFLICT);
        }

        ScheduleVisibility newVisibility = request.visibilityTypePresent()
                ? parseVisibility(request.visibilityType())
                : schedule.visibilityType();
        if (newVisibility != ScheduleVisibility.PERSONAL && !loginMember.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        }

        List<Long> newDepartments = resolveUpdatedDepartments(schedule, request, newVisibility);
        // 수정(S15P11B106-199): 부서관리자는 담당 부서 범위로만 수정할 수 있다(전체·타부서·복수부서로 변경 차단).
        requireManageableScope(loginMember, newVisibility, newDepartments);
        String newTitle = request.titlePresent() ? requireTitle(request.title()) : schedule.title();
        String newContent = request.contentPresent() ? request.content() : schedule.content();
        String newTargetText = request.targetTextPresent() ? request.targetText() : schedule.targetText();
        String newLocation = request.locationPresent() ? request.location() : schedule.location();
        Instant newStart = request.startAtPresent() ? requireInstant(request.startAt(), "시작 시각") : schedule.startAt();
        Instant newEnd = request.endAtPresent() ? requireInstant(request.endAt(), "종료 시각") : schedule.endAt();

        try {
            schedule.update(newTitle, newContent, newTargetText, newLocation, newVisibility, newStart, newEnd);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, exception.getMessage());
        }
        schedule.replaceDepartments(newDepartments);
        // 수정(S15P11B106-87): flush로 @PreUpdate를 즉시 실행해 updatedAt(동시성 토큰)을 갱신한 뒤 응답을 만든다.
        //   그래야 수정 성공 응답의 updatedAt이 실제 저장값과 같아, 프론트가 그 값을 다음 수정 요청에 그대로 쓸 수 있다.
        scheduleRepository.flush();
        // 수정: 원본문서 노출 여부로 관리자 여부를 전달(update·approve는 관리자 경로).
        return ScheduleDetailResponse.from(schedule, loginMember.isAdmin());
    }

    /**
     * SCH-APPROVE 일정 draft 승인입니다. (관리자 전용)
     * draft가 아니면 409로 거절합니다.
     */
    @Transactional
    public ScheduleDetailResponse approve(AuthenticatedMember loginMember, long scheduleId) {
        requireAuthenticated(loginMember);
        requireAdmin(loginMember);
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        // 수정(S15P11B106-199): 부서관리자는 담당 부서 일정만 승인할 수 있다(전체·타부서는 존재 숨김 404).
        requireManageableSchedule(loginMember, schedule);
        try {
            schedule.approve();
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE_STATUS, exception.getMessage());
        }
        // 수정: 원본문서 노출 여부로 관리자 여부를 전달(update·approve는 관리자 경로).
        return ScheduleDetailResponse.from(schedule, loginMember.isAdmin());
    }

    /**
     * SCH-DELETE 일정 하드 삭제(= draft 거부)입니다.
     * 원본문서에서 추출된 마지막 일정이 삭제되면 원본·파싱 파일도 정리합니다.
     */
    @Transactional
    public void delete(AuthenticatedMember loginMember, long scheduleId) {
        requireAuthenticated(loginMember);
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        requireCanModify(loginMember, schedule);

        String sourceGroupKey = schedule.sourceGroupKey();

        scheduleRepository.delete(schedule);

        if (sourceGroupKey != null) {
            scheduleRepository.flush();
            if (scheduleRepository.countBySourceGroupKey(sourceGroupKey) == 0) {
                deleteSourceFiles(sourceGroupKey);
            }
        }
    }

    /**
     * SCH-SOURCE 일정 원본문서 다운로드입니다. (관리자 전용)
     * 수동·개인 일정처럼 원본이 없으면 404로 처리합니다.
     */
    @Transactional(readOnly = true)
    public ScheduleSourceFile getSourceFile(AuthenticatedMember loginMember, long scheduleId) {
        requireAuthenticated(loginMember);
        requireAdmin(loginMember);
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if (!schedule.hasSourceDocument()) {
            throw new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND);
        }
        Resource resource = scheduleSourceFileStorage.load(schedule.sourceOriginalPath());
        return new ScheduleSourceFile(resource, schedule.sourceOriginalFileName());
    }

    /**
     * 같은 원본문서에서 나온 마지막 일정이 사라질 때 원본·파싱 파일을 정리합니다.
     */
    private void deleteSourceFiles(String sourceGroupKey) {
        if (sourceGroupKey == null) {
            return;
        }
        try {
            scheduleSourceFileStorage.deleteSourceGroup(sourceGroupKey);
        } catch (IOException ignored) {
            // 파일 정리 실패는 삭제 트랜잭션을 되돌리지 않는다.
        }
    }

    private List<Long> resolveUpdatedDepartments(
            Schedule schedule,
            ScheduleUpdateRequest request,
            ScheduleVisibility newVisibility
    ) {
        boolean hasValues = request.departmentIdsPresent()
                && request.departmentIds() != null
                && !request.departmentIds().isEmpty();
        if (newVisibility != ScheduleVisibility.DEPARTMENT) {
            if (hasValues) {
                throw new BusinessException(ErrorCode.INVALID_SCHEDULE, "부서 공개 범위가 아닐 때는 부서를 지정할 수 없습니다.");
            }
            return List.of();
        }
        if (request.departmentIdsPresent()) {
            return resolveDepartmentIds(ScheduleVisibility.DEPARTMENT, request.departmentIds());
        }
        List<Long> existing = schedule.departmentIds();
        if (existing.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, "부서 공개 일정은 부서를 하나 이상 지정해야 합니다.");
        }
        return existing;
    }

    private void requireCanModify(AuthenticatedMember loginMember, Schedule schedule) {
        if (schedule.visibilityType() == ScheduleVisibility.PERSONAL) {
            // personal 일정은 작성자 본인만 수정/삭제할 수 있다. 관리자도 타인의 personal 일정은 수정/삭제할 수 없다.
            if (schedule.authorId() != loginMember.memberId()) {
                throw new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND);
            }
            return;
        }
        // all/department 일정은 관리자만 수정/삭제할 수 있다.
        if (!loginMember.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        }
        // 수정(S15P11B106-199): 부서관리자는 담당 부서 일정만 수정/삭제할 수 있다(전체·타부서는 존재 숨김 404).
        requireManageableSchedule(loginMember, schedule);
    }

    private void requireAdmin(AuthenticatedMember loginMember) {
        if (!loginMember.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        }
    }

    private Instant requireInstant(Instant value, String label) {
        if (value == null) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, label + "은(는) 비울 수 없습니다.");
        }
        return value;
    }

    private String fileNameOf(String path) {
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(separator + 1);
    }

    /**
     * 일정 접근 권한입니다.
     * personal 일정은 작성자 본인만 접근합니다(관리자도 타인의 personal 일정은 조회할 수 없습니다).
     * all/department 일정은 관리자가 draft 포함 전체를, 사원은 승인된 소속 범위만 접근합니다.
     */
    private boolean canAccess(
            AuthenticatedMember loginMember,
            Schedule schedule,
            ScopeAccess adminScope,
            Long memberDepartmentId
    ) {
        boolean superAdmin = adminScope != null && adminScope.isSuperAdmin();
        return switch (schedule.visibilityType()) {
            case PERSONAL -> schedule.authorId() == loginMember.memberId();
            // 수정(S15P11B106-199): 전체(ALL) 일정은 최고관리자·사원만 접근. 부서관리자는 전체 일정을 조회할 수 없다.
            case ALL -> superAdmin || (adminScope == null && schedule.status() == ScheduleStatus.APPROVED);
            case DEPARTMENT -> {
                if (superAdmin) {
                    yield true;
                }
                if (adminScope != null) {
                    // 부서관리자: 담당 부서 단독 일정만(타부서·복수부서 제외, draft 포함).
                    yield adminScope.canManageDepartmentScope(schedule.departmentIds());
                }
                // 사원: 승인된 소속 부서 일정만.
                yield schedule.status() == ScheduleStatus.APPROVED
                        && memberDepartmentId != null
                        && schedule.departmentIds().contains(memberDepartmentId);
            }
        };
    }

    /**
     * 부서관리자 관리 스코프 가드(생성·수정 대상 범위, S15P11B106-199).
     * 최고관리자는 제한 없음. 부서관리자는 DEPARTMENT 공개 + 담당 부서 단독([managedDeptId])만 허용하고,
     * 전체(ALL)·타부서·복수 부서는 거절한다. PERSONAL은 작성자 본인 일정이라 제한하지 않는다.
     */
    private void requireManageableScope(
            AuthenticatedMember loginMember,
            ScheduleVisibility visibility,
            List<Long> departmentIds
    ) {
        if (visibility == ScheduleVisibility.PERSONAL) {
            return;
        }
        ScopeAccess scope = departmentScopePolicy.resolve(loginMember.memberId());
        if (scope.isSuperAdmin()) {
            return;
        }
        if (visibility != ScheduleVisibility.DEPARTMENT || !scope.canManageDepartmentScope(departmentIds)) {
            throw new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED,
                    "부서관리자는 담당 부서 일정만 관리할 수 있습니다.");
        }
    }

    /**
     * 기존 일정에 대한 부서관리자 스코프 가드(수정·삭제·승인 대상, S15P11B106-199).
     * 부서관리자가 담당 밖(전체·타부서·복수부서) 일정을 다루려 하면 존재를 숨겨 SCHEDULE_NOT_FOUND로 처리한다.
     */
    private void requireManageableSchedule(AuthenticatedMember loginMember, Schedule schedule) {
        if (schedule.visibilityType() == ScheduleVisibility.PERSONAL) {
            return;
        }
        ScopeAccess scope = departmentScopePolicy.resolve(loginMember.memberId());
        if (scope.isSuperAdmin()) {
            return;
        }
        if (schedule.visibilityType() != ScheduleVisibility.DEPARTMENT
                || !scope.canManageDepartmentScope(schedule.departmentIds())) {
            throw new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND);
        }
    }

    private Specification<Schedule> withinRange(Instant windowStart, Instant windowEndExclusive) {
        return (root, query, cb) -> cb.and(
                cb.lessThan(root.<Instant>get("startAt"), windowEndExclusive),
                cb.greaterThanOrEqualTo(root.<Instant>get("endAt"), windowStart));
    }

    private Specification<Schedule> hasStatus(ScheduleStatus statusFilter, boolean admin) {
        return (root, query, cb) -> {
            if (statusFilter != null) {
                return cb.equal(root.get("status"), statusFilter);
            }
            if (!admin) {
                return cb.equal(root.get("status"), ScheduleStatus.APPROVED);
            }
            return cb.conjunction();
        };
    }

    private Specification<Schedule> hasVisibility(ScheduleVisibility visibilityFilter) {
        return (root, query, cb) -> visibilityFilter == null
                ? cb.conjunction()
                : cb.equal(root.get("visibilityType"), visibilityFilter);
    }

    private Long currentDepartmentId(AuthenticatedMember loginMember) {
        Member member = memberRepository.findById(loginMember.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        return member.getDepartment().getId();
    }

    private List<Long> resolveDepartmentIds(ScheduleVisibility visibility, List<String> rawDepartmentIds) {
        boolean hasValues = rawDepartmentIds != null && !rawDepartmentIds.isEmpty();
        if (visibility != ScheduleVisibility.DEPARTMENT) {
            if (hasValues) {
                throw new BusinessException(ErrorCode.INVALID_SCHEDULE, "부서 공개 범위가 아닐 때는 부서를 지정할 수 없습니다.");
            }
            return List.of();
        }
        if (!hasValues) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, "부서 공개 일정은 부서를 하나 이상 지정해야 합니다.");
        }

        List<Long> departmentIds = new ArrayList<>();
        for (String rawId : rawDepartmentIds) {
            departmentIds.add(parseDepartmentId(rawId));
        }
        List<Long> distinctIds = departmentIds.stream().distinct().toList();
        if (departmentRepository.findAllById(distinctIds).size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, "존재하지 않는 부서가 포함되어 있습니다.");
        }
        return distinctIds;
    }

    private long parseDepartmentId(String rawId) {
        try {
            return Long.parseLong(rawId.trim());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, "부서 ID 형식이 올바르지 않습니다.");
        }
    }

    private ScheduleVisibility parseVisibility(String visibilityType) {
        try {
            return ScheduleVisibility.fromApiValue(visibilityType);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, exception.getMessage());
        }
    }

    private ScheduleStatus parseStatusFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ScheduleStatus.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, exception.getMessage());
        }
    }

    private ScheduleVisibility parseVisibilityFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ScheduleVisibility.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, exception.getMessage());
        }
    }

    private Long parseDepartmentFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, "부서 ID 형식이 올바르지 않습니다.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE_RANGE, "조회 기간을 입력해주세요.");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE_RANGE, "조회 기간 형식이 올바르지 않습니다.");
        }
    }

    private void validatePeriod(Instant startAt, Instant endAt) {
        if (endAt.isBefore(startAt)) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, "종료 시각은 시작 시각 이후여야 합니다.");
        }
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE, "제목을 입력해주세요.");
        }
        return title.trim();
    }

    private void requireAuthenticated(AuthenticatedMember loginMember) {
        if (loginMember == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
