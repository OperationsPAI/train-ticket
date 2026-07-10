package com.trainticket.marketingcampaign.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CampaignBudgetTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test void reservesConsumesAndReleasesBudget() {
        CampaignBudget budget = CampaignBudget.set("budget-1", "campaign-1", new Money("usd", 1_000), T0)
            .reserve(new Money("USD", 400), "item-1", T0.plusSeconds(1));

        assertThat(budget.reservedAmount()).isEqualTo(new Money("USD", 400));

        budget = budget.consume(new Money("USD", 150), "benefit-1", T0.plusSeconds(2))
            .release(new Money("USD", 250), "wallet_failed", T0.plusSeconds(3));

        assertThat(budget.reservedAmount()).isEqualTo(new Money("USD", 0));
        assertThat(budget.consumedAmount()).isEqualTo(new Money("USD", 150));
        assertThat(budget.domainEvents()).hasSize(4);
    }

    @Test void preventsOverdraftAndNegativeBalances() {
        CampaignBudget budget = CampaignBudget.set("budget-1", "campaign-1", new Money("USD", 100), T0);

        assertThatThrownBy(() -> budget.reserve(new Money("USD", 101), "item-1", T0.plusSeconds(1)))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("exceeds totalBudget");

        CampaignBudget reserved = budget.reserve(new Money("USD", 80), "item-1", T0.plusSeconds(1));
        assertThatThrownBy(() -> reserved.consume(new Money("USD", 81), "benefit-1", T0.plusSeconds(2)))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("exceeds reserved amount");
        assertThatThrownBy(() -> reserved.release(new Money("USD", 81), "release", T0.plusSeconds(3)))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("exceeds reserved amount");
        assertThatThrownBy(() -> reserved.reserve(new Money("EUR", 1), "item-2", T0.plusSeconds(4)))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("currency mismatch");
    }

    @Test void closedBudgetIsReadOnly() {
        CampaignBudget closed = CampaignBudget.set("budget-1", "campaign-1", new Money("USD", 100), T0)
            .close("campaign_cancelled", T0.plusSeconds(1));

        assertThat(closed.closed()).isTrue();
        assertThatThrownBy(() -> closed.reserve(new Money("USD", 1), "item-1", T0.plusSeconds(2)))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("closed budget");
    }
}
