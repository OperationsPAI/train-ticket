# Marketing Campaign Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Marketing Campaign |
| Status | proposed-ddd-baseline |
| Phase | ADR-0003 wave C |
| Last Updated | 2026-07-10 |
| High-Level Inputs | `docs/adr/0003-commercial-realism-scope.md`, `docs/02-domains/wallet-promotion.md`, `docs/08-contracts/api/wallet-promotion.md`, `docs/08-contracts/events/wallet-promotion.md` |

## 1. 领域目标

Marketing Campaign 负责营销活动的业务规则与编排：活动预算、有效时窗、目标人群规则、券模板、发放批次、幂等防重、核销前活动态校验，以及面向触达和报表的营销事实输出。

本域独立存在的原因是：营销活动有投放预算、客群筛选、灰度、频控、批次重放、券模板版本和核销资格等商业规则。如果把这些规则放进 Wallet / Promotion，会让钱包承担营销策划和预算不变量；如果放进 Notification，会把触达执行与营销决策混在一起；如果放进 Reporting，会让分析域反向成为业务事实源。

关键边界裁定：券的实体和余额/核销账本仍归 Wallet / Promotion。Marketing Campaign 只拥有 `Campaign`、`CouponTemplate`、`IssuanceBatch` 和核销校验规则，不创建 `PromotionInstrument` 实体，不维护钱包余额，不确认券是否已核销成功。发券时本域出站调用 Wallet / Promotion 已存在的 `POST /api/v1/benefits`，但当前真实契约字段名是 `issuanceSource`，且枚举只包含 `MANUAL_OPS`、`POST_SALES_COMP`、`DISRUPTION_COMP`；ADR-0003 期望的 `CAMPAIGN` 来源值需要 Wallet / Promotion 后续契约扩展后才可在线启用，本设计不修改 `docs/08-contracts/`。

## 2. 边界

### In Scope

- `Campaign` 的草稿、审批、排期、启动、暂停、恢复、完成、失败和取消。
- 活动预算、预算冻结、预算消耗、预算释放和预算超限防护；金额使用 `Money { currency, minorUnits }`，不使用浮点数。
- 活动时窗、报名/发放/核销有效期、时区归一和 RFC3339 UTC 时间比较。
- 目标人群规则：账户、旅客、渠道、线路、历史订单、履约事实、资格标签、实验分桶和排除规则。
- `CouponTemplate`：券面额、门槛、适用范围、核销规则、有效期策略和版本快照。
- `IssuanceBatch`：批量发券计划、收件人快照、幂等发放、重试、部分成功和失败安息查询。
- 发券编排：按模板生成 Wallet / Promotion `IssueBenefit` 请求材料，调用 `POST /api/v1/benefits`，记录出站请求与结果。
- 核销前校验：Wallet / Promotion 在核销时回查本域，确认活动、模板、预算和规则仍允许该券被使用。
- 面向 Notification 的发券触达意图，以及面向 Reporting 的活动曝光、发放、失败、核销校验和预算消耗事件。

### Out of Scope

