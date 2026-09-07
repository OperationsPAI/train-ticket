package com.trainticket.marketingcampaign.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.marketingcampaign.domain.CampaignBudget;
import com.trainticket.marketingcampaign.domain.CampaignWindow;
import com.trainticket.marketingcampaign.domain.CouponTemplate;
import com.trainticket.marketingcampaign.domain.DomainException;
import com.trainticket.marketingcampaign.domain.IssuanceBatch;
import com.trainticket.marketingcampaign.domain.Money;
import com.trainticket.platformkit.persistence.OutboxAppender;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * {@code campaign_budgets}, {@code coupon_templates} and {@code issuance_batches} each declare their owning-aggregate
 * columns {@code NOT NULL} with no default, and {@code issuance_batches} declares {@code template_id} the same way.
 * The insert used to name only {@code (id_column, version, data)}, so against Postgres the very first budget write
 * failed with SQLSTATE 23502 and {@code findBudgetByCampaignId} queried a column nothing ever populated.
 *
 * <p>These tests assert the <em>shape of the emitted SQL and its bound parameters</em>. They cannot and do not prove
 * Postgres accepts the statement — there is no database in this test — but they do fail loudly if an owning-aggregate
 * column stops being written, which is the regression that mattered.
 */
class PostgresCampaignRepositoryOwnerColumnTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void budgetInsertNamesCampaignIdAndBindsIt() {
        JdbcOperations jdbc = acceptingJdbc();
        repository(jdbc).saveBudget(budget());

