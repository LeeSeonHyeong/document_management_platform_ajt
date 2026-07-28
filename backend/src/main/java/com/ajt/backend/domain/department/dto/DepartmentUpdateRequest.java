package com.ajt.backend.domain.department.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * 부서 수정 요청입니다.
 * managerId 필드는 생략하면 유지하고, null로 보내면 관리자를 해제합니다.
 */
public class DepartmentUpdateRequest {

    @Size(max = 50, message = "부서명은 50자 이하여야 합니다.")
    private String name;

    private String managerId;
    private boolean managerIdPresent;

    public String name() {
        return name;
    }

    public String managerId() {
        return managerId;
    }

    public boolean managerIdPresent() {
        return managerIdPresent;
    }

    @JsonProperty("name")
    public void setName(String name) {
        this.name = name;
    }

    @JsonProperty("managerId")
    public void setManagerId(String managerId) {
        this.managerId = managerId;
        this.managerIdPresent = true;
    }
}