- 券、余额、积分、补偿权益、冻结、核销、撤销、过期和账本实体，归 Wallet / Promotion。
- 钱包组合支付、现金剩余应付和支付渠道状态，归 Wallet / Promotion 与 Payment。
- 用户联系方式、通知偏好和触达渠道执行，归 Account 与 Notification。
- 报表指标定义、DataMart、看板查询和历史重算，归 Reporting。
- 订单、票证、履约、售后、异常恢复和会员积分事实，归对应业务上下文。
- 真实广告平台、短信平台、外部 DMP、第三方营销 SaaS 或真实网络调用；本域只允许确定性 SIM 防腐层。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| `Campaign` | 一个营销活动的聚合根，承载目标、预算、时窗、目标人群、券模板集合和发布状态。 | 不等于券实体；券实体在 Wallet / Promotion。 |
| `CampaignWindow` | 活动可发放、可展示和可核销的时间边界。 | 所有时间以 RFC3339 UTC 存储和比较。 |
| `CampaignBudget` | 活动预算上限、已预留、已消耗、已释放和失败补偿的金额视图。 | 使用 `Money` 的 `minorUnits`，禁止跨币种扣减。 |
| `TargetingRule` | 目标人群规则，基于已授权账户/旅客/订单/履约/渠道事实做匹配。 | 规则结果必须可解释和可重放。 |
| `CouponTemplate` | 发放到 Wallet / Promotion 的券模板规则快照。 | 包含面额、门槛、适用范围、有效期、核销校验策略和版本。 |
| `IssuanceBatch` | 一次批量发放计划和执行记录。 | 同一批次对同一接收方与模板必须幂等。 |
| `IssuanceItem` | 批次中的单个目标接收方发券意图。 | 记录 Wallet benefit 请求指纹和结果引用。 |
| `CampaignBenefitRequest` | 本域映射到 Wallet / Promotion `POST /api/v1/benefits` 的出站请求材料。 | 当前真实字段为 `issuanceSource`；`CAMPAIGN` 值待 Wallet 契约扩展。 |
| `RedemptionValidation` | Wallet / Promotion 核销前向本域确认活动规则仍有效的校验结果。 | 本域只返回 allow/deny 与原因，不扣减钱包。 |
| `AudienceSnapshot` | 目标人群规则在某个版本和时间点的确定性匹配结果。 | 保存规则版本、输入水印和哈希，不保存不必要的敏感明文。 |
| `CampaignHoldout` | 活动实验保留组或灰度分桶。 | 分桶必须由稳定种子确定，便于审计和复算。 |
| `IssuanceIdempotencyMaterial` | 生成发放幂等键的材料集合。 | 仓库裁决：最终导线形制采用 UUID-v7；材料折叠见第 7 节 Notes。 |

## 4. 上下游契约

本表只列已存在于 `docs/08-contracts/` 的上下游契约或端点名。Marketing Campaign 新命令和新领域事件只在第 7 节出现；本任务不新增或修改契约文档。

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Account | `PreferenceUpdated`; `GET /api/v1/accounts/{accountId}` | 营销触达偏好、账户可用性和账户状态门禁。真实 Account 契约没有单独的 consent 事件，本域不能杜撰 `ConsentRecorded`。 |
| Traveler Profile | `TravelerSnapshotUpdated`, `EligibilityDetermined`, `EligibilityExpired`; `GET /api/v1/travelers/{travelerId}` | 目标人群可使用旅客快照和资格结果，但只引用 `travelerId`、资格引用和掩码摘要。 |
| Offer Management | `OfferQuoted`, `OfferExpired`; `POST /api/v1/offers`, `GET /api/v1/offers/{offerId}` | 可按报价、渠道、线路和 Offer 生命周期做活动归因或排除。 |
| Journey Order | `JourneyOrderCreated`, `JourneyOrderPendingPayment`, `JourneyOrderPaymentRecorded`, `JourneyOrderConfirmed`, `JourneyOrderCancelled`, `JourneyOrderPostSalesAdjusted`; `GET /api/v1/journey-orders/{orderId}` | 目标人群、预算归因和核销校验可引用订单状态与金额摘要。 |
| Fulfillment | `BoardingVerified`, `NoShowRecorded`, `FulfillmentCompleted`, `SegmentDelayed`, `SegmentArrived`, `SegmentCancelled` | 履约事实用于复购、召回、No-show 排除和活动效果归因。 |
| Wallet / Promotion | `BenefitIssued`, `BenefitReserved`, `BenefitRedeemed`, `BenefitReservationReleased`, `BenefitExpired`, `BenefitRevoked`, `BenefitRedemptionReversed`; `GET /api/v1/benefits/{benefitId}` | 回收发券结果、核销/过期/撤销事实并更新活动预算和效果视图；券实体仍在 Wallet / Promotion。 |

### Downstream

