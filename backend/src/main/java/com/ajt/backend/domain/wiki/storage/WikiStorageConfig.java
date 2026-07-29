package com.ajt.backend.domain.wiki.storage;

import com.ajt.backend.domain.document.storage.DocumentStorageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiki 본문·목차 파일 저장소 설정입니다.
 * Wiki 파일은 원본문서와 같은 {@code wiki/{scopeKey}/} 트리 아래에 있어야 하므로 저장 루트를 공유합니다.
 */
@Configuration
public class WikiStorageConfig {

    @Bean
    WikiFileStorage wikiFileStorage(DocumentStorageProperties properties) {
        return new LocalWikiFileStorage(properties.rootPath());
    }
}
