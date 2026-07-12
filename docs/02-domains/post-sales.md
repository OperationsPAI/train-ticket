# Post Sales Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Post Sales |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-post-sales |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/domain-glossary.md`, `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/aggregate-model.md`, `docs/01-ddd-high-level/state-machines.md`, `docs/01-ddd-high-level/event-storming.md`, `docs/01-ddd-high-level/consistency-and-saga.md`, `docs/01-ddd-high-level/order-inventory-payment-model.md`, `docs/01-ddd-high-level/acl-provider-contracts.md`, `docs/01-ddd-high-level/general-travel-ddd.md`, `docs/02-domains/journey-order.md`, `docs/02-domains/capacity-availability.md`, `docs/02-domains/payment.md`, `docs/02-domains/entitlement-ticketing.md`, `docs/02-domains/offer-management.md` |

## 1. 领域目标

Post Sales 负责用户下单后、履约前后发生的售后业务流程：取消、退票、改签、改程、变更到站、升降级、重订、退款资格判断、手续费/差价/补偿决策，以及售后 Case 的状态机和补偿编排。它把“用户是否可以变更原购买承诺、需要付或退多少钱、哪些票证和库存副作用必须完成、失败后如何补偿”收敛到一个可审计的 `PostSalesCase`。

本领域独立存在的原因：

1. 售后规则不同于初始下单规则。已出票、未出票、已取票、已登乘、部分使用、No-show、供应商取消、换乘失败都会改变可退改范围。
2. `JourneyOrder` 只表达商业订单和用户可见承诺，不应直接计算退改规则、手续费、差价或补偿，也不应绕过售后流程修改核心不变量。
3. `Fare & Pricing` 是规则和费用计算的权威来源；Post Sales 负责在 Case 生命周期中调用规则、冻结决策快照、编排执行和处理异常。
4. `Payment` 只执行 `Refund` 或补差价收款，不判断能否退改；Post Sales 提供原因、金额、幂等键和业务引用。
5. `Entitlement & Ticketing` 拥有票证作废、冻结、换发；Post Sales 只请求这些动作并消费结果。
6. `Capacity & Availability` 拥有库存释放、新库存 Hold 和 Occupancy；Post Sales 不直接改库存表。
7. 多交通方式、多 Segment、联乘/中转场景需要一个统一售后编排层，避免火车、飞机、大巴、轮船、网约车各自把退改和资金规则写进订单或支付。

第一阶段 Train Ticket 范围覆盖：未支付取消、已支付未出票取消、已出票退票、改签、补差价、差额退款、旧票作废、新票签发、座位释放、当前 `ts-cancel-service` 和 `ts-rebook-service` 的迁移。未来 General Travel 范围扩展到跨 modal 变更、保障联乘失败、供应商规则不一致、部分退改和人工补偿。

## 2. 边界

### In Scope

- 创建、查询、推进 `PostSalesCase`，覆盖 `Cancellation`、退票、`Change`、`Rebook`、退款资格、补差价、差额退款、`Compensation`。
- 读取 `JourneyOrder`、Order Item、TravelerRef、SegmentRef、EntitlementRef、Price Snapshot、Rule Snapshot、Connection Contract 等售后输入快照。
- 判断售后申请范围：整单、单个 Order Item、单个 Segment、单个 Traveler、附加服务、联乘组合。
- 调用 `Fare & Pricing` 获取售后规则计算结果，包括退改资格、手续费、差价、供应商罚金、平台服务费、税费返还、补偿上限。
- 冻结 `PostSalesDecision`：规则版本、计算时间、输入快照、费用明细、应补金额、应退金额、补偿建议、责任方。
- 管理 `PostSalesCase` 自有状态机和步骤编排，保证每个副作用成功、失败、重试和补偿都可追踪。
- 编排票证作废/冻结/换发：向 Entitlement & Ticketing 发出 `VoidEntitlement`、`SuspendEntitlement`、`IssueReplacementEntitlement` 等命令或请求。
- 编排库存释放和新库存占用：向 Capacity & Availability 发出 `ReleaseCapacityAfterVoid`、`HoldReplacementCapacity`、`ReleaseOriginalAfterChange` 等命令。
- 编排补差价收款和退款执行：向 Payment 发出 `CreatePaymentIntent` 或 `RequestRefund`，并消费 `PaymentCaptured`、`RefundSettled`、`RefundFailed` 等资金事件。
- 编排供应商取消、改签、改程、重订和状态查询：通过 Provider Integration ACL 发送统一供应商请求，不泄漏供应商原始状态。
- 处理多 Segment、多 Traveler、部分退改、部分失败、换乘失败导致连带退改和供应商规则冲突。
- 为用户、客服、运营、报表提供售后读模型：Case 列表、进度、费用解释、退款进度、改签进度、异常补偿队列。
- 支持 Customer Service / Admin & Audit 的受控人工审批、人工豁免、人工补偿和审计记录。

### Out of Scope

- 不直接修改 `JourneyOrder` 的核心不变量、订单金额汇总或生命周期状态；只能发布售后结果事件或发送受控命令请求 Journey Order 调整商业摘要。
- 不拥有 Fare Rule、票价表、退改规则配置、手续费公式或促销规则；这些属于 `Fare & Pricing`。
- 不拥有 `PaymentIntent`、`Refund` 的渠道状态、回调、对账、资金流水或 late payment 处理；这些属于 Payment。
- 不拥有 Entitlement 状态机、Credential、票号、二维码、取票码、登乘核验；这些属于 Entitlement & Ticketing / Fulfillment。
- 不拥有 CapacityHold、InventoryPool、Quota、Waitlist 排序或库存账本；这些属于 Capacity & Availability / Waitlist。
- 不创建初始订单、不执行下单 Saga、不直接确认供应商预订；这些属于 Journey Order 和 Booking Orchestration。
- 不拥有路线规划、搜索、初始 Offer 报价；改签预览使用 Offer Management 的 `ChangeOffer` 快照。
- 不拥有供应商原始 API、PNR 原始报文、外部错误码解释；这些由 Provider Integration ACL 映射。
- 不拥有通知发送渠道、文案、重试；Notification 消费售后事件或指令。
- 不拥有实际履约事实，如进站、登乘、到达、完成；这些属于 Fulfillment。
- 不直接处理财务清结算、发票、会计分录；Finance Settlement 消费资金和售后事件。

## 3. 统一语言补充

只补充 Post Sales 内部术语。跨域通用术语沿用 high-level glossary。