| Downstream Context | Published or Called Existing Contract | Reason |
|---|---|---|
| Wallet / Promotion | `POST /api/v1/benefits`; accepted command `IssueBenefit`; event `BenefitIssued` | 发放营销券时调用真实现有端点。当前请求字段是 `issuanceSource`，当前枚举未包含 `CAMPAIGN`；上线前必须先由 Wallet 契约扩展，不能在本设计中伪造。 |
| Notification | bus-only command `ScheduleNotification`; events `NotificationScheduled`, `NotificationDispatched`, `NotificationDelivered`, `NotificationFailed`, `NotificationCancelled` | 发券到账、活动提醒和失败补发触达由 Notification 执行，本域只给出触达意图和模板变量。 |
| Reporting | `GET /api/v1/metrics`, `GET /api/v1/metrics/{metricId}`, `GET /api/v1/dashboards/{dashboardId}`; events `MetricDefined`, `MetricVersionPublished`, `ReadModelRebuilt` | Reporting 作为活动效果分析消费方和指标展示方；营销事实由第 7 节本域事件进入 EventStream，Reporting 不回写活动聚合。 |

### Wallet / Promotion 契约核实 Notes

- 真实发券端点是 `POST /api/v1/benefits`，不是 `/benefits` 裸路径；本域适配层可在内部配置 base path，但文档引用必须保留真实路径。
- 请求字段为 `accountId`、`benefitType`、`balanceType`、`amount`、`issuanceSource`、`caseId`、`applicableScope`、`redemptionRule`、`revocationRule`、`validFrom`、`validUntil`、`businessReason`。
- Money 必须是 `{currency, minorUnits}`，`amount.minorUnits` 必须为正整数。
- 当前 `issuanceSource` 枚举无 `CAMPAIGN`。ADR-0003 的 `issueSource=CAMPAIGN` 在本设计中解释为目标业务语义；真实契约上线前必须用 Wallet / Promotion 契约变更补齐，不能以 `MANUAL_OPS` 冒充营销来源。

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `Campaign` | `campaignId` 唯一；发布前必须有预算、时窗、至少一个已发布 `CouponTemplate`、目标规则版本和审批引用；活动时窗不能倒置；活动终态后只能查询安息，不能再发放或重新打开。 | `DraftCampaign`, `SubmitCampaignForReview`, `ApproveCampaign`, `ScheduleCampaign`, `StartCampaign`, `PauseCampaign`, `ResumeCampaign`, `CancelCampaign`, `CompleteCampaign`, `FailCampaign` | `CampaignDrafted`, `CampaignSubmittedForReview`, `CampaignApproved`, `CampaignScheduled`, `CampaignStarted`, `CampaignPaused`, `CampaignResumed`, `CampaignCancelled`, `CampaignCompleted`, `CampaignFailed` |
| `CampaignBudget` | 同一 `campaignId` + `currency` 只有一个预算账本；`reservedAmount + consumedAmount <= totalBudget`；释放和失败补偿不得让余额为负；预算终态可查询不可改写。 | `SetCampaignBudget`, `ReserveCampaignBudget`, `ConsumeCampaignBudget`, `ReleaseCampaignBudget`, `CloseCampaignBudget` | `CampaignBudgetSet`, `CampaignBudgetReserved`, `CampaignBudgetConsumed`, `CampaignBudgetReleased`, `CampaignBudgetClosed` |
| `CouponTemplate` | 同一 `campaignId` + `templateCode` + `version` 唯一；Published 版本不可原地修改；模板有效期必须落在活动允许区间内；核销规则必须可被 Wallet / Promotion 表达或由本域回查表达。 | `DraftCouponTemplate`, `ValidateCouponTemplate`, `PublishCouponTemplate`, `RetireCouponTemplate` | `CouponTemplateDrafted`, `CouponTemplateValidated`, `CouponTemplatePublished`, `CouponTemplateRetired` |
| `TargetingRuleSet` | 规则必须有版本、输入来源、排除条件和解释文本；同一输入水印可重放得到同一 `AudienceSnapshot`；规则不得保存未授权敏感明文。 | `DefineTargetingRuleSet`, `EvaluateAudience`, `FreezeAudienceSnapshot`, `RetireTargetingRuleSet` | `TargetingRuleSetDefined`, `AudienceEvaluated`, `AudienceSnapshotFrozen`, `TargetingRuleSetRetired` |
| `IssuanceBatch` | 同一批次内 `(campaignId, templateId, accountId, audienceSnapshotId)` 最多一个 active `IssuanceItem`；重放同一幂等材料必须返回原结果；批次终态后不可追加 item；部分失败必须可查询安息。 | `PlanIssuanceBatch`, `StartIssuanceBatch`, `IssueCampaignCoupon`, `RecordWalletIssuanceAccepted`, `RecordIssuanceItemFailed`, `RetryIssuanceItem`, `CloseIssuanceBatch` | `IssuanceBatchPlanned`, `IssuanceBatchStarted`, `CampaignCouponIssueRequested`, `WalletIssuanceAccepted`, `IssuanceItemFailed`, `IssuanceItemRetryScheduled`, `IssuanceBatchClosed` |
| `RedemptionValidationPolicy` | 每次校验必须绑定 `campaignId`、`templateId`、`benefitId`、请求时间和规则版本；同一 wallet 回查请求幂等；拒绝结果不可自动转允许，除非新请求带更高规则版本并有审计。 | `ValidateCampaignRedemption`, `RecordRedemptionValidationOutcome`, `RetireRedemptionValidationPolicy` | `CampaignRedemptionValidated`, `CampaignRedemptionDenied`, `RedemptionValidationPolicyRetired` |

