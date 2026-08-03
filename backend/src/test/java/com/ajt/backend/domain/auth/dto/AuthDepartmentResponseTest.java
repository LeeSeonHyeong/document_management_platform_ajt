package com.ajt.backend.domain.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.ajt.backend.domain.department.Department;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("로그인 부서 응답의 명목상 부서 숨김(S15P11B106-183)")
class AuthDepartmentResponseTest {

    @Test
    @DisplayName("명목상 부서('최고관리자')는 응답에서 null로 숨긴다")
    void hidesNominalDepartment() {
        assertThat(AuthDepartmentResponse.from(new Department(Department.NOMINAL_DEPARTMENT_NAME))).isNull();
    }

    @Test
    @DisplayName("기본 부서('전체')는 정상 부서이므로 그대로 노출한다")
    void exposesDefaultDepartment() {
        AuthDepartmentResponse response = AuthDepartmentResponse.from(new Department(Department.DEFAULT_NAME));

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo(Department.DEFAULT_NAME);
    }

    @Test
    @DisplayName("일반 부서는 이름을 그대로 노출한다")
    void exposesRealDepartment() {
        AuthDepartmentResponse response = AuthDepartmentResponse.from(new Department("개발부"));

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("개발부");
    }
}
