package com.ajt.backend.global.health;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    @GetMapping("/api/v1/health")
    HealthCheckResponse health() {
        return new HealthCheckResponse("UP", Instant.now());
    }

    record HealthCheckResponse(String status, Instant timestamp) {
    }
}