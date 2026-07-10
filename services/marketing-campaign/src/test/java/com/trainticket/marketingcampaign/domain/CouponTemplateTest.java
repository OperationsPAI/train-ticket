package com.trainticket.marketingcampaign.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CouponTemplateTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final CampaignWindow CAMPAIGN_WINDOW = new CampaignWindow(T0, Instant.parse("2026-03-01T00:00:00Z"));
    private static final CampaignWindow TEMPLATE_WINDOW = new CampaignWindow(T0.plusSeconds(60), Instant.parse("2026-02-01T00:00:00Z"));

    @Test void followsValidationPublishSupersedeRetireLifecycle() {
        CouponTemplate template = template()
            .validate(T0.plusSeconds(1))
            .publish("approval-1", T0.plusSeconds(2))
            .supersede("template-2", T0.plusSeconds(3))
            .retire("new_version_live", T0.plusSeconds(4));

        assertThat(template.status()).isEqualTo(CouponTemplateStatus.RETIRED);
        assertThat(template.domainEvents()).hasSize(5);
    }

    @Test void publishedTemplatesAreImmutable() {
        CouponTemplate published = template().validate(T0.plusSeconds(1)).publish("approval-1", T0.plusSeconds(2));

        assertThatThrownBy(() -> published.revise(new Money("USD", 200), Money.zero("USD"), "ANY_TRIP", "single-use", TEMPLATE_WINDOW, CAMPAIGN_WINDOW, T0.plusSeconds(3)))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("new version");
        assertThatThrownBy(() -> published.validate(T0.plusSeconds(4)))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("invalid coupon template transition");
    }

    @Test void validityMustStayWithinCampaignWindow() {
        CampaignWindow outside = new CampaignWindow(T0.minusSeconds(1), T0.plusSeconds(10));

        assertThatThrownBy(() -> CouponTemplate.draft("template-1", "campaign-1", "WELCOME", 1, new Money("USD", 100), Money.zero("USD"), "ANY_TRIP", "single-use", outside, CAMPAIGN_WINDOW, T0))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("within campaign window");
    }

    private static CouponTemplate template() {
        return CouponTemplate.draft("template-1", "campaign-1", "WELCOME", 1, new Money("USD", 100), Money.zero("USD"), "ANY_TRIP", "single-use", TEMPLATE_WINDOW, CAMPAIGN_WINDOW, T0);
    }
}
