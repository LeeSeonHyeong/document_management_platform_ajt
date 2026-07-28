package com.ajt.backend.domain.document.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * CAT-02 문서 카테고리 생성 요청입니다.
 * scopeKey는 카테고리를 만들 Wiki 공간, name은 화면에 보이는 카테고리 이름입니다.
 */
public record DocumentCategoryCreateRequest(
        @NotBlank(message = "scopeKey를 입력해주세요.")
        @Size(max = 255, message = "scopeKey는 255자 이하로 입력해주세요.")
        String scopeKey,

        @NotBlank(message = "카테고리명을 입력해주세요.")
        @Size(max = 50, message = "카테고리명은 50자 이하로 입력해주세요.")
        String name,

        @Size(max = 1000, message = "카테고리 설명은 1000자 이하로 입력해주세요.")
        String description
) {
}
