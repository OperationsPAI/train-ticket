# Disruption Recovery Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Disruption Recovery |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-disruption-recovery |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/general-travel-ddd.md`, `docs/02-domains/booking-orchestration.md`, `docs/02-domains/post-sales.md`, `docs/02-domains/transfer-management.md` |

## 1. 领域目标

Disruption Recovery 负责识别、归并和处理出行异常，并把“发生了什么、影响了谁、平台承诺什么、用户可选择什么、后续由谁执行”转化为可审计的恢复决策。它覆盖晚点、取消、停运、天气或管制、供应商失败、网约车司机取消、换乘错过、批量事件以及跨交通方式联乘保护。

本 domain 的核心业务事实是 `RecoveryCase`：一个单客、单订单、单 Segment、联乘组或批量事件在异常下的影响范围、责任判断、恢复方案、用户选择、执行进度和人工兜底。它不直接卖票、出票、退款或改写订单，而是编排恢复 Saga，并向 Booking Orchestration、Post Sales、Payment、Notification、Customer Service 等上下文发布恢复决策或受控命令。

它独立存在的原因：

1. 异常恢复不是普通售后。晚点、停运、司机取消、换乘错过可能改变 waiver、赔付、优先改乘和平台责任。
2. `Service Plan` 只拥有计划运行数据和运营日历，不拥有某个用户受影响后的恢复决策。
3. `Post Sales` 执行退改规则和售后 Case，但异常责任、Protection、waiver policy 和批量恢复策略由 Disruption Recovery 决定。
4. `Booking Orchestration` 执行 replacement SegmentBooking，不判断哪种 Reaccommodation 对用户和平台最合适。
5. `Payment` 执行资金退款或赔付，不决定退款资格、赔付原因或补偿上限。
6. General Travel 的核心差异是端到端出行承诺，异常恢复必须跨火车、飞机、大巴、轮船、网约车和联乘统一建模。

## 2. 边界

### In Scope

- 接收并归一化 `Disruption` 信号：晚点、取消、停运、停航、封路、天气、管制、供应商接口失败、司机取消、派单失败、站点变更、靠港变化、批量系统事件。
- 创建和维护 `Incident`、`ServiceAlert`、`RecoveryCase`、`RecoveryOptionSet`、`WaiverPolicySnapshot`、`CompensationDecision` 等聚合或值对象。
- 识别受影响范围：单 Segment、单旅客、整单 Journey、联乘组、Connection Contract 覆盖范围、批量订单、特定车次/航班/班线/司机派单。
- 生成恢复方案：等待原服务、免费改乘、保护性 Reaccommodation、自费重订、全额或部分退款、费用减免、赔付、人工处理。
- 管理用户选择：自动恢复、用户确认、用户拒绝、超时默认策略、客服代选、批量默认处理。
- 编排 Recovery Saga：请求 Booking 创建 replacement booking，请求 Post Sales 执行退改，请求 Payment 执行退款或赔付，请求 Notification 通知用户。
- 处理 Protected Connection、Supplier Protected、Platform Assisted、Self Transfer 在异常下的责任和费用差异。
- 为客服、运营和用户提供异常恢复读模型、批量进度、影响范围、责任解释和人工队列。

### Out of Scope