| Term | Definition | Notes |
|---|---|---|
| PostSalesCase | 一次售后申请或系统售后处理的聚合根。 | 绑定 JourneyOrder、Order Item、Segment、Traveler、Entitlement 和规则快照。 |
| PostSalesScope | 售后影响范围。 | 可为整单、单 Segment、单 Traveler、多段联乘、附加服务或组合范围。 |
| Cancellation | 关闭未完成订单或取消尚未最终出票/确认的服务。 | 未支付取消、已支付未出票取消、已确认服务取消需要区分。 |
| Ticket Refund | 已出票或已确认权益的退票流程。 | 通常包含 Entitlement 作废、供应商取消、库存释放和 Payment Refund。 |
| Change | 对原行程承诺的变更。 | 包含改签、改期、改程、升降级、变更到站、变更席别。 |
| Rebook | 放弃原 Segment 或原供应商确认后，重新预订替代 Segment。 | 可由用户主动发起，也可由异常恢复或换乘失败触发。 |
| ChangeOfferRef | 改签预览报价快照引用。 | Offer Management 拥有快照，Post Sales 使用其结果推进 Case。 |
| RefundEligibilityDecision | 退票或取消是否可退款的决策值对象。 | 包含资格、原因码、规则版本、阻断原因。 |
| FeeDifferenceDecision | 手续费、差价、税费返还、应补/应退金额的决策值对象。 | 金额计算来自 Fare & Pricing，Post Sales 冻结并解释。 |
| RefundDecision | 向 Payment 请求退款前的业务决策。 | 不等于 Payment `Refund` 聚合；后者负责渠道执行状态。 |
| ExtraChargeDecision | 改签、升舱或重订需要补收的业务决策。 | 由 Payment 创建新的 `PaymentIntent` 执行。 |
| CompensationDecision | 因服务失败、供应商异常、平台责任或保障联乘失败产生的补偿决策。 | 可为退款、费用减免、券、人工赔付或重订补贴；不等同于 Refund。 |
| ProviderPostSalesAction | 对供应商发起的取消、改签、改程、重订、查询等动作。 | 通过 Provider Integration ACL 执行。 |
| PostSalesStep | Case 内部编排步骤。 | 如 RuleCheck、VoidEntitlement、ReleaseCapacity、RequestRefund、HoldReplacement。 |
| PartialPostSales | 只影响订单的一部分。 | 多旅客、多 Segment、多附加服务场景必须明确粒度。 |
| LinkedChangeGroup | 联乘或中转中需要一起判定的变更组。 | 换乘失败可能导致前后段连带退改或补偿。 |
| ManualException | 规则无法自动决策或执行结果冲突时的人工例外。 | 必须有 operatorId、reason、approvalRef、evidenceRef。 |
| PostSalesResult | 售后 Case 对外发布的业务结果。 | JourneyOrder、Payment、Entitlement、Capacity 等按自身边界消费。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Journey Order | Order Snapshot、Order Item、TravelerRef、SegmentRef、EntitlementRef、PaymentRef、Order Lifecycle Summary、`PostSalesRequested` command | 售后必须绑定原订单和可变更范围，但不能读取或修改订单内部可变状态。 |
| Offer Management | Original Offer RuleSnapshotRef、PriceSnapshot、`QuoteChangeOffer`、`ChangeOfferQuoted`、`ChangeOfferAccepted` | 退改需要追溯原票规则；改签预览需要目标方案和差价快照。 |
| Fare & Pricing | `EvaluateRefundRule`、`EvaluateChangeRule`、FareRuleResult、Fee/Tariff/Tax breakdown、RuleVersion | 规则和费用计算来源；Post Sales 冻结决策并执行流程。 |
| Entitlement & Ticketing | EntitlementStatusView、`EntitlementVoided`、`EntitlementVoidFailed`、`EntitlementSuspended`、`EntitlementIssued`、`EntitlementUsed` | 判断可退改窗口和推进作废/换发步骤。 |
| Capacity & Availability | `ReplacementCapacityHeld`、`CapacityReleased`、`CapacityReleaseFailed`、可售性/库存结果 | 改签锁新库存、退票释放旧库存、失败时进入补偿或人工。 |
| Payment | `PaymentCaptured` for extra charge、`PaymentFailed`、`RefundAccepted`、`RefundSubmitted`、`RefundSettled`、`RefundFailed`、`RefundManualReviewRequired` | 资金执行结果推进售后 Case，但不替代业务决策。 |
| Booking Orchestration | SegmentBooking 摘要、`SegmentBookingCancelled`、`SegmentBookingChanged`、Replacement booking result、Saga failure events | 取消供应侧预订、改签或重订需要编排结果。 |
| Provider Integration | `ProviderCancelResult`、`ProviderChangeResult`、`ProviderStatusResult`、mapped provider errors | 供应商侧取消/改签/重订结果必须经 ACL 转换。 |
| Disruption Recovery | `JourneyAffectedByDisruption`、`RecoveryOptionAccepted`、disruption reason、responsibility、waiver policy | 异常恢复可触发免费退改、保护性重订或补偿。 |
| Transfer Management | Connection Contract、`TransferAtRisk`、`ConnectionMissed`、connection protection scope | 换乘失败或保障联乘影响连带退改和补偿责任。 |
| Fulfillment | `EntitlementCheckedIn`、`EntitlementBoarded`、`SegmentCompleted`、No-show facts | 已使用或部分使用会改变退款资格和补偿方式。 |
| Ancillary Service | Ancillary order status、cancellation/refund policy、linked service references | 主行程退改可能连带保险、餐饮、托运等附加服务。 |
| Customer Service / Admin & Audit | Manual approval command、operatorId、reason、evidenceRef、override scope | 人工售后和例外处理必须审计并受不变量约束。 |
| Risk & Compliance | Risk hold/release decision、fraud/abuse signal、chargeback risk | 高风险售后可进入人工审核或限制自动退款。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Journey Order | `PostSalesRequested`、`PostSalesApproved`、`PostSalesApplied`、`JourneyCancelledByPostSales`、`OrderItemCancelledByPostSales`、`ChangeApplied`、`PostSalesFailed` | JourneyOrder 只根据售后结果调整商业摘要或状态，不执行售后规则。 |
| Fare & Pricing | Rule evaluation request with immutable input snapshot、decision audit feedback | 费用和规则计算需要完整上下文；规则命中结果需可追溯。 |
| Payment | `PostSalesRefundApproved` / `RequestRefund`、`CreatePaymentIntent` with purpose=fare-difference、`PostSalesRefundCancelled` | Payment 执行资金收退和渠道状态，金额和原因来自 Post Sales。 |
| Entitlement & Ticketing | `VoidEntitlement`、`SuspendEntitlement`、`IssueReplacementEntitlement` request、void/change reason | 退票、改签和补偿需要作废、冻结或换发凭证。 |
| Capacity & Availability | `HoldReplacementCapacity`、`ReleaseCapacityAfterVoid`、`ReleaseOriginalAfterChange`、`ApplyPostSalesCapacityAdjustment` | 退改影响旧库存释放和新库存 Hold，但库存不变量由 Capacity 保护。 |
| Booking Orchestration | `CancelSegmentBookingForPostSales`、`ChangeSegmentBookingForPostSales`、`StartRebookSaga` | 供应侧预订取消、改签、重订需要编排跨供应商和票证。 |
| Provider Integration | Normalized provider cancel/change/rebook/query commands with idempotency key | 隔离供应商退改语言、错误码和重试策略。 |
| Notification | `PostSalesCaseSubmitted`、`PostSalesQuoteReady`、`PostSalesExecutionStarted`、`RefundProgressUpdated`、`PostSalesCompleted`、`PostSalesManualReviewRequired` | 通知用户售后进度；通知失败不回滚 Case。 |
| Customer Service | PostSalesCaseView、PostSalesTimeline、ManualExceptionQueue、Action required events | 客服查询、审批、人工补偿和异常兜底。 |
| Reporting | PostSales event stream、refund/change/cancellation metrics | 分析退改率、退款耗时、供应商失败、补偿成本。 |
| Finance Settlement | refund reason、fee retained、supplier penalty、compensation reason | 清结算、收入冲减、供应商罚金和补偿成本归集。 |
| Disruption Recovery | `PostSalesAppliedForDisruption`、`PostSalesCompensationFailed`、case progress | 异常恢复批量处理需要知道售后执行是否闭环。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| PostSalesCase | 每个 Case 必须绑定 `JourneyOrderId` 和明确 `PostSalesScope`；申请范围内每个 Order Item / Segment / Traveler / Entitlement 只能有一个 active 冲突 Case；规则判定必须记录 RuleSnapshot、RuleVersion、输入时间和 actor；已确认执行的金额决策不可静默改写，只能追加修正决策；执行步骤必须幂等、可重试、可补偿；不得直接修改 JourneyOrder、Payment、Entitlement 或 Capacity 内部状态；人工例外必须有权限、原因和审计。 | `OpenPostSalesCase`、`RequestCancellation`、`RequestRefundByRule`、`RequestChange`、`RequestRebook`、`EvaluatePostSalesEligibility`、`QuotePostSalesDecision`、`ApprovePostSalesCase`、`RejectPostSalesCase`、`StartPostSalesExecution`、`RecordPostSalesStepResult`、`ApplyPostSalesResult`、`FailPostSalesCase`、`CancelPostSalesCase`、`EscalateManualException`、`ApplyManualPostSalesDecision` | `PostSalesCaseOpened`、`CancellationRequested`、`RefundByRuleRequested`、`ChangeRequested`、`RebookRequested`、`PostSalesEligibilityEvaluated`、`PostSalesDecisionQuoted`、`PostSalesApproved`、`PostSalesRejected`、`PostSalesExecutionStarted`、`PostSalesStepSucceeded`、`PostSalesStepFailed`、`PostSalesApplied`、`PostSalesFailed`、`PostSalesCaseCancelled`、`PostSalesManualReviewRequired`、`ManualPostSalesDecisionApplied` |
| PostSalesDecision | 同一 Case 和 decision version 只能对应一个不可变业务决策；金额币种必须与原支付或规则允许币种一致；应退、应补、手续费、补偿不能同时违反资金守恒；决策必须可解释到 Fare & Pricing 返回和人工审批。 | `CreateRefundDecision`、`CreateChangeDecision`、`CreateExtraChargeDecision`、`CreateCompensationDecision`、`ReviseDecisionByManualApproval`、`ExpireDecisionQuote` | `RefundDecisionCreated`、`ChangeDecisionCreated`、`ExtraChargeDecisionCreated`、`CompensationDecisionCreated`、`PostSalesDecisionRevised`、`PostSalesDecisionExpired` |
| PostSalesExecutionPlan | 每个执行步骤必须有前置条件、目标上下文、幂等键、最大重试、补偿动作和状态；不可并行执行存在顺序依赖的步骤；部分成功必须进入 Case 可见状态。 | `BuildExecutionPlan`、`StartExecutionStep`、`RetryExecutionStep`、`SkipExecutionStepByPolicy`、`CompensateExecutionStep`、`CompleteExecutionPlan` | `PostSalesExecutionPlanBuilt`、`PostSalesExecutionStepStarted`、`PostSalesExecutionStepRetried`、`PostSalesExecutionStepSkipped`、`PostSalesExecutionStepCompensated`、`PostSalesExecutionPlanCompleted` |

