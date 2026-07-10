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
    private final ConcurrentMap<String, CampaignBudget> budgets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CouponTemplate> templates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, IssuanceBatch> batches = new ConcurrentHashMap<>();

    @Override
    public void saveCampaign(Campaign campaign) {
        campaigns.put(campaign.campaignId(), campaign);
    }

    @Override
    public Optional<Campaign> findCampaign(String campaignId) {
        return Optional.ofNullable(campaigns.get(campaignId));
    }

    @Override
    public void saveBudget(CampaignBudget budget) {
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
        batches.put(batch.issuanceBatchId(), batch);
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