- 不拥有 `Service Plan` 的计划变更源数据、车次/航班/船班运行日历、停售限售配置；这些属于 Service Plan。
- 不拥有 `Transfer Management` 的最短换乘时间、Connection Contract 创建、换乘风险评估和 `ConnectionMissed` 事实。
- 不拥有 `Post Sales` 的售后执行规则、退改 Case 状态机、票证作废、库存释放和差价编排。
- 不拥有 `Payment` 的退款渠道、赔付打款、支付回调、对账或资金流水。
- 不拥有 `Booking Orchestration` 的初始预订或 replacement booking 执行状态机。
- 不拥有 `Entitlement & Ticketing` 的票证生命周期、凭证签发、作废和核验。
- 不拥有供应商原始 API、错误码、接口重试和报文解析；这些通过 Provider Integration ACL 进入平台语言。
- 不直接发送通知；Notification 负责模板、渠道、发送、重试和送达状态。
- 不直接修改 JourneyOrder 商业状态或读模型；只发布恢复事实和执行结果。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| Disruption | 影响计划履约或连接可达性的异常事实。 | 可由供应商、履约、Service Plan、Transfer 或运营人工触发。 |
| Incident | 一组同源 Disruption 的运营事件。 | 例如台风导致某港口全天停航，或某车次停运。 |
| ServiceAlert | 面向订单、用户、客服或运营发布的异常提示。 | 不等于通知发送记录；发送归 Notification。 |
| RecoveryCase | 对一个受影响对象的恢复处理聚合根。 | 粒度可为单客、单 Segment、联乘组或批量子 Case。 |
| RecoveryOptionSet | 针对 RecoveryCase 生成的一组可选恢复方案。 | 包含 Reaccommodation、退款、等待、人工等。 |
| Reaccommodation | 因异常为用户重新安排可接受的替代出行。 | 可能免费、补差价、自费或平台补贴。 |
| Protection | Connection Contract 或平台承诺下的保护范围。 | 决定是否免手续费、优先改乘、住宿或赔付。 |
| Waiver | 因异常免除普通退改手续费、罚金、差价或服务费的政策。 | 需要冻结为 waiver policy snapshot。 |
| Compensation | 超出普通退款的补偿决策。 | 可为现金、券、积分、费用减免、人工赔付或重订补贴。 |
| Recovery Decision | 对某个 Case 最终选择的恢复动作。 | 后续由 Booking、Post Sales、Payment 等执行。 |
| Batch Recovery | 一个 Incident 下批量生成、分组和推进 RecoveryCase。 | 批量策略不能覆盖单客特殊约束。 |
| Manual Recovery | 自动方案无法安全执行时的人工兜底。 | 必须有原因、权限、证据和审计。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Service Plan | `ServiceDisrupted`、`ScheduleChanged`、`ServiceCancelled`、`OperationRestrictionPublished`、affected service refs | 固定班次计划异常的权威输入，但恢复决策不回写计划。 |
| Provider Integration | `ProviderDisruptionReported`、`ProviderCancellationReported`、`ProviderFailureDetected`、provider evidence | 供应商异常、接口失败、司机取消、航司/铁路/船司状态映射。 |
| Fulfillment | `SegmentDelayed`、`SegmentDeparted`、`SegmentArrived`、`SegmentCompleted`、actual time facts | 判断晚点、错过接续、已履约或部分履约后的恢复范围。 |
| Transfer Management | `TransferAtRisk`、`ConnectionMissed`、Connection Contract、protection scope | 保障联乘和换乘错过是恢复责任判断的重要输入。 |
| Journey Order | order snapshot、traveler refs、order item refs、user promise summary | 识别受影响用户和商业承诺，不直接修改订单。 |
| Booking Orchestration | SegmentBooking summary、replacement booking result、booking failure events | 了解原预订和替代预订执行结果。 |
| Post Sales | post-sales progress、`PostSalesAppliedForDisruption`、execution failure events | 跟踪退改或退款执行是否完成。 |
| Entitlement & Ticketing | entitlement status summary、void/suspend/issue events | 判断是否可改乘、退票、冻结或需人工。 |
| Customer Service / Admin & Audit | manual disruption report、manual override、operator evidence | 人工录入 Incident、强制恢复或特殊赔付需要审计。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Journey Order | `JourneyAffectedByDisruption`、`RecoveryDecisionApplied`、recovery progress summary | 订单详情展示异常和恢复状态。 |
| Booking Orchestration | `RequestReaccommodationBooking`、`CancelOriginalAfterRecovery`、`RecoveryOptionAccepted` | 创建保护性 replacement SegmentBooking 或取消原段。 |
| Post Sales | `RecoveryRefundApproved`、`RecoveryChangeApproved`、waiver policy、disruption reason | 执行免费退改、退款、改签或重订。 |
| Payment | `RecoveryCompensationApproved`、refund/compensation business intent | Payment 只执行资金动作，金额和原因来自恢复决策或 Post Sales。 |
| Notification | `ServiceAlertPublished`、`RecoveryOptionOffered`、`RecoveryDecisionConfirmed`、`ManualRecoveryRequired` | 告知用户异常、方案、选择期限和进度。 |
| Customer Service | RecoveryCaseView、ManualRecoveryQueue、batch impact view | 客服解释异常、代选方案、人工赔付和兜底。 |
| Reporting / Finance Settlement | disruption metrics、waiver cost、compensation reason、responsibility party | 统计异常成本、供应商责任、赔付和运营质量。 |
| Transfer Management | `ConnectionRecovered`、recovery outcome | 回填换乘恢复结果，帮助后续风险模型。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `Incident` | 同一来源、同一服务范围、同一时间窗口的异常应归并；严重级别、影响范围和证据必须可追溯；批量关闭前所有子 Case 需有终态或明确人工队列。 | `OpenIncident`、`MergeDisruptionSignal`、`UpdateIncidentScope`、`PublishServiceAlert`、`CloseIncident` | `IncidentOpened`、`DisruptionSignalMerged`、`IncidentScopeUpdated`、`ServiceAlertPublished`、`IncidentClosed` |
| `RecoveryCase` | 每个受影响对象同一 Incident 下只能有一个 active Case；必须绑定 affected scope、责任方、Protection、waiver 和执行目标；自动决策不能跳过用户必须确认的费用或风险；终态后只能追加修正 Case。 | `OpenRecoveryCase`、`AssessRecoveryImpact`、`GenerateRecoveryOptions`、`SelectRecoveryOption`、`ApplyRecoveryDecision`、`EscalateManualRecovery`、`CloseRecoveryCase` | `RecoveryCaseOpened`、`RecoveryImpactAssessed`、`RecoveryOptionsGenerated`、`RecoveryOptionSelected`、`RecoveryDecisionApplied`、`ManualRecoveryRequired`、`RecoveryCaseClosed` |
| `RecoveryOptionSet` | 每个 option 必须有适用条件、费用责任、执行上下文、有效期和失败回退；Reaccommodation option 必须引用可报价或可预订的替代 Segment；waiver 必须与 Incident 和责任来源一致。 | `CreateRecoveryOptionSet`、`ExpireRecoveryOption`、`RefreshRecoveryOptions`、`RejectInvalidRecoveryOption` | `RecoveryOptionSetCreated`、`RecoveryOptionExpired`、`RecoveryOptionsRefreshed`、`InvalidRecoveryOptionRejected` |
| `WaiverPolicySnapshot` | 对同一 Case 的 waiver 决策需冻结规则版本、责任方、免除项目、有效窗口和审批来源；不能因后续规则变化覆盖历史。 | `CreateWaiverPolicySnapshot`、`ApproveManualWaiver`、`RevokeUnusedWaiver` | `WaiverPolicySnapshotCreated`、`ManualWaiverApproved`、`UnusedWaiverRevoked` |
| `CompensationDecision` | 赔付必须有责任、金额或权益、上限、执行上下文和审计依据；不能重复赔付同一 purpose；现金赔付需与退款区分。 | `CreateCompensationDecision`、`ApproveCompensation`、`CancelCompensationDecision` | `CompensationDecisionCreated`、`CompensationApproved`、`CompensationDecisionCancelled` |

