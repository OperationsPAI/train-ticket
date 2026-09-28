package com.trainticket.journeyorder.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * `JOURNEY_ORDER_RISK_SCENARIOS` is the release's key for this setting, and the
 * environment mapping is Spring's (`journey-order.risk.scenarios`), so the two
 * values below are what a deployment's declaration is read into.
 */
class RiskScenarioSettingsTest {

    @Test
    void theShippedListNamesEveryScenarioThePlatformEmits() {
        RiskScenarioSettings settings = RiskScenarioSettings.shipped();

        assertThat(RiskScenarioSettings.PROPERTY).isEqualTo("journey-order.risk.scenarios");
        assertThat(settings.names("order_risk")).isTrue();
        assertThat(settings.names("payment_risk")).isTrue();
        assertThat(settings.names("post_sales_risk")).isTrue();
        assertThat(settings.names("account_risk")).isTrue();
    }

    @Test
    void aDeclaredListIsTheWholeOfWhatTheSettingNames() {
        RiskScenarioSettings settings = new RiskScenarioSettings(" order_risk , payment_risk ");

        assertThat(settings.names("order_risk")).isTrue();
        assertThat(settings.names("payment_risk")).isTrue();
        assertThat(settings.names("post_sales_risk")).isFalse();
        assertThat(settings.names(null)).isFalse();
        // Names are matched exactly: the vocabulary belongs to risk-compliance.
        assertThat(settings.names("order-risk")).isFalse();
    }

    @Test
    void anEmptyDeclarationNamesNothing() {
        assertThat(new RiskScenarioSettings("").scenarios()).isEmpty();
        assertThat(new RiskScenarioSettings(null).scenarios()).isEmpty();
    }
}
