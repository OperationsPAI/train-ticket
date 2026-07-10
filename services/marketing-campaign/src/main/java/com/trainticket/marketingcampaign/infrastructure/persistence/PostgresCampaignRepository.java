package com.trainticket.marketingcampaign.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.marketingcampaign.application.CampaignRepository;
import com.trainticket.marketingcampaign.application.DomainEventPayloads;
import com.trainticket.marketingcampaign.domain.Campaign;
import com.trainticket.marketingcampaign.domain.CampaignBudget;
import com.trainticket.marketingcampaign.domain.CouponTemplate;
import com.trainticket.marketingcampaign.domain.DomainEvent;
import com.trainticket.marketingcampaign.domain.IssuanceBatch;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import com.trainticket.platformkit.persistence.OutboxAppender;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresCampaignRepository implements CampaignRepository {
    private final JdbcOperations jdbc;
    private final ObjectMapper mapper;
    private final OutboxAppender outbox;

    public PostgresCampaignRepository(DataSource dataSource, ObjectMapper mapper, OutboxAppender outbox) {
        this(new JdbcTemplate(dataSource), mapper, outbox);
    }

    PostgresCampaignRepository(JdbcOperations jdbc, ObjectMapper mapper, OutboxAppender outbox) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.outbox = outbox;
    }

    @Override
    public void saveCampaign(Campaign campaign) {
        saveSnapshot("campaigns", "campaign_id", campaign.campaignId(), campaign.version(), campaign);
        appendEvents(campaign.domainEvents());
    }

    @Override
    public Optional<Campaign> findCampaign(String campaignId) {
        return findSnapshot("SELECT data::text FROM campaigns WHERE campaign_id = ?", SnapshotMapper::campaign, campaignId);
    }

    @Override
    public void saveBudget(CampaignBudget budget) {
        saveSnapshot("campaign_budgets", "budget_id", budget.budgetId(), budget.version(), budget);
        appendEvents(budget.domainEvents());
    }

    @Override
    public Optional<CampaignBudget> findBudget(String budgetId) {
        return findSnapshot("SELECT data::text FROM campaign_budgets WHERE budget_id = ?", SnapshotMapper::budget, budgetId);
    }

    @Override
    public Optional<CampaignBudget> findBudgetByCampaignId(String campaignId) {
        return findSnapshot("SELECT data::text FROM campaign_budgets WHERE campaign_id = ? LIMIT 1", SnapshotMapper::budget, campaignId);
    }

    @Override
    public void saveTemplate(CouponTemplate template) {
        saveSnapshot("coupon_templates", "template_id", template.templateId(), template.version(), template);
        appendEvents(template.domainEvents());
    }

    @Override
    public Optional<CouponTemplate> findTemplate(String templateId) {
        return findSnapshot("SELECT data::text FROM coupon_templates WHERE template_id = ?", SnapshotMapper::template, templateId);
    }

    @Override
    public List<CouponTemplate> findTemplatesByCampaignId(String campaignId) {
        return jdbc.query("SELECT data::text FROM coupon_templates WHERE campaign_id = ? ORDER BY template_id", (rs, row) -> read(rs.getString(1), SnapshotMapper::template), campaignId);
    }

    @Override
    public void saveBatch(IssuanceBatch batch) {
        saveSnapshot("issuance_batches", "batch_id", batch.issuanceBatchId(), batch.version(), batch);
        appendEvents(batch.domainEvents());
    }

    @Override
    public Optional<IssuanceBatch> findBatch(String issuanceBatchId) {
        return findSnapshot("SELECT data::text FROM issuance_batches WHERE batch_id = ?", SnapshotMapper::batch, issuanceBatchId);
    }

    @Override
    public List<IssuanceBatch> findBatchesByCampaignId(String campaignId) {
        return jdbc.query("SELECT data::text FROM issuance_batches WHERE campaign_id = ? ORDER BY batch_id", (rs, row) -> read(rs.getString(1), SnapshotMapper::batch), campaignId);
    }

    private <T> Optional<T> findSnapshot(String sql, java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, T> decoder, String id) {
        return jdbc.query(sql, rs -> rs.next() ? Optional.of(read(rs.getString(1), decoder)) : Optional.empty(), id);
    }

    private void saveSnapshot(String table, String idColumn, String id, long newVersion, Object data) {
        long expectedVersion = newVersion - 1;
        if (expectedVersion < 0) {
            throw new OptimisticConcurrencyException("snapshot version conflict for " + id);
        }
        int rows = expectedVersion == 0
            ? insertSnapshot(table, idColumn, id, newVersion, data)
            : updateSnapshot(table, idColumn, id, expectedVersion, newVersion, data);
        if (rows == 0) {
            throw new OptimisticConcurrencyException("snapshot version conflict for " + id);
        }
    }

    private int insertSnapshot(String table, String idColumn, String id, long newVersion, Object data) {
        return jdbc.update("INSERT INTO " + table + "(" + idColumn + ", version, data) VALUES (?, ?, ?::jsonb) ON CONFLICT DO NOTHING", id, newVersion, json(data));
    }

    private int updateSnapshot(String table, String idColumn, String id, long expectedVersion, long newVersion, Object data) {
        return jdbc.update("UPDATE " + table + " SET version = ?, data = ?::jsonb, updated_at = now() WHERE " + idColumn + " = ? AND version = ?", newVersion, json(data), id, expectedVersion);
    }

    private void appendEvents(List<DomainEvent> events) {
        for (DomainEvent event : events) {
            outbox.append(new EventEnvelope(
                DeterministicEventIds.forTransition(event.getClass().getSimpleName(), event.aggregateId(), event.aggregateVersion()),
                event.getClass().getSimpleName(),
                event.occurredAt(),
                PrefixedIds.newCorrelationId(),
                null,
                "marketing-campaign",
                1,
                DomainEventPayloads.toMap(event)
            ));
        }
    }

    private <T> T read(String json, java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, T> decoder) {
        try {
            return decoder.apply(mapper.readTree(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored JSON could not be decoded", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(toSerializable(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("value could not be encoded as JSON", exception);
        }
    }

    private static Object toSerializable(Object value) {
        if (value instanceof Campaign c) {
            return campaignSnapshot(c);
        } else if (value instanceof CampaignBudget b) {
            return budgetSnapshot(b);
        } else if (value instanceof CouponTemplate t) {
            return templateSnapshot(t);
        } else if (value instanceof IssuanceBatch b) {
            return batchSnapshot(b);
        }
        return value;
    }

    private static Map<String, Object> campaignSnapshot(Campaign c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("campaignId", c.campaignId());
        m.put("externalKey", c.externalKey());
        m.put("name", c.name());
        m.put("window", windowMap(c.window()));
        m.put("targetRuleSetId", c.targetRuleSetId());
        m.put("budgetId", c.budgetId());
        m.put("approvalRef", c.approvalRef());
        m.put("status", c.status().name());
        m.put("createdAt", c.createdAt().toString());
        m.put("updatedAt", c.updatedAt().toString());
        m.put("version", c.version());
        return m;
    }

    private static Map<String, Object> budgetSnapshot(CampaignBudget b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("budgetId", b.budgetId());
        m.put("campaignId", b.campaignId());
        m.put("totalBudget", moneyMap(b.totalBudget()));
        m.put("reservedAmount", moneyMap(b.reservedAmount()));
        m.put("consumedAmount", moneyMap(b.consumedAmount()));
        m.put("closed", b.closed());
        m.put("createdAt", b.createdAt().toString());
        m.put("updatedAt", b.updatedAt().toString());
        m.put("version", b.version());
        return m;
    }

    private static Map<String, Object> templateSnapshot(CouponTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateId", t.templateId());
        m.put("campaignId", t.campaignId());
        m.put("templateCode", t.templateCode());
        m.put("templateVersion", t.templateVersion());
        m.put("status", t.status().name());
        m.put("faceValue", moneyMap(t.faceValue()));
        m.put("minimumSpend", moneyMap(t.minimumSpend()));
        m.put("applicableScope", t.applicableScope());
        m.put("redemptionRule", t.redemptionRule());
        m.put("validityWindow", windowMap(t.validityWindow()));
        m.put("createdAt", t.createdAt().toString());
        m.put("updatedAt", t.updatedAt().toString());
        m.put("version", t.version());
        return m;
    }

    private static Map<String, Object> batchSnapshot(IssuanceBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuanceBatchId", b.issuanceBatchId());
        m.put("campaignId", b.campaignId());
        m.put("templateId", b.templateId());
        m.put("audienceSnapshotId", b.audienceSnapshotId());
        m.put("status", b.status().name());
        m.put("plannedAt", b.plannedAt().toString());
        m.put("updatedAt", b.updatedAt().toString());
        m.put("version", b.version());
        return m;
    }

    private static Map<String, String> windowMap(com.trainticket.marketingcampaign.domain.CampaignWindow w) {
        if (w == null) return Map.of();
        return Map.of("validFrom", w.validFrom().toString(), "validUntil", w.validUntil().toString());
    }

    private static Map<String, Object> moneyMap(com.trainticket.marketingcampaign.domain.Money m) {
        if (m == null) return Map.of();
        return Map.of("currency", m.currency(), "minorUnits", m.minorUnits());
    }
}
