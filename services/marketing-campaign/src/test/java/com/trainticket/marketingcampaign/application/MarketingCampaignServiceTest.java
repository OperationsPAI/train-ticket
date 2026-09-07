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

class MarketingCampaignServiceTest {
    private final InMemoryCampaignRepository repository = new InMemoryCampaignRepository();
    private final EventPublisher publisher = envelope -> {};
    private final MarketingCampaignService service = new MarketingCampaignService(
        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
        repository,
        publisher
    );

    @Test
    void draftsCampaignAndAddsBudgetReadiness() {
        MarketingCampaignService.CampaignDetail campaign = service.draftCampaign(
            new MarketingCampaignService.DraftCampaignCommand("winter-2026", "Winter", window()),
            null
        );

        MarketingCampaignService.CampaignDetail ready = service.setBudget(
            campaign.campaignId(),
            new MarketingCampaignService.SetBudgetCommand(new Money("usd", 100_00), "trs-winter"),
            null
        );

        assertThat(ready.status()).isEqualTo("DRAFT");
        assertThat(ready.budgetId()).startsWith("mcb-");
        assertThat(ready.targetRuleSetId()).isEqualTo("trs-winter");
        assertThat(ready.budget().totalBudget().currency()).isEqualTo("USD");
    }

    @Test
    void draftsValidatesAndPublishesTemplate() {
        MarketingCampaignService.CampaignDetail campaign = service.draftCampaign(
            new MarketingCampaignService.DraftCampaignCommand("spring-2026", "Spring", window()),
            null
        );
        MarketingCampaignService.TemplateDetail draft = service.draftTemplate(
            campaign.campaignId(),
            new MarketingCampaignService.DraftTemplateCommand(
                "SPRING10",
                1,
                new Money("USD", 1_000),
                Money.zero("USD"),
                "TRAIN_TICKET",
                "minimum spend",
                window()
            ),
            null
        );

        MarketingCampaignService.TemplateDetail validated = service.validateTemplate(draft.templateId(), null);
        MarketingCampaignService.TemplateDetail published = service.publishTemplate(validated.templateId(), new MarketingCampaignService.ApproveCampaignCommand("apr-1"), null);

        assertThat(published.status()).isEqualTo("PUBLISHED");
    }

    @Test
    void plansAndStartsBatch() {
        MarketingCampaignService.CampaignDetail campaign = service.draftCampaign(
            new MarketingCampaignService.DraftCampaignCommand("batch-2026", "Batch", window()),
            null
        );
        MarketingCampaignService.TemplateDetail template = service.draftTemplate(
            campaign.campaignId(),
            new MarketingCampaignService.DraftTemplateCommand("BATCH10", 1, new Money("USD", 1_000), Money.zero("USD"), "TRAIN_TICKET", "rule", window()),
            null
        );

        MarketingCampaignService.BatchDetail planned = service.planBatch(campaign.campaignId(), new MarketingCampaignService.PlanBatchCommand(template.templateId(), "aud-1"), null);
        MarketingCampaignService.BatchDetail running = service.startBatch(planned.issuanceBatchId(), null);

        assertThat(running.status()).isEqualTo("RUNNING");
    }

    /**
     * Reusing an externalKey another campaign already owns is a genuine duplicate: the constraint is doing its job.
     * The requirement is that the failure is reported as a duplicate business key naming the externalKey, not as an
     * optimistic-concurrency conflict on a campaignId the caller never saw.
     */
    @Test
    void redraftingWithATakenExternalKeyReportsADuplicateBusinessKey() {
        service.draftCampaign(new MarketingCampaignService.DraftCampaignCommand("summer-2026", "Summer", window()), null);

        assertThatThrownBy(() -> service.draftCampaign(
            new MarketingCampaignService.DraftCampaignCommand("summer-2026", "Summer again", window()),
            null
        ))
            .isInstanceOf(DuplicateBusinessKeyException.class)
            .hasMessageContaining("externalKey")
            .hasMessageContaining("summer-2026")
            .hasMessageNotContaining("snapshot version conflict");
    }

    @Test
    void distinctExternalKeysBothDraftSuccessfully() {
        MarketingCampaignService.CampaignDetail first = service.draftCampaign(
            new MarketingCampaignService.DraftCampaignCommand("promo-a", "Promo A", window()), null);
        MarketingCampaignService.CampaignDetail second = service.draftCampaign(
            new MarketingCampaignService.DraftCampaignCommand("promo-b", "Promo B", window()), null);

        assertThat(first.campaignId()).isNotEqualTo(second.campaignId());
        assertThat(second.status()).isEqualTo("DRAFT");
    }

    private static CampaignWindow window() {
        return new CampaignWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"));
    }
}
