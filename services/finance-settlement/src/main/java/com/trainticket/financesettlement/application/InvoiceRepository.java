package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Invoice;
import java.util.Optional;

public interface InvoiceRepository {
    Optional<Invoice> findById(String invoiceId);
    Optional<Invoice> findByOrderId(String orderId);
    void save(Invoice invoice);
}