### PostSalesCase 内部结构

| Type | Kind | Responsibility | Ownership Notes |
|---|---|---|---|
| PostSalesScope | Value Object | 表达受影响订单项、Segment、Traveler、Entitlement、Ancillary、Connection Group。 | 只保存引用和快照摘要，不拥有其他域状态。 |
| EligibilitySnapshot | Value Object | 固化判断时的订单、票证、履约、规则、时间窗口、风险输入。 | 防止后续状态变化污染历史决策。 |
| RefundEligibilityDecision | Value Object | 表达是否可退、可退比例、不可退原因、规则版本。 | 由 Fare & Pricing 计算，Post Sales 解释和固化。 |
| FeeDifferenceDecision | Value Object | 表达手续费、差价、税费、服务费、供应商罚金、平台减免。 | 金额结果可被人工审批修正，但需追加版本。 |
| ChangePlan | Entity | 表达旧 Segment/Entitlement 与目标 Segment/ChangeOffer 的替换关系。 | 支持改签、改程、变更到站、升降级。 |
| RebookPlan | Entity | 表达原行程失败或用户选择后的重订策略。 | 可关联 Disruption Recovery 或 Transfer Management。 |
| RefundInstruction | Entity / Value Object | 向 Payment 请求 `Refund` 的业务原因、金额、币种、幂等键。 | Payment 仍拥有 Refund 聚合。 |
| CompensationPlan | Entity | 表达退款外补偿、费用减免、券、人工赔付、重订补贴。 | 具体钱包/券/赔付执行归属需由相关域执行。 |
| PostSalesStep | Entity | 跟踪每个外部副作用步骤的状态和结果。 | 通过 Inbox/Outbox 幂等关联外部事件。 |
| ManualAuditTrail | Value Object Collection | 记录人工审批、拒绝、覆盖、证据、操作人和时间。 | 不允许无审计人工修改决策。 |

### 核心不变量

