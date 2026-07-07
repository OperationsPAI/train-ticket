package com.trainticket.bookingorchestration;

import java.time.Clock;
import com.trainticket.bookingorchestration.infrastructure.persistence.BookingOrchestrationReadiness;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final Clock clock;
    private final BookingOrchestrationReadiness readiness;

    public HealthController() {
        this(Clock.systemUTC(), new BookingOrchestrationReadiness(java.util.Optional.empty()));
    }

    public HealthController(BookingOrchestrationReadiness readiness) {
        this(Clock.systemUTC(), readiness);
    }

    HealthController(Clock clock) {
        this(clock, new BookingOrchestrationReadiness(java.util.Optional.empty()));
    }

    HealthController(Clock clock, BookingOrchestrationReadiness readiness) {
        this.clock = clock;
        this.readiness = readiness;
    }

    @GetMapping({"/health", "/healthz"})
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", Application.profile());
    }

    @GetMapping({"/live", "/livez"})
    public Map<String, Object> live() {
        return Map.of("status", "live", "serviceId", Application.profile().serviceId());
    }

    @GetMapping({"/ready", "/readyz"})
    public ResponseEntity<Map<String, Object>> ready() {
        boolean ready = readiness.isReady();
        Map<String, Object> body = Map.of("status", ready ? "ready" : "not_ready", "serviceId", Application.profile().serviceId());
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @GetMapping("/metadata")
    public Map<String, Object> metadata() {
        return Map.of(
            "service", Application.profile(),
            "generatedAt", Instant.now(clock).toString()
        );
    }
}
