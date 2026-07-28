package com.ajt.backend.domain.document.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "document_category")
public class DocumentCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_category_id")
    private Long id;

    @Column(name = "scope_key", nullable = false, length = 255)
    private String scopeKey;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    protected DocumentCategory() {
    }

    private DocumentCategory(String scopeKey, String name, String description) {
        this.scopeKey = scopeKey;
        this.name = name;
        this.description = description;
    }

    public static DocumentCategory create(String scopeKey, String name, String description) {
        return new DocumentCategory(scopeKey, name, description);
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
}
