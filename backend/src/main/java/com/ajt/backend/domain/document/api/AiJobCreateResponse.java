package com.ajt.backend.domain.document.api;

import java.util.List;

/** AI 작업 생성 응답입니다. 공개 범위별로 나뉜 작업 목록을 담는다(S15P11B106-276). */
public record AiJobCreateResponse(List<AiJobCreatedJob> jobs) {
}
