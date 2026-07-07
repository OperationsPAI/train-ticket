package com.trainticket.adminaudit.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.adminaudit.application.ports.AdminAuditRepository;
import com.trainticket.adminaudit.domain.*;
import com.trainticket.platformkit.persistence.SnapshotRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresAdminAuditRepository implements AdminAuditRepository {
    private final ObjectMapper objectMapper;
    private final SnapshotRepository<JsonSnapshot> operators;
    private final SnapshotRepository<JsonSnapshot> actions;
    private final SnapshotRepository<JsonSnapshot> audits;
    private final JdbcTemplate jdbc;

    public PostgresAdminAuditRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.operators = new SnapshotRepository<>(dataSource, objectMapper, "operator_identity_snapshots", JsonSnapshot.class);
        this.actions = new SnapshotRepository<>(dataSource, objectMapper, "manual_action_snapshots", JsonSnapshot.class);
        this.audits = new SnapshotRepository<>(dataSource, objectMapper, "audit_entry_snapshots", JsonSnapshot.class);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override public Optional<OperatorIdentity> findOperator(String operatorId) { return operators.get(operatorId).map(s -> operator(s.data().data()).withVersion(s.version())); }
    @Override public Optional<OperatorIdentity> findOperatorByEmail(String email) { return jdbc.query("SELECT id FROM operator_identity_snapshots WHERE lower(data->>'email') = lower(?) LIMIT 1", rs -> rs.next()?findOperator(rs.getString("id")):Optional.empty(), email); }
    @Override public void saveOperator(OperatorIdentity operator) { operator.withVersion(operators.save(operator.operatorId(), operator.version(), json(operator))); }
    @Override public Optional<ManualAction> findManualAction(String id) { return actions.get(id).map(s -> action(s.data().data()).withVersion(s.version())); }
    @Override public void saveManualAction(ManualAction action) { action.withVersion(actions.save(action.manualActionId(), action.version(), json(action))); }
    @Override public void saveAuditEntry(AuditEntry entry) { audits.save(entry.entryId(), 0, json(entry)); }
    @Override public List<AuditEntry> listAuditEntries(String businessRef, int limit, int offset) {
        if (businessRef == null || businessRef.isBlank()) return jdbc.query("SELECT data::text AS data FROM audit_entry_snapshots ORDER BY (data->>'recordedAt')::timestamptz DESC LIMIT ? OFFSET ?", (rs,n)->entry(read(rs.getString("data"))), limit, offset);
        return jdbc.query("SELECT data::text AS data FROM audit_entry_snapshots WHERE data->>'resourceRef' = ? ORDER BY (data->>'recordedAt')::timestamptz DESC LIMIT ? OFFSET ?", (rs,n)->entry(read(rs.getString("data"))), businessRef, limit, offset);
    }
    @Override public int countAuditEntries(String businessRef) { Integer c = (businessRef == null || businessRef.isBlank()) ? jdbc.queryForObject("SELECT count(*) FROM audit_entry_snapshots", Integer.class) : jdbc.queryForObject("SELECT count(*) FROM audit_entry_snapshots WHERE data->>'resourceRef' = ?", Integer.class, businessRef); return c == null ? 0 : c; }

    private JsonSnapshot json(OperatorIdentity o){ ObjectNode n=objectMapper.createObjectNode(); n.put("operatorId",o.operatorId()); n.put("email",o.email()); n.put("role",o.role().name()); n.set("scopes", objectMapper.valueToTree(o.scopes().stream().map(Enum::name).toList())); n.put("active",o.active()); n.put("registeredAt",o.registeredAt().toString()); return JsonSnapshot.of(n); }
    private OperatorIdentity operator(JsonNode n){
        List<String> values = new ArrayList<>();
        n.path("scopes").forEach(scope -> values.add(scope.asText()));
        Set<PermissionScope> scopes = values.stream().map(PermissionScope::valueOf).collect(Collectors.toSet());
        return OperatorIdentity.rehydrate(text(n,"operatorId"), text(n,"email"), OperatorRole.valueOf(text(n,"role")), scopes, n.path("active").asBoolean(), Instant.parse(text(n,"registeredAt")), List.of());
    }
    private JsonSnapshot json(ManualAction a){ ObjectNode n=objectMapper.createObjectNode(); n.put("manualActionId",a.manualActionId()); n.put("targetDomain",a.targetDomain()); n.put("targetCommand",a.targetCommand()); n.put("businessRef",a.businessRef()); n.put("reasonCode",a.reasonCode()); n.put("description",a.description()); n.set("requestedBy", ref(a.requestedBy())); n.put("requiresApproval",a.requiresApproval()); n.put("requestedAt",a.requestedAt().toString()); if(a.approvedBy()==null)n.putNull("approvedBy"); else n.set("approvedBy", ref(a.approvedBy())); if(a.approvedAt()==null)n.putNull("approvedAt"); else n.put("approvedAt",a.approvedAt().toString()); put(n,"rejectionReason",a.rejectionReason()); n.put("state",a.state().name()); put(n,"resultSummary",a.resultSummary()); return JsonSnapshot.of(n); }
    private ManualAction action(JsonNode n){ return ManualAction.rehydrate(text(n,"manualActionId"), text(n,"targetDomain"), text(n,"targetCommand"), text(n,"businessRef"), text(n,"reasonCode"), text(n,"description"), ref(n.path("requestedBy")), n.path("requiresApproval").asBoolean(), Instant.parse(text(n,"requestedAt")), n.path("approvedBy").isNull()?null:ref(n.path("approvedBy")), n.path("approvedAt").isNull()?null:Instant.parse(text(n,"approvedAt")), n.path("rejectionReason").asText(null), ManualActionState.valueOf(text(n,"state")), n.path("resultSummary").asText(null), List.of()); }
    private JsonSnapshot json(AuditEntry e){ return JsonSnapshot.of(objectMapper.valueToTree(e)); }
    private AuditEntry entry(JsonNode n){ return new AuditEntry(text(n,"entryId"), text(n,"actorId"), text(n,"actorDisplayName"), text(n,"actionType"), text(n,"resourceRef"), text(n,"resourceDomain"), text(n,"reasonCode"), text(n,"correlationId"), n.path("resultSummary").asText(null), n.path("correctedEntryId").asText(null), Instant.parse(text(n,"recordedAt"))); }
    private ObjectNode ref(OperatorRef r){ ObjectNode n=objectMapper.createObjectNode(); n.put("operatorId",r.operatorId()); n.put("displayName",r.displayName()); return n; }
    private OperatorRef ref(JsonNode n){ return new OperatorRef(text(n,"operatorId"), text(n,"displayName")); }
    private JsonNode read(String json){ try{return objectMapper.readTree(json);}catch(Exception e){throw new IllegalStateException(e);} }
    private static void put(ObjectNode n,String f,String v){ if(v==null)n.putNull(f); else n.put(f,v); }
    private static String text(JsonNode n,String f){ String v=n.path(f).asText(null); if(v==null||v.isBlank()) throw new IllegalStateException(f+" missing"); return v; }
    public record JsonSnapshot(@com.fasterxml.jackson.annotation.JsonValue ObjectNode data){ @com.fasterxml.jackson.annotation.JsonCreator(mode=com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING) public static JsonSnapshot of(ObjectNode data){ return new JsonSnapshot(data); } }
}