## 6. 状态机

Disruption Recovery 拥有 `Incident`、`RecoveryCase` 和 `RecoveryOption` 状态机。它不定义 Service Plan、Post Sales、Payment、Booking、Entitlement、JourneyOrder 的内部状态。

### `RecoveryCase` 状态

| 状态 | 含义 |
|---|---|
| `Opened` | 已确认某对象受 Incident 或 Disruption 影响。 |
| `AssessingImpact` | 正在判定影响范围、Protection、责任和可恢复路径。 |
| `OptionsGenerated` | 已生成恢复方案，可自动选择或等待用户确认。 |
| `AwaitingUserChoice` | 需要用户选择改乘、退款、等待或自费方案。 |
| `ExecutingRecovery` | 已选方案正在由 Booking、Post Sales、Payment 等执行。 |
| `ManualReview` | 自动流程无法安全决策或执行，进入人工。 |
| `Recovered` | 恢复完成，用户获得替代出行、退款、赔付或明确结果。 |
| `Declined` | 用户拒绝可用恢复方案或选择自行处理。 |
| `Failed` | 恢复执行失败且无法自动补偿。 |
| `Closed` | Case 已归档，保留审计和后续修正入口。 |

### 关键转换

| 当前状态 | 触发 | 目标状态 | 规则 |
|---|---|---|---|
| `Opened` | `AssessRecoveryImpact` | `AssessingImpact` | 必须有 affected scope 和 source evidence。 |
| `AssessingImpact` | `RecoveryImpactAssessed` | `OptionsGenerated` | 已判断 Protection、责任方和 waiver 候选。 |
| `OptionsGenerated` | 自动规则可决策 | `ExecutingRecovery` | 仅限无额外费用、风险已披露且符合用户偏好。 |
| `OptionsGenerated` | 需要用户确认 | `AwaitingUserChoice` | 有费用、路线变化、时间明显变化或放弃权利时必须确认。 |
| `AwaitingUserChoice` | `RecoveryOptionSelected` | `ExecutingRecovery` | option 未过期且仍可执行。 |
| `ExecutingRecovery` | 下游执行成功 | `Recovered` | Booking/Post Sales/Payment 等必要结果已收敛。 |
| `ExecutingRecovery` | 可恢复失败 | `OptionsGenerated` 或 `ManualReview` | 可刷新方案或转人工。 |
| 任意非终态 | 高风险或规则冲突 | `ManualReview` | 需要客服、运营或供应商确认。 |
| `Recovered` / `Declined` / `Failed` | `CloseRecoveryCase` | `Closed` | 关闭后不再执行原 Case。 |

