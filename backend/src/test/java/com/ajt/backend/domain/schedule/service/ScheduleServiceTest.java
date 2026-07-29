package com.ajt.backend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.schedule.api.ScheduleCreateRequest;
import com.ajt.backend.domain.schedule.api.ScheduleCreateResponse;
import com.ajt.backend.domain.schedule.api.ScheduleDetailResponse;
import com.ajt.backend.domain.schedule.api.ScheduleListResponse;
import com.ajt.backend.domain.schedule.api.ScheduleUpdateRequest;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@Transactional
@DisplayName("일정 서비스")
class ScheduleServiceTest {

    private static final Instant START = Instant.parse("2026-08-03T01:00:00Z");
    private static final Instant END = Instant.parse("2026-08-03T03:00:00Z");
    private static final Instant OUT_START = Instant.parse("2026-09-10T01:00:00Z");
    private static final Instant OUT_END = Instant.parse("2026-09-10T03:00:00Z");
    private static final String FROM = "2026-08-01";
    private static final String TO = "2026-08-31";

    private final ScheduleService scheduleService;
    private final ScheduleRepository scheduleRepository;
    private final MemberRepository memberRepository;
    private final DepartmentRepository departmentRepository;

    @Autowired
    ScheduleServiceTest(
            ScheduleService scheduleService,
            ScheduleRepository scheduleRepository,
            MemberRepository memberRepository,
            DepartmentRepository departmentRepository
    ) {
        this.scheduleService = scheduleService;
        this.scheduleRepository = scheduleRepository;
        this.memberRepository = memberRepository;
        this.departmentRepository = departmentRepository;
    }

