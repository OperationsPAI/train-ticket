package com.trainticket.marketingcampaign.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.trainticket.marketingcampaign.domain.Campaign;
import com.trainticket.marketingcampaign.domain.CampaignBudget;
import com.trainticket.marketingcampaign.domain.CampaignStatus;
import com.trainticket.marketingcampaign.domain.CampaignWindow;
import com.trainticket.marketingcampaign.domain.CouponTemplate;
import com.trainticket.marketingcampaign.domain.CouponTemplateStatus;
import com.trainticket.marketingcampaign.domain.IssuanceBatch;
import com.trainticket.marketingcampaign.domain.IssuanceBatchStatus;
import com.trainticket.marketingcampaign.domain.IssuanceItem;
import com.trainticket.marketingcampaign.domain.IssuanceItemStatus;
import com.trainticket.marketingcampaign.domain.Money;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class SnapshotMapper {
    private SnapshotMapper() {}

    static Campaign campaign(JsonNode node) {
        return Campaign.restore(
            text(node, "campaignId"),
            text(node, "externalKey"),
            text(node, "name"),
            window(node.path("window")),
            nullableText(node, "targetRuleSetId"),
            nullableText(node, "budgetId"),
            nullableText(node, "approvalRef"),
            CampaignStatus.valueOf(text(node, "status")),
            instant(node, "createdAt"),
            instant(node, "updatedAt"),
            node.path("version").asLong()
        );
    }

    static CampaignBudget budget(JsonNode node) {
        return CampaignBudget.restore(
            text(node, "budgetId"),
            text(node, "campaignId"),
            money(node.path("totalBudget")),
            money(node.path("reservedAmount")),
            money(node.path("consumedAmount")),
            node.path("closed").asBoolean(),
            instant(node, "createdAt"),
            instant(node, "updatedAt"),
            node.path("version").asLong()
        );
    }

    static CouponTemplate template(JsonNode node) {
        return CouponTemplate.restore(
            text(node, "templateId"),
            text(node, "campaignId"),
            text(node, "templateCode"),
            node.path("templateVersion").asInt(),
            CouponTemplateStatus.valueOf(text(node, "status")),
            money(node.path("faceValue")),
            money(node.path("minimumSpend")),
            text(node, "applicableScope"),
            text(node, "redemptionRule"),
            window(node.path("validityWindow")),
            instant(node, "createdAt"),
            instant(node, "updatedAt"),
            node.path("version").asLong()
        );
    }

    static IssuanceBatch batch(JsonNode node) {
        Map<String, IssuanceItem> items = new LinkedHashMap<>();
        JsonNode itemsNode = node.path("itemsById");
        if (itemsNode.isObject()) {
            itemsNode.properties().forEach(entry -> items.put(entry.getKey(), item(entry.getValue())));
        }
        return IssuanceBatch.restore(
            text(node, "issuanceBatchId"),
            text(node, "campaignId"),
            text(node, "templateId"),
            text(node, "audienceSnapshotId"),
            IssuanceBatchStatus.valueOf(text(node, "status")),
            items,
            instant(node, "plannedAt"),
            instant(node, "updatedAt"),
            node.path("version").asLong()
        );
    }

    private static IssuanceItem item(JsonNode node) {
        return new IssuanceItem(
            text(node, "issuanceItemId"),
            text(node, "campaignId"),
            text(node, "templateId"),
            text(node, "accountId"),
            text(node, "audienceSnapshotId"),
            text(node, "idempotencyKey"),
            money(node.path("amount")),
            window(node.path("benefitWindow")),
            IssuanceItemStatus.valueOf(text(node, "status")),
            nullableText(node, "walletBenefitId"),
            nullableText(node, "failureCode"),
            node.path("retryAttemptNo").asInt(),
            instant(node, "createdAt"),
            instant(node, "updatedAt")
        );
    }

    private static CampaignWindow window(JsonNode node) {
        return new CampaignWindow(Instant.parse(text(node, "validFrom")), Instant.parse(text(node, "validUntil")));
    }

    private static Money money(JsonNode node) {
        return new Money(text(node, "currency"), node.path("minorUnits").asLong());
    }

    private static Instant instant(JsonNode node, String field) {
        return Instant.parse(text(node, field));
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