### `Incident` 状态

| 状态 | 含义 |
|---|---|
| `Detected` | 已接收异常信号，但影响范围可能未稳定。 |
| `Confirmed` | 异常被确认，可打开 RecoveryCase。 |
| `BatchProcessing` | 正在批量识别订单、发布 alert、生成子 Case。 |
| `Monitoring` | 主要恢复动作已启动，等待执行和新信号。 |
| `Resolved` | 异常结束或运营确认无新增影响。 |
| `Closed` | 批量 Case 已收敛或转入人工队列。 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `OpenIncident` | Incident | `IncidentOpened` | sourceSystem + sourceIncidentId + scopeHash |
| `MergeDisruptionSignal` | Incident | `DisruptionSignalMerged` | incidentId + sourceEventId |
| `PublishServiceAlert` | Incident | `ServiceAlertPublished` | incidentId + alertVersion + audience |
| `OpenRecoveryCase` | RecoveryCase | `RecoveryCaseOpened` | incidentId + affectedScopeHash |
| `AssessRecoveryImpact` | RecoveryCase | `RecoveryImpactAssessed` | caseId + inputSnapshotVersion |
| `CreateWaiverPolicySnapshot` | WaiverPolicySnapshot | `WaiverPolicySnapshotCreated` | caseId + responsibility + ruleVersion |
| `GenerateRecoveryOptions` | RecoveryOptionSet | `RecoveryOptionsGenerated` | caseId + optionInputVersion |
| `SelectRecoveryOption` | RecoveryCase | `RecoveryOptionSelected` | caseId + optionId + actorId |
| `ApplyRecoveryDecision` | RecoveryCase | `RecoveryDecisionApplied` | caseId + decisionVersion |
| `RequestReaccommodationBooking` | RecoveryCase | `ReaccommodationRequested` | caseId + optionId + travelerId |
| `ApproveRecoveryRefund` | RecoveryCase | `RecoveryRefundApproved` | caseId + refundPurpose + waiverVersion |
| `CreateCompensationDecision` | CompensationDecision | `CompensationDecisionCreated` | caseId + compensationPurpose |
| `EscalateManualRecovery` | RecoveryCase | `ManualRecoveryRequired` | caseId + reason + sourceEventId |
| `RecordRecoveryExecutionResult` | RecoveryCase | `RecoveryExecutionResultRecorded` | caseId + targetContext + externalEventId |
| `CloseRecoveryCase` | RecoveryCase | `RecoveryCaseClosed` | caseId + finalOutcomeVersion |
| `CloseIncident` | Incident | `IncidentClosed` | incidentId + closeReason + operatorOrRule |

