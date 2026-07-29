package com.ajt.backend.global.ai.client;

import java.util.List;
import java.util.Objects;

/**
 * `POST /internal/v1/wiki-edits` 요청입니다.
 * FastAPI가 정의되지 않은 필드를 거부하므로(extra=forbid) 계약에 있는 필드만 보낸다.
 */
public record WikiEditRequest(
        String wikiId,
        String scopeKey,
        String instruction,
        WikiBody currentWiki,
        List<EvidenceDocument> evidenceDocuments,
        List<ChatMessage> chatHistory
) {
    public WikiEditRequest {
        wikiId = requireNotBlank(wikiId, "wikiId");
        scopeKey = requireNotBlank(scopeKey, "scopeKey");
        instruction = requireNotBlank(instruction, "instruction");
        currentWiki = Objects.requireNonNull(currentWiki, "currentWiki must not be null");
        evidenceDocuments = evidenceDocuments == null ? List.of() : List.copyOf(evidenceDocuments);
        chatHistory = chatHistory == null ? List.of() : List.copyOf(chatHistory);
    }

    public record WikiBody(String title, String contentMarkdown) {
        public WikiBody {
            title = requireNotBlank(title, "title");
            contentMarkdown = requireNotBlank(contentMarkdown, "contentMarkdown");
        }
    }

    public record EvidenceDocument(String documentId, String originalFileName, String parsedMarkdown) {
        public EvidenceDocument {
            documentId = requireNotBlank(documentId, "documentId");
            originalFileName = requireNotBlank(originalFileName, "originalFileName");
            parsedMarkdown = requireNotBlank(parsedMarkdown, "parsedMarkdown");
        }
    }

    /**
     * 해당 Wiki의 관리자 대화 1건입니다.
     * senderType은 계약대로 소문자 admin·agent를 사용한다.
     */
    public record ChatMessage(String senderType, String content) {
        public ChatMessage {
            senderType = requireNotBlank(senderType, "senderType");
            content = requireNotBlank(content, "content");
        }
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
