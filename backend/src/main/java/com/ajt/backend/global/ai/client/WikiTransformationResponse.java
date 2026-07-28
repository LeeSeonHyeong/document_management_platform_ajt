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
            String categoryId,
            String title,
            String contentMarkdown,
            List<Evidence> evidence
    ) {
        public WikiChange {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
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
