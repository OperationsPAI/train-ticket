package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.Map;

public sealed interface FinanceSettlementEvent permits
    RevenueRecognized,
    RevenueRecognitionReversed,
    ReconciliationCaseOpened,
    ReconciliationCaseResolved,
    ReconciliationCompleted,
    SupplierSettlementCalculated,
    FeeAccrued,
    InvoiceGenerated,
    SettlementViewRebuilt {
    String eventId();
    Instant occurredAt();
    String sourceCommandId();
    String causationId();
    String correlationId();
    int schemaVersion();
    Map<String, String> attributes();
    default String eventType() { return getClass().getSimpleName(); }
}
