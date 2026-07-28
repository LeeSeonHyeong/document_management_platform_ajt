package com.ajt.backend.global.ai.client;

import org.springframework.core.io.ByteArrayResource;

final class TestSourceParseRequestFactory {

    private TestSourceParseRequestFactory() {
    }

    static SourceParseRequest create() {
        return new SourceParseRequest(
                "req-001",
                SourceType.SCHEDULE,
                "schedule-source-001",
                new ByteArrayResource("original document".getBytes()) {
                    @Override
                    public String getFilename() {
                        return "agenda.pdf";
                    }
                },
                "agenda.pdf",
                "application/pdf"
        );
    }
}
