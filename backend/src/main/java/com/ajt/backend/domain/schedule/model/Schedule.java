package com.ajt.backend.domain.schedule.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "schedule")
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long id;

    @Column(name = "author_id", nullable = false)
    private long authorId;

    @Column(name = "source_group_key", length = 100)
    private String sourceGroupKey;

    @Column(name = "source_original_path", length = 500)
    private String sourceOriginalPath;

    /**
     * 업로드 당시의 원본 파일명입니다.
     * 저장 경로는 source.{ext} 형태로 확장자만 유지하므로 경로에서 원래 이름을 복원할 수 없어 따로 보관한다.
     */
    @Column(name = "source_original_file_name", length = 255)
    private String sourceOriginalFileName;

    @Column(name = "source_parsed_path", length = 500)
    private String sourceParsedPath;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Column(name = "target_text", length = 500)
    private String targetText;

    @Column(name = "location", length = 200)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_type", nullable = false, length = 30)
    private ScheduleVisibility visibilityType;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ScheduleStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 공개 부서 목록입니다.
     * 부서 참조 무결성을 DB가 강제하도록 schedule_department 조인 테이블로 저장합니다.
     * 목록 조회는 페이지네이션 없이 기간 내 전체를 반환하므로, 지연 로딩 N+1을 막기 위해
     * BatchSize로 부서 목록을 한 번에 모아 읽습니다.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "schedule_id", nullable = false)
    @BatchSize(size = 100)
    private List<ScheduleDepartment> departments = new ArrayList<>();

    protected Schedule() {
    }

    private Schedule(
            long authorId,
            String title,
            String content,
            String targetText,
            String location,
            ScheduleVisibility visibilityType,
            Instant startAt,
            Instant endAt,
            ScheduleStatus status
    ) {
        validatePeriod(startAt, endAt);
        this.authorId = authorId;
        this.title = title;
        this.content = content;
        this.targetText = targetText;
        this.location = location;
        this.visibilityType = visibilityType;
        this.startAt = startAt;
        this.endAt = endAt;
        this.status = status;
    }

    public static Schedule create(
            long authorId,
            String title,
            String content,
            String targetText,
            String location,
            ScheduleVisibility visibilityType,
            Instant startAt,
            Instant endAt
    ) {
        return new Schedule(
                authorId, title, content, targetText, location,
                visibilityType, startAt, endAt, ScheduleStatus.APPROVED
        );
    }

    public static Schedule draft(
            long authorId,
            String title,
            String content,
            String targetText,
            String location,
            ScheduleVisibility visibilityType,
            Instant startAt,
            Instant endAt
    ) {
        return new Schedule(
                authorId, title, content, targetText, location,
                visibilityType, startAt, endAt, ScheduleStatus.DRAFT
        );
    }

    public void approve() {
        if (status != ScheduleStatus.DRAFT) {
            throw new IllegalStateException("DRAFT 상태의 일정만 승인할 수 있습니다.");
        }
        status = ScheduleStatus.APPROVED;
    }

    // 수정(S15P11B106-87): 동시 수정 시 마지막 쓰기가 이기던 문제를, DB 스키마 변경 없이 updated_at을 동시성
    //   토큰으로 쓰는 애플리케이션 레벨 낙관적 검증으로 막는다(서비스에서 저장 직전 짧은 pessimistic 락으로
    //   updated_at을 비교, 불일치 시 409). 토큰이 응답값==저장값==다음 조회값으로 일치하도록 updatedAt은
    //   밀리초로 절삭해 둔다(JSON 왕복·마이크로초 절삭에 따른 오탐 방지).
    public void update(
            String title,
            String content,
            String targetText,
            String location,
            ScheduleVisibility visibilityType,
            Instant startAt,
            Instant endAt
    ) {
        validatePeriod(startAt, endAt);
        this.title = title;
        this.content = content;
        this.targetText = targetText;
        this.location = location;
        this.visibilityType = visibilityType;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public void linkSource(
            String sourceGroupKey,
            String sourceOriginalPath,
            String sourceOriginalFileName,
            String sourceParsedPath
    ) {
        this.sourceGroupKey = sourceGroupKey;
        this.sourceOriginalPath = sourceOriginalPath;
        this.sourceOriginalFileName = sourceOriginalFileName;
        this.sourceParsedPath = sourceParsedPath;
    }

    public boolean hasSourceDocument() {
        return sourceOriginalPath != null;
    }

    /**
     * 공개 부서 목록을 지정한 목록으로 맞춥니다.
     * 전체를 비우고 다시 넣으면 유지되는 부서도 삭제·재삽입 대상이 되는데, Hibernate가
     * 삽입을 orphan 삭제보다 먼저 실행해 uk_schedule_department를 위반한다.
     * 그래서 빠질 부서만 지우고 새로 들어올 부서만 추가한다.
     */
    public void replaceDepartments(Collection<Long> departmentIds) {
        List<Long> target = departmentIds == null
                ? List.of()
                : departmentIds.stream().distinct().sorted().toList();
        departments.removeIf(department -> !target.contains(department.departmentId()));
        List<Long> retained = departments.stream()
                .map(ScheduleDepartment::departmentId)
                .toList();
        target.stream()
                .filter(departmentId -> !retained.contains(departmentId))
                .forEach(departmentId -> departments.add(new ScheduleDepartment(departmentId)));
    }

    private static void validatePeriod(Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null || endAt.isBefore(startAt)) {
            throw new IllegalArgumentException("일정 종료 시각은 시작 시각 이후여야 합니다.");
        }
    }

    @PrePersist
    void prePersist() {
        // 수정(S15P11B106-87): 낙관적 동시성 토큰으로 쓰므로 밀리초로 절삭해 응답·저장·재조회 값이 일치하게 한다.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        // 수정(S15P11B106-87): updatedAt은 낙관적 동시성 토큰이므로 매 수정마다 반드시 증가해야 한다. 같은
        //   밀리초 안에서 연속 저장되면 절삭값이 이전과 같아 오래된 토큰이 통과할 수 있으므로, 새 값이 기존
        //   값보다 크지 않으면 기존 값 + 1ms로 강제해 단조 증가를 보장한다.
        Instant next = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (updatedAt != null && !next.isAfter(updatedAt)) {
            next = updatedAt.plusMillis(1);
        }
        updatedAt = next;
    }

    public Long id() {
        return id;
    }

    public long authorId() {
        return authorId;
    }

    public String sourceGroupKey() {
        return sourceGroupKey;
    }

    public String sourceOriginalPath() {
        return sourceOriginalPath;
    }

    public String sourceOriginalFileName() {
        return sourceOriginalFileName;
    }

    public String sourceParsedPath() {
        return sourceParsedPath;
    }

    public String title() {
        return title;
    }

    public String content() {
        return content;
    }

    public String targetText() {
        return targetText;
    }

    public String location() {
        return location;
    }

    public ScheduleVisibility visibilityType() {
        return visibilityType;
    }

    public Instant startAt() {
        return startAt;
    }

    public Instant endAt() {
        return endAt;
    }

    public ScheduleStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public List<Long> departmentIds() {
        // 유지된 부서와 새로 추가된 부서가 섞여 저장 순서가 보장되지 않으므로 정렬해 반환한다.
        return departments.stream()
                .map(ScheduleDepartment::departmentId)
                .sorted()
                .toList();
    }
}
