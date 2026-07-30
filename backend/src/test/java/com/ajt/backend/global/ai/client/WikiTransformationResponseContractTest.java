package com.ajt.backend.global.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WikiTransformationResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsWikiCategoryReferenceAndAgentIssuedPathFromCreateChange() throws Exception {
        WikiTransformationResponse response = objectMapper.readValue("""
                {
                  "summary": "휴가 규정 Wiki를 생성했습니다.",
                  "categoryChanges": [
                    {
                      "action": "create",
                      "tempCategoryId": "category-temp-1",
                      "name": "휴가 및 근태"
                    }
                  ],
                  "wikiChanges": [
                    {
                      "action": "create",
                      "tempWikiId": "wiki-temp-1",
                      "wikiCategoryRef": "category-temp-1",
                      "wikiPath": "wiki/D1-D2/pages/a3f2c1d4.md",
                      "title": "휴가 규정",
                      "contentMarkdown": "# 휴가 규정"
                    }
                  ],
                  "relationChanges": [],
                  "indexEntries": []
                }
                """, WikiTransformationResponse.class);

        WikiTransformationResponse.WikiChange change = response.wikiChanges().getFirst();
        assertThat(change.wikiCategoryRef()).isEqualTo("category-temp-1");
        assertThat(change.wikiPath()).isEqualTo("wiki/D1-D2/pages/a3f2c1d4.md");
    }
}
