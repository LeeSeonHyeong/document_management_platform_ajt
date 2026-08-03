package com.ajt.backend.domain.department;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false",
        // ensure()를 직접 호출해 검증하므로 시작 시 자동 생성 러너는 끄고 격리한다(S15P11B106-146).
        "ajt.default-department.enabled=false"
})
@Transactional
@DisplayName("기본 부서 보장(S15P11B106-146)")
class DefaultDepartmentEnsurerTest {

    private final DefaultDepartmentEnsurer ensurer;
    private final DepartmentRepository departmentRepository;

    @Autowired
    DefaultDepartmentEnsurerTest(DefaultDepartmentEnsurer ensurer, DepartmentRepository departmentRepository) {
        this.ensurer = ensurer;
        this.departmentRepository = departmentRepository;
    }

    @Test
    @DisplayName("예전 이름('전체')으로 남아 있으면 새로 만들지 않고 개명한다")
    void renamesTheLegacyDefaultInsteadOfCreatingAnother() {
        Department legacy = departmentRepository.save(new Department(Department.LEGACY_DEFAULT_NAME));

        Department ensured = ensurer.ensure();

        // 부서 ID 가 그대로여야 소속 사용자·일정·Wiki 범위가 영향받지 않는다.
        assertThat(ensured.getId()).isEqualTo(legacy.getId());
        assertThat(ensured.getName()).isEqualTo(Department.DEFAULT_NAME);
        assertThat(countDefault()).isEqualTo(1);
        assertThat(departmentRepository.findAllByOrderByNameAsc())
                .noneMatch(department -> Department.LEGACY_DEFAULT_NAME.equals(department.getName()));
    }

    @Test
    @DisplayName("새 이름이 이미 있으면 예전 이름을 개명하지 않는다 — 이름이 유니크라 충돌한다")
    void leavesTheLegacyAloneWhenTheNewNameAlreadyExists() {
        departmentRepository.save(new Department(Department.DEFAULT_NAME));
        Department legacy = departmentRepository.save(new Department(Department.LEGACY_DEFAULT_NAME));

        ensurer.ensure();

        assertThat(departmentRepository.findById(legacy.getId()))
                .get()
                .extracting(Department::getName)
                .isEqualTo(Department.LEGACY_DEFAULT_NAME);
    }

    @Test
    @DisplayName("기본 부서가 없으면 생성한다")
    void createsDefaultDepartmentWhenAbsent() {
        assertThat(countDefault()).isZero();

        Department ensured = ensurer.ensure();

        assertThat(ensured.getName()).isEqualTo(Department.DEFAULT_NAME);
        assertThat(countDefault()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 있으면 중복 생성하지 않는다(idempotent)")
    void doesNotDuplicateWhenAlreadyPresent() {
        Department first = ensurer.ensure();
        Department second = ensurer.ensure();

        assertThat(countDefault()).isEqualTo(1);
        // 같은 부서를 반환한다.
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    private long countDefault() {
        return departmentRepository.findAllByOrderByNameAsc().stream()
                .filter(department -> Department.DEFAULT_NAME.equals(department.getName()))
                .count();
    }
}
