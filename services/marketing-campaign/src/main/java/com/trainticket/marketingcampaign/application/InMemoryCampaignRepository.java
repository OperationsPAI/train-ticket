package com.trainticket.marketingcampaign.application;

import com.trainticket.marketingcampaign.domain.Campaign;
import com.trainticket.marketingcampaign.domain.CampaignBudget;
import com.trainticket.marketingcampaign.domain.CouponTemplate;
import com.trainticket.marketingcampaign.domain.IssuanceBatch;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnExpression("'${DATABASE_URL:}' == ''")
public class InMemoryCampaignRepository implements CampaignRepository {
    private final ConcurrentMap<String, Campaign> campaigns = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> campaignIdsByExternalKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CampaignBudget> budgets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> budgetIdsByCampaignId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CouponTemplate> templates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> templateIdsByCodeVersion = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, IssuanceBatch> batches = new ConcurrentHashMap<>();

    @Override
    public void saveCampaign(Campaign campaign) {
        // Mirrors campaigns_external_key_idx in migrations/001_marketing_campaign.sql. Without this the in-memory
        // profile silently accepted writes that Postgres rejects with 409, so the conflict path was untestable.
        String owner = campaignIdsByExternalKey.putIfAbsent(campaign.externalKey(), campaign.campaignId());
        if (owner != null && !owner.equals(campaign.campaignId())) {
            throw new DuplicateBusinessKeyException("externalKey", campaign.externalKey());
        }
        campaigns.put(campaign.campaignId(), campaign);
    }

    @Override
    public Optional<Campaign> findCampaign(String campaignId) {
        return Optional.ofNullable(campaigns.get(campaignId));
    }

    @Override
    public void saveBudget(CampaignBudget budget) {
        // Mirrors campaign_budgets_campaign_id_idx (UNIQUE) and campaign_budgets.campaign_id NOT NULL. Postgres
        // physically stores the owning campaign in its own column; the in-memory profile has no columns, so the
        // equivalent guarantees are enforced here. Without this the in-memory profile accepted a second budget for
        // a campaign that Postgres rejects, and accepted a budget with no owner at all.
        requireOwner("campaignId", budget.campaignId());
        String owner = budgetIdsByCampaignId.putIfAbsent(budget.campaignId(), budget.budgetId());
        if (owner != null && !owner.equals(budget.budgetId())) {
            throw new DuplicateBusinessKeyException("campaignId", budget.campaignId());
        }
        budgets.put(budget.budgetId(), budget);
    }

    @Override
    public Optional<CampaignBudget> findBudget(String budgetId) {
        return Optional.ofNullable(budgets.get(budgetId));
    }

    @Override
    public Optional<CampaignBudget> findBudgetByCampaignId(String campaignId) {
        return budgets.values().stream().filter(budget -> budget.campaignId().equals(campaignId)).findFirst();
    }

    @Override
    public void saveTemplate(CouponTemplate template) {
        // Mirrors coupon_templates.campaign_id NOT NULL and coupon_templates_code_version_idx (UNIQUE on
        // templateCode + templateVersion).
        requireOwner("campaignId", template.campaignId());
        String codeVersion = template.templateCode() + "/" + template.templateVersion();
        String owner = templateIdsByCodeVersion.putIfAbsent(codeVersion, template.templateId());
        if (owner != null && !owner.equals(template.templateId())) {
            throw new DuplicateBusinessKeyException("templateCode/templateVersion", codeVersion);
        }
        templates.put(template.templateId(), template);
    }

    @Override
    public Optional<CouponTemplate> findTemplate(String templateId) {
        return Optional.ofNullable(templates.get(templateId));
    }

    @Override
    public List<CouponTemplate> findTemplatesByCampaignId(String campaignId) {
        return templates.values().stream().filter(template -> template.campaignId().equals(campaignId)).toList();
    }

    @Override
    public void saveBatch(IssuanceBatch batch) {
        // Mirrors issuance_batches.campaign_id NOT NULL and issuance_batches.template_id NOT NULL. Both are real
        // columns in Postgres with foreign keys behind them, so neither may be absent here either.
        requireOwner("campaignId", batch.campaignId());
        requireOwner("templateId", batch.templateId());
        batches.put(batch.issuanceBatchId(), batch);
    }

    /**
     * The three child tables declare their owning-aggregate columns NOT NULL with no default, so a missing owner is
     * a write Postgres refuses. The in-memory profile has to refuse it too, otherwise tests pass against a store
     * that is strictly more permissive than production.
     */
    private static void requireOwner(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required for the owning aggregate reference");
        }
    }

    @Override
    public Optional<IssuanceBatch> findBatch(String issuanceBatchId) {
        return Optional.ofNullable(batches.get(issuanceBatchId));
    }

    @Override
    public List<IssuanceBatch> findBatchesByCampaignId(String campaignId) {
        return batches.values().stream().filter(batch -> batch.campaignId().equals(campaignId)).toList();
    }
}
