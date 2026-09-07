package com.trainticket.marketingcampaign.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.marketingcampaign.application.CampaignRepository;
import com.trainticket.marketingcampaign.application.DomainEventPayloads;
import com.trainticket.marketingcampaign.application.DuplicateBusinessKeyException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresCampaignRepository implements CampaignRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresCampaignRepository.class);

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
        saveSnapshot("campaigns", "campaign_id", campaign.campaignId(), campaign.version(), campaign,
            noOwnerColumns(),
            () -> new DuplicateBusinessKeyException("externalKey", campaign.externalKey()));
        appendEvents(campaign.domainEvents());
    }

    @Override
    public Optional<Campaign> findCampaign(String campaignId) {
        return findSnapshot("SELECT data::text FROM campaigns WHERE campaign_id = ?", SnapshotMapper::campaign, campaignId);
    }

    @Override
    public void saveBudget(CampaignBudget budget) {
        saveSnapshot("campaign_budgets", "budget_id", budget.budgetId(), budget.version(), budget,
            ownerColumns("campaign_id", budget.campaignId()),
            () -> new DuplicateBusinessKeyException("campaignId", budget.campaignId()));
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
        saveSnapshot("coupon_templates", "template_id", template.templateId(), template.version(), template,
            ownerColumns("campaign_id", template.campaignId()),
            () -> new DuplicateBusinessKeyException("templateCode/templateVersion", template.templateCode() + "/" + template.templateVersion()));
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
        saveSnapshot("issuance_batches", "batch_id", batch.issuanceBatchId(), batch.version(), batch,
            ownerColumns("campaign_id", batch.campaignId(), "template_id", batch.templateId()),
            () -> new DuplicateBusinessKeyException("campaignId/templateId/audienceSnapshotId",
                batch.campaignId() + "/" + batch.templateId() + "/" + batch.audienceSnapshotId()));
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

    /**
     * Persists a snapshot under optimistic concurrency control.
     *
     * <p>{@code ownerColumns} carries the denormalized owning-aggregate keys that the child tables declare
     * {@code NOT NULL} with no default ({@code campaign_budgets.campaign_id}, {@code coupon_templates.campaign_id},
     * {@code issuance_batches.campaign_id} and {@code issuance_batches.template_id}). They are real relational
     * columns backed by foreign keys and by the indexes the parent-scoped reads use, so they must be written on
     * every insert and refreshed on every update rather than left to be derived from {@code data}.
     *
     * <p>{@code duplicateBusinessKey} supplies the error raised when the insert violates a <em>business</em> unique
     * index (for example {@code campaigns_external_key_idx}) rather than the aggregate's own primary key. Without
     * that distinction a duplicate externalKey and a genuine lost-update race both surfaced as
     * "snapshot version conflict for &lt;brand-new-id&gt;", which is actively misleading: the id in that message had
     * just been generated locally and could not possibly have raced with anything.
     */
    private void saveSnapshot(String table, String idColumn, String id, long newVersion, Object data,
                              Map<String, String> ownerColumns,
                              Supplier<DuplicateBusinessKeyException> duplicateBusinessKey) {
        long expectedVersion = newVersion - 1;
        if (expectedVersion < 0) {
            throw new OptimisticConcurrencyException("snapshot version conflict for " + id);
        }
        int rows = expectedVersion == 0
            ? insertSnapshot(table, idColumn, id, newVersion, data, ownerColumns, duplicateBusinessKey)
            : updateSnapshot(table, idColumn, id, expectedVersion, newVersion, data, ownerColumns);
        if (rows == 0) {
            LOGGER.warn("write rejected table={} id={} expectedVersion={} newVersion={} reason=SNAPSHOT_VERSION_CONFLICT",
                table, id, expectedVersion, newVersion);
            throw new OptimisticConcurrencyException("snapshot version conflict for " + id);
        }
    }

    private int insertSnapshot(String table, String idColumn, String id, long newVersion, Object data,
                               Map<String, String> ownerColumns,
                               Supplier<DuplicateBusinessKeyException> duplicateBusinessKey) {
        StringBuilder columns = new StringBuilder(idColumn);
        StringBuilder placeholders = new StringBuilder("?");
        for (String ownerColumn : ownerColumns.keySet()) {
            columns.append(", ").append(ownerColumn);
            placeholders.append(", ?");
        }
        columns.append(", version, data");
        placeholders.append(", ?, ?::jsonb");

        List<Object> arguments = new ArrayList<>();
        arguments.add(id);
        arguments.addAll(ownerColumns.values());
        arguments.add(newVersion);
        arguments.add(json(data));

        try {
            // The conflict target is deliberately explicit. A bare "ON CONFLICT DO NOTHING" also swallows violations
            // of the business unique indexes on this table, which then reach the caller mislabelled as a version
            // conflict. Only a re-insert of the same aggregate id is a benign no-op. The target stays the primary
            // key even now that owner columns are written: campaign_budgets_campaign_id_idx is a *business* unique
            // index, so a second budget for the same campaign must surface as a duplicate business key, not be
            // silently discarded.
            return jdbc.update("INSERT INTO " + table + "(" + columns + ") VALUES (" + placeholders + ")"
                + " ON CONFLICT (" + idColumn + ") DO NOTHING", arguments.toArray());
        } catch (DuplicateKeyException exception) {
            DuplicateBusinessKeyException duplicate = duplicateBusinessKey.get();
            LOGGER.warn("write rejected table={} id={} {}={} reason=DUPLICATE_BUSINESS_KEY",
                table, id, duplicate.keyName(), duplicate.keyValue());
            throw duplicate;
        }
    }

    private int updateSnapshot(String table, String idColumn, String id, long expectedVersion, long newVersion,
                               Object data, Map<String, String> ownerColumns) {
        StringBuilder assignments = new StringBuilder("version = ?, data = ?::jsonb, updated_at = now()");
        List<Object> arguments = new ArrayList<>();
        arguments.add(newVersion);
        arguments.add(json(data));
        // Owner columns are rewritten so a snapshot can never drift from the row that indexes and foreign keys see.
        for (Map.Entry<String, String> owner : ownerColumns.entrySet()) {
            assignments.append(", ").append(owner.getKey()).append(" = ?");
            arguments.add(owner.getValue());
        }
        arguments.add(id);
        arguments.add(expectedVersion);
        return jdbc.update("UPDATE " + table + " SET " + assignments
            + " WHERE " + idColumn + " = ? AND version = ?", arguments.toArray());
    }

    private static Map<String, String> noOwnerColumns() {
        return Map.of();
    }

    private static Map<String, String> ownerColumns(String column, String value) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put(column, requiredOwner(column, value));
        return columns;
    }

    private static Map<String, String> ownerColumns(String firstColumn, String firstValue,
                                                    String secondColumn, String secondValue) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put(firstColumn, requiredOwner(firstColumn, firstValue));
        columns.put(secondColumn, requiredOwner(secondColumn, secondValue));
        return columns;
    }

    /**
     * These columns are {@code NOT NULL} with no default, so a null here is a not-null violation at the database.
     * Failing in Java names the offending column instead of surfacing an opaque SQLSTATE 23502.
     */
    private static String requiredOwner(String column, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(column + " is required for the owning aggregate reference");
        }
        return value;
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
