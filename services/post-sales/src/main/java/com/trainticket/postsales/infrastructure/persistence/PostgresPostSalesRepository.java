package com.trainticket.postsales.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.platformkit.persistence.SnapshotRepository;
import com.trainticket.postsales.application.RefundAlreadyInProgressException;
import com.trainticket.postsales.application.PostSalesRepository;
import com.trainticket.postsales.domain.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresPostSalesRepository implements PostSalesRepository {
    private final ObjectMapper objectMapper;
    private final SnapshotRepository<PostSalesSnapshot> snapshots;
    private final JdbcTemplate jdbc;

    public PostgresPostSalesRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.snapshots = new SnapshotRepository<>(dataSource, objectMapper, "post_sales_case_snapshots", PostSalesSnapshot.class);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override public void save(PostSalesCase postSalesCase) {
        if (isRefundConflictCase(postSalesCase) && isActive(postSalesCase)) {
            reserveActiveRefundSlot(postSalesCase);
        }
        long version = snapshots.save(postSalesCase.caseId(), postSalesCase.version(), snapshot(postSalesCase));
        postSalesCase.withVersion(version);
        if (isRefundConflictCase(postSalesCase) && !isActive(postSalesCase)) {
            releaseActiveRefundSlot(postSalesCase);
        }
    }

    @Override public Optional<PostSalesCase> findById(String caseId) {
        return snapshots.get(strip(caseId)).map(s -> toCase(s.data()).withVersion(s.version()));
    }

    @Override public Optional<PostSalesCase> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("SELECT id FROM post_sales_case_snapshots WHERE data->>'idempotencyKey' = ? LIMIT 1", rs -> rs.next() ? findById(rs.getString("id")) : Optional.empty(), idempotencyKey);
    }

    @Override public Optional<PostSalesCase> findActiveRefundCaseForOrder(String journeyOrderId) {
        return jdbc.query("""
            SELECT s.id
              FROM post_sales_active_refunds a
              JOIN post_sales_case_snapshots s ON s.id = a.case_id
             WHERE a.journey_order_id = ?
             LIMIT 1
            """, rs -> rs.next() ? findById(rs.getString("id")) : Optional.empty(), journeyOrderId);
    }

    @Override public List<PostSalesCase> findAll() {
        return jdbc.query("SELECT version, data::text AS data FROM post_sales_case_snapshots", (rs, rowNum) -> toCase(readSnapshot(rs.getString("data"))).withVersion(rs.getLong("version")));
    }


    private void reserveActiveRefundSlot(PostSalesCase postSalesCase) {
        try {
            jdbc.update(
                "INSERT INTO post_sales_active_refunds (journey_order_id, case_id) VALUES (?, ?) ON CONFLICT (journey_order_id) DO UPDATE SET case_id = EXCLUDED.case_id, updated_at = now() WHERE post_sales_active_refunds.case_id = EXCLUDED.case_id",
                postSalesCase.journeyOrderId(),
                postSalesCase.caseId()
            );
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new RefundAlreadyInProgressException(existingActiveRefundCaseId(postSalesCase.journeyOrderId()).orElse(null));
        }
        String reservedCaseId = existingActiveRefundCaseId(postSalesCase.journeyOrderId()).orElse(null);
        if (!postSalesCase.caseId().equals(reservedCaseId)) {
            throw new RefundAlreadyInProgressException(reservedCaseId);
        }
    }

    private void releaseActiveRefundSlot(PostSalesCase postSalesCase) {
        jdbc.update("DELETE FROM post_sales_active_refunds WHERE journey_order_id = ? AND case_id = ?", postSalesCase.journeyOrderId(), postSalesCase.caseId());
    }

    private Optional<String> existingActiveRefundCaseId(String journeyOrderId) {
        return jdbc.query("SELECT case_id FROM post_sales_active_refunds WHERE journey_order_id = ?", rs -> rs.next() ? Optional.of(rs.getString("case_id")) : Optional.empty(), journeyOrderId);
    }

    private static boolean isRefundConflictCase(PostSalesCase postSalesCase) {
        return postSalesCase.caseType() == PostSalesCaseType.REFUND
            || postSalesCase.caseType() == PostSalesCaseType.CANCELLATION
            || postSalesCase.caseType() == PostSalesCaseType.REBOOK
            || postSalesCase.caseType() == PostSalesCaseType.CHANGE;
    }

    private static boolean isActive(PostSalesCase postSalesCase) {
        return postSalesCase.status() != PostSalesCaseStatus.REJECTED
            && postSalesCase.status() != PostSalesCaseStatus.CANCELLED
            && postSalesCase.status() != PostSalesCaseStatus.FAILED;
    }

    private PostSalesSnapshot readSnapshot(String json) {
        try { return PostSalesSnapshot.of((ObjectNode) objectMapper.readTree(json)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("post-sales snapshot JSON could not be decoded", exception); }
    }

    private PostSalesSnapshot snapshot(PostSalesCase c) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("caseId", c.caseId()); root.put("journeyOrderId", c.journeyOrderId()); root.put("caseType", c.caseType().name());
        root.set("scope", objectMapper.valueToTree(c.scope())); root.put("reasonCode", c.reasonCode()); root.put("actorRef", c.actorRef()); root.put("idempotencyKey", c.idempotencyKey());
        root.put("status", c.status().name()); if (c.terminalReason()==null) root.putNull("terminalReason"); else root.put("terminalReason", c.terminalReason());
        if (c.decision()==null) root.putNull("decision"); else root.set("decision", decision(c.decision()));
        ArrayNode steps = root.putArray("executionPlan"); c.executionPlan().forEach(s -> steps.add(step(s)));
        if (c.executionPlanAggregate()==null) root.putNull("executionPlanAggregate"); else root.set("executionPlanAggregate", plan(c.executionPlanAggregate()));
        return PostSalesSnapshot.of(root);
    }

    private PostSalesCase toCase(PostSalesSnapshot snap) {
        ObjectNode r = snap.data();
        return PostSalesCase.rehydrate(text(r,"caseId"), text(r,"journeyOrderId"), PostSalesCaseType.valueOf(text(r,"caseType")), objectMapper.convertValue(r.path("scope"), PostSalesScope.class), text(r,"reasonCode"), text(r,"actorRef"), text(r,"idempotencyKey"), PostSalesCaseStatus.valueOf(text(r,"status")), r.path("decision").isNull()?null:decision(r.path("decision")), steps(r.path("executionPlan")), r.path("executionPlanAggregate").isNull()?null:plan(r.path("executionPlanAggregate")), r.path("terminalReason").asText(null), List.of());
    }

    private ObjectNode decision(PostSalesDecision decision) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("caseId", decision.caseId());
        node.put("version", decision.version());
        node.put("kind", decision.kind().name());
        node.put("eligible", decision.eligible());
        node.put("reasonCode", decision.reasonCode());
        node.set("ruleSnapshot", objectMapper.valueToTree(decision.ruleSnapshot()));
        node.set("amountSnapshot", amount(decision.amountSnapshot()));
        if (decision.changeFlowSnapshot() == null) {
            node.putNull("changeFlowSnapshot");
        } else {
            node.set("changeFlowSnapshot", objectMapper.valueToTree(decision.changeFlowSnapshot()));
        }
        if (decision.refundAssessment() == null) {
            node.putNull("refundAssessment");
        } else {
            node.set("refundAssessment", objectMapper.valueToTree(decision.refundAssessment()));
        }
        if (decision.changeAssessment() == null) {
            node.putNull("changeAssessment");
        } else {
            node.set("changeAssessment", objectMapper.valueToTree(decision.changeAssessment()));
        }
        node.put("quotedAt", decision.quotedAt().toString());
        node.put("expiresAt", decision.expiresAt().toString());
        return node;
    }
    private PostSalesDecision decision(JsonNode node) {
        AmountDecisionSnapshot amount = amount(node.path("amountSnapshot"));
        RefundAssessment refundAssessment = node.path("refundAssessment").isMissingNode() || node.path("refundAssessment").isNull()
            ? legacyRefund(node, amount)
            : objectMapper.convertValue(node.path("refundAssessment"), RefundAssessment.class);
        ChangeAssessment changeAssessment = node.path("changeAssessment").isMissingNode() || node.path("changeAssessment").isNull()
            ? legacyChange(node, amount)
            : objectMapper.convertValue(node.path("changeAssessment"), ChangeAssessment.class);
        return new PostSalesDecision(
            text(node, "caseId"),
            node.path("version").asInt(),
            DecisionKind.valueOf(text(node, "kind")),
            node.path("eligible").asBoolean(),
            text(node, "reasonCode"),
            objectMapper.convertValue(node.path("ruleSnapshot"), RuleEvaluationSnapshot.class),
            amount,
            node.path("changeFlowSnapshot").isNull() ? null : objectMapper.convertValue(node.path("changeFlowSnapshot"), ChangeFlowSnapshot.class),
            refundAssessment,
            changeAssessment,
            Instant.parse(text(node, "quotedAt")),
            Instant.parse(text(node, "expiresAt"))
        );
    }
    private ObjectNode amount(AmountDecisionSnapshot amount) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("feeAmount", money(amount.feeAmount()));
        node.set("refundAmount", money(amount.refundAmount()));
        node.set("extraChargeAmount", money(amount.extraChargeAmount()));
        node.put("explanation", amount.explanation());
        node.set("componentDecisions", objectMapper.valueToTree(amount.componentDecisions()));
        return node;
    }
    private AmountDecisionSnapshot amount(JsonNode node) {
        List<RefundComponentDecision> componentDecisions = node.path("componentDecisions").isMissingNode()
            ? List.of()
            : objectMapper.convertValue(node.path("componentDecisions"), objectMapper.getTypeFactory().constructCollectionType(List.class, RefundComponentDecision.class));
        return new AmountDecisionSnapshot(money(node.path("feeAmount")), money(node.path("refundAmount")), money(node.path("extraChargeAmount")), text(node, "explanation"), componentDecisions);
    }
    private RefundAssessment legacyRefund(JsonNode node, AmountDecisionSnapshot amount) {
        return DecisionKind.valueOf(text(node, "kind")) == DecisionKind.REFUND
            ? new RefundAssessment(amount.refundAmount(), amount.feeAmount(), java.math.BigDecimal.ZERO, "LEGACY", amount.explanation(), RefundClassification.VOLUNTARY, amount.componentDecisions())
            : null;
    }

    private ChangeAssessment legacyChange(JsonNode node, AmountDecisionSnapshot amount) {
        return DecisionKind.valueOf(text(node, "kind")) == DecisionKind.CHANGE
            ? new ChangeAssessment(amount.feeAmount(), amount.extraChargeAmount().isZero() ? amount.refundAmount() : amount.extraChargeAmount(), amount.extraChargeAmount(), amount.refundAmount(), amount.explanation())
            : null;
    }
    private ObjectNode money(Money m){ ObjectNode n=objectMapper.createObjectNode(); n.put("currency", m.currency().getCurrencyCode()); n.put("minorUnits", m.toMinorUnits()); return n; }
    private Money money(JsonNode n){ return Money.fromMinorUnits(n.path("minorUnits").asLong(), text(n,"currency")); }
    private ObjectNode step(PostSalesStep s){ ObjectNode n=objectMapper.createObjectNode(); n.put("type",s.type().name()); n.put("targetContext",s.targetContext()); n.put("idempotencyKey",s.idempotencyKey()); n.put("maxRetries",s.maxRetries()); n.put("status",s.status().name()); if(s.externalRef()==null)n.putNull("externalRef"); else n.put("externalRef",s.externalRef()); if(s.failureReason()==null)n.putNull("failureReason"); else n.put("failureReason",s.failureReason()); if(s.completedAt()==null)n.putNull("completedAt"); else n.put("completedAt",s.completedAt().toString()); return n; }
    private PostSalesStep step(JsonNode n){ return PostSalesStep.rehydrate(PostSalesStepType.valueOf(text(n,"type")), text(n,"targetContext"), text(n,"idempotencyKey"), n.path("maxRetries").asInt(), PostSalesStepStatus.valueOf(text(n,"status")), n.path("externalRef").asText(null), n.path("failureReason").asText(null), n.path("completedAt").asText(null)==null?null:Instant.parse(n.path("completedAt").asText())); }
    private List<PostSalesStep> steps(JsonNode n){ List<PostSalesStep> out=new ArrayList<>(); n.forEach(x -> out.add(step(x))); return out; }
    private ObjectNode plan(PostSalesExecutionPlan p){ ObjectNode n=objectMapper.createObjectNode(); n.put("caseId",p.caseId()); n.put("version",p.version()); n.put("status",p.status().name()); ArrayNode a=n.putArray("steps"); p.steps().forEach(s -> a.add(step(s))); return n; }
    private PostSalesExecutionPlan plan(JsonNode n){ return PostSalesExecutionPlan.rehydrate(text(n,"caseId"), n.path("version").asInt(), steps(n.path("steps")), PostSalesStepStatus.valueOf(text(n,"status"))); }
    private static String strip(String id){ return id!=null && id.startsWith("psc-") ? id.substring(4) : id; }
    private static String text(JsonNode n,String f){ String v=n.path(f).asText(null); if(v==null||v.isBlank()) throw new IllegalStateException(f+" missing"); return v; }
    public record PostSalesSnapshot(@com.fasterxml.jackson.annotation.JsonValue ObjectNode data){ @com.fasterxml.jackson.annotation.JsonCreator(mode=com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING) public static PostSalesSnapshot of(ObjectNode data){ return new PostSalesSnapshot(data); } }
}
