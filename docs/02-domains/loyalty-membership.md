# Loyalty Membership Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Loyalty Membership |
| Status | proposed-ddd-baseline |
| Owner Agent | agentm-loyalty-membership |
| Last Updated | 2026-07-09 |
| High-Level Inputs | `docs/adr/0003-commercial-realism-scope.md`, `docs/02-domains/wallet-promotion.md`, `docs/08-contracts/events/fulfillment.md`, `docs/08-contracts/events/journey-order.md`, `docs/08-contracts/api/wallet-promotion.md` |

## 1. 领域目标

Loyalty Membership 负责会员档案、等级机、积分账本、滚动 12 个月等级积分计算、积分过期和等级权益表。它回答“某个 account 当前属于哪个会员等级、哪些履约事实已经产生积分、哪些积分仍可兑换、兑换后的权益如何交付”。

独立建模的原因是：积分不是钱包余额的简单字段，而是由履约事实、等级倍率、滚动 12 个月窗口、过期批次、撤销和兑换意图共同约束的商业账本。Wallet / Promotion 只拥有兑换产物（券、补偿权益、钱包权益）的发行、冻结、核销和过期生命周期；Loyalty Membership 拥有积分来源、积分余额、等级资格和兑换规则。两者通过受控命令衔接，不能共享可变余额。

本域在 ADR-0003 Wave C 中激活，定位为 P1 commercial capability：不改变 P0 购票、支付、出票闭环；只从已经存在的订单/履约事实衍生会员权益。

## 2. 边界

### In Scope

- `Membership` 会员档案：account 维度会员号、状态、等级、等级有效期、等级评估窗口和 opt-in/opt-out 审计引用。
- 等级机：`BASIC -> SILVER -> GOLD -> PLATINUM`，按滚动 12 个月 tier points 评估；降级只在评估周期边界发生。
- `PointsLedger`：积分 accrual、redemption reservation、redemption spend、expiry、adjustment、reversal 的不可变分录和可查询余额。
- 从真实履约完成事实生成积分：当前可消费的契约事实是 Fulfillment 的 `FulfillmentCompleted`；`NoShowRecorded` 可生成不可逆的 missed accrual 记录。
- 兑换编排：校验可用积分、冻结/扣减积分，并通过 Wallet / Promotion 的 `IssueBenefit`（`POST /api/v1/benefits`）发行兑换产物。
- 积分过期：按积分批次、有效期和滚动策略生成过期分录，终态可查询。
- 等级权益表：每个 tier 的积分倍率、兑换目录可见性、客服优先级、通知提醒规则和 Wallet 发行参数模板。
- 管理员审计修正：只通过 Admin & Audit 审批后的命令调整积分或等级，保留原因码和操作引用。

### Out of Scope

- 现金支付、退款、支付渠道状态和组合支付，归 Payment / Payment Channel。
- 券、钱包余额、补偿权益、PromotionInstrument 生命周期、BenefitRedemption 和权益核销，归 Wallet / Promotion。
- 商品价格、票规、折扣计算、营销券发放策略，归 Fare & Pricing / Offer Management / Marketing Campaign。
- 订单商业状态、订单项金额、售后可退改范围，归 Journey Order / Post Sales。
- 票证签发、检票、乘车、到达和履约完成原始证据，归 Entitlement & Ticketing / Fulfillment。
- 登录主体、账户冻结、旅客证件和资格认证，归 Account / Traveler Profile / Identity Verification。
- 财务收入确认、对账、发票、税务处理，归 Finance Settlement / Invoicing。
- 真实第三方会员、航司里程、酒店积分或外部积分联盟接入；ADR-0003 要求外部方均模拟，本域 baseline 不接真实网络。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| Membership | account 维度会员档案聚合根，保存会员状态、等级、评估窗口和汇总余额。 | 不保存完整账户身份；Account 仍是登录与账户生命周期来源。 |
| Tier | `BASIC`、`SILVER`、`GOLD`、`PLATINUM` 四级会员等级。 | 枚举对外使用 SCREAMING_SNAKE_CASE。 |
| Tier Points | 用于滚动 12 个月等级评估的积分。 | 可与 redeemable points 同源，但部分活动积分可配置为不计等级。 |
| Redeemable Points | 可兑换的积分余额。 | 本域账本余额，不是 Wallet `POINTS` 子账本。 |
| Points Ledger Entry | 积分账本不可变分录。 | 每条分录必须有 source fact、businessReason、occurredAt 和幂等键。 |
| Points Lot | 一次 accrual 产生的积分批次，携带 validFrom、expiresAt、remainingPoints。 | expiry 按 lot 处理，避免只按账户总额扣减。 |
| Accrual | 根据履约完成事实或受控补录产生积分。 | `FulfillmentCompleted` 是当前契约中真实存在的完成事实。 |
| Missed Accrual | 明确不应产生积分的履约/未履约事实记录。 | 例如 `NoShowRecorded`；Missed 终态不可逆，只能新建人工 adjustment。 |
| Redemption | 会员用积分兑换权益的意图和结果。 | 产物由 Wallet / Promotion 发行；本域只扣积分和保存 wallet benefit 引用。 |
| Tier Benefit Table | 等级权益表和值对象集合。 | 保存权益规则模板，不拥有 Wallet 产物生命周期。 |
| Rolling 12-Month Window | 从评估时点向前 12 个月计算有效 tier points 的窗口。 | 使用 UTC 日期边界，避免时区差异。 |

