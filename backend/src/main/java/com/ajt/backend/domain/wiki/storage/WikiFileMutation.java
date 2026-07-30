package com.ajt.backend.domain.wiki.storage;

import java.io.IOException;

/**
 * 하나의 Wiki 반영에서 발생한 파일 변경을 보상할 수 있는 작업 단위입니다.
 *
 * <p>파일 시스템은 DB 트랜잭션에 참여하지 않으므로, 반영 중 예외나 DB 롤백이 나면
 * {@link #rollback()}으로 파일을 반영 전 상태로 되돌립니다.
 */
public interface WikiFileMutation {

    void storeWikiMarkdown(String wikiPath, String contentMarkdown) throws IOException;

    void deleteWikiMarkdown(String wikiPath) throws IOException;

    String storeIndex(String scopeKey, String indexMarkdown) throws IOException;

    void rollback() throws IOException;

    void discardBackup();
}
