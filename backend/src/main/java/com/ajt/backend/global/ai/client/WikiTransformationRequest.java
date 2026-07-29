package com.ajt.backend.global.ai.client;

import java.util.List;
import java.util.Objects;

public record WikiTransformationRequest(
        String jobId,
        String documentId,
        String scopeKey,
        WikiDocumentChangeType changeType,
        String parsedMarkdown,
        String removedParsedMarkdown,
        String currentIndex,
        List<CurrentCategory> currentCategories,
        List<SelectedWiki> selectedWikis
) {
    public WikiTransformationRequest {
        jobId = requireNotBlank(jobId, "jobId");
        documentId = requireNotBlank(documentId, "documentId");
        scopeKey = requireNotBlank(scopeKey, "scopeKey");
        changeType = Objects.requireNonNull(changeType, "changeType must not be null");
        currentIndex = requireNotBlank(currentIndex, "currentIndex");
        currentCategories = List.copyOf(Objects.requireNonNull(
                currentCategories,
                "currentCategories must not be null"
        ));
        selectedWikis = List.copyOf(Objects.requireNonNull(selectedWikis, "selectedWikis must not be null"));

        if (changeType != WikiDocumentChangeType.DOCUMENT_REMOVED) {
            parsedMarkdown = requireNotBlank(parsedMarkdown, "parsedMarkdown");
        }
        if (changeType != WikiDocumentChangeType.DOCUMENT_ADDED) {
            removedParsedMarkdown = requireNotBlank(removedParsedMarkdown, "removedParsedMarkdown");
        }
    }

    public record CurrentCategory(
            String categoryId,
            String name
    ) {
        public CurrentCategory {
            categoryId = requireNotBlank(categoryId, "categoryId");
            name = requireNotBlank(name, "name");
        }
    }

    public record SelectedWiki(
            String wikiId,
            String categoryId,
            String title,
            String summary,
            String contentMarkdown,
            List<String> documentRefs,
            List<String> wikiRefs
    ) {
        public SelectedWiki {
            wikiId = requireNotBlank(wikiId, "wikiId");
            categoryId = requireNotBlank(categoryId, "categoryId");
            title = requireNotBlank(title, "title");
            summary = requireNotBlank(summary, "summary");
            contentMarkdown = requireNotBlank(contentMarkdown, "contentMarkdown");
            documentRefs = List.copyOf(Objects.requireNonNull(documentRefs, "documentRefs must not be null"));
            wikiRefs = List.copyOf(Objects.requireNonNull(wikiRefs, "wikiRefs must not be null"));
        }
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