## 4. 上下游契约

本节只列已在 `docs/08-contracts/` 中存在的契约名或端点名。Loyalty Membership 新命令和新事件只在第 7 节列出；本任务不新增契约文档。

### Upstream

| Upstream Context | Consumed Existing Contract | Reason / Boundary |
|---|---|---|
| Fulfillment | `FulfillmentCompleted` from `docs/08-contracts/events/fulfillment.md` | 积分 accrual 的权威完成事实；payload 含 `fulfillmentRecordId`、`entitlementId`、`segmentBookingId`、`journeyOrderId`、`travelerId`、`completedAt`、`completionSource`。 |
| Fulfillment | `NoShowRecorded` from `docs/08-contracts/events/fulfillment.md` | 记录 `MISSED` accrual，防止后续补发误把 no-show 当作完成履约。 |
| Journey Order | `JourneyOrderCreated`、`JourneyOrderConfirmed`、`JourneyOrderCancelled` from `docs/08-contracts/events/journey-order.md` | 只用于会员档案预热、订单归属和取消/异常排查；不作为完成履约 accrual 依据。 |
| Account | `GET /api/v1/accounts/{accountId}` from `docs/08-contracts/api/account.md` | 注册/查询会员时验证 account 存在与账户状态；Account 不保存积分余额。 |
| Traveler Profile | `GET /api/v1/travelers/{travelerId}` from `docs/08-contracts/api/traveler-profile.md` | 需要旅客归属或展示快照时查询脱敏资料；本域不保存证件号。 |
| Admin & Audit | `ManualActionApproved`、`ManualActionExecuted` from `docs/08-contracts/events/admin-audit.md`; `POST /api/v1/admin/manual-actions` | 人工补积分、扣积分、强制等级调整必须有审批和审计闭环。 |

### Downstream

| Downstream Context | Used Existing Contract | Reason / Boundary |
|---|---|---|
| Wallet / Promotion | `IssueBenefit` via `POST /api/v1/benefits` in `docs/08-contracts/api/wallet-promotion.md` | 兑换成功后发行兑换产物；请求使用 Wallet 既有 `benefitType` / `balanceType` / `businessReason` 形状。 |
| Wallet / Promotion | `BenefitIssued` from `docs/08-contracts/events/wallet-promotion.md` | 兑换 Saga 等待钱包发行事实后把本域 redemption 标记为 `ISSUED`。 |
| Wallet / Promotion | `BenefitRevoked`、`BenefitExpired`、`BenefitRedemptionReversed` from `docs/08-contracts/events/wallet-promotion.md` | 只作为客服时间线和异常补偿输入；不回写历史积分 accrual。 |
| Notification | `ScheduleNotification` bus-only command in `docs/08-contracts/events/notification.md` | 会员升级、降级提醒、积分到账、积分即将过期、兑换结果通知；发送失败不回滚积分账本。 |
| Reporting | `GET /api/v1/metrics`、`GET /api/v1/dashboards/{dashboardId}` and bus-only `RebuildReadModel` from `docs/08-contracts/api/reporting.md` | 报表只读消费后续 Loyalty 事件流；不反向修改会员等级或积分。 |
| Admin & Audit | `RecordAuditEntry` bus-only command in `docs/08-contracts/api/admin-audit.md` | 记录人工积分调整、兑换人工重试和等级 override 的审计摘要。 |

