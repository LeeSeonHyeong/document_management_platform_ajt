package com.ajt.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 최고관리자(super-admin) 식별 설정입니다(S15P11B106-146).
 *
 * <p>최고관리자/부서관리자 구분을 {@code department.manager_id} 지정 여부로 판단하면, 최고관리자가 부서 관리
 * 화면에서 어느 부서의 manager로 지정되는 순간 최고관리자 자격을 잃어(가입 승인 메뉴가 사라져) 버린다.
 * 이를 막기 위해 최고관리자는 설정값(이메일)으로 고정 식별한다. ERD·role enum은 변경하지 않는다.
 */
@ConfigurationProperties(prefix = "ajt.super-admin")
public record SuperAdminProperties(String email) {

    public SuperAdminProperties {
        if (email == null || email.isBlank()) {
            // 로컬/테스트 기본값. 운영에서는 SUPER_ADMIN_EMAIL 환경변수로 실제 계정 이메일을 주입한다.
            email = "superadmin@ajt.com";
        }
        email = email.trim();
    }
}