## 6. 状态机

### `Campaign` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `DRAFT` | 活动正在设计。 | `IN_REVIEW`, `CANCELLED` |
| `IN_REVIEW` | 活动等待审批。 | `APPROVED`, `REJECTED`, `DRAFT`, `CANCELLED` |
| `REJECTED` | 审批拒绝，需要重新设计或放弃。 | `DRAFT`, `CANCELLED` |
| `APPROVED` | 活动已批准但未排期。 | `SCHEDULED`, `CANCELLED` |
| `SCHEDULED` | 活动已排期，等待 `validFrom` 或人工启动。 | `ACTIVE`, `CANCELLED`, `FAILED` |
| `ACTIVE` | 活动可发放、可校验。 | `PAUSED`, `COMPLETING`, `FAILED`, `CANCELLED` |
| `PAUSED` | 活动临停，不能新发券；已发券核销按策略可允许或拒绝。 | `ACTIVE`, `COMPLETING`, `FAILED`, `CANCELLED` |
| `COMPLETING` | 到达结束时窗或预算耗尽，正在关闭批次和预算。 | `COMPLETED`, `FAILED` |
| `COMPLETED` | 正常完成。 | 终态 |
| `FAILED` | 系统或规则错误导致不可继续。 | 终态 |
| `CANCELLED` | 运营或审批取消。 | 终态 |

`COMPLETED`、`FAILED`、`CANCELLED` 是结局态，可查询安息，不能恢复。`FAILED` 不可逆：不得通过直接改状态重启活动，只能复制为新 campaign 并重新审批。错过发放窗口的批次进入 `MISSED` 或 `PARTIALLY_FAILED` 后也不可逆推进为成功，最多创建新批次。

### `CouponTemplate` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `DRAFT` | 模板草案。 | `VALIDATED`, `RETIRED` |
| `VALIDATED` | 规则已通过静态校验。 | `PUBLISHED`, `DRAFT`, `RETIRED` |
| `PUBLISHED` | 可被活动发放引用。 | `RETIRED`, `SUPERSEDED` |
| `SUPERSEDED` | 已被新版本替代，历史券继续按原快照解释。 | `RETIRED` |
| `RETIRED` | 不再新发放。 | 终态 |

Published 模板不可原地修改；任何面额、门槛、适用范围或核销规则变更都必须发布新版本。

### `IssuanceBatch` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `PLANNED` | 批次已规划，尚未执行。 | `RUNNING`, `CANCELLED`, `MISSED` |
| `RUNNING` | 正在评估目标和调用 Wallet。 | `PARTIALLY_SUCCEEDED`, `SUCCEEDED`, `PARTIALLY_FAILED`, `FAILED`, `PAUSED` |
| `PAUSED` | 因预算、风控或下游不可用暂停。 | `RUNNING`, `CANCELLED`, `MISSED` |
| `PARTIALLY_SUCCEEDED` | 部分 item 成功，仍有可重试或待确认。 | `RUNNING`, `PARTIALLY_FAILED`, `SUCCEEDED`, `CLOSED` |
| `SUCCEEDED` | 全部 item 已成功或幂等确认。 | `CLOSED` |
| `PARTIALLY_FAILED` | 部分 item 失败且不可再自动推进。 | `CLOSED` |
| `FAILED` | 批次整体失败。 | `CLOSED` |
| `MISSED` | 批次未在活动/发放窗口内启动或完成。 | `CLOSED` |
| `CANCELLED` | 人工或活动取消导致批次取消。 | `CLOSED` |
| `CLOSED` | 批次安息。 | 终态 |

