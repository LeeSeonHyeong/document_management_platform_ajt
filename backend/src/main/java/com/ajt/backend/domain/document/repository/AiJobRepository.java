package com.ajt.backend.domain.document.repository;

import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiJobRepository extends JpaRepository<AiJob, Long> {

    /**
     * 같은 Wiki 공간에서 아직 끝나지 않은 작업이 있는지 확인합니다.
     * 계약상 문서 변환 중인 공간의 Wiki는 관리자 대화로 수정할 수 없습니다(409).
     */
    boolean existsByScopeKeyAndStatusIn(String scopeKey, Collection<AiJobStatus> statuses);
}
