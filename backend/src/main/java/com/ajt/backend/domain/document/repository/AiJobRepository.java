package com.ajt.backend.domain.document.repository;

import com.ajt.backend.domain.document.model.AiJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiJobRepository extends JpaRepository<AiJob, Long> {
}