1. **订单边界不变量**：Post Sales 不直接写 JourneyOrder 的 Order Item、MonetarySummary 或生命周期状态；只发布 `PostSalesApplied`、`JourneyCancelledByPostSales`、`OrderItemCancelledByPostSales`、`ChangeApplied` 等事实或发送受控命令。
2. **规则快照不变量**：每次资格和金额决策必须引用原 Offer RuleSnapshot、当前 Fare Rule version、判断时刻、履约状态、票证状态和来源事件。
3. **范围不变量**：部分退改必须显式列出 Segment、Traveler、Order Item 和 Entitlement；不能用整单字段隐式代表部分范围。
4. **资金不变量**：Post Sales 决定业务金额，Payment 校验资金余额；同一 Case 的同一退款/补收 purpose 只能产生一个语义等价资金请求。
5. **票证不变量**：已 `Used` 或已 `Boarded` 的 Entitlement 默认不能普通作废；特殊异常只能进入 Compensation 或人工例外。
6. **库存不变量**：改签应先 Hold 目标库存，再按规则处理差价和旧票作废；旧 Occupancy 释放必须以票证作废或供应商取消结果为前置事实。
7. **供应商不变量**：供应商返回 Unknown、Timeout、Conflict 时不得静默完成售后；必须进入查询、重试、补偿或人工分支。
8. **幂等不变量**：用户重复点击、客服重复提交、事件重复投递不能产生重复作废、重复退款、重复补差价或重复释放库存。
9. **可补偿不变量**：任一外部步骤失败后，Case 必须保留已完成副作用和下一步处理建议，不得回滚历史事实。

## 6. 状态机

列出本 domain 拥有的状态机。不要定义其他 domain 的状态。

### PostSalesCase 状态机

| 状态 | 含义 | 用户/客服可见重点 |
|---|---|---|
| `Opened` | Case 已创建，范围和申请原因已记录。 | “售后申请已提交”。 |
| `EligibilityChecking` | 正在收集订单、票证、履约、规则、供应商状态并判断资格。 | “正在校验退改条件”。 |
| `Quoted` | 已形成售后决策报价，等待用户确认或自动审批。 | 展示手续费、差价、应退/应补、补偿和有效期。 |
| `PendingUserConfirmation` | 需要用户确认费用、差价、风险或放弃原票。 | 用户必须确认后才能执行。 |
| `PendingApproval` | 需要客服、风控、运营或供应商人工审批。 | 展示人工处理中。 |
| `Approved` | 规则或人工审批通过，准备执行外部副作用。 | Case 不再允许普通修改范围。 |
| `Executing` | 正在作废票证、释放库存、锁新库存、供应商改签、收退款或补偿。 | 展示步骤级进度。 |
| `CompensationPending` | 主要流程部分失败，需要补偿、重试或人工兜底。 | 展示异常处理和责任方。 |
| `Applied` | 售后业务结果已应用，必要事件已发布。 | 展示完成、退款/补差价/新票状态摘要。 |
| `Rejected` | 规则或审批拒绝，未执行不可逆副作用。 | 展示拒绝原因和可选下一步。 |
| `Failed` | 不可补偿失败或人工终止。 | 展示客服入口和失败原因。 |
| `Cancelled` | 用户或系统在允许阶段取消售后 Case。 | 原订单承诺不因本 Case 改变。 |

### 允许转换

| 当前状态 | 触发 | 目标状态 | Post Sales 判断条件 |
|---|---|---|---|
| `Opened` | `EvaluatePostSalesEligibility` | `EligibilityChecking` | Case 范围、actor、幂等键、订单引用有效。 |
| `EligibilityChecking` | `PostSalesDecisionQuoted` | `Quoted` | Fare & Pricing 返回资格和费用决策，且无阻断风险。 |
| `EligibilityChecking` | `PostSalesRejected` | `Rejected` | 规则明确不可退改或范围不合法。 |
| `EligibilityChecking` | `PostSalesManualReviewRequired` | `PendingApproval` | 规则冲突、供应商状态未知、风险命中或金额超阈值。 |
| `Quoted` | `RequireUserConfirmation` | `PendingUserConfirmation` | 需要用户确认手续费、补差价、风险或旧票作废。 |
| `Quoted` | `ApprovePostSalesCase` | `Approved` | 自动审批规则允许或无需用户确认。 |
| `PendingUserConfirmation` | `UserConfirmedPostSalesQuote` | `Approved` | 确认在报价有效期内且范围未变化。 |
| `PendingUserConfirmation` | `UserDeclinedPostSalesQuote` | `Cancelled` | 未执行不可逆副作用。 |
| `PendingApproval` | `ManualPostSalesDecisionApplied` | `Approved` | 人工审批通过并记录审计。 |
| `PendingApproval` | `RejectPostSalesCase` | `Rejected` | 人工或风险拒绝。 |
| `Approved` | `StartPostSalesExecution` | `Executing` | ExecutionPlan 已构建，外部命令幂等键已生成。 |
| `Executing` | `AllRequiredStepsSucceeded` | `Applied` | 必要票证、库存、供应商、资金步骤已满足 Case 完成条件。 |
| `Executing` | `RecoverableStepFailed` | `CompensationPending` | 至少一个步骤失败但存在重试、替代或人工补偿路径。 |
| `Executing` | `UnrecoverableStepFailed` | `Failed` | 不可补偿失败且人工终止。 |
| `CompensationPending` | `CompensationSucceeded` | `Applied` | 补偿完成并发布最终售后结果。 |
| `CompensationPending` | `EscalateManualException` | `PendingApproval` | 需要人工选择恢复、退款、重订或赔付。 |
| `CompensationPending` | `CompensationFailed` | `Failed` | 补偿方案失败或超出处理期限。 |

### 禁止转换

| 禁止转换 | 原因 |
|---|---|
| `Opened` -> `Applied` | 必须有资格、决策和执行记录。 |
| `Quoted` -> `Applied` | 用户确认、审批和外部副作用不能跳过。 |
| `Applied` -> `Executing` | 已完成 Case 不可重新执行；后续修正应创建新 Case 或人工 Adjustment Case。 |
| `Rejected` -> `Executing` | 被拒绝的 Case 不能直接执行；需要重新申请或人工重新打开新 Case。 |
| `Cancelled` -> `Approved` | 用户取消后不能恢复同一 Case，避免旧报价和旧范围被误用。 |
| `Failed` -> `Applied` | 失败后若需要修复，必须通过人工 Case 或补偿 Case 产生新审计链。 |

### PostSalesStep 状态机