事件必须携带 `eventId`、`occurredAt`、`correlationId`、`causationId`、`incidentId`、`recoveryCaseId`、`journeyOrderId`、`affectedScope`、`schemaVersion`。消费外部事件使用 Inbox 幂等；对下游命令使用 Outbox 并带业务幂等键。

## 8. 策略和 Saga 参与点

### 异常识别策略

- Service Plan 发布计划取消、停运、运营限制时，创建或更新 `Incident`，再按受影响 service refs 查询订单范围。
- Provider Integration 报告供应商取消、司机取消、接口长时间失败时，先映射为平台 `Disruption`，再判断是否影响已确认订单。
- Fulfillment 的实际晚点、到达、未发车和履约中断用于判断是否需要恢复，而不是替代 Service Plan 源数据。
- Transfer Management 发布 `ConnectionMissed` 时，Disruption Recovery 根据 Connection Contract 判断 Protection 和恢复责任。

### 单客 Recovery Saga

| Step | Disruption Recovery 行为 | 下游协作 |
|---|---|---|
| 打开 Case | 绑定 JourneyOrder、Segment、Traveler、Incident、Protection。 | Journey Order、Transfer Management。 |
| 影响评估 | 判断可等待、需改乘、需退票、是否免手续费、是否赔付。 | Fare & Pricing、Post Sales、Customer Service。 |
| 生成方案 | 查询替代 Segment 或 ChangeOffer，生成 Reaccommodation / refund / wait options。 | Offer Management、Booking、Post Sales。 |
| 用户或自动选择 | 根据费用、时间变化、用户偏好决定自动或等待选择。 | Notification、Journey Order。 |
| 执行恢复 | 发起 replacement booking、退改、退款、赔付或人工。 | Booking、Post Sales、Payment。 |
| 收敛 | 记录执行结果，发布恢复完成或人工事件。 | Journey Order、Customer Service、Reporting。 |

### 批量 Recovery 策略

- `Incident` 可批量生成子 `RecoveryCase`，但每个 Case 保留独立旅客、订单、票证、支付和用户偏好。
- 批量默认策略可用于“全额退款”“全部发布停运 alert”“相同 Reaccommodation 候选”，但不能忽略特殊旅客、已履约、已售后中、风险冻结和人工标记。
- 批量处理应分组：同车次/航班/船班、同出发时间、同 Connection Contract、同供应商责任、同恢复方案窗口。
- 大面积天气或管制事件可以先发布 `ServiceAlert`，再逐步生成可执行方案，避免用户在无信息状态下等待。

### Protection、Waiver、Compensation 策略