### Contract Notes

- `docs/08-contracts/events/journey-order.md` 当前没有 `JourneyCompleted` 或 `JourneyOrderCompleted` 发布事件；`docs/02-domains/journey-order.md` 中的 `JourneyCompleted` 尚未进入 08-contracts 契约面。因此本 baseline 禁止用杜撰的 journey-order 完成事件做 accrual，必须以 `FulfillmentCompleted` 为准；若未来补充 Journey Order 完成契约，应通过单独契约变更接入。
- Wallet / Promotion 现有契约包含 `benefitType=POINTS` 和 `balanceType=POINTS`，但 Loyalty Membership 激活后，会员积分的权威账本在本域。Loyalty 发起兑换时原则上请求 Wallet 发行 `COUPON`、`COMPENSATION_CREDIT` 或其他权益产物；不得把 Wallet 的 `POINTS` 子账本当作会员积分余额来源。

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| Membership | 同一 `accountId` + loyalty program 只能有一个 active membership；状态为 `CLOSED` 后不能重新打开同一 membershipId；tier 只能是 `BASIC`、`SILVER`、`GOLD`、`PLATINUM`；tier 变更必须由滚动 12 月评估、受控人工命令或迁移命令产生；会员档案不保存未脱敏 PII。 | EnrollMembership、SuspendMembership、ResumeMembership、CloseMembership、EvaluateTier、ApplyManualTierOverride | MembershipEnrolled、MembershipSuspended、MembershipResumed、MembershipClosed、MembershipTierEvaluated、MembershipTierChanged、ManualTierOverrideApplied |
| PointsLedger | 每个 source fact 对同一 membership 最多产生一次 accrual 或 missed 记录；ledger entry 不可修改，只能追加 reversal/adjustment；可用积分不得为负；lot expiry 只能扣未过期且未兑换的 remainingPoints；所有分录必须有 businessReason 和 idempotencyKey。 | AccruePointsFromFulfillment、RecordMissedAccrual、ReservePointsForRedemption、CommitPointsRedemption、ReleasePointsReservation、ExpirePointsLot、ApplyPointsAdjustment、ReversePointsEntry | PointsAccrued、PointsAccrualMissed、PointsReservedForRedemption、PointsRedemptionCommitted、PointsReservationReleased、PointsExpired、PointsAdjusted、PointsEntryReversed |
| PointsRedemption | 同一 membership + redemptionCatalogItemId + clientRequestId 只能有一个兑换意图；进入 `FAILED` 后不可自动改成功，只能新建 redemption 或人工补偿；Wallet 发行成功前不得提交积分扣减为最终 spend；walletBenefitId 一旦绑定不可替换。 | RequestPointsRedemption、ConfirmWalletBenefitIssued、MarkRedemptionFailed、CancelPointsRedemption、RetryWalletIssuance | PointsRedemptionRequested、WalletBenefitIssuanceRequested、PointsRedemptionIssued、PointsRedemptionFailed、PointsRedemptionCancelled、WalletIssuanceRetryScheduled |
| TierBenefitTable | 每个 tier + effectiveFrom 只有一个已发布版本；已发布版本不可原地修改，只能发布新版本；权益参数只保存模板和 Wallet issuance mapping，不保存 Wallet benefit 状态。 | DraftTierBenefitTable、PublishTierBenefitTable、RetireTierBenefitTable | TierBenefitTableDrafted、TierBenefitTablePublished、TierBenefitTableRetired |
| TierEvaluationCycle | 同一 membership + evaluationDate 只能有一次完成评估；评估输入必须包含滚动 12 月窗口、计等级积分汇总、上次等级和目标等级；降级必须遵守 grace policy。 | ScheduleTierEvaluation、RunTierEvaluation、CompleteTierEvaluation、MarkTierEvaluationFailed | TierEvaluationScheduled、TierEvaluationCompleted、TierEvaluationFailed |