| 状态 | 含义 | 允许转换 |
|---|---|---|
| `Planned` | 步骤已在 ExecutionPlan 中生成。 | `WaitingDependency`, `Executing`, `Skipped` |
| `WaitingDependency` | 等待前置步骤或外部事实。 | `Executing`, `Skipped`, `Failed` |
| `Executing` | 外部命令已发送或本地动作执行中。 | `Succeeded`, `Failed`, `RetryScheduled` |
| `RetryScheduled` | 失败后等待重试窗口。 | `Executing`, `Failed`, `ManualRequired` |
| `Succeeded` | 步骤成功且结果已记录。 | 终态 |
| `Skipped` | 按规则不需要执行。 | 终态 |
| `Failed` | 步骤不可继续或达到重试上限。 | `ManualRequired`, `Compensated` |
| `ManualRequired` | 需要人工处理。 | `Executing`, `Compensated`, `Failed` |
| `Compensated` | 已执行补偿动作或记录人工兜底。 | 终态 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `OpenPostSalesCase` | PostSalesCase | `PostSalesCaseOpened` | journeyOrderId + scopeHash + requestType + clientRequestId |
| `RequestCancellation` | PostSalesCase | `CancellationRequested` | caseId + scopeHash + requesterId |
| `RequestRefundByRule` | PostSalesCase | `RefundByRuleRequested` | caseId + entitlementId/orderItemId + rulePurpose |
| `RequestChange` | PostSalesCase | `ChangeRequested` | caseId + originalEntitlementId + targetItineraryId + requestId |
| `RequestRebook` | PostSalesCase | `RebookRequested` | caseId + affectedSegmentGroup + rebookReason |
| `EvaluatePostSalesEligibility` | PostSalesCase | `PostSalesEligibilityEvaluated` | caseId + eligibilityInputVersion |
| `QuotePostSalesDecision` | PostSalesDecision | `PostSalesDecisionQuoted` | caseId + ruleVersion + decisionPurpose |
| `CreateRefundDecision` | PostSalesDecision | `RefundDecisionCreated` | caseId + refundPurpose + decisionVersion |
| `CreateChangeDecision` | PostSalesDecision | `ChangeDecisionCreated` | caseId + changeOfferId + decisionVersion |
| `CreateExtraChargeDecision` | PostSalesDecision | `ExtraChargeDecisionCreated` | caseId + paymentPurpose + decisionVersion |
| `CreateCompensationDecision` | PostSalesDecision | `CompensationDecisionCreated` | caseId + compensationReason + decisionVersion |
| `ApprovePostSalesCase` | PostSalesCase | `PostSalesApproved` | caseId + approvalActor + decisionVersion |
| `RejectPostSalesCase` | PostSalesCase | `PostSalesRejected` | caseId + rejectionReason + actorId |
| `BuildExecutionPlan` | PostSalesExecutionPlan | `PostSalesExecutionPlanBuilt` | caseId + approvedDecisionVersion |
| `StartPostSalesExecution` | PostSalesCase / PostSalesExecutionPlan | `PostSalesExecutionStarted` | caseId + executionAttempt |
| `StartExecutionStep` | PostSalesExecutionPlan | `PostSalesExecutionStepStarted` | caseId + stepId + attemptNo |
| `RecordPostSalesStepResult` | PostSalesExecutionPlan | `PostSalesStepSucceeded` / `PostSalesStepFailed` | caseId + stepId + externalEventId/resultDigest |
| `RetryExecutionStep` | PostSalesExecutionPlan | `PostSalesExecutionStepRetried` | caseId + stepId + retryNo |
| `CompensateExecutionStep` | PostSalesExecutionPlan | `PostSalesExecutionStepCompensated` | caseId + stepId + compensationAttempt |
| `ApplyPostSalesResult` | PostSalesCase | `PostSalesApplied` | caseId + executionPlanId + finalResultVersion |
| `FailPostSalesCase` | PostSalesCase | `PostSalesFailed` | caseId + failureReason + actorOrStepId |
| `CancelPostSalesCase` | PostSalesCase | `PostSalesCaseCancelled` | caseId + cancelReason + requesterId |
| `EscalateManualException` | PostSalesCase | `PostSalesManualReviewRequired` | caseId + exceptionReason + sourceStepId |
| `ApplyManualPostSalesDecision` | PostSalesCase / PostSalesDecision | `ManualPostSalesDecisionApplied` | caseId + approvalRef + operatorId |

### 对外命令意图

| Outgoing Command Intent | Target Context | Triggering PostSales Event | Purpose |
|---|---|---|---|
| `VoidEntitlement` | Entitlement & Ticketing | `PostSalesExecutionStepStarted(step=VoidEntitlement)` | 退票、改签、异常取消前作废旧票证。 |
| `SuspendEntitlement` | Entitlement & Ticketing | `PostSalesManualReviewRequired` or provider conflict | 供应商冲突或风险审核期间冻结凭证。 |
| `IssueReplacementEntitlement` | Entitlement & Ticketing | replacement booking confirmed and switch approved | 改签或重订成功后签发新凭证。 |
| `HoldReplacementCapacity` | Capacity & Availability | `ChangeDecisionCreated` or `RebookRequested` | 改签或重订先锁定目标库存。 |
| `ReleaseCapacityAfterVoid` | Capacity & Availability | `EntitlementVoided` consumed by Case | 旧票作废后释放原 Occupancy。 |
| `CreatePaymentIntent` | Payment | `ExtraChargeDecisionCreated` | 补差价、升舱、重订补收。 |
| `RequestRefund` | Payment | `RefundDecisionCreated` and prerequisites satisfied | 退票、差额退款、费用退还或补偿型退款。 |
| `CancelSegmentBookingForPostSales` | Booking Orchestration / Provider Integration | cancellation approved | 取消供应侧预订。 |
| `ChangeSegmentBookingForPostSales` | Booking Orchestration / Provider Integration | change approved | 供应商原单改签或改程。 |
| `StartRebookSaga` | Booking Orchestration | rebook approved | 放弃原确认后重新预订替代 Segment。 |
| `SendPostSalesNotification` | Notification | user-visible progress events | 告知用户报价、执行、退款和人工处理进展。 |

事件发布要求：

1. Post Sales 写状态和 OutboxEvent 必须在同一本地事务内完成。
2. 所有跨域命令必须有 `caseId`、`stepId`、`correlationId`、`causationId`、`idempotencyKey`、`decisionVersion`。
3. 消费外部事件必须通过 Inbox 按 producer event id 幂等处理。
4. 事件必须使用过去式事实命名，不用供应商原始状态码作为领域事件名。
5. 金额事件必须携带 amount、currency、fee breakdown、reasonCode、ruleVersion、decisionVersion。

## 8. 策略和 Saga 参与点

说明本 domain 如何响应外部事件，以及会触发哪些跨域命令。

### 未支付取消策略