    @Test
    @DisplayName("사원은 personal 일정을 생성하면 즉시 approved 상태가 된다")
    void employeeCreatesPersonalSchedule() {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));

        ScheduleCreateResponse response = scheduleService.create(
                authOf(employee),
                new ScheduleCreateRequest("개인 일정", "내용", "본인", "본사",
                        "personal", List.of(), START, END));

        assertThat(response.status()).isEqualTo("approved");
        assertThat(response.visibilityType()).isEqualTo("personal");
        assertThat(response.ownerId()).isEqualTo(String.valueOf(employee.getId()));
        assertThat(response.departmentIds()).isEmpty();
    }

    @Test
    @DisplayName("관리자는 department 일정을 지정 부서와 함께 생성한다")
    void adminCreatesDepartmentSchedule() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Department plan = departmentRepository.save(new Department("기획부"));
        Member admin = memberRepository.save(admin(dev));

        ScheduleCreateResponse response = scheduleService.create(
                authOf(admin),
                new ScheduleCreateRequest("부서 회의", null, "개발/기획", "3층",
                        "department",
                        List.of(String.valueOf(dev.getId()), String.valueOf(plan.getId())),
                        START, END));

        assertThat(response.status()).isEqualTo("approved");
        assertThat(response.departmentIds())
                .containsExactly(String.valueOf(dev.getId()), String.valueOf(plan.getId()));
    }

    @Test
    @DisplayName("사원이 department 일정을 생성하려 하면 403으로 거절한다")
    void employeeCannotCreateDepartmentSchedule() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev));

        assertThatThrownBy(() -> scheduleService.create(
                authOf(employee),
                new ScheduleCreateRequest("부서 회의", null, null, null,
                        "department", List.of(String.valueOf(dev.getId())), START, END)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("department 일정에 부서를 지정하지 않으면 400으로 거절한다")
    void departmentScheduleRequiresDepartments() {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));

        assertThatThrownBy(() -> scheduleService.create(
                authOf(admin),
                new ScheduleCreateRequest("부서 회의", null, null, null,
                        "department", List.of(), START, END)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCHEDULE);
    }

    @Test
    @DisplayName("존재하지 않는 부서로 department 일정을 생성하면 400으로 거절한다")
    void departmentScheduleRejectsUnknownDepartment() {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));

        assertThatThrownBy(() -> scheduleService.create(
                authOf(admin),
                new ScheduleCreateRequest("부서 회의", null, null, null,
                        "department", List.of("999999"), START, END)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCHEDULE);
    }

    @Test
    @DisplayName("종료 시각이 시작 시각보다 빠르면 400으로 거절한다")
    void rejectsInvalidPeriod() {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));

        assertThatThrownBy(() -> scheduleService.create(
                authOf(employee),
                new ScheduleCreateRequest("개인 일정", null, null, null,
                        "personal", List.of(), END, START)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCHEDULE);
    }

    @Test
    @DisplayName("상세 조회에서 존재하지 않는 일정은 404다")
    void detailRejectsMissingSchedule() {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));

        assertThatThrownBy(() -> scheduleService.getDetail(authOf(employee), 999999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("사원은 본인 personal 일정 상세를 조회할 수 있다")
    void employeeViewsOwnPersonalSchedule() {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                employee.getId(), "개인 일정", null, null, null,
                ScheduleVisibility.PERSONAL, START, END));

        ScheduleDetailResponse response = scheduleService.getDetail(authOf(employee), schedule.id());

        assertThat(response.scheduleId()).isEqualTo(String.valueOf(schedule.id()));
        assertThat(response.sourceDocument()).isNull();
    }

    @Test
    @DisplayName("사원은 다른 사람의 personal 일정을 404로 볼 수 없다")
    void employeeCannotViewOthersPersonalSchedule() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member owner = memberRepository.save(employee(dev));
        Member other = memberRepository.save(employeeWithEmail(dev, "other@ajt.com"));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                owner.getId(), "개인 일정", null, null, null,
                ScheduleVisibility.PERSONAL, START, END));

        assertThatThrownBy(() -> scheduleService.getDetail(authOf(other), schedule.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("사원은 draft 일정을 404로 볼 수 없고 관리자는 조회할 수 있다")
    void draftVisibleOnlyToAdmin() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev));
        Member admin = memberRepository.save(admin(dev, "admin@ajt.com"));
        Schedule draft = scheduleRepository.save(Schedule.draft(
                admin.getId(), "추출 일정", null, null, null,
                ScheduleVisibility.ALL, START, END));

        assertThatThrownBy(() -> scheduleService.getDetail(authOf(employee), draft.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
        assertThat(scheduleService.getDetail(authOf(admin), draft.id()).status()).isEqualTo("draft");
    }

    @Test
    @DisplayName("목록: 사원은 승인된 전체·소속부서·본인 개인 일정만 조회한다")
    void listReturnsOnlyVisibleApprovedForEmployee() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Department plan = departmentRepository.save(new Department("기획부"));
        Member employee = memberRepository.save(employee(dev));
        Member admin = memberRepository.save(admin(dev, "admin@ajt.com"));
        Member colleague = memberRepository.save(employeeWithEmail(dev, "colleague@ajt.com"));

        scheduleRepository.save(Schedule.create(admin.getId(), "전체 공지", null, null, null,
                ScheduleVisibility.ALL, START, END));
        scheduleRepository.save(departmentSchedule(admin.getId(), "개발부 회의", dev.getId()));
        scheduleRepository.save(departmentSchedule(admin.getId(), "기획부 회의", plan.getId()));
        scheduleRepository.save(Schedule.create(employee.getId(), "내 개인", null, null, null,
                ScheduleVisibility.PERSONAL, START, END));
        scheduleRepository.save(Schedule.create(colleague.getId(), "남 개인", null, null, null,
                ScheduleVisibility.PERSONAL, START, END));
        scheduleRepository.save(Schedule.draft(admin.getId(), "초안 공지", null, null, null,
                ScheduleVisibility.ALL, START, END));

        ScheduleListResponse response = scheduleService.list(authOf(employee), FROM, TO, null, null, null);

        assertThat(response.items()).extracting(ScheduleListResponse.Item::title)
                .containsExactlyInAnyOrder("전체 공지", "개발부 회의", "내 개인");
    }

    @Test
    @DisplayName("목록: 관리자는 draft 일정도 함께 조회한다")
    void listReturnsDraftForAdmin() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(admin(dev));
        scheduleRepository.save(Schedule.create(admin.getId(), "승인 공지", null, null, null,
                ScheduleVisibility.ALL, START, END));
        scheduleRepository.save(Schedule.draft(admin.getId(), "초안 공지", null, null, null,
                ScheduleVisibility.ALL, START, END));

        ScheduleListResponse response = scheduleService.list(authOf(admin), FROM, TO, null, null, null);

        assertThat(response.items()).extracting(ScheduleListResponse.Item::title)
                .containsExactlyInAnyOrder("승인 공지", "초안 공지");
    }

    @Test
    @DisplayName("목록: 조회 기간을 벗어난 일정은 제외한다")
    void listExcludesOutOfRange() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(admin(dev));
        scheduleRepository.save(Schedule.create(admin.getId(), "기간 내", null, null, null,
                ScheduleVisibility.ALL, START, END));
        scheduleRepository.save(Schedule.create(admin.getId(), "기간 밖", null, null, null,
                ScheduleVisibility.ALL, OUT_START, OUT_END));

        ScheduleListResponse response = scheduleService.list(authOf(admin), FROM, TO, null, null, null);

        assertThat(response.items()).extracting(ScheduleListResponse.Item::title)
                .containsExactly("기간 내");
    }

    @Test
    @DisplayName("목록: departmentId 필터는 해당 부서를 포함하는 일정만 남긴다")
    void listFiltersByDepartmentId() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Department plan = departmentRepository.save(new Department("기획부"));
        Member admin = memberRepository.save(admin(dev));
        scheduleRepository.save(departmentSchedule(admin.getId(), "개발부 회의", dev.getId()));
        scheduleRepository.save(departmentSchedule(admin.getId(), "기획부 회의", plan.getId()));

        ScheduleListResponse response = scheduleService.list(
                authOf(admin), FROM, TO, null, null, String.valueOf(dev.getId()));

        assertThat(response.items()).extracting(ScheduleListResponse.Item::title)
                .containsExactly("개발부 회의");
    }

    @Test
    @DisplayName("목록: 시작일이 종료일보다 늦으면 400으로 거절한다")
    void listRejectsInvalidRange() {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));

        assertThatThrownBy(() -> scheduleService.list(authOf(admin), TO, FROM, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCHEDULE_RANGE);
    }

    @Test
    @DisplayName("목록: 조회 기간이 없으면 400으로 거절한다")
    void listRejectsMissingRange() {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));

        assertThatThrownBy(() -> scheduleService.list(authOf(admin), null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCHEDULE_RANGE);
    }

    @Test
    @DisplayName("목록: 조회 기간이 1년을 초과하면 400으로 거절한다")
    void listRejectsRangeLongerThanOneYear() {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));

        assertThatThrownBy(() -> scheduleService.list(
                        authOf(admin), "2026-01-01", "2027-01-02", null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCHEDULE_RANGE);
    }

    @Test
    @DisplayName("수정: 사원은 본인 personal 일정의 제목과 시간을 바꿀 수 있다")
    void updateOwnPersonalSchedule() {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                employee.getId(), "기존 제목", "기존", null, null,
                ScheduleVisibility.PERSONAL, START, END));
        ScheduleUpdateRequest request = new ScheduleUpdateRequest();
        request.setTitle("수정된 제목");

        ScheduleDetailResponse response = scheduleService.update(authOf(employee), schedule.id(), request);

        assertThat(response.title()).isEqualTo("수정된 제목");
        assertThat(response.status()).isEqualTo("approved");
    }

    @Test
    @DisplayName("수정: 사원이 남의 personal 일정을 수정하려 하면 존재를 숨기기 위해 404다")
    void updateOthersScheduleRejected() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member owner = memberRepository.save(employee(dev));
        Member other = memberRepository.save(employeeWithEmail(dev, "other@ajt.com"));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                owner.getId(), "개인", null, null, null, ScheduleVisibility.PERSONAL, START, END));
        ScheduleUpdateRequest request = new ScheduleUpdateRequest();
        request.setTitle("침범");

        assertThatThrownBy(() -> scheduleService.update(authOf(other), schedule.id(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("수정: 사원이 개인 일정을 all로 승격하려 하면 관리자 권한 오류다")
    void updateEscalateVisibilityRejected() {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                employee.getId(), "개인", null, null, null, ScheduleVisibility.PERSONAL, START, END));
        ScheduleUpdateRequest request = new ScheduleUpdateRequest();
        request.setVisibilityType("all");

        assertThatThrownBy(() -> scheduleService.update(authOf(employee), schedule.id(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("승인: 관리자가 draft를 승인하면 approved가 된다")
    void approveDraftAsAdmin() {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));
        Schedule draft = scheduleRepository.save(Schedule.draft(
                admin.getId(), "초안", null, null, null, ScheduleVisibility.ALL, START, END));

        ScheduleDetailResponse response = scheduleService.approve(authOf(admin), draft.id());

        assertThat(response.status()).isEqualTo("approved");
    }

    @Test
    @DisplayName("승인: 이미 approved인 일정을 승인하면 409다")
    void approveNonDraftRejected() {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));
        Schedule approved = scheduleRepository.save(Schedule.create(
                admin.getId(), "승인됨", null, null, null, ScheduleVisibility.ALL, START, END));

        assertThatThrownBy(() -> scheduleService.approve(authOf(admin), approved.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCHEDULE_STATUS);
    }

    @Test
    @DisplayName("승인: 사원이 승인하려 하면 관리자 권한 오류다")
    void approveByEmployeeRejected() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(admin(dev, "admin@ajt.com"));
        Member employee = memberRepository.save(employee(dev));
        Schedule draft = scheduleRepository.save(Schedule.draft(
                admin.getId(), "초안", null, null, null, ScheduleVisibility.ALL, START, END));

        assertThatThrownBy(() -> scheduleService.approve(authOf(employee), draft.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("상세: 관리자도 타인의 personal 일정은 404로 볼 수 없다")
    void adminCannotViewOthersPersonalSchedule() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev));
        Member admin = memberRepository.save(admin(dev, "admin@ajt.com"));
        Schedule personal = scheduleRepository.save(Schedule.create(
                employee.getId(), "사원 개인", null, null, null, ScheduleVisibility.PERSONAL, START, END));

        assertThatThrownBy(() -> scheduleService.getDetail(authOf(admin), personal.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제: 관리자도 타인의 personal 일정을 삭제하려 하면 존재를 숨기기 위해 404다")
    void adminCannotDeleteOthersPersonalSchedule() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev));
        Member admin = memberRepository.save(admin(dev, "admin@ajt.com"));
        Schedule personal = scheduleRepository.save(Schedule.create(
                employee.getId(), "사원 개인", null, null, null, ScheduleVisibility.PERSONAL, START, END));

        assertThatThrownBy(() -> scheduleService.delete(authOf(admin), personal.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("목록: 관리자도 타인의 personal 일정은 목록에서 제외된다")
    void listExcludesOthersPersonalForAdmin() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev));
        Member admin = memberRepository.save(admin(dev, "admin@ajt.com"));
        scheduleRepository.save(Schedule.create(admin.getId(), "전체 공지", null, null, null,
                ScheduleVisibility.ALL, START, END));
        scheduleRepository.save(Schedule.create(employee.getId(), "사원 개인", null, null, null,
                ScheduleVisibility.PERSONAL, START, END));

        ScheduleListResponse response = scheduleService.list(authOf(admin), FROM, TO, null, null, null);

        assertThat(response.items()).extracting(ScheduleListResponse.Item::title)
                .containsExactly("전체 공지");
    }

    @Test
    @DisplayName("삭제: 사원이 본인 personal 일정을 삭제하면 제거된다")
    void deleteOwnPersonalSchedule() {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                employee.getId(), "개인", null, null, null, ScheduleVisibility.PERSONAL, START, END));

        scheduleService.delete(authOf(employee), schedule.id());

        assertThat(scheduleRepository.existsById(schedule.id())).isFalse();
    }

    @Test
    @DisplayName("삭제: 존재하지 않는 일정은 404다")
    void deleteMissingSchedule() {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));

        assertThatThrownBy(() -> scheduleService.delete(authOf(admin), 999999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("원본조회: 원본문서가 없는 일정은 404다")
    void sourceFileMissingReturns404() {
        Member admin = memberRepository.save(admin(departmentRepository.save(new Department("개발부"))));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                admin.getId(), "수동 일정", null, null, null, ScheduleVisibility.ALL, START, END));

        assertThatThrownBy(() -> scheduleService.getSourceFile(authOf(admin), schedule.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("원본조회: 사원은 관리자 권한 오류로 거절된다")
    void sourceFileByEmployeeRejected() {
        Member employee = memberRepository.save(employee(departmentRepository.save(new Department("개발부"))));
        Schedule schedule = scheduleRepository.save(Schedule.create(
                employee.getId(), "개인", null, null, null, ScheduleVisibility.PERSONAL, START, END));

        assertThatThrownBy(() -> scheduleService.getSourceFile(authOf(employee), schedule.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
    }

    private Schedule departmentSchedule(long authorId, String title, long departmentId) {
        Schedule schedule = Schedule.create(authorId, title, null, null, null,
                ScheduleVisibility.DEPARTMENT, START, END);
        schedule.replaceDepartments(List.of(departmentId));
        return schedule;
    }

    private AuthenticatedMember authOf(Member member) {
        return new AuthenticatedMember(member.getId(), member.getEmail(), member.getRole());
    }

    private Member employee(Department department) {
        return employeeWithEmail(department, "employee@ajt.com");
    }

    private Member employeeWithEmail(Department department, String email) {
        return Member.approvedEmployee(department, email, "홍길동", "hashed-password", employeeNo(email));
    }

    private Member admin(Department department) {
        return admin(department, "admin@ajt.com");
    }

    private Member admin(Department department, String email) {
        return Member.approved(department, email, "관리자", "hashed-password", employeeNo(email), Role.ADMIN);
    }

    private String employeeNo(String email) {
        return "AJT-2026-" + Math.abs(email.hashCode() % 100000);
    }
}
