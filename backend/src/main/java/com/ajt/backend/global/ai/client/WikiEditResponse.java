package com.ajt.backend.global.ai.client;

import java.util.List;

/**
 * `POST /internal/v1/wiki-edits` 응답입니다.
 * 변경 목록의 구조는 Wiki 변환 응답과 같으므로 그쪽 레코드를 재사용하고, 여기서는
 * summary 대신 관리자에게 보여줄 agentMessage를 받는다.
 */
public record WikiEditResponse(
        String agentMessage,
        List<WikiTransformationResponse.CategoryChange> categoryChanges,
        List<WikiTransformationResponse.WikiChange> wikiChanges,
        List<WikiTransformationResponse.RelationChange> relationChanges,
        List<WikiTransformationResponse.IndexEntry> indexEntries
) {
}
