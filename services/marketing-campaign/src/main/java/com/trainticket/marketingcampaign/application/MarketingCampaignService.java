package com.trainticket.marketingcampaign.application;

import com.trainticket.marketingcampaign.domain.Campaign;
import com.trainticket.marketingcampaign.domain.CampaignBudget;
import com.trainticket.marketingcampaign.domain.CampaignWindow;
import com.trainticket.marketingcampaign.domain.CouponTemplate;
import com.trainticket.marketingcampaign.domain.DomainEvent;
import com.trainticket.marketingcampaign.domain.IssuanceBatch;
import com.trainticket.marketingcampaign.domain.Money;
import com.trainticket.platformkit.idempotency.UuidV7;
import com.trainticket.platformkit.messaging.EventEnvelopeFactory;
import com.trainticket.platformkit.messaging.EventPublisher;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MarketingCampaignService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MarketingCampaignService.class);
    private static final String PRODUCER = "marketing-campaign";

    private final Clock clock;
    private final CampaignRepository repository;
    private final EventPublisher publisher;
    private final EventEnvelopeFactory envelopeFactory;

    @Autowired
    public MarketingCampaignService(CampaignRepository repository, EventPublisher publisher) {
        this(Clock.systemUTC(), repository, publisher);
    }

    public MarketingCampaignService(Clock clock, CampaignRepository repository, EventPublisher publisher) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.publisher = Objects.requireNonNull(publisher, "publisher is required");
        this.envelopeFactory = new EventEnvelopeFactory(PRODUCER, clock);
    }

    public CampaignDetail draftCampaign(DraftCampaignCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        Campaign campaign = Campaign.draft(
            "mc-" + UuidV7.generate(),
            command.externalKey(),
            command.name(),
            command.window(),
            clock.instant()
        );
        try {
            repository.saveCampaign(campaign);
        } catch (DuplicateBusinessKeyException exception) {
            // The caller reused an externalKey another campaign already owns. Retrying the identical request can
            // never succeed; 409 is the correct answer and the externalKey belongs in the log so this is diagnosable
            // server-side instead of only from the client's status codes.
            LOGGER.warn("DraftCampaign rejected externalKey={} campaignId={} correlationId={} reason=DUPLICATE_EXTERNAL_KEY",
                command.externalKey(), campaign.campaignId(), correlationId);
            throw exception;
        } catch (OptimisticConcurrencyException exception) {
            LOGGER.warn("DraftCampaign rejected externalKey={} campaignId={} correlationId={} reason=SNAPSHOT_VERSION_CONFLICT",
                command.externalKey(), campaign.campaignId(), correlationId);
            throw exception;
        }
        publish(campaign.domainEvents(), correlationId);
        LOGGER.info("DraftCampaign accepted campaignId={} externalKey={} correlationId={}",
            campaign.campaignId(), campaign.externalKey(), correlationId);
        return detail(campaign);
    }

    public CampaignDetail submitCampaign(String campaignId, String correlationId) {
        Campaign previous = getCampaign(campaignId);
        Campaign campaign = previous.submitForReview(clock.instant());
        repository.saveCampaign(campaign);
        publishSince(campaign.domainEvents(), previous.version(), correlationId);
        return detail(campaign);
    }

    public CampaignDetail approveCampaign(String campaignId, ApproveCampaignCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        Campaign previous = getCampaign(campaignId);
        Campaign campaign = previous.approve(command.approvalRef(), clock.instant());
        repository.saveCampaign(campaign);
        publishSince(campaign.domainEvents(), previous.version(), correlationId);
        return detail(campaign);
    }

    public CampaignDetail scheduleCampaign(String campaignId, String correlationId) {
        Campaign previous = getCampaign(campaignId);
        Campaign campaign = previous.schedule(clock.instant());
        repository.saveCampaign(campaign);
        publishSince(campaign.domainEvents(), previous.version(), correlationId);
        return detail(campaign);
    }

    public CampaignDetail startCampaign(String campaignId, String correlationId) {
        Campaign previous = getCampaign(campaignId);
        Campaign campaign = previous.start(clock.instant());
        repository.saveCampaign(campaign);
        publishSince(campaign.domainEvents(), previous.version(), correlationId);
        return detail(campaign);
    }

    public CampaignDetail pauseCampaign(String campaignId, OperatorReasonCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        Campaign previous = getCampaign(campaignId);
        Campaign campaign = previous.pause(command.reason(), command.operatorRef(), clock.instant());
        repository.saveCampaign(campaign);
        publishSince(campaign.domainEvents(), previous.version(), correlationId);
        return detail(campaign);
    }

    public CampaignDetail completeCampaign(String campaignId, ReasonCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        Instant now = clock.instant();
        Campaign previous = getCampaign(campaignId);
        Campaign campaign = previous.beginCompletion(command.reason(), now).complete(command.reason(), now);
        repository.saveCampaign(campaign);
        publishSince(campaign.domainEvents(), previous.version(), correlationId);
        return detail(campaign);
    }

    public CampaignDetail cancelCampaign(String campaignId, OperatorReasonCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        Campaign previous = getCampaign(campaignId);
        Campaign campaign = previous.cancel(command.reason(), command.operatorRef(), clock.instant());
        repository.saveCampaign(campaign);
        publishSince(campaign.domainEvents(), previous.version(), correlationId);
        return detail(campaign);
    }

    public CampaignDetail setBudget(String campaignId, SetBudgetCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        Campaign campaign = getCampaign(campaignId);
        String budgetId = "mcb-" + UuidV7.generate();
        CampaignBudget budget = CampaignBudget.set(budgetId, campaignId, command.totalBudget(), clock.instant());
        Campaign readyCampaign = campaign.withLaunchReadiness(budgetId, command.targetRuleSetId(), clock.instant());
        repository.saveBudget(budget);
        repository.saveCampaign(readyCampaign);
        publish(budget.domainEvents(), correlationId);
        return detail(readyCampaign);
    }

    public BudgetDetail reserveBudget(String budgetId, MoneyCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        CampaignBudget previous = getBudget(budgetId);
        CampaignBudget budget = previous.reserve(command.amount(), command.reference(), clock.instant());
        repository.saveBudget(budget);
        publishSince(budget.domainEvents(), previous.version(), correlationId);
        return BudgetDetail.from(budget);
    }

    public BudgetDetail consumeBudget(String budgetId, MoneyCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        CampaignBudget previous = getBudget(budgetId);
        CampaignBudget budget = previous.consume(command.amount(), command.reference(), clock.instant());
        repository.saveBudget(budget);
        publishSince(budget.domainEvents(), previous.version(), correlationId);
        return BudgetDetail.from(budget);
    }

    public BudgetDetail releaseBudget(String budgetId, MoneyCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        CampaignBudget previous = getBudget(budgetId);
        CampaignBudget budget = previous.release(command.amount(), command.reference(), clock.instant());
        repository.saveBudget(budget);
        publishSince(budget.domainEvents(), previous.version(), correlationId);
        return BudgetDetail.from(budget);
    }

    public TemplateDetail draftTemplate(String campaignId, DraftTemplateCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        Campaign campaign = getCampaign(campaignId);
        CouponTemplate template = CouponTemplate.draft(
            "mct-" + UuidV7.generate(),
            campaignId,
            command.templateCode(),
            command.templateVersion(),
            command.faceValue(),
            command.minimumSpend(),
            command.applicableScope(),
            command.redemptionRule(),
            command.validityWindow(),
            campaign.window(),
            clock.instant()
        );
        repository.saveTemplate(template);
        publish(template.domainEvents(), correlationId);
        return TemplateDetail.from(template);
    }

    public TemplateDetail validateTemplate(String templateId, String correlationId) {
        CouponTemplate previous = getTemplate(templateId);
        CouponTemplate template = previous.validate(clock.instant());
        repository.saveTemplate(template);
        publishSince(template.domainEvents(), previous.version(), correlationId);
        return TemplateDetail.from(template);
    }

    public TemplateDetail publishTemplate(String templateId, ApproveCampaignCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        CouponTemplate previous = getTemplate(templateId);
        CouponTemplate template = previous.publish(command.approvalRef(), clock.instant());
        repository.saveTemplate(template);
        publishSince(template.domainEvents(), previous.version(), correlationId);
        return TemplateDetail.from(template);
    }

    public TemplateDetail retireTemplate(String templateId, ReasonCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        CouponTemplate previous = getTemplate(templateId);
        CouponTemplate template = previous.retire(command.reason(), clock.instant());
        repository.saveTemplate(template);
        publishSince(template.domainEvents(), previous.version(), correlationId);
        return TemplateDetail.from(template);
    }

    public BatchDetail planBatch(String campaignId, PlanBatchCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        getCampaign(campaignId);
        getTemplate(command.templateId());
        IssuanceBatch batch = IssuanceBatch.plan(
            "mcbat-" + UuidV7.generate(),
            campaignId,
            command.templateId(),
            command.audienceSnapshotId(),
            clock.instant()
        );
        repository.saveBatch(batch);
        publish(batch.domainEvents(), correlationId);
        return BatchDetail.from(batch);
    }

    public BatchDetail startBatch(String issuanceBatchId, String correlationId) {
        IssuanceBatch previous = getBatch(issuanceBatchId);
        IssuanceBatch batch = previous.start(clock.instant());
        repository.saveBatch(batch);
        publishSince(batch.domainEvents(), previous.version(), correlationId);
        return BatchDetail.from(batch);
    }

    public BatchDetail closeBatch(String issuanceBatchId, ReasonCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        IssuanceBatch previous = getBatch(issuanceBatchId);
        IssuanceBatch batch = previous.close(command.reason(), clock.instant());
        repository.saveBatch(batch);
        publishSince(batch.domainEvents(), previous.version(), correlationId);
        return BatchDetail.from(batch);
    }

    public CampaignDetail get(String campaignId) {
        return detail(getCampaign(campaignId));
    }

    private Campaign getCampaign(String campaignId) {
        return repository.findCampaign(campaignId).orElseThrow(() -> new NotFoundException("campaign not found: " + campaignId));
    }

    private CampaignBudget getBudget(String budgetId) {
        return repository.findBudget(budgetId).orElseThrow(() -> new NotFoundException("campaign budget not found: " + budgetId));
    }

    private CouponTemplate getTemplate(String templateId) {
        return repository.findTemplate(templateId).orElseThrow(() -> new NotFoundException("coupon template not found: " + templateId));
    }

    private IssuanceBatch getBatch(String issuanceBatchId) {
        return repository.findBatch(issuanceBatchId).orElseThrow(() -> new NotFoundException("issuance batch not found: " + issuanceBatchId));
    }

    private CampaignDetail detail(Campaign campaign) {
        return CampaignDetail.from(
            campaign,
            repository.findBudgetByCampaignId(campaign.campaignId()).map(BudgetDetail::from).orElse(null),
            repository.findTemplatesByCampaignId(campaign.campaignId()).stream().map(TemplateDetail::from).toList(),
            repository.findBatchesByCampaignId(campaign.campaignId()).stream().map(BatchDetail::from).toList()
        );
    }

    private void publishSince(List<DomainEvent> events, long previousVersion, String correlationId) {
        publish(events.stream().filter(event -> event.aggregateVersion() > previousVersion).toList(), correlationId);
    }

    private void publish(List<DomainEvent> events, String correlationId) {
        String normalizedCorrelationId = PrefixedIds.isCorrelationId(correlationId) ? correlationId : PrefixedIds.newCorrelationId();
        String causationId = PrefixedIds.newCommandId();
        for (DomainEvent event : events) {
            publisher.publish(envelopeFactory.create(event.getClass().getSimpleName(), normalizedCorrelationId, causationId, DomainEventPayloads.toMap(event)));
        }
    }

    public record DraftCampaignCommand(String externalKey, String name, CampaignWindow window) {}
    public record ApproveCampaignCommand(String approvalRef) {}
    public record ReasonCommand(String reason) {}
    public record OperatorReasonCommand(String reason, String operatorRef) {}
    public record SetBudgetCommand(Money totalBudget, String targetRuleSetId) {}
    public record MoneyCommand(Money amount, String reference) {}
    public record DraftTemplateCommand(String templateCode, int templateVersion, Money faceValue, Money minimumSpend, String applicableScope, String redemptionRule, CampaignWindow validityWindow) {}
    public record PlanBatchCommand(String templateId, String audienceSnapshotId) {}

    public record CampaignDetail(
        String campaignId,
        String externalKey,
        String name,
        Map<String, String> window,
        String targetRuleSetId,
        String budgetId,
        String approvalRef,
        String status,
        String createdAt,
        String updatedAt,
        long version,
        BudgetDetail budget,
        List<TemplateDetail> templates,
        List<BatchDetail> batches
    ) {
        static CampaignDetail from(Campaign campaign, BudgetDetail budget, List<TemplateDetail> templates, List<BatchDetail> batches) {
            CampaignWindow w = campaign.window();
            Map<String, String> windowMap = w != null ? Map.of("validFrom", w.validFrom().toString(), "validUntil", w.validUntil().toString()) : Map.of();
            return new CampaignDetail(campaign.campaignId(), campaign.externalKey(), campaign.name(), windowMap, campaign.targetRuleSetId(), campaign.budgetId(), campaign.approvalRef(), campaign.status().name(), campaign.createdAt().toString(), campaign.updatedAt().toString(), campaign.version(), budget, templates, batches);
        }
    }

    public record BudgetDetail(String budgetId, String campaignId, Money totalBudget, Money reservedAmount, Money consumedAmount, boolean closed, String createdAt, String updatedAt, long version) {
        static BudgetDetail from(CampaignBudget budget) {
            return new BudgetDetail(budget.budgetId(), budget.campaignId(), budget.totalBudget(), budget.reservedAmount(), budget.consumedAmount(), budget.closed(), budget.createdAt().toString(), budget.updatedAt().toString(), budget.version());
        }
    }

    public record TemplateDetail(String templateId, String campaignId, String templateCode, int templateVersion, String status, Money faceValue, Money minimumSpend, String applicableScope, String redemptionRule, Map<String, String> validityWindow, String createdAt, String updatedAt, long version) {
        static TemplateDetail from(CouponTemplate template) {
            CampaignWindow vw = template.validityWindow();
            Map<String, String> vwMap = vw != null ? Map.of("validFrom", vw.validFrom().toString(), "validUntil", vw.validUntil().toString()) : Map.of();
            return new TemplateDetail(template.templateId(), template.campaignId(), template.templateCode(), template.templateVersion(), template.status().name(), template.faceValue(), template.minimumSpend(), template.applicableScope(), template.redemptionRule(), vwMap, template.createdAt().toString(), template.updatedAt().toString(), template.version());
        }
    }

    public record BatchDetail(String issuanceBatchId, String campaignId, String templateId, String audienceSnapshotId, String status, int itemCount, String plannedAt, String updatedAt, long version) {
        static BatchDetail from(IssuanceBatch batch) {
            return new BatchDetail(batch.issuanceBatchId(), batch.campaignId(), batch.templateId(), batch.audienceSnapshotId(), batch.status().name(), batch.itemsById().size(), batch.plannedAt().toString(), batch.updatedAt().toString(), batch.version());
        }
    }
}
