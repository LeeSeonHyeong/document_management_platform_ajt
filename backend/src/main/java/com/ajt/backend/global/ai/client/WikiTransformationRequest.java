package com.ajt.backend.global.ai.client;

import java.util.Objects;

/**
 * Wiki 변환 요청입니다. (POST /internal/v1/wiki-transformations)
 *
 * <p>수정(S15P11B106-174): <b>자료 선택 단계가 없어졌다.</b> 현재 목차·카테고리와 선택된 Wiki
 * 본문을 밀어 보내지 않는다 — 에이전트가 Wiki 조회 API로 필요한 것을 직접 읽는다. 그래서 요청이
 * 작업 번호와 파싱 본문, 그리고 조회 권한으로 끝난다.
 */
public record WikiTransformationRequest(
        String jobId,
        String documentId,
        String scopeKey,
        WikiDocumentChangeType changeType,
        String parsedMarkdown,
        String removedParsedMarkdown,
        /*
         * 수정(S15P11B106-174): 선택 → 필수. 빠지면 에이전트가 조회 API를 부를 수 없어 현재
         * 위키를 전혀 못 보고, 그 상태로 라이브를 덮을 수 있다. 로그·오류 응답에 남기지 않는다.
         */
        String wikiCapability,
        /** 요청 시작 시점의 범위 버전. 작업 중 같은 범위가 바뀌면 AI가 이 값으로 알아채고 중단한다. */
        Long scopeVersion
) {
    public WikiTransformationRequest {
        jobId = requireNotBlank(jobId, "jobId");
        documentId = requireNotBlank(documentId, "documentId");
        scopeKey = requireNotBlank(scopeKey, "scopeKey");
        changeType = Objects.requireNonNull(changeType, "changeType must not be null");
        wikiCapability = requireNotBlank(wikiCapability, "wikiCapability");
        if (scopeVersion == null) {
            throw new IllegalArgumentException("scopeVersion must not be null");
        }

        if (changeType == WikiDocumentChangeType.DOCUMENT_REMOVED) {
            // 삭제에는 새 본문이 없다. 그래도 null 로 두면 안 된다 — Jackson 이
            // `"parsedMarkdown": null` 을 실어 보내고, 계약이 이 자리를 문자열로 정의해
            // FastAPI 가 요청 전체를 400 으로 거부한다(S15P11B106-194). 그러면 걷어내기
            // 에이전트가 아예 돌지 않아, 원본만 지워지고 Wiki 는 그대로 남는다.
            parsedMarkdown = parsedMarkdown == null ? "" : parsedMarkdown;
        } else {
            parsedMarkdown = requireNotBlank(parsedMarkdown, "parsedMarkdown");
        }
        if (changeType != WikiDocumentChangeType.DOCUMENT_ADDED) {
            removedParsedMarkdown = requireNotBlank(removedParsedMarkdown, "removedParsedMarkdown");
        }
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