- 用户、超时任务或客服可通过 Journey Order 入口打开 `Cancellation` Case，也可由 JourneyOrder 直接处理纯未支付商业取消。
- 若未创建 Entitlement、未 Confirmed Occupancy、未 Captured payment，Post Sales 可判定为无退款、无作废，仅请求 JourneyOrder 记录取消或由 JourneyOrder 自行完成取消。
- 若存在 CapacityHold，Post Sales 或 Booking Orchestration 请求 Capacity 释放 Hold；不得创建 Payment `Refund`。
- 若取消后收到 late payment，Payment 打开 late payment case；Post Sales 只在收到上游退款决策请求时创建退款业务 Case。

### 已支付未出票取消策略

| Step | Post Sales Role | Cross-Context Collaboration |
|---|---|---|
| 打开 Case | 绑定 JourneyOrder、PaymentIntent、SegmentBooking、未签发 Entitlement 状态。 | Journey Order / Booking 提供快照。 |
| 规则判断 | 判断是否可取消、是否有服务费或供应商罚金。 | Fare & Pricing 返回费用决策。 |
| 取消供应侧确认 | 若已有 SegmentBooking，发起取消或查询最终状态。 | Booking Orchestration / Provider Integration。 |
| 释放库存 | 供应侧取消或未确认时释放 Hold/Occupancy。 | Capacity & Availability。 |
| 退款 | 对已 Captured 金额发起 `RequestRefund`。 | Payment 执行 Refund。 |
| 应用结果 | 发布 `PostSalesApplied`，请求 JourneyOrder 标记商业取消或订单项取消。 | Journey Order 消费结果。 |

### 已出票退票 Saga

1. Post Sales 读取 Order Snapshot、EntitlementStatusView、Fulfillment facts、RuleSnapshot 和当前时间窗口。
2. Fare & Pricing 计算退票资格、手续费、应退金额、不可退原因和供应商罚金。
3. 用户确认或规则自动审批后，Post Sales 发起 `VoidEntitlement`；若供应商需要先取消 Provider reservation，则通过 Provider Integration 或 Booking Orchestration 执行。
4. `EntitlementVoided` 后，Post Sales 请求 Capacity 释放原 Occupancy，并根据决策向 Payment 发起 `RequestRefund`。
5. `RefundSettled` 后，Case 可进入 `Applied`；如果票证作废成功但退款失败，Case 进入 `CompensationPending` 或 `PendingApproval`，不得恢复已作废票证。
6. JourneyOrder 根据 `PostSalesApplied` 更新订单项取消摘要或整单取消状态。

### 改签 / Change Saga

| 阶段 | Post Sales 行为 | 关键边界 |
|---|---|---|
| 申请 | 用户选择原 Entitlement 和目标 Itinerary；Post Sales 打开 `Change` Case。 | JourneyOrder 提供原票引用；Trip/Offer 生成目标方案。 |
| 改签报价 | 调用 Offer Management `QuoteChangeOffer`，并调用 Fare & Pricing 判断原票可改、手续费、差价。 | Offer Management 冻结目标报价；Fare & Pricing 计算规则。 |
| 锁新库存 | 对目标 Segment 发起 `HoldReplacementCapacity`。 | Capacity 保护库存，不由 Post Sales 分配座位。 |
| 补差价或差额退款 | 更贵时创建补差价 PaymentIntent；更便宜时生成 RefundDecision。 | Payment 执行资金，Post Sales 不接触渠道。 |
| 供应商改签或重订 | 根据 provider 能力选择 `ChangeSegmentBookingForPostSales` 或 `StartRebookSaga`。 | Provider Integration / Booking Orchestration 执行。 |
| 旧票处理 | 在新 Segment 确认、资金条件满足后作废旧 Entitlement；失败时按规则保留旧票或人工。 | Entitlement 拥有作废和换发。 |
| 新票签发 | 请求 replacement Entitlement；成功后发布 `ChangeApplied`。 | Entitlement 签发新票，JourneyOrder 更新订单项替换摘要。 |

改签保守顺序：目标 Hold 成功前不作废旧票；补差价失败时释放目标 Hold；新票签发失败时 Case 进入 `CompensationPending`，可重试、恢复旧票、重新选择目标或转人工。

### Rebook 策略

`Rebook` 用于原 Segment 不适合直接 Change 的场景：供应商不支持原单改签、联乘失败后需要购买替代段、异常恢复方案需要全新确认、跨 modal 替换等。

- Post Sales 负责确定是否允许重订、费用责任、是否保留原票、是否需补偿。
- Booking Orchestration 负责新 Segment 的预订 Saga；Capacity 和 Provider Integration 按自身规则执行。
- 若重订成功但旧票取消失败，Case 进入 `CompensationPending`，避免用户同时持有不可解释的新旧承诺。
- 若重订失败且旧票仍有效，Post Sales 不应取消旧票；若旧票已作废，则进入补偿或人工恢复。

### 多 Segment、联乘和换乘失败策略

- `PostSalesScope` 必须明确哪些 Segment 是独立退改，哪些属于 `LinkedChangeGroup`。
- 保障联乘或平台承诺的 Connection Contract 失败时，Post Sales 可依据 Disruption Recovery / Transfer Management 的责任判断免手续费、重订补贴或全额退款。
- 非保障自助中转只按用户购买时的 RiskDisclosure 和各 Segment 票规执行，不自动承诺连带退款。
- 部分退改时，Post Sales 只对受影响 Order Item 发布结果；JourneyOrder 仍保留未受影响部分的商业承诺。
- 若前段延误导致后段错过，Disruption Recovery 识别影响范围，Post Sales 执行用户选择的退款、Change 或 Rebook。

### 已使用、部分使用和 No-show 策略

- 未出票：通常只需取消供应侧或释放 Hold，不作废 Entitlement。
- 已出票未取票/未登乘：按退改规则作废 Entitlement 后退款或改签。
- 已取票/CheckedIn：可能需要更严格规则或人工确认；作废需 Entitlement 允许。
- 已 Boarded 或 Used：默认禁止普通退票，进入 Compensation、争议或人工 Case。
- 多段 Journey 中部分 Segment 已完成时，只能处理未使用后续 Segment 或补偿，不应整体回退 JourneyOrder。
- No-show 由 Fulfillment 提供事实，Post Sales 根据 No-show 规则决定是否保留费用、允许改签或仅补偿。

### 供应商规则不一致策略

- Fare & Pricing 给出平台规则决策；Provider Integration 返回供应商可执行性和罚金结果。
- 平台规则允许但供应商拒绝时，Case 进入 `PendingApproval` 或 `CompensationPending`，由人工、补偿或重新报价处理。
- 供应商规则允许但平台规则拒绝时，默认按平台对用户承诺执行；人工豁免需审计。
- 供应商状态 Unknown 或 Timeout 不得视为成功；应查询最终状态、重试或冻结相关 Entitlement。

### Compensation 编排