### 内部实体和值对象

| Type | Kind | Responsibility | Ownership Notes |
|---|---|---|---|
| PointsLot | Entity | 保存 accrual 批次、原始积分、剩余积分、validFrom、expiresAt、tierEligible。 | 由 PointsLedger 拥有；expiry 和 redemption 只通过 lot 消耗。 |
| LedgerEntry | Entity | 不可变账本分录，类型包括 `ACCRUAL`、`MISSED`、`RESERVATION`、`REDEMPTION`、`RELEASE`、`EXPIRY`、`ADJUSTMENT`、`REVERSAL`。 | 分录一旦写入不可更新。 |
| TierThreshold | Value Object | 等级门槛和保级规则。 | 由 TierBenefitTable 或配置版本引用。 |
| RedemptionCatalogItem | Value Object | 积分兑换目录项、所需积分、Wallet issuance template、库存/有效期策略。 | 不拥有 Wallet 产物。 |
| SourceFactRef | Value Object | 上游事实引用：stream、eventType、eventId、aggregateId、occurredAt。 | 用于幂等和可追溯。 |
| BusinessReason | Value Object | reasonType、reasonCode、referenceType、referenceId、description。 | description 禁止未脱敏证件或 PII。 |

### 等级权益表 baseline

| Tier | Rolling 12-Month Tier Points | Accrual Multiplier | Baseline Benefits | Wallet Boundary |
|---|---:|---:|---|---|
| `BASIC` | 0+ | 1.00x | 积分到账、积分过期提醒、基础兑换目录。 | 兑换产物由 Wallet `IssueBenefit` 发行。 |
| `SILVER` | 5,000+ | 1.10x | 优先客服标签、部分兑换目录提前可见、过期前 30 天提醒。 | Wallet 只看到具体权益发行请求，不计算 tier。 |
| `GOLD` | 20,000+ | 1.25x | 更高兑换目录、生日/节日权益模板、过期前 60/30 天提醒。 | 生日权益若实现为券，仍由 Wallet 拥有券生命周期。 |
| `PLATINUM` | 50,000+ | 1.50x | 最高兑换目录、客服高优先级、定制权益模板、过期前 90/60/30 天提醒。 | Loyalty 不核销权益，只保存 walletBenefitId 引用。 |

阈值和倍率是 baseline 设计值，实际商业数值必须版本化发布到 `TierBenefitTable`，不能硬编码在消费者中。

## 6. 状态机

### Membership 状态机

| State | Meaning | Allowed Next | Notes |
|---|---|---|---|
| `PENDING_ENROLLMENT` | 会员创建中，等待 account 验证或迁移补齐。 | `ACTIVE`, `FAILED`, `CLOSED` | 查询可见。 |
| `ACTIVE` | 正常会员，可 accrual、兑换和评估等级。 | `SUSPENDED`, `CLOSED` | 默认状态。 |
| `SUSPENDED` | 风控、账户冻结或人工原因暂停 accrual/兑换。 | `ACTIVE`, `CLOSED` | 历史积分可查询；是否过期按策略执行。 |
| `CLOSED` | 会员关闭。 | - | 终态，可查询安息；不可重新打开。 |
| `FAILED` | 注册/迁移失败且不能自动修复。 | - | 终态，可查询安息；不可自动转 active。 |

### Tier 状态机

| Current | Trigger | Target | Rule |
|---|---|---|---|
| `BASIC` | rolling 12-month tier points >= SILVER threshold | `SILVER` | 升级即时生效。 |
| `SILVER` | rolling 12-month tier points >= GOLD threshold | `GOLD` | 可跨级升级，但事件记录 from/to。 |
| `GOLD` | rolling 12-month tier points >= PLATINUM threshold | `PLATINUM` | 可跨级升级。 |
| `PLATINUM` / `GOLD` / `SILVER` | evaluation cycle below current threshold after grace period | lower tier | 降级只在评估周期边界发生，不由单笔消费回滚。 |
| any tier | approved manual override | target tier | 必须带 Admin & Audit 引用和 expiresAt。 |

等级不是终态；它是 Membership 内的可重算状态。降级不删除历史权益，只影响后续 accrual multiplier 和兑换目录。

### PointsLot / Accrual 状态机

