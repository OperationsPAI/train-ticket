package com.trainticket.marketingcampaign.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CampaignTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final CampaignWindow WINDOW = new CampaignWindow(T0, Instant.parse("2026-02-01T00:00:00Z"));

    @Test void followsApprovalSchedulingAndCompletionLifecycle() {
        Campaign campaign = Campaign.draft("campaign-1", "spring-sale-v1", "Spring Sale", WINDOW, T0)
            .submitForReview(T0.plusSeconds(1))
            .approve("approval-1", T0.plusSeconds(2))
            .withLaunchReadiness("budget-1", "rules-1")
            .schedule(T0.plusSeconds(3))
            .start(T0.plusSeconds(4))
            .pause("downstream_unavailable", "operator-1", T0.plusSeconds(5))
            .resume("wallet_recovered", "operator-1", T0.plusSeconds(6))
            .beginCompletion("window_elapsed", T0.plusSeconds(7))
            .complete("all_batches_closed", T0.plusSeconds(8));

        assertThat(campaign.status()).isEqualTo(CampaignStatus.COMPLETED);
        assertThat(campaign.version()).isEqualTo(9);
        assertThat(campaign.domainEvents()).hasSize(9);
    }

    @Test void rejectsInvalidTransitionsAndTerminalReopening() {
        Campaign draft = Campaign.draft("campaign-1", "spring-sale-v1", "Spring Sale", WINDOW, T0);

        assertThatThrownBy(() -> draft.schedule(T0)).isInstanceOf(DomainException.class)
            .hasMessageContaining("invalid campaign transition");
        assertThatThrownBy(() -> draft.start(T0.minusSeconds(1))).isInstanceOf(DomainException.class)
            .hasMessageContaining("before validFrom");

        Campaign cancelled = draft.cancel("duplicate", "operator-1", T0.plusSeconds(1));
        assertThatThrownBy(() -> cancelled.submitForReview(T0.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> cancelled.withLaunchReadiness("budget-1", "rules-1")).isInstanceOf(DomainException.class);
    }

    @Test void requiresLaunchReadinessBeforeSchedulingApprovedCampaign() {
        Campaign approved = Campaign.draft("campaign-1", "spring-sale-v1", "Spring Sale", WINDOW, T0)
            .submitForReview(T0.plusSeconds(1))
            .approve("approval-1", T0.plusSeconds(2));

        assertThatThrownBy(() -> approved.schedule(T0.plusSeconds(3))).isInstanceOf(DomainException.class)
            .hasMessageContaining("budgetId is required");
    }
}