`MISSED`、`FAILED`、`PARTIALLY_FAILED` 的失败事实不可反写为成功；若 Wallet 后续返回成功，只能记录为延迟结果并触发补偿/人工 Case，不能改变已安息的批次结论。

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `DraftCampaign` | `Campaign` | `CampaignDrafted` | campaign external key + draft version |
| `ApproveCampaign` | `Campaign` | `CampaignApproved` | campaignId + approverRef + reviewVersion |
| `ScheduleCampaign` | `Campaign` | `CampaignScheduled` | campaignId + scheduleVersion |
| `StartCampaign` | `Campaign` | `CampaignStarted` | campaignId + startWindow bucket |
| `PauseCampaign` | `Campaign` | `CampaignPaused` | campaignId + pauseReason + operatorRef |
| `ResumeCampaign` | `Campaign` | `CampaignResumed` | campaignId + resumeReason + operatorRef |
| `CancelCampaign` | `Campaign` | `CampaignCancelled` | campaignId + cancellationReason + operatorRef |
| `CompleteCampaign` | `Campaign` | `CampaignCompleted` | campaignId + completionReason + closeWindow |
| `FailCampaign` | `Campaign` | `CampaignFailed` | campaignId + failureCode + detectedAt bucket |
| `SetCampaignBudget` | `CampaignBudget` | `CampaignBudgetSet` | campaignId + currency + budgetVersion |
| `ReserveCampaignBudget` | `CampaignBudget` | `CampaignBudgetReserved` | campaignId + issuanceBatchId + templateId + amount fingerprint |
| `ConsumeCampaignBudget` | `CampaignBudget` | `CampaignBudgetConsumed` | campaignId + benefitId + wallet event id |
| `ReleaseCampaignBudget` | `CampaignBudget` | `CampaignBudgetReleased` | campaignId + releaseReason + source item id |
| `DraftCouponTemplate` | `CouponTemplate` | `CouponTemplateDrafted` | campaignId + templateCode + draftVersion |
| `PublishCouponTemplate` | `CouponTemplate` | `CouponTemplatePublished` | templateId + semanticVersion + approvalRef |
| `DefineTargetingRuleSet` | `TargetingRuleSet` | `TargetingRuleSetDefined` | campaignId + ruleSetVersion |
| `EvaluateAudience` | `TargetingRuleSet` | `AudienceEvaluated` | ruleSetId + inputWatermark + evaluationSeed |
| `FreezeAudienceSnapshot` | `TargetingRuleSet` | `AudienceSnapshotFrozen` | ruleSetId + audienceHash + frozenAt bucket |
| `PlanIssuanceBatch` | `IssuanceBatch` | `IssuanceBatchPlanned` | campaignId + templateId + audienceSnapshotId + planVersion |
| `StartIssuanceBatch` | `IssuanceBatch` | `IssuanceBatchStarted` | issuanceBatchId + startAttemptNo |
| `IssueCampaignCoupon` | `IssuanceBatch` | `CampaignCouponIssueRequested` | UUID-v7 from folded issuance material; see Notes |
| `RecordWalletIssuanceAccepted` | `IssuanceBatch` | `WalletIssuanceAccepted` | issuanceItemId + wallet benefitId + wallet aggregateVersion |
| `RecordIssuanceItemFailed` | `IssuanceBatch` | `IssuanceItemFailed` | issuanceItemId + failureCode + failureClassifierVersion |
| `RetryIssuanceItem` | `IssuanceBatch` | `IssuanceItemRetryScheduled` | issuanceItemId + retryAttemptNo |
| `CloseIssuanceBatch` | `IssuanceBatch` | `IssuanceBatchClosed` | issuanceBatchId + closeReason + finalStatus |
| `ValidateCampaignRedemption` | `RedemptionValidationPolicy` | `CampaignRedemptionValidated` or `CampaignRedemptionDenied` | wallet request id + benefitId + campaignId + templateVersion |
| `RecordRedemptionValidationOutcome` | `RedemptionValidationPolicy` | `CampaignRedemptionValidated` or `CampaignRedemptionDenied` | validationId + ruleVersion + outcome |

