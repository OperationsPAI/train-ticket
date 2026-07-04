package com.trainticket.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final Clock clock;

    public HealthController() {
        this(Clock.systemUTC());
    }

    HealthController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", Application.profile());
    }

    @GetMapping({"/live", "/livez"})
    public Map<String, Object> live() {
        return Map.of("status", "live", "serviceId", Application.profile().serviceId());
    }

    @GetMapping({"/ready", "/readyz"})
    public Map<String, Object> ready() {
        return Map.of("status", "ready", "serviceId", Application.profile().serviceId());
    }

    @GetMapping("/metadata")
    public Map<String, Object> metadata() {
        return Map.of(
            "service", Application.profile(),
            "generatedAt", Instant.now(clock).toString()
        );
    }
}
