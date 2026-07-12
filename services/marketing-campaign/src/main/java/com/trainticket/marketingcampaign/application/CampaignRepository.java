package com.trainticket.marketingcampaign.application;

import com.trainticket.marketingcampaign.domain.Campaign;
import com.trainticket.marketingcampaign.domain.CampaignBudget;
import com.trainticket.marketingcampaign.domain.CouponTemplate;
import com.trainticket.marketingcampaign.domain.IssuanceBatch;
import java.util.List;
import java.util.Optional;

public interface CampaignRepository {
    void saveCampaign(Campaign campaign);
    Optional<Campaign> findCampaign(String campaignId);

    void saveBudget(CampaignBudget budget);
    Optional<CampaignBudget> findBudget(String budgetId);
    Optional<CampaignBudget> findBudgetByCampaignId(String campaignId);

    void saveTemplate(CouponTemplate template);
    Optional<CouponTemplate> findTemplate(String templateId);
    List<CouponTemplate> findTemplatesByCampaignId(String campaignId);

    void saveBatch(IssuanceBatch batch);
    Optional<IssuanceBatch> findBatch(String issuanceBatchId);
    List<IssuanceBatch> findBatchesByCampaignId(String campaignId);
}