| State | Meaning | Allowed Next | Irreversible Rules |
|---|---|---|---|
| `PENDING` | 已接收 source fact，等待规则计算或去重。 | `ACCRUED`, `MISSED`, `FAILED` | - |
| `ACCRUED` | 已生成 points lot。 | `PARTIALLY_REDEEMED`, `RESERVED`, `EXPIRED`, `REVERSED` | 不可原地改数值。 |
| `RESERVED` | 积分为兑换冻结。 | `REDEEMED`, `ACCRUED`, `EXPIRED` | 释放回 `ACCRUED` 必须有 release 分录。 |
| `PARTIALLY_REDEEMED` | 部分积分已兑换，仍有余额。 | `RESERVED`, `REDEEMED`, `EXPIRED`, `REVERSED` | 已兑换部分不可被 expiry 再扣。 |
| `REDEEMED` | 批次积分全部用于兑换。 | `REVERSED` | 只有补偿 reversal 可追加，不恢复原事件。 |
| `EXPIRED` | 批次剩余积分已过期。 | - | 终态，可查询安息。 |
| `MISSED` | 明确无积分，例如 `NoShowRecorded` 或不合规则。 | - | Missed 不可逆；若客服补发，必须新建 `ADJUSTMENT` lot。 |
| `FAILED` | 规则计算或数据校验失败，无法自动处理。 | - | Failed 不可逆；只能人工新建调整或重放新 source fact。 |
| `REVERSED` | 原 accrual 被反向冲正。 | - | 终态，可查询安息。 |

### Redemption 状态机

| State | Meaning | Allowed Next | Notes |
|---|---|---|---|
| `REQUESTED` | 用户/客服提出兑换，尚未冻结积分。 | `POINTS_RESERVED`, `CANCELLED`, `FAILED` | 幂等键锁定兑换意图。 |
| `POINTS_RESERVED` | 积分已冻结，等待 Wallet 发行。 | `WALLET_ISSUANCE_PENDING`, `CANCELLED`, `FAILED` | 超时可释放积分。 |
| `WALLET_ISSUANCE_PENDING` | 已调用 Wallet `IssueBenefit` 或等待 `BenefitIssued`。 | `ISSUED`, `FAILED` | 不盲目重复调用，按幂等键重试。 |
| `ISSUED` | Wallet 已发行兑换产物，本域积分扣减提交。 | - | 终态，可查询安息。 |
| `CANCELLED` | 发行前用户或规则取消，积分释放。 | - | 终态。 |
| `FAILED` | Wallet 拒绝、规则失败或重试耗尽。 | - | Failed 不可逆；新兑换必须新 redemptionId。 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| EnrollMembership | Membership | MembershipEnrolled | accountId + loyaltyProgramId + enrollmentRequestId |
| SuspendMembership | Membership | MembershipSuspended | membershipId + suspensionReason + sourceEventId/manualActionId |
| ResumeMembership | Membership | MembershipResumed | membershipId + resumeReason + sourceEventId/manualActionId |
| CloseMembership | Membership | MembershipClosed | membershipId + closeReason + requestId |
| AccruePointsFromFulfillment | PointsLedger | PointsAccrued | membershipId + `FulfillmentCompleted.eventId` |
| RecordMissedAccrual | PointsLedger | PointsAccrualMissed | membershipId + `NoShowRecorded.eventId` |
| ReservePointsForRedemption | PointsLedger / PointsRedemption | PointsReservedForRedemption | membershipId + redemptionId + reservationAttempt |
| CommitPointsRedemption | PointsLedger / PointsRedemption | PointsRedemptionCommitted | membershipId + redemptionId + walletBenefitId |
| ReleasePointsReservation | PointsLedger / PointsRedemption | PointsReservationReleased | membershipId + redemptionId + releaseReason + sourceEventId |
| RequestPointsRedemption | PointsRedemption | PointsRedemptionRequested、WalletBenefitIssuanceRequested | membershipId + redemptionCatalogItemId + clientRequestId |
| ConfirmWalletBenefitIssued | PointsRedemption | PointsRedemptionIssued | redemptionId + `BenefitIssued.eventId` |
| MarkRedemptionFailed | PointsRedemption | PointsRedemptionFailed | redemptionId + failureCode + sourceEventId |
| CancelPointsRedemption | PointsRedemption | PointsRedemptionCancelled | redemptionId + cancelReason + requesterId |
| RetryWalletIssuance | PointsRedemption | WalletIssuanceRetryScheduled | redemptionId + retryAttemptNo |
| ExpirePointsLot | PointsLedger | PointsExpired | membershipId + pointsLotId + expiryRunId |
| ApplyPointsAdjustment | PointsLedger | PointsAdjusted | membershipId + manualActionId + adjustmentReasonCode |
| ReversePointsEntry | PointsLedger | PointsEntryReversed | membershipId + originalLedgerEntryId + reversalReasonCode |
| ScheduleTierEvaluation | TierEvaluationCycle | TierEvaluationScheduled | membershipId + evaluationDate |
| RunTierEvaluation | TierEvaluationCycle | TierEvaluationCompleted 或 TierEvaluationFailed | membershipId + evaluationDate + evaluationRunId |
| EvaluateTier | Membership | MembershipTierEvaluated、MembershipTierChanged | membershipId + evaluationCycleId |
| ApplyManualTierOverride | Membership | ManualTierOverrideApplied、MembershipTierChanged | membershipId + manualActionId + targetTier |
| PublishTierBenefitTable | TierBenefitTable | TierBenefitTablePublished | programId + tierBenefitTableVersion |
| RetireTierBenefitTable | TierBenefitTable | TierBenefitTableRetired | tierBenefitTableId + retireEffectiveAt |

