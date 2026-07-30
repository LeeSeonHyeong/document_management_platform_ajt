package com.ajt.backend.global.ai.client;

import java.util.List;

public record WikiTransformationResponse(
        String summary,
        List<CategoryChange> categoryChanges,
        List<WikiChange> wikiChanges,
        List<RelationChange> relationChanges,
        List<IndexEntry> indexEntries
) {
    public record CategoryChange(
            String action,
            String categoryId,
            String tempCategoryId,
            String name
    ) {
    }

    public record WikiChange(
            String action,
            String wikiId,
            String tempWikiId,
            String wikiCategoryRef,
            String wikiPath,
            String title,
            String contentMarkdown,
            List<Evidence> evidence
    ) {
        public WikiChange {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        /**
         * 신규 페이지 경로가 없던 기존 변환 호출·테스트 호환용 생성자입니다.
         */
        public WikiChange(
                String action,
                String wikiId,
                String tempWikiId,
                String wikiCategoryRef,
                String title,
                String contentMarkdown,
                List<Evidence> evidence
        ) {
            this(action, wikiId, tempWikiId, wikiCategoryRef, null, title, contentMarkdown, evidence);
        }

        /**
         * @deprecated S15P11B106-139 계약의 {@link #wikiCategoryRef()}로 전환 중인
         * 기존 반영 코드 호환용입니다. 새 코드는 사용하지 않습니다.
         */
        @Deprecated(forRemoval = false)
        public String categoryId() {
            return wikiCategoryRef;
        }
    }

    public record Evidence(
            String documentId,
            String footnote,
            String location,
            String quote
    ) {
    }

    public record RelationChange(
            String action,
            String sourceWikiRef,
            String targetWikiRef
    ) {
    }

    public record IndexEntry(
            String wikiRef,
            Integer order,
            String title,
            String summary
    ) {
    }
}
