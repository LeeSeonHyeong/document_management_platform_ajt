package com.ajt.backend.domain.document.repository;

import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiJobRepository extends JpaRepository<AiJob, Long> {

    /**
     * 같은 Wiki 공간에서 아직 끝나지 않은 작업이 있는지 확인합니다.
     * 계약상 문서 변환 중인 공간의 Wiki는 관리자 대화로 수정할 수 없습니다(409).
     */
    boolean existsByScopeKeyAndStatusIn(String scopeKey, Collection<AiJobStatus> statuses);

    /**
     * 작업 이력을 최신순으로 조회합니다. 관리자 「요약 목록」 화면이 회차별로 묶어 보여줍니다.
     *
     * <p>{@code created_at} 동시 생성으로 순서가 흔들리지 않도록 {@code job_id}를 뒤에 둡니다.
     * 업로드 여러 건이 같은 밀리초에 작업을 만들면 정렬만으로는 페이지 경계에서 같은 항목이
     * 두 번 나오거나 빠질 수 있습니다.
     */
    Page<AiJob> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /**
     * 아직 끝나지 않은 작업들입니다(S15P11B106-284).
     *
     * <p>어떤 문서가 이미 작업에 들어갔는지 판단하는 데 쓴다. 문서 상태만으로는 알 수 없다 —
     * 작업이 시작돼도 워커가 그 문서를 집을 때까지 문서는 UPLOADED 로 남고, 동시 실행 수가
     * 제한돼 그 창이 몇 분씩 된다. {@code document_ids} 는 JSON 이라 SQL 로 찾지 않고
     * 진행 중 작업만 읽어 애플리케이션에서 펼친다(진행 중 작업은 소수다).
     */
    List<AiJob> findAllByStatusIn(Collection<AiJobStatus> statuses);

    void deleteAllByScopeKey(String scopeKey);
}
