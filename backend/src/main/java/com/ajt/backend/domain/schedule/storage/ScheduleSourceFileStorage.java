package com.ajt.backend.domain.schedule.storage;

import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * 일정 원본문서 저장소입니다.
 * 일정 원본문서는 Wiki·문서 검색에 사용하지 않으므로 documents 계열과 분리된
 * schedule-sources 네임스페이스에 보관한다.
 */
public interface ScheduleSourceFileStorage {

    String storeOriginal(String sourceGroupKey, MultipartFile file) throws IOException;

    String storeParsedMarkdown(String sourceGroupKey, String parsedMarkdown) throws IOException;

    Resource load(String storedPath);

    /**
     * 원본·파싱 파일과 sourceGroupKey 디렉터리를 함께 제거합니다.
     * 같은 원본문서에서 나온 마지막 일정이 삭제될 때 호출한다.
     */
    void deleteSourceGroup(String sourceGroupKey) throws IOException;
}
