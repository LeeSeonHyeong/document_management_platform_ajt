package com.ajt.backend.domain.document.model;

public enum DocumentStatus {
    UPLOADED,
    PARSING,
    PROCESSING,
    COMPLETED,
    FAILED,
    /**
     * 삭제 요청을 받아 Wiki 걷어내기를 기다리는 상태입니다(S15P11B106-195).
     *
     * <p>행과 파일은 아직 살아 있다. 걷어내기가 성공해야 지운다 — 되돌릴 수 없는 일을 마지막에
     * 두어, 실패했을 때 원본만 사라지고 Wiki에 근거가 남는 복구 불가능한 상태를 만들지 않는다.
     */
    DELETING,
    CANCELLED
}