### Event payload rules

- 所有新增事件 payload 使用 camelCase 字段、SCREAMING_SNAKE_CASE 枚举、RFC3339 UTC 时间戳；Money 若出现必须使用 `{currency, minorUnits}`，但积分数量自身使用整数 `points`，不伪装成 Money。
- 新事件 envelope 需遵守 `docs/08-contracts/messaging.md`：`eventId`、`eventType`、`occurredAt`、`producer`、`schemaVersion`、`correlationId`、`causationId`。
- `PointsAccrued` 必须携带 `sourceFactRef`，其中 `eventType=FULFILLMENT_COMPLETED`，并保存 `journeyOrderId`、`travelerId`、`fulfillmentRecordId` 引用；不得保存未脱敏证件。
- `WalletBenefitIssuanceRequested` 是本域内部 outbox/saga 事件，不替代 Wallet 的 `IssueBenefit` 契约。

### Notes: UUID-v7-shaped material folding

Repository 层裁决：命令处理仓库为每个幂等命令按上表材料计算 SHA-256 fold，并生成导线形制 UUID-v7 的 `commandId` / `eventId` 候选；若同一幂等键 replay 且 request fingerprint 相同，返回原结果且不追加新事件；若同一幂等键 fingerprint 不同，拒绝为 `IDEMPOTENCY_KEY_REUSED` / domain conflict。该裁决是仓库幂等实现细节，不要求上游传入真实 UUID-v7，也不改变现有 08-contracts 字段名。

## 8. 策略和 Saga 参与点

