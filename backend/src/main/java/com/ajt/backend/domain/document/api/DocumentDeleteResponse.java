package com.ajt.backend.domain.document.api;

/**
 * DOC-05 원본문서 삭제 응답입니다(S15P11B106-93).
 *
 * <p>삭제는 성공했는데 재처리할 내용이 없어 {@code jobId}가 {@code null}인 정상 상황을, 삭제 실패나
 * 재처리 작업 생성 실패와 프론트가 구분할 수 있도록 응답을 명확히 한다.
 * <ul>
 *   <li>{@code deleted}: 삭제 성공 여부. 이 응답이 내려오면 항상 {@code true}(실패는 에러 응답으로 처리).</li>
 *   <li>{@code reprocessRequired}: 삭제로 인해 Wiki 재처리 작업이 필요/생성됐는지 여부.</li>
 *   <li>{@code jobId}: 재처리 작업 ID. {@code reprocessRequired=false}이면 {@code null}(정상).</li>
 *   <li>{@code status}: 재처리 작업이 생성되면 {@code waiting}, 필요 없으면 {@code skipped}.</li>
 * </ul>
 */
public record DocumentDeleteResponse(
        boolean deleted,
        boolean reprocessRequired,
        String jobId,
        String scopeKey,
        String status
) {
}
