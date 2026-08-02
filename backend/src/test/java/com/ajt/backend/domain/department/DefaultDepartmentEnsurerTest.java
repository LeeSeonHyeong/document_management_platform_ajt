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
    @DisplayName("'전체' 부서가 없으면 생성한다")
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
