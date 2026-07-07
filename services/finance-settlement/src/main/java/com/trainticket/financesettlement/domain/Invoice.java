package com.trainticket.financesettlement.domain;

import com.trainticket.platformkit.idempotency.UuidV7;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Invoice {
    private final String invoiceId;
    private final String orderId;
    private final String invoiceNumber;
    private final Money totalAmount;
    private final List<String> revenueRecognitionIds;
    private final Instant generatedAt;
    private final List<FinanceSettlementEvent> domainEvents;
    private long version;

    private Invoice(String invoiceId, String orderId, String invoiceNumber, Money totalAmount, List<String> revenueRecognitionIds, Instant generatedAt) {
        this.invoiceId = requireText(invoiceId, "invoiceId");
        this.orderId = requireText(orderId, "orderId");
        this.invoiceNumber = requireText(invoiceNumber, "invoiceNumber");
        this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount is required");
        if (totalAmount.isNegative() || totalAmount.isZero()) {
            throw new DomainRuleViolation("invoice total amount must be positive");
        }
        this.revenueRecognitionIds = List.copyOf(Objects.requireNonNull(revenueRecognitionIds, "revenueRecognitionIds are required"));
        if (this.revenueRecognitionIds.isEmpty()) {
            throw new DomainRuleViolation("invoice requires at least one revenue recognition");
        }
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt is required");
        this.domainEvents = new ArrayList<>();
    }

    public static Invoice generate(String orderId, Money totalAmount, List<String> revenueRecognitionIds, Instant now, String sourceCommandId, String correlationId) {
        Invoice invoice = new Invoice(
            "inv-" + UuidV7.generate(),
            orderId,
            "INV-" + UuidV7.generate(),
            totalAmount,
            revenueRecognitionIds,
            now
        );
        invoice.domainEvents.add(new InvoiceGenerated(
            invoice.invoiceId, invoice.orderId, invoice.invoiceNumber, invoice.totalAmount,
            invoice.revenueRecognitionIds, invoice.generatedAt,
            EventMetadata.create(now, sourceCommandId, sourceCommandId, correlationId,
                Map.of("invoiceId", invoice.invoiceId, "orderId", invoice.orderId))
        ));
        return invoice;
    }

    public static Invoice rehydrate(String invoiceId, String orderId, String invoiceNumber, Money totalAmount,
                                    List<String> revenueRecognitionIds, Instant generatedAt) {
        return new Invoice(invoiceId, orderId, invoiceNumber, totalAmount, revenueRecognitionIds, generatedAt);
    }

    public Invoice withVersion(long version) {
        if (version < 0) {
            throw new DomainRuleViolation("version must not be negative");
        }
        this.version = version;
        return this;
    }

    public String invoiceId() { return invoiceId; }
    public String orderId() { return orderId; }
    public String invoiceNumber() { return invoiceNumber; }
    public Money totalAmount() { return totalAmount; }
    public List<String> revenueRecognitionIds() { return revenueRecognitionIds; }
    public Instant generatedAt() { return generatedAt; }
    public List<FinanceSettlementEvent> domainEvents() { return Collections.unmodifiableList(domainEvents); }
    public long version() { return version; }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
