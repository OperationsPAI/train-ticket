package com.trainticket.journeyorder.config;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The risk scenarios whose assessment an order acts on.
 *
 * <p>risk-compliance assesses the order, the payment intent, the post-sales case and the account
 * on one stream, and only the order's own assessment resolves to an order here. Which of them this
 * service acts on belongs to the release rather than to the image: it is the release that pairs
 * this service with the risk service it consumes, and a deployment whose consumer assesses a
 * different vocabulary should not need an image that knows it. The shipped value is every
 * scenario the platform emits today.
 *
 * <p>Names are matched exactly. A scenario the list does not name is acknowledged the same way as
 * any other event this service does not act on, so the list is the whole of the deployment's
 * decision, and the vocabulary stays on the producer's side.
 */
@Component
public class RiskScenarioSettings {

    /** The setting a release declares; the environment variable is {@code JOURNEY_ORDER_RISK_SCENARIOS}. */
    public static final String PROPERTY = "journey-order.risk.scenarios";

    private static final String SHIPPED_SCENARIOS = "order_risk,payment_risk,post_sales_risk,account_risk";

    private final Set<String> scenarios;

    public RiskScenarioSettings(@Value("${" + PROPERTY + ":" + SHIPPED_SCENARIOS + "}") String declared) {
        this.scenarios = parse(declared);
    }

    /** The value this image carries when no release declares one. */
    public static RiskScenarioSettings shipped() {
        return new RiskScenarioSettings(SHIPPED_SCENARIOS);
    }

    public boolean names(String scenario) {
        return scenario != null && scenarios.contains(scenario);
    }

    public Set<String> scenarios() {
        return scenarios;
    }

    private static Set<String> parse(String declared) {
        if (declared == null) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String part : declared.split(",")) {
            String scenario = part.trim();
            if (!scenario.isEmpty()) {
                parsed.add(scenario);
            }
        }
        return Set.copyOf(parsed);
    }
}
