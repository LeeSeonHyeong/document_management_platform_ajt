package com.ajt.backend.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청값을 확인해주세요."),
    INVALID_DOCUMENT_UPLOAD(HttpStatus.BAD_REQUEST, "파일 형식, 개수 또는 용량 제한을 확인해주세요."),
    INVALID_DOCUMENT_STATUS(HttpStatus.CONFLICT, "문서 처리 상태를 확인해주세요."),
    AI_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "AI 작업을 찾을 수 없습니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ADMIN_PERMISSION_REQUIRED(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    DEPARTMENT_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 부서입니다."),
    DEPARTMENT_RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 부서입니다."),
    DEPARTMENT_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 존재하는 부서명입니다."),
    DEPARTMENT_IN_USE(HttpStatus.CONFLICT, "사용 중인 부서는 삭제할 수 없습니다."),
    DEPARTMENT_MANAGER_INVALID(HttpStatus.BAD_REQUEST, "부서 관리자는 승인된 활성 관리자만 지정할 수 있습니다."),
    DEPARTMENT_MANAGER_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "이미 다른 부서를 담당 중인 관리자입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    SIGNUP_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "가입 신청을 찾을 수 없습니다."),
    INVALID_SIGNUP_STATUS(HttpStatus.CONFLICT, "승인 대기 상태의 신청만 처리할 수 있습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    SIGNUP_ALREADY_PENDING(HttpStatus.CONFLICT, "이미 가입 승인 대기 중인 이메일입니다."),
    SIGNUP_ALREADY_APPROVED(HttpStatus.CONFLICT, "이미 가입 완료된 이메일입니다."),
    INVALID_OR_EXPIRED_RESET_TOKEN(HttpStatus.BAD_REQUEST, "비밀번호 재설정 토큰이 올바르지 않거나 만료되었습니다."),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "CSRF 토큰이 올바르지 않습니다."),
    CSRF_TOKEN_ISSUE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CSRF 토큰을 발급하지 못했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
