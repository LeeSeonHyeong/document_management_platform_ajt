package com.ajt.backend.domain.schedule.service;

import org.springframework.core.io.Resource;

/**
 * 일정 원본문서 다운로드 결과입니다. (파일 스트림 + 원본 파일명)
 */
public record ScheduleSourceFile(Resource resource, String fileName) {
}
