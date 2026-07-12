package com.trainticket.marketingcampaign.api;

import com.trainticket.marketingcampaign.application.NotFoundException;
import com.trainticket.marketingcampaign.domain.CampaignWindow;
import com.trainticket.marketingcampaign.domain.DomainException;
import com.trainticket.marketingcampaign.domain.Money;
import com.trainticket.platformkit.messaging.PrefixedIds;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

abstract class CampaignApiSupport {
    protected static String correlationId(HttpServletRequest request) {
        String header = request.getHeader("X-Correlation-Id");
        return PrefixedIds.isCorrelationId(header) ? header : PrefixedIds.newCorrelationId();
    }

    protected static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DomainException(name + " is required");
        }
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Map<String, String>> domainError(DomainException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "VALIDATION_FAILED", "message", exception.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND", "message", exception.getMessage()));
    }

    record MoneyRequest(String currency, long minorUnits) {
        Money toMoney() {
            return new Money(currency, minorUnits);
        }
    }

    record WindowRequest(String validFrom, String validUntil) {
        CampaignWindow toWindow() {
            return new CampaignWindow(Instant.parse(validFrom), Instant.parse(validUntil));
        }
    }
}
