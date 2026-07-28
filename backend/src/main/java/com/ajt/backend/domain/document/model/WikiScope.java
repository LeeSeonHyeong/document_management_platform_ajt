package com.ajt.backend.domain.document.model;

import com.ajt.backend.domain.document.ScopeKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "wiki_scope")
public class WikiScope {

    @Id
    @Column(name = "scope_key", nullable = false, length = 255)
    private String scopeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_type", nullable = false, length = 30)
    private WikiScopeVisibilityType visibilityType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "department_refs", nullable = false, columnDefinition = "json")
    private List<Long> departmentRefs = List.of();

    @Column(name = "index_path", nullable = false, length = 500)
    private String indexPath;

    protected WikiScope() {
    }

    private WikiScope(String scopeKey, WikiScopeVisibilityType visibilityType, List<Long> departmentRefs) {
        this.scopeKey = scopeKey;
        this.visibilityType = visibilityType;
        this.departmentRefs = List.copyOf(departmentRefs);
        this.indexPath = scopeKey + "/index.md";
    }

    public static WikiScope all() {
        return new WikiScope("ALL", WikiScopeVisibilityType.ALL, List.of());
    }

    public static WikiScope department(Collection<Long> departmentIds) {
        String scopeKey = ScopeKey.from("department", departmentIds).value();
        List<Long> refs = departmentIds.stream().distinct().sorted().toList();
        return new WikiScope(scopeKey, WikiScopeVisibilityType.DEPARTMENT, refs);
    }

    public String scopeKey() {
        return scopeKey;
    }

    public WikiScopeVisibilityType visibilityType() {
        return visibilityType;
    }

    public List<Long> departmentRefs() {
        return List.copyOf(departmentRefs);
    }

    public String indexPath() {
        return indexPath;
    }
}
