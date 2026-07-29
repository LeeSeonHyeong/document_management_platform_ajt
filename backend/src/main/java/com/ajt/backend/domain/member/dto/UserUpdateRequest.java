package com.ajt.backend.domain.member.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Size;

/**
 * 관리자가 사용자 정보를 수정할 때 받는 요청입니다.
 *
 * <p>수정: PATCH 부분 수정 규칙(rest-api-convention §4.2)을 지키려고 record → class로 바꾸고,
 * "필드 생략(변경 안 함)"과 "명시적 null(값 제거 시도)"을 구분한다.
 * JSON에 키가 들어오면 해당 setter가 호출되어 present 플래그가 true가 되고, 키가 아예 없으면 false로 남는다.
 * 이 4개 필드는 모두 필수(비울 수 없음)이므로, 명시적 null이 오면 서비스에서 400으로 거절한다.
 */
public class UserUpdateRequest {

    @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
    private String name;
    private boolean namePresent;

    private String role;
    private boolean rolePresent;

    private String departmentId;
    private boolean departmentIdPresent;

    private String accountStatus;
    private boolean accountStatusPresent;

    // 수정: Jackson 역직렬화용 기본 생성자. JSON에 있는 키만 setter가 호출되어 present로 표시된다.
    //       (다인자 생성자를 두면 Jackson이 그것을 creator로 오인해 생략한 필드까지 present 처리하므로 두지 않는다.)
    public UserUpdateRequest() {
    }

    // 수정: 프로그래밍·테스트용 팩토리. 전달한 값을 모두 "전달됨(present)"으로 표시한다.
    public static UserUpdateRequest of(String name, String role, String departmentId, String accountStatus) {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setName(name);
        request.setRole(role);
        request.setDepartmentId(departmentId);
        request.setAccountStatus(accountStatus);
        return request;
    }

    @JsonSetter("name")
    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    @JsonSetter("role")
    public void setRole(String role) {
        this.role = role;
        this.rolePresent = true;
    }

    @JsonSetter("departmentId")
    public void setDepartmentId(String departmentId) {
        this.departmentId = departmentId;
        this.departmentIdPresent = true;
    }

    @JsonSetter("accountStatus")
    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
        this.accountStatusPresent = true;
    }

    public String name() {
        return name;
    }

    public boolean namePresent() {
        return namePresent;
    }

    public String role() {
        return role;
    }

    public boolean rolePresent() {
        return rolePresent;
    }

    public String departmentId() {
        return departmentId;
    }

    public boolean departmentIdPresent() {
        return departmentIdPresent;
    }

    public String accountStatus() {
        return accountStatus;
    }

    public boolean accountStatusPresent() {
        return accountStatusPresent;
    }
}