Compensation 用于服务失败、售后执行失败、保障联乘失败、供应商违约、平台责任或人工安抚：

- 可表现为手续费减免、额外退款、代金券、积分、重订补贴、人工赔付或客服承诺。
- 若 Compensation 是原路资金退回，Post Sales 生成 RefundDecision，Payment 执行 `Refund`。
- 若 Compensation 是券、积分或钱包余额，Post Sales 只发布补偿决策事件，具体执行归 Promotion、Wallet 或 Customer Service。
- Compensation 不应改写 `Refund`、Entitlement 或 JourneyOrder 历史事实，只在读模型和审计中展示。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| PostSalesCaseView | `PostSalesCaseOpened`、`PostSalesEligibilityEvaluated`、`PostSalesDecisionQuoted`、`PostSalesApproved`、`PostSalesExecutionStarted`、`PostSalesApplied`、`PostSalesFailed` | 用户售后详情、客服工作台。 |
| PostSalesEligibilityView | `PostSalesEligibilityEvaluated`、Fare rule result、Entitlement/Fulfillment summary events | 用户退改入口、客服判断可操作项。 |
| PostSalesQuoteView | `PostSalesDecisionQuoted`、`RefundDecisionCreated`、`ChangeDecisionCreated`、`ExtraChargeDecisionCreated`、`CompensationDecisionCreated` | 用户确认页、客服费用解释、Notification。 |
| PostSalesExecutionTimeline | `PostSalesExecutionPlanBuilt`、`PostSalesExecutionStepStarted`、`PostSalesStepSucceeded`、`PostSalesStepFailed`、Payment/Entitlement/Capacity/Provider results | 客服排障、用户进度条、运营异常处理。 |
| RefundProgressByCase | `RefundDecisionCreated`、`RefundAccepted`、`RefundSubmitted`、`RefundSettled`、`RefundFailed` | 用户退款进度、客服资金查询；资金权威仍是 Payment。 |
| ChangeAndRebookProgressView | `ChangeRequested`、`RebookRequested`、`ReplacementCapacityHeld`、`PaymentCaptured`、`EntitlementVoided`、`EntitlementIssued`、`ChangeApplied` | 改签页、客服、Booking Orchestration 协作。 |
| CompensationQueueView | `CompensationDecisionCreated`、`PostSalesManualReviewRequired`、`PostSalesExecutionStepCompensated` | Customer Service、运营补偿团队、Disruption Recovery。 |
| ManualExceptionQueue | `PostSalesManualReviewRequired`、`PostSalesStepFailed`、Provider conflict events、risk review events | 客服/运营人工审批和异常兜底。 |
| PostSalesMetricsFeed | 所有 PostSalesCase、Decision、Execution events | Reporting、Finance Settlement、供应商质量分析。 |
| OrderPostSalesEntryProjection | JourneyOrder events、Entitlement status events、Fulfillment facts、PostSalesCase events | 用户订单详情的“可退/可改/售后中”入口。 |

读模型规则：

1. 读模型可以冗余订单号、车次、旅客脱敏信息、票证状态、退款状态和库存步骤，但不能成为规则判断或资金执行的权威来源。
2. 用户展示的费用解释必须来自 `PostSalesDecision` 的冻结版本，不能实时重算覆盖历史报价。
3. 客服时间线可以聚合 Payment、Entitlement、Capacity、Provider 事件，但人工动作必须通过 Post Sales 或对应上下文命令执行。
4. Reporting 可以统计退改率、退款耗时、供应商失败、补偿成本，但不得反向修正 Case 状态。

## 10. 外部系统和防腐层

Post Sales 需要供应商售后防腐、规则防腐、资金防腐、票证/库存防腐和遗留服务防腐。

### Provider Integration ACL

Post Sales 不直接调用铁路、航司、大巴、船司、网约车或第三方平台的原始取消/改签 API。所有供应商动作必须通过 Provider Integration 映射为平台语言。

| Provider Language | Platform Contract | ACL Requirement |
|---|---|---|
| provider refundability code | `ProviderPostSalesRuleResult` | 映射为平台可退改、罚金、窗口、最终性和人工标记。 |
| cancel booking / cancel ticket | `ProviderCancelRequest` / `ProviderCancelResult` | 返回 success、failed、pending、unknown、retryable、finalFailure。 |
| change reservation | `ProviderChangeRequest` / `ProviderChangeResult` | 区分原单改签、重订、升舱、变更到站和供应商不支持。 |
| query post-sales status | `ProviderStatusRequest` / `ProviderStatusResult` | 用于超时、状态冲突和对账。 |
| supplier penalty / waiver | `ProviderPenaltyResult` | 映射到 FeeDifferenceDecision 的供应商罚金或豁免项。 |
| provider conflict | `ProviderStateConflict` | 进入 ManualException 或 CompensationPending，不直接覆盖 Case。 |

ACL 规则：

- 原始供应商状态码、PNR 内部状态、外部错误文本不进入 PostSalesCase 核心字段，只作为脱敏审计摘要引用。
- 供应商动作必须有平台幂等键，避免重复取消、重复改签或重复重订。
- Unknown/Timeout 必须可查询、可重试、可人工接管，不得被映射为成功。
- 供应商规则和平台规则不一致时，Case 记录两个来源的结果并进入受控决策。

### Fare & Pricing 防腐

- Post Sales 使用 `EvaluateRefundRule`、`EvaluateChangeRule`、`EvaluateNoShowRule`、`EvaluateCompensationRule` 等平台契约。
- Fare & Pricing 返回规则版本、命中规则、金额明细和解释码；Post Sales 不复制公式。
- 人工减免或豁免不会改写 Fare & Pricing 规则，只在 `PostSalesDecisionRevised` 中记录人工审批。

### Payment 防腐

- Post Sales 只发送业务资金意图：`RequestRefund`、`CreatePaymentIntent(purpose=fare-difference/post-sales-charge)`、`CancelRefund` 或 `RetryRefund`。
- Payment 返回资金状态事件；Post Sales 不读取渠道原始回调、不处理签名、不对账。
- `RefundSettled` 不等于 `PostSalesApplied`；补差价 `PaymentCaptured` 也不等于新票已签发。

### Entitlement / Capacity 防腐

- Post Sales 通过命令请求作废、冻结、换发和库存释放/替换，不直接修改票证或库存状态。
- Entitlement 作废失败、Capacity 释放失败、Replacement Hold 失败均作为步骤结果进入 Case 状态机。
- 已作废票证不能因退款失败自动恢复；恢复必须由 Entitlement 的 replacement 或人工命令实现。

### 遗留服务防腐

