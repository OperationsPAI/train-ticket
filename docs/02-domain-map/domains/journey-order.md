# Journey Order Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Journey Order |
| Status | map-draft |
| Owner Agent | agentm-journey-order |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/domain-glossary.md`, `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/aggregate-model.md`, `docs/01-ddd-high-level/state-machines.md`, `docs/01-ddd-high-level/event-storming.md`, `docs/01-ddd-high-level/consistency-and-saga.md`, `docs/01-ddd-high-level/order-inventory-payment-model.md`, `docs/00-current-state/functional-recovery.md`, `docs/00-current-state/service-dependency-map.md` |

## 1. 领域目标

Journey Order 是用户视角的商业订单上下文，回答“用户买了什么、为谁买、多少钱、当前整单处于什么生命周期、用户下一步可以做什么”。它把 Offer 接受后的购买事实固化为 JourneyOrder，并为用户、客服、售后、通知和报表提供稳定的订单身份与订单时间线锚点。

它是独立边界，因为订单的商业事实与供应侧确认、库存锁定、支付渠道状态、票证权益、履约事实、退款执行是不同生命周期：

1. JourneyOrder 保护订单身份、Order Item、TravelerRef、金额汇总、生命周期状态和用户可见的商业承诺。
2. JourneyOrder 只保存外部上下文的引用和汇总结果，不拥有库存、支付、供应商预订、票证或退款渠道状态。
3. JourneyOrder 是售后入口和客服查询入口，但售后规则、资金执行、票证作废、供应商取消必须由对应上下文完成。
4. 第一阶段 Train Ticket 中，JourneyOrder 统一替代当前 `order` / `order-other` 的分裂订单模型，并把 `NOTPAID/PAID/COLLECTED/USED/CANCEL/CHANGE` 迁移为更明确的 JourneyOrder 生命周期。
5. 未来 General Travel 中，JourneyOrder 支持多 Segment、多交通方式、Transfer、Ancillary Service 与部分成功场景，但仍不把交通方式专有状态塞入订单聚合。

本文只形成领域地图草案，不直接创建或拆分 `project-index.yaml` 需求条目。

## 2. 边界

### In Scope

- 创建和维护 JourneyOrder 的全局订单身份、Account 引用、渠道引用、幂等创建键。
- 保存从 Offer 固化而来的订单快照：OfferId、OfferVersion、Journey 摘要、SegmentRef 列表、Price Snapshot、Rule Snapshot 摘要。
- 管理 Order Item：主 Segment 商品项、Ancillary Service 商品项、服务费、优惠、税费、可取消或已取消标记。
- 保存 TravelerRef：旅客 ID、脱敏证件引用、旅客与 Order Item / Segment 的绑定关系。
- 保护订单金额汇总：订单项、税费、费用、折扣、应付总额、已取消项影响后的商业汇总。
- 管理 JourneyOrder 自有状态机：Draft、PendingConfirmation、PendingPayment、Confirming、Confirmed、PartiallyConfirmed、InTravel、Completed、Cancelled、Disrupted、Failed。
- 接收 Booking、Payment、Entitlement、Fulfillment、Post Sales、Disruption 的领域事件，并用受控命令更新订单汇总状态。
- 发布订单商业事件，供 Booking Orchestration、Payment、Notification、Customer Service、Reporting 等上下文消费。
- 为用户订单列表、订单详情、客服时间线、售后入口和报表投影提供权威订单事实。
- 支持第一阶段 Train Ticket 单 Segment 或简单往返/改签场景，同时为未来 General Travel 多 Segment、Transfer 和部分成功场景预留模型。

### Out of Scope

- 不生成 Offer，不重新计算实时价格、余票或票规；这些属于 Offer Management、Fare & Pricing、Capacity & Availability。
- 不锁库存、不分配座位、不释放库存；这些属于 Booking Orchestration 和 Capacity & Availability。
- 不直接调用铁路、航司、大巴、船司、网约车等 Provider；供应商交互属于 Provider Integration 和 Booking Orchestration。
- 不拥有 SegmentBooking 状态机，不保存供应商 PNR、票号、原始状态码等供应侧内部事实。
- 不创建或推进 PaymentIntent / Refund 的渠道状态，不处理支付回调，不决定退款通道结果；这些属于 Payment。
- 不签发、作废、冻结、核验 Entitlement；这些属于 Entitlement & Ticketing。
- 不记录检票、登乘、到达、完成等履约事实；这些属于 Fulfillment。
- 不计算退改签规则、手续费、差价和补偿规则；这些属于 Post Sales、Fare & Pricing、Disruption Recovery。
- 不发送通知；Notification 消费订单事件并独立重试。
- 不直接修正报表或运营数据；Reporting 是只读投影，Admin & Audit 通过受控命令进入。

## 3. 统一语言补充

只补充 Journey Order 内部术语。跨域通用术语沿用 high-level glossary。

| Term | Definition | Notes |
|---|---|---|
| JourneyOrder | 用户接受 Offer 后形成的商业订单聚合根。 | 订单不等于 SegmentBooking、PaymentIntent 或 Entitlement。 |
| Order Item | JourneyOrder 内的收费或服务项。 | 可引用 Segment、Ancillary Service、Fee、Discount，不保存供应商内部状态。 |
| Order Line Binding | Order Item 与 TravelerRef、SegmentRef、AncillaryRef 的绑定关系。 | 支持多旅客、多段、多附加服务。 |
| Monetary Summary | JourneyOrder 内部的金额汇总值对象。 | 包含 itemSubtotal、taxTotal、feeTotal、discountTotal、payableTotal、currency；不代表支付已成功。 |
| Order Confirmation Condition | JourneyOrder 进入 Confirmed 所需的跨上下文事实集合。 | 由 JourneyOrder 根据外部事件汇总判断，不直接执行外部动作。 |
| Partial Order Decision | 多 Segment 或多旅客部分成功时用户或客服选择的处理决策。 | 如接受部分行程、取消剩余段、转人工、等待恢复方案。 |
| Order Timeline Anchor | 订单详情和客服时间线中的 JourneyOrder 锚点。 | 时间线可聚合多上下文事件，但写入仍回各自聚合。 |
| Commercial Cancellation | JourneyOrder 对整单或订单项商业取消的记录。 | 票证作废、库存释放、退款执行由其他上下文完成。 |
| Order Lifecycle State | JourneyOrder 自有生命周期状态。 | 只能表达整单商业汇总状态，不替代支付、票证、履约状态。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Offer Management | `OfferQuoted`、`OfferExpired`、Offer Detail / Price Snapshot、Rule Snapshot、Offer Item | JourneyOrder 必须基于有效 Offer 创建，并固化价格、规则、风险和有效期快照。 |
| Account | AccountId、渠道身份、登录态验证结果 | 订单必须归属可追溯账号或受控匿名渠道。 |
| Traveler Profile | TravelerRef、证件脱敏信息、旅客资格校验结果 | 订单保存旅客引用和订单项绑定，不保存完整证件生命周期。 |
| Risk & Compliance | 下单允许/拒绝/挑战结果、风控冻结或解除事件 | 阻断型风险会拒绝创建或阻止订单确认。 |
| Booking Orchestration | `SegmentReservationConfirmed`、`SegmentReservationFailed`、`SegmentBookingCancelled`、`SegmentBookingChanged`、BookingProgress 摘要 | JourneyOrder 汇总分段确认进度和部分成功状态。 |
| Payment | `PaymentIntentCreated`、`PaymentCaptured`、`PaymentFailed`、`PaymentExpired`、`PaymentLateSuccessDetected` | JourneyOrder 根据资金事实推进 PendingPayment、Confirming、Cancelled 或异常处理。 |
| Entitlement & Ticketing | `EntitlementIssued`、`EntitlementIssueFailed`、`EntitlementVoided`、`EntitlementSuspended` | JourneyOrder 汇总票证权益是否满足确认条件或是否受售后影响。 |
| Fulfillment | `FirstSegmentBoarded`、`SegmentCompleted`、`JourneyFulfillmentCompleted` | JourneyOrder 进入 InTravel 或 Completed 的事实来源。 |
| Post Sales | `PostSalesRequested`、`PostSalesApplied`、`JourneyCancelledByPostSales`、`OrderItemCancelledByPostSales` | 售后结果改变订单项或整单商业状态。 |
| Disruption Recovery | `JourneyAffectedByDisruption`、`ReaccommodationAccepted`、`DisruptionRefundApplied` | 异常影响和恢复结果决定 Disrupted、Confirming 或 Cancelled。 |
| Ancillary Service | `AncillaryServiceConfirmed`、`AncillaryServiceFailed`、`AncillaryServiceCancelled` | 附加服务作为 Order Item 的确认或取消摘要。 |
| Customer Service / Admin & Audit | 受控人工命令、审计原因、操作人 | 人工修复或例外必须通过命令进入 JourneyOrder。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Booking Orchestration | `JourneyOrderCreated`、`OrderItemsReadyForBooking`、`JourneyOrderCancelled`、Order Segment Plan | 创建订单后触发分段预订；取消订单后触发取消预订和释放资源。 |
| Payment | `JourneyOrderPendingPayment`、Payment Request Snapshot、`JourneyOrderCancelled` | 生成或取消待支付请求；Payment 不读取订单内部可变结构。 |
| Entitlement & Ticketing | Order Confirmation Snapshot、Traveler / Segment / Booking 引用 | 出票需知道订单、旅客、分段预订和金额条件是否满足，但不由订单直接出票。 |
| Post Sales | Order Snapshot、Order Item 状态、Traveler / Entitlement 引用、商业取消入口 | 售后需要读取订单可退改范围，执行规则后通过事件回写结果。 |
| Disruption Recovery | JourneyOrder Snapshot、SegmentRef 列表、TravelerRef、Connection Contract 引用 | 异常恢复识别受影响订单并生成恢复方案。 |
| Notification | `JourneyOrderCreated`、`JourneyOrderPendingPayment`、`JourneyOrderConfirmed`、`JourneyOrderCancelled`、`JourneyOrderDisrupted`、`JourneyCompleted` | 通知用户订单进展，但通知失败不回滚订单。 |
| Customer Service | OrderTimeline 投影、受控命令入口、状态摘要 | 客服查询和人工处理需要完整订单锚点。 |
| Reporting | JourneyOrder 事件流、OrderSummary 投影 | 统计销售、取消、转化、异常订单；Reporting 不反向修改订单。 |
| Finance Settlement | Order Commercial Snapshot、订单金额与订单项分类 | 收入确认和对账可读取订单商业事实，但资金事实以 Payment 为准。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| JourneyOrder | 订单必须来自有效 Offer 或受控人工命令；创建幂等键唯一；Order Item 金额、税费、费用、优惠必须汇总为 Monetary Summary；每个收费 Order Item 必须可追溯到 Segment、Ancillary Service 或 Fee 原因；TravelerRef 必须与 Order Item / Segment 绑定；Confirmed 不能早于必要 SegmentBooking、PaymentIntent、Entitlement 的确认事实；状态只能按 JourneyOrder 状态机推进；部分成功必须有 Partial Order Decision 或进入人工。 | CreateJourneyOrder、AttachSegmentBookingRefs、MarkPendingPayment、RecordPaymentCaptured、RecordPaymentFailed、RecordEntitlementIssued、ConfirmJourneyOrder、MarkPartiallyConfirmed、AcceptPartialOrderDecision、CancelJourneyOrder、MarkOrderItemCancelled、MarkInTravel、CompleteJourney、MarkDisrupted、MarkFailed、ApplyManualOrderAdjustment | JourneyOrderCreated、JourneyOrderBookingRefsAttached、JourneyOrderPendingPayment、JourneyOrderPaymentRecorded、JourneyOrderConfirmed、JourneyOrderPartiallyConfirmed、PartialOrderDecisionRecorded、JourneyOrderCancelled、OrderItemCancelled、JourneyOrderInTravel、JourneyCompleted、JourneyOrderDisrupted、JourneyOrderFailed、ManualOrderAdjustmentApplied |

### JourneyOrder 内部实体和值对象

| Type | Kind | Responsibility | Ownership Notes |
|---|---|---|---|
| OrderItem | Entity | 表达主 Segment、Ancillary Service、服务费、优惠等订单项。 | 由 JourneyOrder 拥有；只保存外部对象引用和商业快照。 |
| OrderLineBinding | Entity / Value Object | 绑定 OrderItem、TravelerRef、SegmentRef、AncillaryRef。 | 支持多旅客、多段、多附加服务。 |
| TravelerRef | Value Object | 保存旅客 ID、脱敏证件引用、旅客类型、资格校验版本。 | 完整旅客资料归 Traveler Profile。 |
| MonetarySummary | Value Object | 汇总币种、订单项小计、税费、费用、优惠、应付总额、已取消金额。 | 不表达 PaymentIntent 是否 Captured。 |
| OfferSnapshotRef | Value Object | 保存 OfferId、OfferVersion、quotedAt、expiresAt、ruleSnapshotId。 | 完整 Offer 归 Offer Management。 |
| SegmentOrderSnapshot | Value Object | 保存 SegmentRef、出发到达摘要、交通方式、计划时间、Connection Contract 引用。 | 不保存供应商确认号或库存状态。 |
| OrderLifecycle | Value Object | 当前状态、状态原因、最近推进事件、人工冻结标记。 | 状态变化通过 JourneyOrder 命令。 |
| ExternalRefs | Value Object Collection | 保存 SegmentBookingId、PaymentIntentId、EntitlementId、PostSalesCaseId、DisruptionCaseId 等引用。 | 只用于关联和读模型拼装。 |

### 第一阶段 Train Ticket 范围

- 一个 JourneyOrder 至少支持一个火车 Segment 和一个或多个 TravelerRef。
- Order Item 覆盖火车票主项、保险、餐饮、托运等当前附加服务引用，但附加服务确认与履约不由 JourneyOrder 执行。
- 高铁/动车和普通车不再分裂为两个订单聚合；车次类型是 SegmentOrderSnapshot 的字段或 Service Plan 属性。
- 当前 `NOTPAID` 映射为 PendingPayment，`PAID` 不能直接等于 Confirmed，`COLLECTED/USED` 由 Entitlement/Fulfillment 事实推动 InTravel/Completed，`CANCEL/CHANGE` 由 Post Sales 结果推动。

### 未来 General Travel 范围

- 一个 JourneyOrder 可包含多个 Segment、Transfer、Connection Contract、不同交通方式和多个 Ancillary Service。
- 支持 PartiallyConfirmed、Disrupted 和 Partial Order Decision，避免多段失败时只能整单失败。
- 支持后付、预授权、担保、企业支付等 Payment 条件，但 JourneyOrder 只记录确认条件和引用。
- 支持多币种或跨境税费时，MonetarySummary 必须按币种和结算规则明确拆分；跨币种结算细节仍归 Finance Settlement。

## 6. 状态机

JourneyOrder 拥有整单商业生命周期状态机。它不定义 PaymentIntent、SegmentBooking、CapacityHold、Entitlement、Refund 或 FulfillmentRecord 的状态。

| 状态 | 含义 | 用户/客服可见重点 |
|---|---|---|
| Draft | 内部草稿或人工创建中，用户尚未提交。 | 通常不对普通用户展示。 |
| PendingConfirmation | 订单已创建，正在确认分段预订或锁定库存。 | “正在确认行程/余票”。 |
| PendingPayment | 必要分段已确认或可担保，等待用户支付。 | 展示应付金额和支付截止时间。 |
| Confirming | 支付已满足，正在出票或确认最终权益。 | “支付成功，正在出票/确认权益”。 |
| Confirmed | 所有必要 Segment 已确认，必要 Entitlement 已签发。 | 展示票证入口和出行信息。 |
| PartiallyConfirmed | 多段、多旅客或附加服务部分成功，等待用户选择或补偿。 | 展示可保留部分、失败部分和下一步选择。 |
| InTravel | 至少一个 Segment 已开始履约，Journey 未完成。 | 展示进行中行程。 |
| Completed | 所有必需 Segment 完成。 | 展示已完成，可进入评价、发票或有限售后。 |
| Cancelled | 订单整体商业取消，必要释放和退款已进入流程或完成。 | 展示取消原因、退款/释放状态摘要。 |
| Disrupted | 订单受异常影响，等待恢复方案或售后处理。 | 展示异常说明和 Recovery Option。 |
| Failed | 订单无法完成且不能自动补偿，需要人工或终止。 | 展示失败原因和客服入口。 |

### 允许转换

| 当前状态 | 触发事件/命令 | 目标状态 | JourneyOrder 判断条件 |
|---|---|---|---|
| Draft | CreateJourneyOrder | PendingConfirmation | Offer 有效或人工命令合法，金额和旅客绑定通过。 |
| PendingConfirmation | AllSegmentReservationsConfirmed | PendingPayment | 所有必需 SegmentBooking 满足支付前确认条件，且需要支付。 |
| PendingConfirmation | NoPaymentRequired | Confirming | 规则允许 0 元、后付或担保且可进入出票确认。 |
| PendingConfirmation | SomeSegmentReservationFailed | PartiallyConfirmed | 至少一个必要或可选部分失败，需要 Partial Order Decision。 |
| PendingConfirmation | AllSegmentReservationsFailed | Failed | 无可保留行程且无法自动重试。 |
| PendingPayment | PaymentCaptured | Confirming | 对应 PaymentIntent 已 Captured 或支付条件满足。 |
| PendingPayment | PaymentFailed | PendingPayment | 允许用户重试，支付截止时间未过。 |
| PendingPayment | PaymentExpired | Cancelled | 支付超时，订单取消并触发释放。 |
| Confirming | EntitlementsIssued | Confirmed | 所有必要 Entitlement 已 Issued 且无阻断风险。 |
| Confirming | SomeEntitlementIssueFailed | PartiallyConfirmed | 部分出票失败且可补偿或等待选择。 |
| Confirmed | FirstSegmentBoarded | InTravel | Fulfillment 表明至少一个 Segment 已开始。 |
| Confirmed | JourneyCancelledByPostSales | Cancelled | Post Sales 已完成整单取消的业务结果。 |
| Confirmed | DisruptionAffectsJourney | Disrupted | Disruption Recovery 标记订单受影响。 |
| InTravel | AllSegmentsCompleted | Completed | Fulfillment 表明所有必需 Segment 完成。 |
| InTravel | DisruptionAffectsJourney | Disrupted | 行程中发生异常影响后续履约。 |
| Disrupted | RecoveryAccepted | Confirming | 用户或规则选择恢复方案，需重新确认权益。 |
| Disrupted | DisruptionRefundApplied | Cancelled | 异常退款或取消方案已应用。 |
| PartiallyConfirmed | UserAcceptsPartialJourney | Confirming | 用户接受部分行程或补偿方案。 |
| PartiallyConfirmed | UserCancelsPartialJourney | Cancelled | 用户取消部分成功订单，补偿流程已触发。 |
| PartiallyConfirmed | CompensationFailed | Failed | 补偿失败且需要人工或终止。 |

### 禁止转换

| 禁止转换 | 原因 |
|---|---|
| PendingPayment -> Confirmed | 必须先确认支付事实和出票/权益事实。 |
| Confirmed -> PendingPayment | 已确认订单不能回到待支付；补差价应创建新的 PaymentIntent。 |
| Completed -> Cancelled | 已完成 Journey 不能整体取消，只能走 Compensation、争议或人工。 |
| Failed -> Confirmed | 失败后若恢复，应通过人工恢复或新确认流程产生可审计事件。 |
| Cancelled -> Confirmed | 取消后晚到支付或晚到出票必须进入 LatePaymentCase / ProviderConflictCase，不直接确认。 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| CreateJourneyOrder | JourneyOrder | JourneyOrderCreated | accountId + offerId + clientRequestId |
| AttachSegmentBookingRefs | JourneyOrder | JourneyOrderBookingRefsAttached | orderId + segmentId + travelerId + bookingId |
| MarkPendingPayment | JourneyOrder | JourneyOrderPendingPayment | orderId + paymentPurpose + paymentAttempt |
| RecordPaymentCaptured | JourneyOrder | JourneyOrderPaymentRecorded | orderId + paymentIntentId + channelTxnId |
| RecordPaymentFailed | JourneyOrder | JourneyOrderPaymentFailedRecorded | orderId + paymentIntentId + failureEventId |
| RecordPaymentExpired | JourneyOrder | JourneyOrderPaymentExpiredRecorded | orderId + paymentIntentId + expiryEventId |
| RecordEntitlementIssued | JourneyOrder | JourneyOrderEntitlementRecorded | orderId + segmentBookingId + entitlementId |
| RecordEntitlementIssueFailed | JourneyOrder | JourneyOrderEntitlementIssueFailedRecorded | orderId + segmentBookingId + issueAttempt |
| ConfirmJourneyOrder | JourneyOrder | JourneyOrderConfirmed | orderId + confirmationAttempt |
| MarkPartiallyConfirmed | JourneyOrder | JourneyOrderPartiallyConfirmed | orderId + partialReason + sourceEventId |
| AcceptPartialOrderDecision | JourneyOrder | PartialOrderDecisionRecorded | orderId + decisionId |
| CancelJourneyOrder | JourneyOrder | JourneyOrderCancelled | orderId + cancelReason + requesterId |
| MarkOrderItemCancelled | JourneyOrder | OrderItemCancelled | orderId + orderItemId + postSalesCaseId |
| MarkDisrupted | JourneyOrder | JourneyOrderDisrupted | orderId + disruptionCaseId |
| MarkInTravel | JourneyOrder | JourneyOrderInTravel | orderId + firstFulfillmentEventId |
| CompleteJourney | JourneyOrder | JourneyCompleted | orderId + completionEventId |
| MarkFailed | JourneyOrder | JourneyOrderFailed | orderId + failureReason + sourceEventId |
| ApplyManualOrderAdjustment | JourneyOrder | ManualOrderAdjustmentApplied | orderId + auditCommandId |

### 事件发布要求

- JourneyOrder 写状态和 OutboxEvent 必须在同一本地事务内完成。
- 事件必须包含 eventId、occurredAt、orderId、accountId、sourceCommandId、causationId、correlationId、schemaVersion。
- 跨上下文消费者必须使用 Inbox 幂等处理，不得根据重复事件重复出票、扣款或退款。
- JourneyOrder 事件使用过去式表达事实，不使用 `CreateOrder`、`PayDone` 等命令式或模糊名称。

## 8. 策略和 Saga 参与点

Journey Order 不是跨域 Saga 的唯一拥有者。它参与 Saga，保护订单聚合状态，并通过事件驱动其他上下文。

### 下单、占座、支付、出票 Saga

| Saga Step | Journey Order Role | Cross-Context Collaboration |
|---|---|---|
| Offer 接受 | 处理 CreateJourneyOrder，固化 Offer 快照和 MonetarySummary。 | 消费 Offer Management 的有效 Offer。 |
| 触发分段预订 | 发布 JourneyOrderCreated / OrderItemsReadyForBooking。 | Booking Orchestration 创建 SegmentBooking，Capacity & Availability 执行 Hold。 |
| 进入待支付 | 根据 SegmentReservationConfirmed 汇总，执行 MarkPendingPayment。 | Payment 创建 PaymentIntent；Payment 不直接修改订单。 |
| 支付成功 | 消费 PaymentCaptured，记录支付条件满足，进入 Confirming。 | Booking Orchestration 确认 Hold，Entitlement & Ticketing 出票。 |
| 出票成功 | 消费 EntitlementIssued，执行 ConfirmJourneyOrder。 | Notification 发送确认通知；Reporting 投影销售事实。 |
| 失败补偿 | 标记 PartiallyConfirmed、Cancelled 或 Failed。 | Booking/Capacity/Payment/Entitlement/Post Sales 按各自补偿执行。 |

### 取消未支付 Saga

- 用户、超时任务或客服对 JourneyOrder 发起 CancelJourneyOrder。
- JourneyOrder 只记录商业取消事实并发布 JourneyOrderCancelled。
- Booking Orchestration 取消 SegmentBooking，Capacity 释放 Hold，Payment 取消 PaymentIntent，Notification 发送取消通知。
- 未支付取消不得创建 Refund；若后续收到 PaymentCaptured，Payment 创建 LatePaymentCase 或发布晚到事件，JourneyOrder 不直接确认。

### 已出票退票和退款 Saga

- JourneyOrder 提供 Order Snapshot 和可售后 Order Item 引用，Post Sales 创建 PostSalesCase。
- Post Sales 决定规则、手续费和应退金额；Payment 只执行 Refund。
- EntitlementVoided、SegmentBookingCancelled、RefundRequested/Settled 等事件投影到 OrderTimeline。
- JourneyOrder 根据 PostSalesApplied 执行 MarkOrderItemCancelled 或 CancelJourneyOrder。
- 票证作废成功但退款失败时，JourneyOrder 不恢复票证，只展示退款异常摘要和客服入口。

### 改签 Saga

- JourneyOrder 提供原 Order Item、TravelerRef、SegmentRef 和 Entitlement 引用。
- Post Sales 负责改签规则和差价，Offer Management 生成 Change Offer，Booking Orchestration 锁新 Segment。
- JourneyOrder 不删除旧订单再创建新订单；应记录 Order Item 替换关系和 PostSalesCase 引用。
- 新旧票切换完成后，Post Sales 发布 ChangeApplied，JourneyOrder 更新订单项和商业摘要。

### 异常恢复 Saga

- Disruption Recovery 识别受影响 JourneyOrder 后发布 JourneyAffectedByDisruption。
- JourneyOrder 执行 MarkDisrupted，暂停普通确认或展示特殊售后入口。
- 用户接受 Recovery Option 后，JourneyOrder 根据 ReaccommodationAccepted / DisruptionRefundApplied 回到 Confirming 或 Cancelled。
- 非保障联乘不得由 JourneyOrder 自动承诺平台兜底；Connection Contract 由 Transfer Management / Offer 快照提供。

### 通知、客服、报表策略

- Notification 消费 JourneyOrder 事件并发送消息；发送失败只影响 NotificationTask，不回滚订单。
- Customer Service 通过 OrderTimeline 和受控命令处理人工例外，不直接改库。
- Reporting 消费 JourneyOrder 事件和读模型，做销售、取消、转化和异常分析；Reporting 不反向修正交易状态。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| OrderSummary | JourneyOrderCreated、JourneyOrderPendingPayment、JourneyOrderConfirmed、JourneyOrderCancelled、JourneyOrderDisrupted、JourneyCompleted、OrderItemCancelled | 用户订单列表、客服订单检索、Reporting。 |
| OrderDetail | JourneyOrder 事件、Offer Snapshot、SegmentBooking 摘要、Payment 摘要、Entitlement 摘要、PostSales 摘要 | 用户订单详情、客服详情页。 |
| OrderTimeline | JourneyOrder、Booking、Capacity、Payment、Entitlement、Fulfillment、PostSales、Disruption、Notification 相关事件 | 客服、用户详情、人工兜底。 |
| OrderPaymentSummary | JourneyOrderPendingPayment、PaymentIntentCreated、PaymentCaptured、PaymentFailed、PaymentExpired、RefundRequested、RefundSettled | 用户支付页、客服资金状态查看；资金权威仍是 Payment。 |
| OrderEntitlementSummary | EntitlementIssued、EntitlementVoided、EntitlementSuspended、SegmentTicketed | 票证入口、用户详情、客服核验入口；票证权威仍是 Entitlement。 |
| OrderPostSalesEntryView | JourneyOrderConfirmed、OrderItemCancelled、PostSalesRequested、PostSalesApplied、Disruption events | 用户退改入口、客服售后入口。 |
| PartialOrderDecisionView | JourneyOrderPartiallyConfirmed、PartialOrderDecisionRecorded、RecoveryOption events | 多段部分成功、异常恢复、人工处理。 |
| CommercialOrderMetrics | JourneyOrderCreated、JourneyOrderConfirmed、JourneyOrderCancelled、JourneyCompleted、JourneyOrderFailed | Reporting、运营、财务分析。 |

读模型可以冗余跨上下文状态，但不得把汇总结果写回 JourneyOrder 或其他聚合。任何修正必须转化为受控命令。

## 10. 外部系统和防腐层

Journey Order 正常情况下不直接对接外部供应商、支付渠道或通知渠道。它需要的防腐主要是上下文契约防腐和遗留服务防腐。

| External / Legacy Boundary | ACL Need | Journey Order Design |
|---|---|---|
| 当前 `ts-order-service` / `ts-order-other-service` | 高铁/普通车订单模型重复且字段/ID 行为漂移。 | 迁移时通过 LegacyOrderACL 映射为 JourneyOrder、OrderItem、TravelerRef、MonetarySummary。 |
| 当前 `ts-preserve-service` / `ts-preserve-other-service` | 同步大编排混合订单、库存、附加服务和通知。 | 新 JourneyOrder 只接收 CreateJourneyOrder 命令；编排职责迁出到 Booking Orchestration Saga。 |
| 当前 `ts-inside-payment-service` / `ts-payment-service` | 支付服务直接读取和修改订单状态。 | PaymentACL 将旧支付结果映射为 PaymentCaptured/Failed/Expired 事件，JourneyOrder 不接收直接状态写入。 |
| 当前 `ts-cancel-service` / `ts-rebook-service` | 取消、退款、改签规则和订单修改耦合。 | PostSalesACL 将旧 cancel/rebook 请求转为 PostSalesCase，JourneyOrder 只消费结果事件。 |
| 当前 `ts-execute-service` | 取票、进站直接改订单状态。 | Fulfillment / Entitlement ACL 将 COLLECTED/USED 映射为 Entitlement 和 Fulfillment 事件，再投影到 JourneyOrder。 |
| Provider Integration | 供应商 PNR、铁路票号、状态码不应污染订单。 | JourneyOrder 只保存 ProviderReference 的业务引用摘要；原始报文留在 Provider Integration / SegmentBooking。 |
| Payment Channel | 渠道交易号、回调签名、退款通道状态不属于订单。 | JourneyOrder 只记录 PaymentIntentId 和支付满足事实。 |
| Notification Channel | 邮件、短信、站内信发送失败不影响订单事实。 | NotificationTask 独立重试；JourneyOrder 只发布事件。 |

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-order-service`, `ts-order-other-service` | 合并为 JourneyOrder 写模型的迁移来源。订单 ID 生成、状态枚举、查询接口需要统一；供应侧字段拆到 SegmentBooking / Entitlement。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 从“同步订票大编排”拆分为 CreateJourneyOrder 入口和 Booking Orchestration Saga；保险、餐饮、托运不再在订单创建链路中隐式失败。 |
| `ts-seat-service` | 不再从订单反推库存或分配座位给订单；JourneyOrder 只保存 Capacity/SegmentBooking 引用。 |
| `ts-inside-payment-service`, `ts-payment-service` | 不能直接修改订单状态；支付结果通过 Payment 事件驱动 JourneyOrder。余额、站外支付、退款流水归 Payment。 |
| `ts-cancel-service` | 取消入口迁移到 Post Sales；JourneyOrder 只处理未支付商业取消和售后结果汇总。 |
| `ts-rebook-service` | 改签迁移为 PostSalesCase + Change Offer + Replacement SegmentBooking；不再删除旧订单再创建新订单。 |
| `ts-execute-service` | 取票、进站迁移到 Entitlement & Ticketing / Fulfillment；JourneyOrder 只根据履约事件进入 InTravel/Completed。 |
| `ts-security-service` | 风控结果作为创建和确认订单的输入；不直接拥有订单状态。 |
| `ts-contacts-service`, `ts-user-service`, `ts-auth-service` | 旅客和账号拆为 Account / Traveler Profile；JourneyOrder 保存 TravelerRef 和 AccountId，不保存完整账号认证信息。 |
| `ts-assurance-service` | 保险作为 Ancillary Service / Insurance Policy 的 Order Item 引用；确认失败不应污染主票订单状态。 |
| `ts-food-service`, `ts-food-delivery-service`, `ts-train-food-service`, `ts-station-food-service` | 餐饮作为 Ancillary Service Order Item；其配送和履约独立确认，订单只展示摘要。 |
| `ts-consign-service`, `ts-consign-price-service` | 托运作为 Ancillary Service Order Item；计价规则归 Fare & Pricing 或 Ancillary Service。 |
| `ts-wait-order-service` | 候补成功后可创建 JourneyOrder 或补全 SegmentBooking；候补排队本身不属于 JourneyOrder。 |
| `ts-admin-order-service` | 后台订单代理迁移为 Customer Service / Admin & Audit 的受控查询和命令入口。 |
| `ts-notification-service` | 改为消费 JourneyOrder 事件；删除订单链路中的同步通知假设。 |

