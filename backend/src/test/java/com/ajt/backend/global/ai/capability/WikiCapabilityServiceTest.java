package com.ajt.backend.global.ai.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WikiCapabilityServiceTest {

    private final WikiCapabilityService service = new WikiCapabilityService();

    @Test
    void issuesCapabilityBoundToScopeAndVersion() {
        String rawCapability = service.issue("D1-D2", 47L, Duration.ofMinutes(1));

        WikiCapability capability = service.require(rawCapability, "D1-D2");

        assertThat(capability.scopeVersion()).isEqualTo(47L);
        assertThat(capability.scopeKey()).isEqualTo("D1-D2");
    }

    @Test
    void rejectsExpiredCapabilityWithoutRevealingScope() {
        String rawCapability = service.issue("D1-D2", 47L, Duration.ZERO);

        assertThatThrownBy(() -> service.require(rawCapability, "D1-D2"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_CAPABILITY_EXPIRED);
    }

    @Test
    void rejectsCapabilityForAnotherScope() {
        String rawCapability = service.issue("D1-D2", 47L, Duration.ofMinutes(1));

        assertThatThrownBy(() -> service.require(rawCapability, "ALL"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_CAPABILITY_EXPIRED);
    }
}
