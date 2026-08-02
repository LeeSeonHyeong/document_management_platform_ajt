package com.ajt.backend.domain.department;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 더미데이터 로더가 꺼져 있어도(ajt.local-data.enabled=false) 서버 기동 시 기본 부서('전체')가
 * DefaultDepartmentConfig 러너에 의해 생성되는지 검증한다(S15P11B106-146).
 * ajt.default-department.enabled는 지정하지 않아 기본값(실행)으로 동작한다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false",
        // 기동 시 러너가 커밋한 '전체'를 읽어야 하므로, 다른 테스트 컨텍스트와 공유되는 in-memory DB(ajt_local)에
        // 오염되지 않도록 이 테스트만 독립 DB를 쓴다(다른 컨텍스트의 create-drop이 데이터를 지우는 것을 방지).
        "spring.datasource.url=jdbc:h2:mem:default_dept_startup;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@DisplayName("기본 부서 시작 시 자동 생성(S15P11B106-146)")
class DefaultDepartmentStartupTest {

    private final DepartmentRepository departmentRepository;

    @Autowired
    DefaultDepartmentStartupTest(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    @Test
    @DisplayName("더미데이터가 꺼져 있어도 시작 시 '전체' 부서가 정확히 1개 생성된다")
    void ensuresDefaultDepartmentOnStartupWithoutLocalData() {
        long count = departmentRepository.findAllByOrderByNameAsc().stream()
                .filter(department -> Department.DEFAULT_NAME.equals(department.getName()))
                .count();

        // 기동 시 러너가 한 번 실행되어 생성되고, idempotent라 중복 생성되지 않는다.
        assertThat(count).isEqualTo(1);
    }
}
