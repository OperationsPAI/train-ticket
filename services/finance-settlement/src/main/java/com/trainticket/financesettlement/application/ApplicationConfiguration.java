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
    ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
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
        EventPublisher eventPublisher,
        DomainEventEnvelopeMapper envelopeMapper,
        Clock clock
    ) {
        return new FinanceSettlementApplicationService(revenueRecognitions, reconciliationCases, invoices, eventPublisher, envelopeMapper, clock);
    }

    @Bean
    FinanceSettlementEventHandler financeSettlementEventHandler(
        ConsumedEventLogRepository consumedEvents,
        PaymentIntentOrderReferenceRepository paymentIntentOrderReferences,
        SegmentBookingOrderReferenceRepository segmentBookingOrderReferences,
        Clock clock,
        FinanceSettlementApplicationService service
    ) {
        return new FinanceSettlementEventHandler(consumedEvents, paymentIntentOrderReferences, segmentBookingOrderReferences, clock, service);
    }
}
