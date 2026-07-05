package com.trainticket.travelerprofile.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.trainticket.travelerprofile.application.ApiDocumentType;
import com.trainticket.travelerprofile.application.CreateTravelerCommand;
import com.trainticket.travelerprofile.application.EligibilityResult;
import com.trainticket.travelerprofile.application.TravelerCreatedView;
import com.trainticket.travelerprofile.application.TravelerProfileService;
import com.trainticket.travelerprofile.application.TravelerProfileView;
import com.trainticket.travelerprofile.application.TravelerType;
import com.trainticket.travelerprofile.application.UpdateTravelerCommand;
import com.trainticket.travelerprofile.application.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/travelers")
public class TravelerController {
    private final TravelerProfileService travelerProfileService;

    public TravelerController(TravelerProfileService travelerProfileService) {
        this.travelerProfileService = travelerProfileService;
    }

    @PostMapping
    public ResponseEntity<TravelerCreatedView> create(
        @RequestBody JsonNode body,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        HttpServletRequest request
    ) {
        requireIdempotencyKey(idempotencyKey);
        CreateTravelerCommand command = new CreateTravelerCommand(
            text(body, "accountId"),
            enumValue(body, "travelerType", TravelerType.class),
            text(body, "givenName"),
            text(body, "familyName"),
            enumValue(body, "documentType", ApiDocumentType.class),
            text(body, "documentNumber"),
            text(body, "contactEmail"),
            text(body, "contactPhone"),
            idempotencyKey.trim(),
            correlationId(request, correlationId)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(travelerProfileService.create(command, fingerprint(request, body)));
    }

    @GetMapping("/{travelerId}")
    public TravelerProfileView get(@PathVariable String travelerId) {
        return travelerProfileService.get(travelerId);
    }

    @PatchMapping("/{travelerId}")
    public TravelerProfileView update(
        @PathVariable String travelerId,
        @RequestBody JsonNode body,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        HttpServletRequest request
    ) {
        requireIdempotencyKey(idempotencyKey);
        UpdateTravelerCommand command = new UpdateTravelerCommand(
            travelerId,
            text(body, "accountId"),
            enumValue(body, "travelerType", TravelerType.class),
            text(body, "givenName"),
            text(body, "familyName"),
            enumValue(body, "documentType", ApiDocumentType.class),
            text(body, "documentNumber"),
            text(body, "contactEmail"),
            text(body, "contactPhone"),
            idempotencyKey.trim(),
            correlationId(request, correlationId)
        );
        return travelerProfileService.update(command, fingerprint(request, body));
    }

    @PostMapping("/{travelerId}/eligibility")
    public EligibilityResult determineEligibility(
        @PathVariable String travelerId,
        @RequestBody(required = false) JsonNode body,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        HttpServletRequest request
    ) {
        requireIdempotencyKey(idempotencyKey);
        return travelerProfileService.determineEligibility(travelerId, idempotencyKey.trim(), correlationId(request, correlationId), fingerprint(request, body));
    }

    private static String fingerprint(HttpServletRequest request, JsonNode body) {
        return TravelerProfileService.fingerprint(request.getMethod(), request.getRequestURI(), body);
    }

    private static String correlationId(HttpServletRequest request, String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue;
        }
        Object attribute = request.getAttribute("X-Correlation-Id");
        return attribute == null ? "corr-unknown" : attribute.toString();
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("Request validation failed", Map.of("Idempotency-Key", "Idempotency-Key header is required"));
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode body, String field, Class<E> enumType) {
        String value = text(body, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException ex) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put(field, "unsupported enum value");
            throw new ValidationException("Request validation failed", details);
        }
    }
}
