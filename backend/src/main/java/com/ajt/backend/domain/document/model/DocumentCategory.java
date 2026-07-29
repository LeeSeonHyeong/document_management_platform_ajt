package com.ajt.backend.domain.document.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * CAT-01~04 문서 카테고리 엔티티입니다.
 * 같은 Wiki 공간(scopeKey) 안에서 문서를 분류할 이름과 설명을 관리합니다.
 */
@Entity
@Table(name = "document_category", uniqueConstraints = {
        @UniqueConstraint(name = "uk_document_category_scope_name", columnNames = {"scope_key", "name"})
})
public class DocumentCategory {

    private static final int SCOPE_KEY_MAX_LENGTH = 255;
    private static final int NAME_MAX_LENGTH = 50;
    private static final int DESCRIPTION_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_category_id")
    private Long id;

    @Column(name = "scope_key", nullable = false, length = SCOPE_KEY_MAX_LENGTH)
    private String scopeKey;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
    private String description;

    protected DocumentCategory() {
    }

    private DocumentCategory(String scopeKey, String name, String description) {
        this.scopeKey = requireScopeKey(scopeKey);
        this.name = requireName(name);
        this.description = normalizeDescription(description);
    }

    public static DocumentCategory create(String scopeKey, String name, String description) {
        return new DocumentCategory(scopeKey, name, description);
    }

    /**
     * CAT-03 카테고리 수정에서 이름만 바꿀 때 사용합니다.
     */
    public void changeName(String name) {
        this.name = requireName(name);
    }

    /**
     * CAT-03 카테고리 수정에서 설명을 바꾸거나 비울 때 사용합니다.
     */
    public void changeDescription(String description) {
        this.description = normalizeDescription(description);
    }

    public Long id() {
        return id;
    }

    public String scopeKey() {
        return scopeKey;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public boolean belongsToScope(String scopeKey) {
        return this.scopeKey.equals(scopeKey);
    }

    private static String requireScopeKey(String scopeKey) {
        String trimmed = requireText(scopeKey, "scopeKey는 필수입니다.");
        if (trimmed.length() > SCOPE_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("scopeKey는 255자 이하로 입력해주세요.");
        }
        return trimmed;
    }

    private static String requireName(String name) {
        String trimmed = requireText(name, "카테고리명을 입력해주세요.");
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("카테고리명은 50자 이하로 입력해주세요.");
        }
        return trimmed;
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }

        String trimmed = description.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        if (trimmed.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("카테고리 설명은 1000자 이하로 입력해주세요.");
        }
        return trimmed;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