- Accrual 策略：只从已契约化的 `FulfillmentCompleted` 生成完成积分；同一 `fulfillmentRecordId` / eventId 只记一次。`completionSource=ADMIN` 仍可记账，但必须保留 source fact 和 correlationId 供审计。
- No-show 策略：`NoShowRecorded` 生成 `MISSED` accrual，作为“明确不发积分”的可查询终态。Missed 不可逆；客服补发必须走 `ApplyPointsAdjustment`，不能把 missed 改成 accrued。
- Journey Order 完成事实策略：当前 08-contracts 未定义 `JourneyCompleted` / `JourneyOrderCompleted`，本域不订阅杜撰事件；Journey Order 只提供 account/order 归属和取消排查输入。
- Tier 策略：升级可在积分到账后即时计算；降级只在月度/季度评估周期边界发生，并可配置 grace period。所有评估使用 UTC rolling 12-month window。
- Expiry 策略：积分 lot 有独立 `expiresAt`。expiry job 只扣当前剩余可用/冻结积分；若 lot 已全部兑换，expiry no-op 并记录跳过指标，不生成负数分录。
- Redemption Saga：`RequestPointsRedemption` 冻结积分 -> 调用 Wallet `IssueBenefit` -> 消费 `BenefitIssued` -> 提交积分扣减并绑定 `walletBenefitId`。Wallet 失败或超时进入 `FAILED` / release path，不盲目重复发行。
- Wallet 分界策略：Wallet 产物发行成功不证明积分来源合法；Loyalty 积分扣减成功也不等于权益可核销。双方以 `redemptionId`、`walletBenefitId`、correlationId 关联，只保存对方引用。
- Adjustment 策略：人工加减积分、等级 override 必须来自 Admin & Audit 的审批/执行事实，且 description 不得包含未脱敏 PII。
- Outbox/Inbox 策略：消费 Fulfillment、Journey Order、Wallet、Admin 事件必须用 Inbox 去重；对 Wallet、Notification、Reporting 的输出使用 Outbox，失败可重试但事件内容不可变。
- Late / out-of-order 策略：若 `FulfillmentCompleted` 晚于 membership closure，到达时生成 `FAILED` 或 `MISSED`（按关闭原因策略），不重开会员；若 Wallet `BenefitIssued` 晚到且 redemption 已 `FAILED`，进入人工异常队列，不自动扣积分。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| Membership Profile View | MembershipEnrolled、MembershipSuspended、MembershipResumed、MembershipClosed、MembershipTierChanged | 用户会员中心、客服、Offer/Marketing eligibility 查询。 |
| Points Balance View | PointsAccrued、PointsReservedForRedemption、PointsReservationReleased、PointsRedemptionCommitted、PointsExpired、PointsAdjusted、PointsEntryReversed | 用户积分余额、兑换校验、客服查询。 |
| Points Lot Expiry Calendar | PointsAccrued、PointsReservedForRedemption、PointsExpired、PointsEntryReversed | 到期提醒、expiry scheduler、客服解释。 |
| Rolling Tier Window View | PointsAccrued、PointsAdjusted、PointsEntryReversed、MembershipTierEvaluated | 等级评估、运营仪表盘。 |
| Redemption Timeline | PointsRedemptionRequested、WalletBenefitIssuanceRequested、PointsRedemptionIssued、PointsRedemptionFailed、PointsRedemptionCancelled | 用户兑换记录、客服、Wallet 异常排查。 |
| Tier Benefit Table View | TierBenefitTablePublished、TierBenefitTableRetired | 会员中心展示、兑换目录、营销配置。 |
| Accrual Exception Queue | PointsAccrualMissed、TierEvaluationFailed、PointsRedemptionFailed、late Wallet issuance detection | 客服、Admin & Audit、运营补偿。 |
| Loyalty Metrics View | MembershipEnrolled、PointsAccrued、PointsExpired、PointsRedemptionCommitted、MembershipTierChanged | Reporting、运营增长分析。 |

读模型可被重建；权威状态仍在聚合和 ledger/outbox 事件中。积分流水展示必须隐藏或脱敏上游旅客证件信息，只展示订单号、行程摘要引用和积分数量。

## 10. 外部系统和防腐层

Loyalty Membership baseline 不接真实第三方会员联盟或积分供应商。所有外部方一律模拟，遵守 ADR-0003 与 Provider Integration 相同的原则：无真实网络、无真实凭证、无生产第三方调用。

### ACL components

1. Fulfillment Event ACL：把 `FulfillmentCompleted` / `NoShowRecorded` payload 映射为 `AccrualSourceFact`，只保留必要引用和时间戳。
2. Journey Order ACL：把 `JourneyOrderCreated` / `JourneyOrderConfirmed` / `JourneyOrderCancelled` 映射为订单归属和排查快照，不创建完成事实。
3. Wallet Issuance ACL：把 `PointsRedemption` 映射为 Wallet `IssueBenefit` 请求；使用 Wallet 既有 `businessReason`、`applicableScope`、`redemptionRule` 和 `validUntil` 形状。
4. Notification ACL：把会员事件映射为 `ScheduleNotification`，模板变量只包含 tier、points、expiry date 和 masked references。
5. Admin & Audit ACL：校验 manualActionId、operator scope、reasonCode，并把审批结果转成本域人工命令。
6. SIM Gateway：用于本地/演示环境生成 tier-benefit catalog、兑换目录可用性和 Wallet issuance mock outcome。

