package com.trainticket.groupbooking.api;

import com.trainticket.groupbooking.application.BookingDetail;
import com.trainticket.groupbooking.application.GroupBookingService;
import com.trainticket.groupbooking.application.NotFoundException;
import com.trainticket.groupbooking.domain.DomainRuleViolation;
import com.trainticket.platformkit.messaging.PrefixedIds;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/group-bookings")
public class GroupBookingController {
    private final GroupBookingService service;

    public GroupBookingController(GroupBookingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BookingDetail> create(@RequestBody CreateRequest request, HttpServletRequest httpRequest) {
        request.validate();
        BookingDetail detail = service.create(
            new GroupBookingService.CreateGroupBookingCommand(
                request.organizerRef(),
                request.segmentRefs(),
                request.targetTravelerCount(),
                request.fare().currency(),
                request.fare().minorUnits(),
                request.fare().discountBasisPoints(),
                request.fare().negotiationRef()
            ),
            correlationId(httpRequest)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @PutMapping("/{groupBookingId}/members")
    public BookingDetail addMembers(@PathVariable String groupBookingId, @RequestBody AddMembersRequest request, HttpServletRequest httpRequest) {
        request.validate();
        return service.addMembers(
            groupBookingId,
            new GroupBookingService.AddMembersCommand(request.members().stream()
                .map(member -> new GroupBookingService.MemberCommand(member.travelerRef(), member.maskedDocumentRef()))
                .toList()),
            correlationId(httpRequest)
        );
    }

    @PostMapping("/{groupBookingId}/confirm")
    public BookingDetail confirm(@PathVariable String groupBookingId, @RequestBody ConfirmRequest request, HttpServletRequest httpRequest) {
        request.validate();
        return service.confirm(groupBookingId, new GroupBookingService.ConfirmGroupBookingCommand(request.capacityHoldId()), correlationId(httpRequest));
    }

    @PostMapping("/{groupBookingId}/cancel")
    public BookingDetail cancel(@PathVariable String groupBookingId, @RequestBody CancelRequest request, HttpServletRequest httpRequest) {
        request.validate();
        return service.cancel(groupBookingId, new GroupBookingService.CancelGroupBookingCommand(request.memberIds(), request.reason()), correlationId(httpRequest));
    }

    @GetMapping("/{groupBookingId}")
    public BookingDetail get(@PathVariable String groupBookingId) {
        return service.get(groupBookingId);
    }

    @ExceptionHandler(DomainRuleViolation.class)
    ResponseEntity<Map<String, String>> domainError(DomainRuleViolation exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "VALIDATION_FAILED", "message", exception.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND", "message", exception.getMessage()));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader("X-Correlation-Id");
        return PrefixedIds.isCorrelationId(header) ? header : PrefixedIds.newCorrelationId();
    }

    public record CreateRequest(String organizerRef, List<String> segmentRefs, int targetTravelerCount, FareRequest fare) {
        void validate() {
            requireText(organizerRef, "organizerRef");
            if (segmentRefs == null || segmentRefs.isEmpty()) {
                throw new DomainRuleViolation("segmentRefs are required");
            }
            if (targetTravelerCount < 10) {
                throw new DomainRuleViolation("targetTravelerCount must be at least 10");
            }
            if (fare == null) {
                throw new DomainRuleViolation("fare is required");
            }
        }
    }

    public record FareRequest(String currency, long minorUnits, int discountBasisPoints, String negotiationRef) {}
    public record AddMembersRequest(List<MemberRequest> members) {
        void validate() {
            if (members == null || members.isEmpty()) {
                throw new DomainRuleViolation("members are required");
            }
        }
    }
    public record MemberRequest(String travelerRef, String maskedDocumentRef) {}
    public record ConfirmRequest(String capacityHoldId) {
        void validate() {
            requireText(capacityHoldId, "capacityHoldId");
        }
    }
    public record CancelRequest(List<String> memberIds, String reason) {
        void validate() {
            requireText(reason, "reason");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolation(name + " is required");
        }
    }
}
