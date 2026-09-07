package com.trainticket.marketingcampaign.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainticket.marketingcampaign.domain.CampaignWindow;
import com.trainticket.marketingcampaign.domain.Money;
import com.trainticket.platformkit.messaging.EventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Round trips for the three child aggregates: write one, then read it back through the parent-scoped lookup that
 * Postgres answers from the denormalized {@code campaign_id} column.
 *
 * <p>Every one of these lookups was dead against Postgres: {@code insertSnapshot} named only
 * {@code (id_column, version, data)}, so {@code campaign_id} was never populated and
 * {@code WHERE campaign_id = ?} could not match. The in-memory profile filtered on the JSON-backed
 * {@code campaignId()} accessor instead, so it kept answering correctly and hid the defect.
 */
class CampaignChildAggregateRoundTripTest {
    private final InMemoryCampaignRepository repository = new InMemoryCampaignRepository();
    private final EventPublisher publisher = envelope -> {};
    private final MarketingCampaignService service = new MarketingCampaignService(
        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
        repository,
        publisher
    );

    @Test
    void budgetIsReadableByItsOwningCampaignAndCarriesThatCampaignId() {
        String campaignId = draftCampaign("budget-roundtrip");

        service.setBudget(campaignId, new MarketingCampaignService.SetBudgetCommand(
            new Money("USD", 100_00), "trs-1"), null);

        assertThat(repository.findBudgetByCampaignId(campaignId))
            .as("budget must be reachable from its owning campaign")
            .isPresent()
            .get()
            .satisfies(budget -> assertThat(budget.campaignId()).isEqualTo(campaignId));
    }

    @Test
    void templateIsReadableByItsOwningCampaignAndCarriesThatCampaignId() {
        String campaignId = draftCampaign("template-roundtrip");

        MarketingCampaignService.TemplateDetail drafted = service.draftTemplate(campaignId, templateCommand(), null);

        assertThat(repository.findTemplatesByCampaignId(campaignId))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.templateId()).isEqualTo(drafted.templateId());
                assertThat(template.campaignId()).isEqualTo(campaignId);
            });
    }

    @Test
    void batchIsReadableByItsOwningCampaignAndCarriesBothOwnerIds() {
        String campaignId = draftCampaign("batch-roundtrip");
        MarketingCampaignService.TemplateDetail template = service.draftTemplate(campaignId, templateCommand(), null);

        MarketingCampaignService.BatchDetail planned = service.planBatch(campaignId,
            new MarketingCampaignService.PlanBatchCommand(template.templateId(), "aud-1"), null);

        assertThat(repository.findBatchesByCampaignId(campaignId))
            .singleElement()
            .satisfies(batch -> {
                assertThat(batch.issuanceBatchId()).isEqualTo(planned.issuanceBatchId());
                assertThat(batch.campaignId()).isEqualTo(campaignId);
                assertThat(batch.templateId()).isEqualTo(template.templateId());
            });
    }

    /**
     * The parent-scoped reads must be genuinely scoped, not "return whatever single child exists".
     */
    @Test
    void childAggregatesAreScopedToTheirOwnCampaign() {
        String first = draftCampaign("scope-a");
        String second = draftCampaign("scope-b");

        service.setBudget(first, new MarketingCampaignService.SetBudgetCommand(new Money("USD", 10_00), "trs-a"), null);
        service.draftTemplate(first, templateCommand("SCOPEA", 1), null);
        service.setBudget(second, new MarketingCampaignService.SetBudgetCommand(new Money("USD", 20_00), "trs-b"), null);

        assertThat(repository.findBudgetByCampaignId(first)).get()
            .satisfies(budget -> assertThat(budget.totalBudget().minorUnits()).isEqualTo(10_00));
        assertThat(repository.findBudgetByCampaignId(second)).get()
            .satisfies(budget -> assertThat(budget.totalBudget().minorUnits()).isEqualTo(20_00));
        assertThat(repository.findTemplatesByCampaignId(second)).isEmpty();
        assertThat(repository.findBatchesByCampaignId(first)).isEmpty();
    }

    /**
     * Survives mutation: reserving against a budget takes the UPDATE path, which must keep the owner column intact.
     */
    @Test
    void budgetRemainsReachableByCampaignAfterMutation() {
        String campaignId = draftCampaign("mutation-roundtrip");
        MarketingCampaignService.CampaignDetail ready = service.setBudget(campaignId,
            new MarketingCampaignService.SetBudgetCommand(new Money("USD", 100_00), "trs-1"), null);

        service.reserveBudget(ready.budgetId(), new MarketingCampaignService.MoneyCommand(
            new Money("USD", 25_00), "ref-1"), null);

        assertThat(repository.findBudgetByCampaignId(campaignId)).get()
            .satisfies(budget -> {
                assertThat(budget.campaignId()).isEqualTo(campaignId);
                assertThat(budget.reservedAmount().minorUnits()).isEqualTo(25_00);
            });
    }

    /**
     * campaign_budgets_campaign_id_idx is UNIQUE, so a campaign cannot own two budgets. The in-memory profile used
     * to accept this silently while Postgres would reject it.
     */
    @Test
    void aSecondBudgetForTheSameCampaignIsRejectedAsADuplicateBusinessKey() {
        String campaignId = draftCampaign("double-budget");
        service.setBudget(campaignId, new MarketingCampaignService.SetBudgetCommand(
            new Money("USD", 100_00), "trs-1"), null);

        assertThatThrownBy(() -> service.setBudget(campaignId, new MarketingCampaignService.SetBudgetCommand(
            new Money("USD", 200_00), "trs-2"), null))
            .isInstanceOf(DuplicateBusinessKeyException.class)
            .hasMessageContaining("campaignId");
    }

    /**
     * coupon_templates_code_version_idx is UNIQUE on (templateCode, templateVersion).
     */
    @Test
    void aDuplicateTemplateCodeAndVersionIsRejectedAsADuplicateBusinessKey() {
        String campaignId = draftCampaign("double-template");
        service.draftTemplate(campaignId, templateCommand("DUPE", 1), null);

        assertThatThrownBy(() -> service.draftTemplate(campaignId, templateCommand("DUPE", 1), null))
            .isInstanceOf(DuplicateBusinessKeyException.class)
            .hasMessageContaining("templateCode/templateVersion");
    }

    @Test
    void adifferentVersionOfTheSameTemplateCodeIsAccepted() {
        String campaignId = draftCampaign("versioned-template");
        service.draftTemplate(campaignId, templateCommand("VERSIONED", 1), null);
        service.draftTemplate(campaignId, templateCommand("VERSIONED", 2), null);

        assertThat(repository.findTemplatesByCampaignId(campaignId)).hasSize(2);
    }

    private String draftCampaign(String externalKey) {
        return service.draftCampaign(
            new MarketingCampaignService.DraftCampaignCommand(externalKey, "Campaign " + externalKey, window()),
            null
        ).campaignId();
    }

    private static MarketingCampaignService.DraftTemplateCommand templateCommand() {
        return templateCommand("ROUNDTRIP", 1);
    }

    private static MarketingCampaignService.DraftTemplateCommand templateCommand(String code, int version) {
        return new MarketingCampaignService.DraftTemplateCommand(
            code, version, new Money("USD", 1_000), Money.zero("USD"), "TRAIN_TICKET", "minimum spend", window());
    }

    private static CampaignWindow window() {
        return new CampaignWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"));
    }
}
