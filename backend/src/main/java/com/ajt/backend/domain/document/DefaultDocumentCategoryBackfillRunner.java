package com.ajt.backend.domain.document;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 애플리케이션 시작 시 이미 존재하는 Wiki 공간의 기본 문서 카테고리를 보충합니다. */
@Component
public class DefaultDocumentCategoryBackfillRunner implements ApplicationRunner {

    private final DefaultDocumentCategoryEnsurer ensurer;

    public DefaultDocumentCategoryBackfillRunner(DefaultDocumentCategoryEnsurer ensurer) {
        this.ensurer = ensurer;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensurer.ensureForExistingScopes();
    }
}