Notes:

- `IssueCampaignCoupon` 的幂等材料折叠为：`campaignId | templateId | templateVersion | issuanceBatchId | audienceSnapshotId | accountId | amount.currency | amount.minorUnits | validFrom | validUntil | applicableScope hash | redemptionRule hash`。仓库裁决记入本文：导线形制使用 UUID-v7，UUID 的随机/序列部分由上述材料的规范化哈希折叠生成或在仓储层绑定唯一索引；同一材料重放返回同一 `issuanceItemId` 和 Wallet 结果。
- 出站调用 Wallet / Promotion 时还必须发送 HTTP `Idempotency-Key`。该 header 使用 `IssueCampaignCoupon` 的幂等键，不使用用户输入文案。
- 本域事件通过 Outbox 发布，事件载荷必须使用 camelCase、SCREAMING_SNAKE_CASE enum、RFC3339 UTC 和 Money `minorUnits`；不得包含未脱敏证件、联系方式或完整旅客姓名。
- `ValidateCampaignRedemption` 是本域未来接受 Wallet 回查的领域命令，放在本域表内；本任务不新增 Wallet 契约文档。

## 8. 策略和 Saga 参与点

- 发券 Saga：`IssuanceBatch` 先冻结或预留活动预算，再按 `IssuanceItem` 调用 Wallet / Promotion `POST /api/v1/benefits`。Wallet 返回成功后记录 `benefitId` 并消耗预算；失败或幂等冲突按错误分类释放预算、重试或关闭 item。
- Wallet 契约兼容策略：当前 Wallet `issuanceSource` 无 `CAMPAIGN`。在契约扩展前，Campaign 发券 Saga 必须保持 `BLOCKED_BY_CONTRACT` 或使用离线模拟，不得把营销券伪装成 `MANUAL_OPS`、`POST_SALES_COMP` 或 `DISRUPTION_COMP`。
- 预算策略：预算以 `CampaignBudget` 为唯一规则源；并发 item 通过预算预留防超发。Wallet 已发券但本域未收到确认时进入待调和队列，不重复扣减。
- 目标人群策略：规则评估基于上游事件/读模型快照和输入水印；同一 seed、规则版本和输入水印必须产出同一 `AudienceSnapshot`。排除冻结账户、关闭账户、已退订营销偏好、近期投诉和 holdout 分桶。
- 核销校验策略：Wallet / Promotion 在核销前携带 `benefitId`、`accountId`、`campaignId`、`templateId`、订单/范围引用和请求时间回查本域。本域确认活动未终止、模板版本未退休到禁止核销、核销时窗有效、预算和目标规则允许，然后返回允许或拒绝原因；实际扣减仍由 Wallet 完成。
- 活动暂停策略：暂停后停止新发券。已发券是否可核销由模板的 `pausedRedemptionPolicy` 决定：`ALLOW_EXISTING`、`DENY_NEW_REDEMPTION` 或 `REQUIRE_MANUAL_REVIEW`。
- 失败不可逆策略：`MISSED`、`FAILED`、`PARTIALLY_FAILED` 批次不因重试晚到而变成功；晚到 Wallet 成功事实只能产生补偿、预算调和或人工审计输入。
- 通知策略：本域只发出营销触达意图，Notification 使用 bus-only `ScheduleNotification` 执行渠道、偏好、频控和回执；Notification 失败不回滚 Wallet 发券。
- Reporting 策略：活动曝光、批次、发放、失败、核销校验、预算消耗和 holdout 结果进入 Reporting；Reporting 只做分析，不修改 Campaign 或 Wallet。
- Outbox/Inbox 策略：消费上游事件和 Wallet 结果使用 Inbox 幂等，发布本域事件使用 Outbox；所有处理记录保留 `correlationId`、`causationId` 和幂等键。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `CampaignCatalogView` | `CampaignDrafted`, `CampaignApproved`, `CampaignScheduled`, `CampaignStarted`, `CampaignPaused`, `CampaignCompleted`, `CampaignFailed`, `CampaignCancelled` | 运营后台、客服只读解释、Admin & Audit。 |
| `CampaignBudgetView` | `CampaignBudgetSet`, `CampaignBudgetReserved`, `CampaignBudgetConsumed`, `CampaignBudgetReleased`, Wallet `BenefitIssued` / `BenefitRevoked` / `BenefitExpired` | 运营、财务分析、预算告警。 |
| `CouponTemplateCatalogView` | `CouponTemplateDrafted`, `CouponTemplatePublished`, `CouponTemplateRetired` | 发券编排、客服解释、运营配置。 |
| `AudienceSnapshotView` | `TargetingRuleSetDefined`, `AudienceEvaluated`, `AudienceSnapshotFrozen`, Account `PreferenceUpdated`, Traveler Profile eligibility events | 运营复盘、审计、发放批次。 |
| `IssuanceBatchProgressView` | `IssuanceBatchPlanned`, `IssuanceBatchStarted`, `CampaignCouponIssueRequested`, `WalletIssuanceAccepted`, `IssuanceItemFailed`, `IssuanceBatchClosed` | 运营、SRE、客服问题定位。 |
| `CampaignBenefitLinkView` | `WalletIssuanceAccepted`, Wallet `BenefitIssued`, Wallet `BenefitRedeemed`, Wallet `BenefitExpired`, Wallet `BenefitRevoked` | 核销校验、客服、Reporting。 |
| `RedemptionValidationDecisionView` | `CampaignRedemptionValidated`, `CampaignRedemptionDenied`, Wallet `BenefitRedeemed`, Wallet `BenefitRedemptionReversed` | Wallet 回查审计、客服争议、Reporting。 |
| `CampaignEffectivenessProjection` | 本域活动事件、Wallet 事件、Journey Order 事件、Fulfillment 事件、Notification delivery events | Reporting、增长运营、实验分析。 |
| `CampaignExceptionQueue` | `IssuanceItemFailed`, `CampaignFailed`, Wallet 幂等冲突、契约阻断结果 | 运营、Admin & Audit、SRE。 |