## 12. Reduce 阶段问题

| Type | Description | Related Contexts | Proposed Resolution |
|---|---|---|---|
| Open Question | PaymentIntent 创建命令由 JourneyOrder 直接触发，还是统一由 Booking Orchestration Saga 在收到 `JourneyOrderPendingPayment` 后触发？高层文档中既有 JourneyOrder 下游 Payment，也有 Booking Orchestration 下游 Payment 的描述。 | Journey Order, Booking Orchestration, Payment | Reduce 阶段统一：建议 JourneyOrder 发布待支付商业事实，Saga/Booking Orchestration 或 Payment Open Host Service 创建 PaymentIntent，JourneyOrder 不直接调用支付渠道。 |
| Open Question | Ancillary Service 失败是否影响 JourneyOrder 主状态，第一阶段保险/餐饮/托运失败历史上返回“主票成功但附加失败”。 | Journey Order, Ancillary Service, Booking Orchestration, Notification | 建议主 Segment 必要项与可选 Ancillary 分级；可选附加失败记录 Order Item 失败摘要，不阻断主票 Confirmed。 |
| Open Question | Train Ticket 第一阶段是否需要实现 PartiallyConfirmed，还是只在模型中预留？ | Journey Order, Booking Orchestration, Post Sales | 建议第一阶段至少在状态机和读模型支持 PartiallyConfirmed；UI/自动决策可先降级为取消或人工处理。 |
| Conflict | 当前服务依赖图第 34-37 行对 `order` / `order-other` 和 `preserve` / `preserve-other` 的普通车/高铁描述与功能恢复文档第 35-36 行、第 147-150 行存在命名对应不一致。 | Current State, Journey Order, Booking Orchestration | Reduce 阶段以代码扫描或接口样例确认真实对应关系；JourneyOrder 目标设计不继承这两个服务名的业务含义。 |
| Open Question | 已完成 Journey 的售后是否允许从 Completed 转为其他商业状态，还是只创建 Compensation / Dispute？ | Journey Order, Post Sales, Customer Service, Payment | 建议 JourneyOrder 保持 Completed，不整体转 Cancelled；争议、补偿、发票更正通过 PostSalesCase / Customer Service 事件投影。 |

