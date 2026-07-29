package com.ajt.backend.domain.schedule.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * SCH 일정 수정 요청입니다.
 * 전달하지 않은 필드는 유지하고, 전달한 필드만 반영하기 위해 setter 호출로 present 여부를 추적합니다.
 */
public class ScheduleUpdateRequest {

    @Size(max = 200, message = "제목은 200자 이하로 입력해주세요.")
    private String title;
    private boolean titlePresent;

    private String content;
    private boolean contentPresent;

    @Size(max = 500, message = "대상 설명은 500자 이하로 입력해주세요.")
    private String targetText;
    private boolean targetTextPresent;

    @Size(max = 200, message = "장소는 200자 이하로 입력해주세요.")
    private String location;
    private boolean locationPresent;

    private String visibilityType;
    private boolean visibilityTypePresent;

    private List<String> departmentIds;
    private boolean departmentIdsPresent;

    private Instant startAt;
    private boolean startAtPresent;

    private Instant endAt;
    private boolean endAtPresent;

    public String title() {
        return title;
    }

    public boolean titlePresent() {
        return titlePresent;
    }

    @JsonProperty("title")
    public void setTitle(String title) {
        this.title = title;
        this.titlePresent = true;
    }

    public String content() {
        return content;
    }

    public boolean contentPresent() {
        return contentPresent;
    }

    @JsonProperty("content")
    public void setContent(String content) {
        this.content = content;
        this.contentPresent = true;
    }

    public String targetText() {
        return targetText;
    }

    public boolean targetTextPresent() {
        return targetTextPresent;
    }

    @JsonProperty("targetText")
    public void setTargetText(String targetText) {
        this.targetText = targetText;
        this.targetTextPresent = true;
    }

    public String location() {
        return location;
    }

    public boolean locationPresent() {
        return locationPresent;
    }

    @JsonProperty("location")
    public void setLocation(String location) {
        this.location = location;
        this.locationPresent = true;
    }

    public String visibilityType() {
        return visibilityType;
    }

    public boolean visibilityTypePresent() {
        return visibilityTypePresent;
    }

    @JsonProperty("visibilityType")
    public void setVisibilityType(String visibilityType) {
        this.visibilityType = visibilityType;
        this.visibilityTypePresent = true;
    }

    public List<String> departmentIds() {
        return departmentIds;
    }

    public boolean departmentIdsPresent() {
        return departmentIdsPresent;
    }

    @JsonProperty("departmentIds")
    public void setDepartmentIds(List<String> departmentIds) {
        this.departmentIds = departmentIds;
        this.departmentIdsPresent = true;
    }

    public Instant startAt() {
        return startAt;
    }

    public boolean startAtPresent() {
        return startAtPresent;
    }

    @JsonProperty("startAt")
    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
        this.startAtPresent = true;
    }

    public Instant endAt() {
        return endAt;
    }

    public boolean endAtPresent() {
        return endAtPresent;
    }

    @JsonProperty("endAt")
    public void setEndAt(Instant endAt) {
        this.endAt = endAt;
        this.endAtPresent = true;
    }
}
