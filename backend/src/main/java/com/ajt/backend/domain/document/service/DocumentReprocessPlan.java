package com.ajt.backend.domain.document.service;

import com.ajt.backend.global.ai.client.WikiDocumentChangeType;
import java.util.Map;

/**
 * 재처리 작업이 문서별로 FastAPI에 보낼 {@code changeType}과, 걷어내기에 필요한 옛 파싱 본문입니다.
 *
 * <p>{@code ai_job}·{@code document} 테이블에 컬럼을 더하지 않고 작업 실행 시점에만 쓰는 값이라
 * 인메모리로 전달합니다. (ERD 변경 금지 원칙)
 *
 * <p>계획에 없는 문서는 {@link WikiDocumentChangeType#DOCUMENT_ADDED}로 처리합니다. 업로드와
 * 재시도는 계획 없이 실행되어 기존 동작이 그대로 유지됩니다.
 */
public record DocumentReprocessPlan(
        Map<Long, WikiDocumentChangeType> changeTypes,
        Map<Long, String> removedParsedMarkdowns
) {
    private static final DocumentReprocessPlan ADDED = new DocumentReprocessPlan(Map.of(), Map.of());

    public DocumentReprocessPlan {
        changeTypes = Map.copyOf(changeTypes);
        removedParsedMarkdowns = Map.copyOf(removedParsedMarkdowns);
    }

    /** 업로드·재시도처럼 문서를 새로 반영하는 기본 계획입니다. */
    public static DocumentReprocessPlan added() {
        return ADDED;
    }

    /**
     * 문서 1건을 이 범위에서 걷어내는 계획입니다.
     *
     * <p>옛 파싱 본문은 계약({@code removedParsedMarkdown})의 필수 값이므로 비어 있으면 만들 수
     * 없습니다. 본문을 읽지 못한 경우 호출자가 걷어내기 작업 자체를 만들지 않습니다.
     */
    public static DocumentReprocessPlan removed(long documentId, String removedParsedMarkdown) {
        if (removedParsedMarkdown == null || removedParsedMarkdown.isBlank()) {
            throw new IllegalArgumentException("걷어내기에는 옛 파싱 본문이 필요합니다: documentId=" + documentId);
        }
        return new DocumentReprocessPlan(
                Map.of(documentId, WikiDocumentChangeType.DOCUMENT_REMOVED),
                Map.of(documentId, removedParsedMarkdown)
        );
    }

    public WikiDocumentChangeType changeTypeOf(long documentId) {
        return changeTypes.getOrDefault(documentId, WikiDocumentChangeType.DOCUMENT_ADDED);
    }

    /** 걷어내기 대상이 아니면 {@code null}입니다. */
    public String removedParsedMarkdownOf(long documentId) {
        return removedParsedMarkdowns.get(documentId);
    }
}
