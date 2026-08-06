package com.ajt.backend.domain.document.api;

import java.util.List;

/**
 * AI 작업 생성 요청입니다(S15P11B106-276).
 *
 * <p>대기 목록에서 카테고리·공개 부서를 확정한 문서 ID들을 보낸다. 공개 범위가 섞여 있어도
 * 되고, 서버가 범위별로 작업을 나눠 만든다.
 */
public record AiJobCreateRequest(List<Long> documentIds) {
}
