package com.ajt.backend.domain.document.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * CAT-03 문서 카테고리 수정 요청입니다.
 * name은 보내면 이름을 바꾸고, description은 보내면 설명을 바꾸거나 비웁니다.
 */
public class DocumentCategoryUpdateRequest {

    @Size(max = 50, message = "카테고리명은 50자 이하로 입력해주세요.")
    private String name;

    @Size(max = 1000, message = "카테고리 설명은 1000자 이하로 입력해주세요.")
    private String description;

    private boolean descriptionPresent;

    public String name() {
        return name;
    }

    @JsonProperty("name")
    public void setName(String name) {
        this.name = name;
    }

    public String description() {
        return description;
    }

    public boolean descriptionPresent() {
        return descriptionPresent;
    }

    @JsonProperty("description")
    public void setDescription(String description) {
        this.description = description;
        this.descriptionPresent = true;
    }
}