当前 `ts-cancel-service`、`ts-rebook-service`、`ts-inside-payment-service`、`ts-order-service` / `ts-order-other-service` 混合了订单修改、退款规则、库存释放、改签和通知。迁移期需要 LegacyPostSalesACL：

- 把旧 `cancel` 请求映射为 `OpenPostSalesCase` + `RequestCancellation` 或 `RequestRefundByRule`。
- 把旧 `rebook` 请求映射为 `RequestChange` 或 `RequestRebook`，不再删除旧订单再创建新订单。
- 把旧退款规则“发车前 80%、发车后 0”迁移为 Fare & Pricing 规则结果；Post Sales 只冻结决策。
- 把旧订单状态 `CANCEL/CHANGE` 映射为售后结果事件，JourneyOrder 和 Entitlement 分别消费。
- 把旧高铁/普通车分流封装在 ACL 中，目标模型使用 Segment / Service Plan 属性。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-cancel-service` | 最大迁移对象之一。取消、退票、退款、通知和订单状态修改拆成 `PostSalesCase`、JourneyOrder 结果事件、Payment Refund、Entitlement Void、Capacity Release。旧接口可作为 Post Sales 入口兼容层。 |
| `ts-rebook-service` | 改签迁移为 `Change` / `Rebook` Case：先生成 ChangeOffer 和 FeeDifferenceDecision，再 Hold 新库存、处理补差价/退款、作废旧票、签发新票；禁止删除旧订单再创建新订单。 |
| `ts-inside-payment-service` | 不再计算退改规则或直接退款；只接收 Payment 的 `RequestRefund` / `CreatePaymentIntent`，余额和渠道状态归 Payment。 |
| `ts-payment-service` | 站外支付模拟纳入 Payment channel ACL；Post Sales 只消费 Payment 统一事件。 |
| `ts-order-service`, `ts-order-other-service` | 不再由 cancel/rebook 直接改 `CANCEL/CHANGE`；JourneyOrder 消费 `PostSalesApplied`、`JourneyCancelledByPostSales`、`ChangeApplied` 更新商业摘要。 |
| `ts-seat-service` | 取消/退票释放和改签新座位 Hold 迁移到 Capacity & Availability；Post Sales 只发起 Release/Hold 命令。 |
| `ts-travel-service`, `ts-travel2-service` | 改签目标车次查询不再由 rebook 直接拼接两套服务；通过 Trip Planning / Offer Management 生成目标 Itinerary 和 ChangeOffer。 |
| `ts-route-service`, `ts-train-service`, `ts-basic-service`, `ts-price-service` | 原改签中路线、车型、价格散算迁移到 Service Plan、Trip Planning、Fare & Pricing；Post Sales 消费规则和费用快照。 |
| `ts-execute-service` | 取票/进站状态拆到 Entitlement/Fulfillment；Post Sales 根据 Entitlement/Fulfillment 事件判断已取票、已登乘、已使用后的退改限制。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 下单编排不再承担后续改签重订；改签重订由 Post Sales + Booking Orchestration 处理，新订单项通过 JourneyOrder 结果事件展示。 |
| `ts-wait-order-service` | 候补兑现和售后释放库存通过 CapacityReleased / WaitlistTrigger 事件协作；Post Sales 不轮询 preserve。 |
| `ts-notification-service` | 从 cancel/rebook 同步调用迁移为消费 Post Sales 事件；通知失败不影响 Case 状态。 |
| `ts-admin-order-service` | 后台售后操作改为 Customer Service / Admin & Audit 发起受控 Post Sales 命令，禁止直接改订单和退款状态。 |
| `ts-security-service` | 售后风控信号接入 Risk & Compliance；高风险退改进入 `PendingApproval`。 |
| `ts-assurance-service`, `ts-food-service`, `ts-consign-service` | 附加服务随主行程退改的连带取消、退款或保留规则需要通过 Ancillary Service 提供状态和规则，Post Sales 只编排主行程与附加服务的联动 Case。 |
| `ts-ticket-office-service`, `ts-voucher-service` | 若涉及取票点、票据或补偿券，需通过 Entitlement、Fulfillment、Finance Settlement 或 Promotion/Wallet 的 ACL 接入，不直接成为 Post Sales 写模型。 |

迁移切片建议：

1. 先建立 `PostSalesCase` 只读时间线和 LegacyPostSalesACL，把旧 cancel/rebook 调用投影成 Case 事件。
2. 将退款规则从 `ts-cancel-service` / `ts-rebook-service` 抽到 Fare & Pricing，Post Sales 冻结 `PostSalesDecision`。
3. 将 Payment Refund 从 cancel/rebook 直接调用迁移为 `RequestRefund` 命令和 Payment 事件。
4. 将退票作废从订单状态改写迁移为 Entitlement `VoidEntitlement`。
5. 将库存释放和改签锁新库存迁移为 Capacity `ReleaseCapacityAfterVoid` / `HoldReplacementCapacity`。
6. 将改签改为 ChangeOffer + ExecutionPlan，不再删除旧订单。
7. 接入 Provider Integration ACL，处理供应商取消/改签/状态查询。
8. 最后让 JourneyOrder 只消费 Post Sales 结果事件，移除旧 `CANCEL/CHANGE` 直接写路径。

## 12. 验收标准

- 本 domain 的聚合所有权明确：`PostSalesCase`、`PostSalesDecision`、`PostSalesExecutionPlan` 属于 Post Sales；`JourneyOrder`、`PaymentIntent`、`Refund`、`Entitlement`、`CapacityHold`、`Offer`、Provider booking internals 不属于本上下文。
- 本 domain 发布和消费的事件明确：售后申请、资格判断、费用决策、审批、执行步骤、补偿、完成、失败均通过 Outbox/Inbox 幂等处理。
- 不变量和状态机没有依赖其他 domain 内部状态：Post Sales 只依赖订单、票证、支付、库存、履约、供应商的已发布事实或受控快照。
- 与 `Journey Order` 边界明确：Post Sales 不直接改商业订单核心不变量，只通过命令/事件请求订单状态变更或行程承诺调整。
- 与 `Fare & Pricing` 边界明确：Fare & Pricing 计算规则和费用，Post Sales 执行业务流程、冻结决策并编排 Case。
- 与 `Payment` 边界明确：Payment 执行资金收退，Post Sales 决定退款资格、原因、金额和补差价业务目的。
- 与 `Entitlement & Ticketing`、`Capacity & Availability`、`Booking Orchestration`、`Provider Integration` 的票证、库存、预订和供应商边界明确。
- 多 modal、联乘/中转、部分退改、供应商规则不一致、已出票/未出票/部分使用差异均已覆盖。
- 当前 `ts-cancel-service`、`ts-rebook-service`、`ts-inside-payment-service`、订单服务、库存服务和执行服务的迁移影响已说明。
