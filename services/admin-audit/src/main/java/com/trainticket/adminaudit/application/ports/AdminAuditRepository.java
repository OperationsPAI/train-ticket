package com.trainticket.adminaudit.application.ports;

import com.trainticket.adminaudit.domain.AuditEntry;
import com.trainticket.adminaudit.domain.ManualAction;
import com.trainticket.adminaudit.domain.OperatorIdentity;
import java.util.List;
import java.util.Optional;

public interface AdminAuditRepository {
    Optional<OperatorIdentity> findOperator(String operatorId);
    Optional<OperatorIdentity> findOperatorByEmail(String email);
    void saveOperator(OperatorIdentity operator);

    Optional<ManualAction> findManualAction(String manualActionId);
    void saveManualAction(ManualAction manualAction);

    void saveAuditEntry(AuditEntry entry);
    List<AuditEntry> listAuditEntries(String businessRef, int limit, int offset);
    int countAuditEntries(String businessRef);
}