## 13. 验收标准

- JourneyOrder 的聚合所有权明确：订单身份、Order Item、TravelerRef、MonetarySummary、生命周期状态属于 Journey Order。
- JourneyOrder 不拥有库存锁定、PaymentIntent 渠道状态、Entitlement 生命周期、SegmentBooking 供应商确认、Refund 渠道状态或 Fulfillment 事实。
- JourneyOrder 发布和消费的事件明确，且跨上下文事件通过 Outbox/Inbox 幂等处理。
- JourneyOrder 状态机只表达整单商业汇总状态，不依赖其他上下文内部状态字段，只依赖已发布事实。
- JourneyOrder 与 Offer Management、Booking Orchestration、Payment、Entitlement & Ticketing、Post Sales、Disruption Recovery、Notification、Customer Service、Reporting 的协作契约明确。
- 第一阶段 Train Ticket 范围能统一 `order` / `order-other`，并解释 `NOTPAID/PAID/COLLECTED/USED/CANCEL/CHANGE` 的迁移关系。
- 未来 General Travel 多 Segment、Transfer、Ancillary Service 和 PartiallyConfirmed 已预留，但没有污染第一阶段最小模型。
- 与 high-level 设计或当前状态文档的冲突和开放问题仅记录在本文件第 12 节。
