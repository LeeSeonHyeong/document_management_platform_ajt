package com.ajt.backend.domain.document.repository;

import com.ajt.backend.domain.document.model.Document;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DocumentRepository extends JpaRepository<Document, Long>, JpaSpecificationExecutor<Document> {

    boolean existsByDocumentCategoryId(long documentCategoryId);

    List<Document> findByScopeKey(String scopeKey);
}
