package com.ajt.backend.domain.document.storage;

import java.io.IOException;

/** 공개 범위 변경에서 원본·파싱 파일 이동을 되돌리는 작업 단위입니다. */
public interface DocumentFileMutation {

    String originalPath();

    String parsedPath();

    void rollback() throws IOException;

    void discardBackup();
}
