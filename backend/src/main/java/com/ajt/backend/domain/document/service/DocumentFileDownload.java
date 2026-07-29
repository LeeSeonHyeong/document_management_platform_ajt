package com.ajt.backend.domain.document.service;

import org.springframework.core.io.Resource;

/**
 * 원본문서 파일 다운로드 응답 재료입니다.
 * 컨트롤러가 이 값으로 Content-Type과 다운로드 파일명 헤더를 구성합니다.
 * 실제 서버 저장 경로는 담지 않습니다(노출 금지, FR-DOC-016).
 */
public record DocumentFileDownload(Resource resource, String fileName, String contentType) {
}
