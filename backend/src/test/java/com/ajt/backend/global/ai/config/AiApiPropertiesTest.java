package com.ajt.backend.global.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ajt.backend.global.ai.client.SourceType;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "ajt.ai.base-url=http://ai.example:8100",
        "ajt.ai.internal-api-key=test-key",
        "ajt.ai.connect-timeout=7s",
        "ajt.ai.read-timeout=181s"
})
class AiApiPropertiesTest {

    @Autowired
    private AiApiProperties properties;

    @Test
    void bindsAiProperties() {
        assertThat(properties.baseUrl()).isEqualTo("http://ai.example:8100");
        assertThat(properties.internalApiKey()).isEqualTo("test-key");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(181));
    }

    @Test
    void sourceTypeUsesContractWireValues() {
        assertThat(SourceType.WIKI.value()).isEqualTo("wiki");
        assertThat(SourceType.SCHEDULE.value()).isEqualTo("schedule");
    }
}
