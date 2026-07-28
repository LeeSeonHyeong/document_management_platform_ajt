package com.ajt.backend.domain.document.repository;

import com.ajt.backend.domain.document.model.WikiScope;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiScopeRepository extends JpaRepository<WikiScope, String> {
}
