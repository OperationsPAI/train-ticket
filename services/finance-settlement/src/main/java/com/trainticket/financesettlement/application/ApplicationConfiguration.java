package com.trainticket.financesettlement.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfiguration {
    @Bean
    org.springframework.web.client.RestClient.Builder financeRestClientBuilder() {
        return org.springframework.web.client.RestClient.builder();
    }
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The read-path settings this process started with, read once before
     * anything serves. The event handler, the scheduler and the HTTP adapter all
     * answer out of the same values, so they are resolved where the rest of the
     * service's wiring is rather than at the one call site that reads them
     * first.
     */
    @Bean
    com.trainticket.financesettlement.ServiceSettings serviceSettings() {
        return com.trainticket.financesettlement.ServiceSettings.loadFromEnvironment();
    }

    @Bean
    DomainEventEnvelopeMapper domainEventEnvelopeMapper() {
        return new DomainEventEnvelopeMapper();
    }

    @Bean
    FinanceSettlementApplicationService financeSettlementApplicationService(
        RevenueRecognitionRepository revenueRecognitions,
        ReconciliationCaseRepository reconciliationCases,
        InvoiceRepository invoices,
        FinanceSettlementProjectionRepository projections,
        ReconciliationBatchRepository reconciliationBatches,
        SupplierSettlementRepository supplierSettlements,
        FeeAccrualRepository feeAccruals,
        EventPublisher eventPublisher,
        DomainEventEnvelopeMapper envelopeMapper,
        Clock clock,
        com.trainticket.financesettlement.adapters.http.ChannelStatementFactsClient channelStatementLines,
        com.trainticket.financesettlement.adapters.http.PlatformRefundFactsClient platformRefunds,
        PaymentIntentOrderReferenceRepository paymentIntentOrderReferences,
        com.trainticket.financesettlement.ServiceSettings settings
    ) {
        return new FinanceSettlementApplicationService(revenueRecognitions, reconciliationCases, invoices, projections, reconciliationBatches, supplierSettlements, feeAccruals, eventPublisher, envelopeMapper, clock)
            .withChannelStatementLines(channelStatementLines)
            .withPlatformRefunds(platformRefunds)
            .withPaymentIntentOrderReferences(paymentIntentOrderReferences)
            .withServiceSettings(settings);
    }

    @Bean
    FinanceSettlementEventHandler financeSettlementEventHandler(
        ConsumedEventLogRepository consumedEvents,
        PaymentIntentOrderReferenceRepository paymentIntentOrderReferences,
        SegmentBookingOrderReferenceRepository segmentBookingOrderReferences,
        FinanceSettlementProjectionRepository projections,
        Clock clock,
        FinanceSettlementApplicationService service,
        com.trainticket.financesettlement.adapters.http.ChangePaymentFactsClient changePayments,
        com.trainticket.financesettlement.adapters.http.SegmentBookingFactsClient segmentBookings
    ) {
        return new FinanceSettlementEventHandler(consumedEvents, paymentIntentOrderReferences, segmentBookingOrderReferences, projections, clock, service)
            .withChangePayments(changePayments)
            .withSegmentBookings(segmentBookings);
    }
}