- Protected Connection：错过接续时优先生成免费 Reaccommodation；若无可行替代，生成全额或部分退款、住宿/餐饮/交通补偿或人工兜底。
- Supplier Protected：遵循供应商保护规则，平台协助执行；供应商拒绝或状态不明时进入人工或平台补偿判断。
- Platform Assisted：平台提供重订入口、客服和有限补贴；免手续费和赔付按服务承诺计算。
- Self Transfer：默认不承诺免费改乘或连带退款；可以提供自费替代方案和单段售后入口，且必须解释责任边界。
- Waiver 可免除退改手续费、供应商罚金、平台服务费、差价或取消费，但每项必须有责任来源和规则版本。
- Compensation 与 Refund 分开：Refund 返还原交易金额，Compensation 是额外权益或赔付。

### 人工兜底策略

- 自动方案不可用、费用超阈值、供应商状态 Unknown、用户诉求超规则、跨境或多供应商责任冲突时进入 `ManualReview`。
- 人工可批准特殊 waiver、选择替代方案、发起赔付、关闭无影响 Case，但必须写入 Admin & Audit。
- 人工操作不得直接改写 Payment、Booking、Post Sales 或 JourneyOrder 状态；必须发受控命令。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `DisruptionDashboard` | `IncidentOpened`、`IncidentScopeUpdated`、`ServiceAlertPublished`、case counts | 运营、客服、Reporting。 |
| `RecoveryCaseView` | `RecoveryCaseOpened`、`RecoveryImpactAssessed`、`RecoveryOptionsGenerated`、`RecoveryDecisionApplied`、execution results | 用户订单详情、客服工作台。 |
| `RecoveryOptionView` | option events、Offer/Booking availability summaries、waiver snapshots | 用户选择页、客服代选。 |
| `BatchRecoveryProgress` | Incident events、RecoveryCase lifecycle events、PostSales/Booking results | 运营批处理、异常指挥台。 |
| `ServiceAlertFeed` | `ServiceAlertPublished`、alert audience and versions | Notification、用户行程页、客服公告。 |
| `WaiverAndCompensationLedger` | waiver snapshots、compensation decisions、Payment/PostSales outcomes | Finance Settlement、Reporting、审计。 |
| `ManualRecoveryQueue` | `ManualRecoveryRequired`、provider conflict、execution failure | Customer Service、Admin & Audit。 |
| `ProtectedConnectionRecoveryView` | Transfer events、Protection decisions、recovery outcomes | Transfer Management、客服、运营优化。 |

读模型规则：读模型可以聚合订单、票证、退款、改乘和通知状态，但不是写入权威；客服和运营操作必须回到 RecoveryCase 或相关上下文命令。用户可见解释必须来自冻结的 Recovery Decision、waiver snapshot 和 CompensationDecision。

## 10. 外部系统和防腐层

Disruption Recovery 不直接接入铁路、航司、大巴、船司、网约车平台或天气/管制系统的原始语言。它通过 Provider Integration、Service Plan、Fulfillment 和运营 ACL 消费平台事件。

| External / Legacy Language | Platform Contract | ACL Requirement |
|---|---|---|
| 铁路停运、晚点、调图、站点封控 | `ProviderDisruptionReported` 或 `ServiceDisrupted` | 映射 affected service、station interval、time window、confidence。 |
| 航班取消、延误、备降、航司保护 | `ProviderCancellationReported`、supplier protection evidence | PNR/航司状态不进入核心字段，只保存引用和映射结果。 |
| 船班停航、天气封航、靠港变化 | `ServiceDisrupted`、`OperationRestrictionPublished` | 区分计划级停航和用户已登船后的履约中断。 |
| 大巴停班、站点变更、道路管制 | `ServiceAlertPublished`、affected boarding point refs | 站点变更需连接 Place & Network 引用。 |
| 网约车司机取消、无车、司机迟到 | `ProviderDisruptionReported`、`RideAssignmentCancelled` | 司机派单细节留在 Dispatch/Provider ACL，恢复只看平台 outcome。 |
| 天气、政策、罢工、自然灾害 | `OperationalIncidentReported` | 作为 Incident 证据和批量范围输入，不直接决定赔付。 |

