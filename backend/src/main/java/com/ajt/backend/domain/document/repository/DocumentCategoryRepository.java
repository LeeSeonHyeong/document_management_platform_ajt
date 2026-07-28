package com.ajt.backend.domain.document.repository;

import com.ajt.backend.domain.document.model.DocumentCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentCategoryRepository extends JpaRepository<DocumentCategory, Long> {
}
