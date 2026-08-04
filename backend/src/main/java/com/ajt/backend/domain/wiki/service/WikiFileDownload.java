package com.ajt.backend.domain.wiki.service;

import org.springframework.core.io.Resource;

/**
 * Wiki 본문 파일 다운로드 응답 재료입니다.
 * 컨트롤러가 이 값으로 Content-Type과 다운로드 파일명 헤더를 구성합니다.
 */
public record WikiFileDownload(Resource resource, String fileName, String contentType) {
}
