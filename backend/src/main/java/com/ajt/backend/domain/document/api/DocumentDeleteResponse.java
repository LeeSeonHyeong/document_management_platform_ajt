package com.ajt.backend.domain.document.api;

/**
 * DOC-05 원본문서 삭제 응답입니다(S15P11B106-93).
 *
 * <p>수정(S15P11B106-195): <b>{@code deleted}가 더 이상 항상 {@code true}가 아니다.</b> 걷어낼 근거가
 * 있는 문서는 Wiki 정리가 끝난 뒤에 지우므로, 응답 시점에는 아직 지워지지 않았다.
 * <ul>
 *   <li>{@code deleted}: <b>이 응답 시점에</b> 실제로 지워졌는지. 걷어낼 내용이 없어 즉시 지운 경우만
 *       {@code true}다. 삭제 자체의 실패는 에러 응답으로 처리한다.</li>
 *   <li>{@code reprocessRequired}: 삭제로 인해 Wiki 걷어내기 작업이 필요/생성됐는지 여부.</li>
 *   <li>{@code jobId}: 걷어내기 작업 ID. {@code reprocessRequired=false}이면 {@code null}(정상).</li>
 *   <li>{@code status}: 걷어내기 작업이 생성되면 {@code deleting}, 필요 없으면 {@code skipped}.
 *       {@code deleting}은 "문서가 삭제 대기 상태로 남아 있고 작업이 끝나면 지워진다"는 뜻이다.</li>
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