读模型可以保存账户/旅客引用、掩码摘要、规则版本、金额汇总、模板版本和 Wallet `benefitId`；不得保存未脱敏证件号、完整联系方式或不必要的 PII。读模型不是 Wallet 核销事实源，也不是 Reporting 指标口径源。

## 10. 外部系统和防腐层

Marketing Campaign 不接入真实外部营销平台、DMP、广告网络、短信网关或第三方 SaaS。所有外部方一律模拟，遵循 Provider Integration 的 ACL 思路：隔离外部语言、保留原始交互摘要、确定性错误映射，但绝不发起真实网络调用。

| Boundary | ACL / SIM Design | Deterministic Behavior |
|---|---|---|
| `AudienceImportSimGateway` | 模拟外部运营名单、渠道包或实验名单导入。 | 输入 `campaignId`、`seed`、`rowCount`、`schemaVersion` 后生成稳定账户引用集合；同一输入哈希输出相同，支持固定比例坏行和重复行。 |
| `AdAttributionSimGateway` | 模拟广告曝光、点击和渠道归因回传。 | 基于 `seed + accountId + campaignId + window` 生成曝光/点击事实；无真实 HTTP、SDK 或 cookie 同步。 |
| `CouponPreviewSimGateway` | 模拟运营后台预览 Wallet 发券请求和模板变量。 | 只做本地 DTO 映射和规则校验；不调用 Wallet，返回稳定预览 ID 与错误列表。 |
| `NotificationIntentAcl` | 将活动触达意图映射成 Notification `ScheduleNotification` 命令材料。 | 只使用 Notification 已有 bus-only 命令形状；渠道选择和 provider 发送不在本域。 |
| `WalletPromotionAcl` | 将 `CouponTemplate` 和 `IssuanceItem` 映射到 Wallet / Promotion `POST /api/v1/benefits`。 | 在线模式只允许真实契约支持的字段和值；当 `CAMPAIGN` 来源未被 Wallet 契约支持时返回确定性 `BLOCKED_BY_CONTRACT`，不降级为真实网络伪调用。 |
| `LegacyMarketingAcl` | 读取旧营销表、CSV 或脚本输出。 | 通过本地文件/fixture 或内存数据模拟迁移输入，按 schema version 和 hash 去重；禁止连接真实生产库。 |