### SIM gateway deterministic behavior

| SIM Adapter | Deterministic Rule | Seed Material | Network Rule |
|---|---|---|---|
| `LOYALTY_CATALOG_SIM` | 根据 tier、catalogVersion 和 seed 返回固定兑换目录与所需积分；同一输入排序稳定。 | `programId:tier:catalogVersion:seed` | 不发起网络调用。 |
| `LOYALTY_WALLET_ISSUANCE_SIM` | 在未接真实 Wallet 服务的 contract-test 环境中，按 redemptionId hash 生成 success / retryable failure / permanent failure；比例由测试配置固定。 | `redemptionId:attemptNo:seed` | 不保存或读取外部凭证。 |
| `LOYALTY_EXPIRY_CLOCK_SIM` | 以注入的 UTC clock 推进 expiry，不读取系统本地时区。 | `expiryRunId:clockInstant:seed` | 无网络。 |

SIM 输出必须可种子化、可重放、可断言；raw request/response 只包含本域 DTO 和脱敏引用。若未来接外部积分联盟，必须新建 provider-style ACL 和契约评审，本 baseline 禁止真实接入。

## 11. 当前服务迁移影响

| Current Service / Area | Migration Impact |
|---|---|
| Account membership stubs | `docs/02-domains/account.md` 中的 Membership 相关命令应迁出为 Loyalty Membership；Account 只保留 account 状态和 membershipRef 展示引用。 |
| Wallet / Promotion points bucket | 现有 Wallet `POINTS` balanceType 不再作为会员积分权威来源；迁移时把历史可兑换积分导入 Loyalty `PointsLedger`，Wallet 只保留已发行权益。 |
| Order completion based rewards scripts | 不能根据订单 `PAID` / `CONFIRMED` 发积分；必须改为消费 Fulfillment `FulfillmentCompleted`。 |
| No-show / refund compensation jobs | No-show 生成 missed accrual；售后补偿若发券走 Wallet，若补积分走 Loyalty adjustment，两者不能混写。 |
| Customer service manual point edits | 迁移为 Admin & Audit 审批后执行 `ApplyPointsAdjustment` 或 `ApplyManualTierOverride`，保留 reasonCode 和 manualActionId。 |
| Notification templates | 新增会员升级、积分到账、积分即将过期、兑换成功/失败模板；仍由 Notification 统一调度发送。 |
| Reporting dashboards | 新增会员规模、tier 分布、积分发放/过期/兑换、兑换失败率指标；Reporting 只消费事件，不直接查写账本。 |
| Data retention / privacy | 历史积分流水只保存 accountId、travelerId、order/fulfillment refs 和脱敏展示字段；不得迁入未脱敏证件号。 |

## 12. 验收标准

- 文档遵守既有领域文档 12 节结构，Metadata `Status` 为 `proposed-ddd-baseline`，High-Level Inputs 引用 ADR-0003。
- Loyalty Membership 聚合所有权明确：`Membership`、`PointsLedger`、`PointsRedemption`、`TierBenefitTable`、`TierEvaluationCycle` 归本域。
- Wallet / Promotion 分界明确：积分账本、积分过期和 tier points 属于 Loyalty；兑换产物发行、冻结、核销和权益过期属于 Wallet。
- 上下游契约表只引用 `docs/08-contracts/` 中真实存在的事件或端点；明确禁止使用未契约化的 `JourneyCompleted` / `JourneyOrderCompleted` 做 accrual。
- Accrual 以 Fulfillment `FulfillmentCompleted` 为权威完成事实，`NoShowRecorded` 可生成不可逆 missed accrual。
- 等级机覆盖 `BASIC -> SILVER -> GOLD -> PLATINUM`，并按 rolling 12-month tier points 评估；升级/降级规则可审计。
- 状态机列出终态可查询安息，`MISSED` / `FAILED` 类不可逆规则写明。
- 命令表包含幂等键材料；Notes 记录 UUID-v7-shaped material folding 的仓库裁决。
- 外部系统和防腐层说明所有外部方模拟、可种子化、确定性、无真实网络，符合 provider-integration 风格。
- 本任务只新增领域设计文档，不新增契约文档、不新增代码、不新增服务目录；`make check` 应保持绿色。
