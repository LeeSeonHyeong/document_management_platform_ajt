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

    // TODO(동시성): @Version 낙관적 락이 없어 동시 수정 시 마지막 쓰기가 이긴다.
    //  프로젝트 전반의 공통 정책으로 도입할지 팀과 검토 필요.
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

    public void linkSource(String sourceGroupKey, String sourceOriginalPath, String sourceParsedPath) {
        this.sourceGroupKey = sourceGroupKey;
        this.sourceOriginalPath = sourceOriginalPath;
        this.sourceParsedPath = sourceParsedPath;
    }

    public boolean hasSourceDocument() {
        return sourceOriginalPath != null;
    }

    public void replaceDepartments(Collection<Long> departmentIds) {
        departments.clear();
        if (departmentIds == null) {
            return;
        }
        departmentIds.stream()
                .distinct()
                .sorted()
                .forEach(departmentId -> departments.add(new ScheduleDepartment(departmentId)));
    }

    private static void validatePeriod(Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null || endAt.isBefore(startAt)) {
            throw new IllegalArgumentException("일정 종료 시각은 시작 시각 이후여야 합니다.");
        }
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
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
        return departments.stream()
                .map(ScheduleDepartment::departmentId)
                .toList();
    }
}