防腐红线：外部广告点击不是订单事实；外部名单不是 Account 真相；Notification 送达不是发券成功；Wallet `BenefitIssued` 才是券实体创建事实；Reporting 指标不是活动状态。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| 旧运营后台活动配置表 | 迁移为 `Campaign`、`CampaignBudget`、`CouponTemplate` 和审批引用；删除直接改券表或直接改钱包余额的能力。 |
| 旧批量发券脚本 / CSV job | 迁移为 `IssuanceBatch` 与 `IssuanceItem`；每个 item 必须有幂等键、预算预留、Wallet 请求指纹和结果记录。 |
| 旧优惠券表 | 不迁入本域成为券实体；券实体统一由 Wallet / Promotion `PromotionInstrument` 承载，本域只保存 `benefitId` 链接和模板快照。 |
| 旧短信/Push 营销发送逻辑 | 迁移为 Notification `ScheduleNotification` 触达意图；模板、渠道、频控、回执由 Notification 管。 |
| 旧报表 SQL / Excel 活动复盘 | 迁移为 Reporting 消费本域事件、Wallet 事件、Notification 事件和订单/履约事件；本域不提供手工修数口径。 |
| 旧广告渠道回传或 DMP 对接 | 改为 SIM ACL fixture，不做真实网络接入；将来若接真实外部系统，必须进入单独防腐和安全评审。 |
| 订单下单链路中的促销校验 | 改为 Wallet 核销前回查本域的 `ValidateCampaignRedemption` 语义；订单域不直接解释活动规则。 |
| 客服查询优惠券来源 | 客服读取 `CampaignBenefitLinkView`、Wallet benefit 视图和 Notification timeline；客服不得直接修改 Campaign 或 Wallet 状态。 |
| Admin & Audit | 活动审批、预算变更、人工取消、批次重试和规则版本发布必须写审计引用；高风险批量发券需要双人审批。 |

迁移顺序建议：先落 Campaign/Template/Budget 设计与只读后台；再接入 SIM audience 和批次 dry-run；随后等待 Wallet `issuanceSource=CAMPAIGN` 契约扩展后启用真实 `POST /api/v1/benefits`；最后接 Notification 和 Reporting 消费。

## 12. 验收标准

- [x] Marketing Campaign 聚合所有权明确：`Campaign`、`CampaignBudget`、`CouponTemplate`、`TargetingRuleSet`、`IssuanceBatch`、`RedemptionValidationPolicy` 属于本域。
- [x] 券实体、钱包余额、冻结、核销、撤销、过期和账本明确归 Wallet / Promotion；本域只做规则与编排。
- [x] 已核实 Wallet / Promotion 真实发券端点为 `POST /api/v1/benefits`，真实字段为 `issuanceSource`，当前真实枚举无 `CAMPAIGN`；本文不伪造契约。
- [x] 上下游契约表只引用已存在的 `docs/08-contracts/` 事件、命令或端点；Marketing Campaign 新命令和事件只出现在第 7 节。
- [x] 活动预算、时窗、目标人群、券模板、发放批次和核销校验规则均有不变量和状态边界。
- [x] `MISSED`、`FAILED`、`PARTIALLY_FAILED`、`COMPLETED`、`CANCELLED` 等结局态可查询安息，失败类不可逆规则已写明。
- [x] 命令幂等键给出材料折叠；`IssueCampaignCoupon` 的 UUID-v7 导线形制仓库裁决已写入 Notes。
- [x] 外部方一律通过确定性、可种子化、无真实网络的 SIM ACL；对照 Provider Integration 的防腐层写法，且不提交任何凭据。
- [x] Notification 和 Reporting 均作为下游消费/执行方，触达失败不回滚发券，报表不回写业务聚合。
- [x] 文档使用既有 12 节领域文档结构；本任务只新增设计文档，不新增契约文档、不写代码、不新增服务目录。
