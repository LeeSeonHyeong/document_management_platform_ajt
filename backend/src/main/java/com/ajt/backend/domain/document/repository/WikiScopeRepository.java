package com.ajt.backend.domain.document.repository;

import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiScopeRepository extends JpaRepository<WikiScope, String> {

    List<WikiScope> findByVisibilityType(WikiScopeVisibilityType visibilityType);
}
