package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Invoice;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryInvoiceRepository implements InvoiceRepository {
    private final ConcurrentMap<String, Invoice> invoices = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> invoiceIdsByOrderId = new ConcurrentHashMap<>();

    @Override
    public Optional<Invoice> findById(String invoiceId) {
        return Optional.ofNullable(invoices.get(invoiceId));
    }

    @Override
    public Optional<Invoice> findByOrderId(String orderId) {
        return Optional.ofNullable(invoiceIdsByOrderId.get(orderId)).flatMap(this::findById);
    }

    @Override
    public void save(Invoice invoice) {
        invoices.put(invoice.invoiceId(), invoice);
        invoiceIdsByOrderId.putIfAbsent(invoice.orderId(), invoice.invoiceId());
    }
}