遗留迁移期需要 LegacyDisruptionACL：把旧系统中的停运通知、订单异常标记、供应商失败、客服批量处理表映射为 `Incident` 和 `RecoveryCase` 事件；禁止旧流程直接批量改订单、直接退款或同步发通知。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-train-service`, `ts-travel-service`, `ts-travel2-service`, `ts-route-plan-service` | 车次、路线和查询侧异常信息迁移到 Service Plan / Trip Planning；Disruption Recovery 只消费异常事件和可恢复方案引用。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 下单失败和供应商失败不再直接拼接异常处理；预订失败归 Booking，已确认后的异常恢复归 RecoveryCase。 |
| `ts-order-service`, `ts-order-other-service` | 订单异常展示从直接状态字段迁移为消费 `JourneyAffectedByDisruption` 和 `RecoveryDecisionApplied`。 |
| `ts-cancel-service` | 因停运、晚点、司机取消导致的免费退票由 Disruption Recovery 决定 waiver，Post Sales 执行退票 Case。 |
| `ts-rebook-service` | 保护性改乘不再走普通改签入口；RecoveryCase 选择 Reaccommodation 后请求 Booking / Post Sales 执行。 |
| `ts-inside-payment-service`, `ts-payment-service` | 退款和赔付执行归 Payment；Recovery 只发布业务意图、责任方和金额/上限决策。 |
| `ts-seat-service` | 异常释放库存、替代库存 Hold 由 Capacity 执行；Recovery 不直接改座位或余票。 |
| `ts-execute-service` | 实际晚点、到达、取票、进站、已使用事实拆到 Fulfillment / Entitlement，作为影响评估输入。 |
| `ts-notification-service` | 从同步异常短信迁移为消费 `ServiceAlertPublished`、`RecoveryOptionOffered` 等事件。 |
| `ts-admin-order-service` | 批量异常处理和人工赔付迁移为 Customer Service / Admin & Audit 的受控 Recovery 命令。 |
| `ts-security-service` | 高风险赔付、批量退款和异常套利信号接入 Risk & Compliance；Recovery 根据风险结果转人工。 |
| `ts-assurance-service`, `ts-food-service`, `ts-consign-service` | 主行程异常对保险、餐饮、托运的连带影响由 Ancillary Service 提供规则；Recovery 只在读模型聚合和发布联动意图。 |

迁移切片建议：先建立 Incident 和 RecoveryCase 只读投影；再把停运/取消类事件接入自动 ServiceAlert；随后接入 Post Sales 免费退票 waiver；再接入 Reaccommodation replacement booking；最后支持批量 Incident、Protection 和 Compensation。

## 12. 验收标准

- 本 domain 的聚合所有权明确：`Incident`、`RecoveryCase`、`RecoveryOptionSet`、`WaiverPolicySnapshot`、`CompensationDecision` 属于 Disruption Recovery。
- 本 domain 不拥有 Service Plan 源数据、Post Sales 执行规则、Payment 资金执行、Booking 预订执行、Entitlement 票证状态或 Notification 发送状态。
- 已覆盖晚点、取消、停运、供应商失败、换乘错过、天气/管制、网约车司机取消、批量事件、改乘、退票、赔付和通知协作。
- 单客和批量 RecoveryCase、自动恢复、用户选择、Protection、Waiver、Compensation、人工兜底均有状态、命令、事件和读模型。
- Recovery Saga 的下游协作明确：Reaccommodation 交给 Booking，退改执行交给 Post Sales，资金交给 Payment，通知交给 Notification。
- 不变量和状态机只定义 Disruption Recovery 自有状态，不依赖其他 domain 内部状态。
- Provider、Service Plan、Fulfillment、Transfer、运营人工等异常输入通过 ACL 或发布语言进入，不泄漏供应商原始状态码。
- 当前服务迁移影响覆盖订单、订票、取消、改签、支付、座位、履约、通知、后台、风控和附加服务。
