package com.ajt.backend.domain.document.repository;

import com.ajt.backend.domain.document.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {
}
