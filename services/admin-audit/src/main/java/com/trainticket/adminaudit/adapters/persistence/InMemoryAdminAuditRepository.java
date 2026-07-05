package com.trainticket.adminaudit.application.ports;

import com.trainticket.adminaudit.application.ports.AdminAuditRepository;
import com.trainticket.adminaudit.domain.AuditEntry;
import com.trainticket.adminaudit.domain.ManualAction;
import com.trainticket.adminaudit.domain.OperatorIdentity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryAdminAuditRepository implements AdminAuditRepository {
    private final ConcurrentMap<String, OperatorIdentity> operators = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ManualAction> manualActions = new ConcurrentHashMap<>();
    private final List<AuditEntry> auditEntries = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public Optional<OperatorIdentity> findOperator(String operatorId) {
        return Optional.ofNullable(operators.get(operatorId));
    }

    @Override
    public Optional<OperatorIdentity> findOperatorByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String normalized = email.toLowerCase(Locale.ROOT);
        return operators.values().stream()
            .filter(operator -> operator.email().toLowerCase(Locale.ROOT).equals(normalized))
            .findFirst();
    }

    @Override
    public void saveOperator(OperatorIdentity operator) {
        operators.put(operator.operatorId(), operator);
    }

    @Override
    public Optional<ManualAction> findManualAction(String manualActionId) {
        return Optional.ofNullable(manualActions.get(manualActionId));
    }

    @Override
    public void saveManualAction(ManualAction manualAction) {
        manualActions.put(manualAction.manualActionId(), manualAction);
    }

    @Override
    public void saveAuditEntry(AuditEntry entry) {
        auditEntries.add(entry);
    }

    @Override
    public List<AuditEntry> listAuditEntries(String businessRef, int limit, int offset) {
        return filteredEntries(businessRef).stream()
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public int countAuditEntries(String businessRef) {
        return filteredEntries(businessRef).size();
    }

    private List<AuditEntry> filteredEntries(String businessRef) {
        synchronized (auditEntries) {
            return auditEntries.stream()
                .filter(entry -> businessRef == null || businessRef.isBlank() || businessRef.equals(entry.resourceRef()))
                .sorted(Comparator.comparing(AuditEntry::recordedAt).reversed())
                .toList();
        }
    }
}
