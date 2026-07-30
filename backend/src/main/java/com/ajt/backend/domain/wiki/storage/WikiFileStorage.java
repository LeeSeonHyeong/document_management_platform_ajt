package com.ajt.backend.domain.wiki.storage;

import com.ajt.backend.domain.wiki.model.Wiki;
import java.io.IOException;

/**
 * Wiki 본문 Markdown과 공간별 목차(index.md)를 읽고 쓰는 저장소입니다.
 * 원본문서 저장소와 같은 루트를 공유하며 {@code wiki/{scopeKey}/} 아래를 사용합니다.
 */
public interface WikiFileStorage {

    /** 검증된 Wiki 상대 경로에 본문을 저장합니다. */
    void storeWikiMarkdown(String wikiPath, String contentMarkdown) throws IOException;

    /**
     * @deprecated 반영기가 AI 발급 경로를 직접 전달하도록 전환 중인 호환 메서드입니다.
     */
    @Deprecated
    default void storeWikiMarkdown(String scopeKey, long wikiId, String contentMarkdown) throws IOException {
        storeWikiMarkdown(Wiki.storagePathOf(scopeKey, wikiId), contentMarkdown);
    }

    /**
     * Wiki 본문을 읽습니다. 파일이 없으면 빈 문자열을 반환합니다.
     */
    String readWikiMarkdown(String wikiPath) throws IOException;

    void deleteWikiMarkdown(String wikiPath) throws IOException;

    /**
     * 공간의 목차를 저장하고 저장 경로를 반환합니다.
     */
    String storeIndex(String scopeKey, String indexMarkdown) throws IOException;

    /**
     * 공간의 목차를 읽습니다. 아직 목차가 없는 새 공간이면 빈 목차를 반환합니다.
     */
    String readIndex(String scopeKey) throws IOException;
}
