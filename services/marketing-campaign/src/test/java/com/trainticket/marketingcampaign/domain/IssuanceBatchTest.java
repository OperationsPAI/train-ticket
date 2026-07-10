package com.trainticket.marketingcampaign.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IssuanceBatchTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final CampaignWindow BENEFIT_WINDOW = new CampaignWindow(T0, Instant.parse("2026-02-01T00:00:00Z"));

    @Test void runsItemsAndClosesSucceededBatch() {
        IssuanceBatch batch = IssuanceBatch.plan("batch-1", "campaign-1", "template-1", "audience-1", T0)
            .start(T0.plusSeconds(1));
        IssuanceBatch.IssueItemResult issued = batch.issueCampaignCoupon("item-1", "account-1", "idem-1", new Money("USD", 100), BENEFIT_WINDOW, T0.plusSeconds(2));

        assertThat(issued.replayed()).isFalse();
        batch = issued.batch().recordWalletIssuanceAccepted("item-1", "benefit-1", T0.plusSeconds(3))
            .markSucceeded(T0.plusSeconds(4))
            .close("all_items_accepted", T0.plusSeconds(5));

        assertThat(batch.status()).isEqualTo(IssuanceBatchStatus.CLOSED);
        assertThat(batch.itemsById().get("item-1").status()).isEqualTo(IssuanceItemStatus.ACCEPTED);
    }

    @Test void replaysSameIdempotencyMaterialAndRejectsDuplicateActiveRecipient() {
        IssuanceBatch batch = IssuanceBatch.plan("batch-1", "campaign-1", "template-1", "audience-1", T0)
            .start(T0.plusSeconds(1));
        IssuanceBatch.IssueItemResult first = batch.issueCampaignCoupon("item-1", "account-1", "idem-1", new Money("USD", 100), BENEFIT_WINDOW, T0.plusSeconds(2));
        IssuanceBatch.IssueItemResult replay = first.batch().issueCampaignCoupon("item-2", "account-1", "idem-1", new Money("USD", 100), BENEFIT_WINDOW, T0.plusSeconds(3));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.item().issuanceItemId()).isEqualTo("item-1");
        assertThat(replay.batch().itemsById()).hasSize(1);
        assertThatThrownBy(() -> replay.batch().issueCampaignCoupon("item-2", "account-1", "idem-2", new Money("USD", 100), BENEFIT_WINDOW, T0.plusSeconds(4)))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("active issuance item already exists");
    }

    @Test void supportsFailureRetryAndIrreversibleRestingStates() {
        IssuanceBatch batch = IssuanceBatch.plan("batch-1", "campaign-1", "template-1", "audience-1", T0)
            .start(T0.plusSeconds(1));
        batch = batch.issueCampaignCoupon("item-1", "account-1", "idem-1", new Money("USD", 100), BENEFIT_WINDOW, T0.plusSeconds(2)).batch()
            .recordIssuanceItemFailed("item-1", "BLOCKED_BY_CONTRACT", T0.plusSeconds(3))
            .retryIssuanceItem("item-1", 1, T0.plusSeconds(4));

        assertThat(batch.itemsById().get("item-1").status()).isEqualTo(IssuanceItemStatus.RETRY_SCHEDULED);

        IssuanceBatch closedFailure = IssuanceBatch.plan("batch-2", "campaign-1", "template-1", "audience-1", T0)
            .start(T0.plusSeconds(1))
            .fail("wallet_down", T0.plusSeconds(2))
            .close("manual_case_opened", T0.plusSeconds(3));

        assertThatThrownBy(() -> closedFailure.markSucceeded(T0.plusSeconds(4)))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("invalid issuance batch transition");
    }
}