        Statement insert = captureSingle(jdbc, "INSERT INTO campaign_budgets");
        assertThat(insert.sql()).contains("campaign_id");
        assertThat(insert.sql()).isEqualTo(
            "INSERT INTO campaign_budgets(budget_id, campaign_id, version, data) VALUES (?, ?, ?, ?::jsonb)"
                + " ON CONFLICT (budget_id) DO NOTHING");
        assertThat(insert.arguments()).containsExactly("mcb-1", "mc-1", 1L, insert.arguments()[3]);
    }

    @Test
    void templateInsertNamesCampaignIdAndBindsIt() {
        JdbcOperations jdbc = acceptingJdbc();
        repository(jdbc).saveTemplate(template());

        Statement insert = captureSingle(jdbc, "INSERT INTO coupon_templates");
        assertThat(insert.sql()).isEqualTo(
            "INSERT INTO coupon_templates(template_id, campaign_id, version, data) VALUES (?, ?, ?, ?::jsonb)"
                + " ON CONFLICT (template_id) DO NOTHING");
        assertThat(insert.arguments()).containsExactly("mct-1", "mc-1", 1L, insert.arguments()[3]);
    }

    /**
     * issuance_batches carries two NOT NULL owning-aggregate columns, not one: campaign_id and template_id, each
     * behind a foreign key.
     */
    @Test
    void batchInsertNamesBothCampaignIdAndTemplateIdAndBindsThem() {
        JdbcOperations jdbc = acceptingJdbc();
        repository(jdbc).saveBatch(batch());

        Statement insert = captureSingle(jdbc, "INSERT INTO issuance_batches");
        assertThat(insert.sql()).isEqualTo(
            "INSERT INTO issuance_batches(batch_id, campaign_id, template_id, version, data) VALUES (?, ?, ?, ?, ?::jsonb)"
                + " ON CONFLICT (batch_id) DO NOTHING");
        assertThat(insert.arguments()).containsExactly("mcbat-1", "mc-1", "mct-1", 1L, insert.arguments()[4]);
    }

    /**
     * The parent aggregate's own table has no owning-aggregate column, so its statement must stay exactly as it was.
     */
    @Test
    void campaignInsertIsUnchangedAndGainsNoOwnerColumn() {
        JdbcOperations jdbc = acceptingJdbc();
        repository(jdbc).saveCampaign(com.trainticket.marketingcampaign.domain.Campaign.draft(
            "mc-1", "summer-2026", "Summer", window(), NOW));

        Statement insert = captureSingle(jdbc, "INSERT INTO campaigns");
        assertThat(insert.sql()).isEqualTo(
            "INSERT INTO campaigns(campaign_id, version, data) VALUES (?, ?, ?::jsonb)"
                + " ON CONFLICT (campaign_id) DO NOTHING");
    }

    /**
     * The conflict target stays the primary key even though owner columns are now written.
     * campaign_budgets_campaign_id_idx is a UNIQUE *business* index: a second budget for the same campaign must
     * surface as a duplicate business key, not be silently swallowed by a widened conflict target.
     */
    @Test
    void conflictTargetsRemainThePrimaryKeyNotTheOwnerColumn() {
        JdbcOperations jdbc = acceptingJdbc();
        PostgresCampaignRepository repository = repository(jdbc);
        repository.saveBudget(budget());
        repository.saveBatch(batch());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(1)).update(sql.capture(), any(Object[].class));

        assertThat(sql.getAllValues())
            .filteredOn(statement -> statement.startsWith("INSERT INTO campaign_budgets"))
            .allMatch(statement -> statement.contains("ON CONFLICT (budget_id) DO NOTHING"))
            .allSatisfy(statement -> assertThat(statement).doesNotContain("ON CONFLICT (campaign_id)"));
        assertThat(sql.getAllValues())
            .filteredOn(statement -> statement.startsWith("INSERT INTO issuance_batches"))
            .allMatch(statement -> statement.contains("ON CONFLICT (batch_id) DO NOTHING"));
    }

    /**
     * An update must refresh the owner columns too, otherwise a snapshot could drift from the row the foreign keys
     * and the parent-scoped indexes actually see.
     */
    @Test
    void budgetUpdateAlsoRewritesCampaignId() {
        JdbcOperations jdbc = acceptingJdbc();
        CampaignBudget reserved = budget().reserve(new Money("USD", 1_00), "ref-1", NOW);
        repository(jdbc).saveBudget(reserved);

        Statement update = captureSingle(jdbc, "UPDATE campaign_budgets");
        assertThat(update.sql()).isEqualTo(
            "UPDATE campaign_budgets SET version = ?, data = ?::jsonb, updated_at = now(), campaign_id = ?"
                + " WHERE budget_id = ? AND version = ?");
        assertThat(update.arguments()[2]).isEqualTo("mc-1");
        assertThat(update.arguments()[3]).isEqualTo("mcb-1");
    }

    @Test
    void batchUpdateRewritesBothOwnerColumns() {
        JdbcOperations jdbc = acceptingJdbc();
        repository(jdbc).saveBatch(batch().start(NOW));

        Statement update = captureSingle(jdbc, "UPDATE issuance_batches");
        assertThat(update.sql()).isEqualTo(
            "UPDATE issuance_batches SET version = ?, data = ?::jsonb, updated_at = now(), campaign_id = ?, template_id = ?"
                + " WHERE batch_id = ? AND version = ?");
        assertThat(update.arguments()[2]).isEqualTo("mc-1");
        assertThat(update.arguments()[3]).isEqualTo("mct-1");
    }

    /**
     * A null owner column would be SQLSTATE 23502 at the database. It cannot get that far: the domain types refuse
     * to construct a child aggregate without its owner, so the invariant behind the NOT NULL column is enforced at
     * the domain boundary. The repository keeps a matching guard as defence in depth for any future caller that
     * bypasses these factories.
     */
    @Test
    void aBudgetWithNoOwningCampaignCannotEvenBeConstructed() {
        assertThatThrownBy(() -> CampaignBudget.restore(
            "mcb-2", null, new Money("USD", 100_00), Money.zero("USD"), Money.zero("USD"), false, NOW, NOW, 1L))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("campaignId is required");
    }

    private record Statement(String sql, Object[] arguments) {}

    private static Statement captureSingle(JdbcOperations jdbc, String prefix) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeast(1)).update(sql.capture(), args.capture());

        List<Statement> matches = new java.util.ArrayList<>();
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            if (sql.getAllValues().get(i).startsWith(prefix)) {
                matches.add(new Statement(sql.getAllValues().get(i), args.getAllValues().get(i)));
            }
        }
        assertThat(matches).as("statements starting with '%s'", prefix).hasSize(1);
        return matches.get(0);
    }

    private static JdbcOperations acceptingJdbc() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        return jdbc;
    }

    private PostgresCampaignRepository repository(JdbcOperations jdbc) {
        return new PostgresCampaignRepository(jdbc, objectMapper, new OutboxAppender(jdbc, objectMapper));
    }

    private static CampaignBudget budget() {
        return CampaignBudget.set("mcb-1", "mc-1", new Money("USD", 100_00), NOW);
    }

    private static CouponTemplate template() {
        return CouponTemplate.draft(
            "mct-1", "mc-1", "SUMMER10", 1, new Money("USD", 1_000), Money.zero("USD"),
            "TRAIN_TICKET", "minimum spend", window(), window(), NOW);
    }

    private static IssuanceBatch batch() {
        return IssuanceBatch.plan("mcbat-1", "mc-1", "mct-1", "aud-1", NOW);
    }

    private static CampaignWindow window() {
        return new CampaignWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"));
    }
}
