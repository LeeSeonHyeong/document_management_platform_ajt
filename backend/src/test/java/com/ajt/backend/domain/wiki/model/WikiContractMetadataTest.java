package com.ajt.backend.domain.wiki.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WikiContractMetadataTest {

    @Test
    void storesSummaryAndTracksTheIndexedContentHash() {
        Wiki wiki = Wiki.create("D1-D2", 10L, "휴가 규정");
        String contentHash = "a".repeat(64);

        wiki.changeSummary("정리된 휴가 규정");
        wiki.changeContentHash(contentHash);
        wiki.markSearchIndexed();

        assertThat(wiki.summary()).isEqualTo("정리된 휴가 규정");
        assertThat(wiki.contentHash()).isEqualTo(contentHash);
        assertThat(wiki.searchIndexedHash()).isEqualTo(contentHash);
    }
}
