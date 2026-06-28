# Booking Orchestration Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Booking Orchestration |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-booking-orchestration |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/domain-glossary.md`, `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/aggregate-model.md`, `docs/01-ddd-high-level/state-machines.md`, `docs/01-ddd-high-level/event-storming.md`, `docs/01-ddd-high-level/consistency-and-saga.md`, `docs/01-ddd-high-level/order-inventory-payment-model.md`, `docs/01-ddd-high-level/acl-provider-contracts.md`, `docs/01-ddd-high-level/general-travel-ddd.md`, `docs/02-domains/journey-order.md`, `docs/02-domains/capacity-availability.md`, `docs/02-domains/payment.md`, `docs/02-domains/entitlement-ticketing.md`, `docs/02-domains/offer-management.md` |

## 1. 领域目标

Booking Orchestration 是 General Travel 交易主链路中的预订执行编排域，负责把已经创建的 `JourneyOrder` 和已接受的 `Offer` 转化为每个 `Segment` 的可追踪供应侧预订结果。它关注“如何把一次端到端行程分解为可执行的分段预订、如何协调库存/供应商/支付/出票、以及失败后如何补偿或降级”。

本 domain 的核心业务事实是 `SegmentBooking`：每一段火车、大巴、网约车、飞机或轮船服务是否已经请求预订、是否获得内部或外部供应确认、是否进入出票、是否取消、变更、失败或完成。`JourneyOrder` 保护商业订单与用户承诺；Booking Orchestration 保护预订执行事实和 Saga 可恢复性。

它独立存在的原因：

1. `JourneyOrder` 不能直接承载供应商确认、外部超时、补偿重试和多段部分成功，否则订单状态会再次变成大杂烩。
2. `Capacity & Availability` 只判断可售性、创建 Hold、确认/释放库存；它不决定多段预订顺序、失败是否保留部分行程或如何跨供应商补偿。
3. `Provider Integration` 只做供应商防腐、调用、状态查询和事件映射；它不拥有平台级 `SegmentBooking` 聚合与跨段 Saga。
4. `Payment` 只表达资金事实；`PaymentCaptured` 不等于预订完成，出票失败或供应商晚确认仍需要 Booking Saga 处理。
5. General Travel 的联乘、换乘和跨方式组合要求分段执行、分段降级、分段补偿和整单汇总分开建模。

第一阶段 Train Ticket 范围中，本 domain 替代当前 `ts-preserve-service` / `ts-preserve-other-service` 的同步大编排，把订票入口拆成：创建 `SegmentBooking`、请求 `CapacityHold`、处理供应确认、等待支付、确认 Hold、触发出票、处理失败补偿。未来 General Travel 范围中，它统一支持火车、飞机、大巴、轮船、网约车以及跨方式组合行程的分段预订 Saga。

## 2. 边界

### In Scope

- 创建和维护 `SegmentBooking` 聚合：绑定 `JourneyOrderId`、`OfferItemRef`、`SegmentRef`、`TravelerRef`、`CapacityHoldId`、`ProviderReference` 和预订状态。
- 处理 `ReservationRequest`：从订单和 Offer 快照生成每个 Segment 的预订请求，携带幂等键、超时策略、供应能力矩阵和补偿策略。
- 管理 booking execution Saga：分段请求、内部 Hold、供应商 Reserve/Confirm、支付等待、Hold 确认、出票触发、订单确认反馈。
- 支持确认、部分确认和失败结果：`AllSegmentReservationsConfirmed`、`JourneyPartiallyConfirmed`、`SegmentReservationFailed`、`BookingSagaFailed` 等事实。
- 处理 `Compensation`：取消已确认 Segment、释放 Hold、取消供应商预订、请求退款、冻结或取消后续出票、进入人工。
- 处理 cross-supplier booking `Saga`：多供应商、多交通方式、多 Segment 的顺序、并发、依赖和回滚策略。
- 管理幂等：下单重复提交、供应商重复回调、超时后查询恢复、补偿重复执行不能造成重复预订、重复锁座、重复出票或重复退款请求。
- 管理超时：库存 Hold 超时、供应商预订超时、支付超时、出票超时、补偿超时和对账恢复。
- 发布 Booking 领域事件并维护 `BookingProgress`、`SegmentBookingTimeline`、`BookingSagaMonitor` 等读模型。
- 对接 Provider Integration 的统一供应商契约，消费 `ProviderReservationResult` / `ProviderStatusResult`，但不直接依赖供应商原始状态码。
- 支持 connected / transfer / multimodal 场景：火车 + 火车、火车 + 大巴、飞机 + 火车、轮船 + 大巴、网约车首末段接驳等组合的分段编排和失败降级。

### Out of Scope

- 不拥有 `JourneyOrder` 的商业订单、价格汇总、订单项、用户承诺和整单生命周期状态。
- 不生成 Offer，不冻结价格、规则、可售性或风险披露；这些属于 Offer Management。
- 不维护库存快照、库存冲突判断、区间座席算法、配额和 Hold 内部状态；这些属于 Capacity & Availability。本 domain 只消费 `Availability`、`Reservation`、`CapacityHeld`、`CapacityHoldConfirmed`、`CapacityReleased` 等结果。
- 不处理支付渠道、扣款、退款渠道执行、晚到支付资金异常；这些属于 Payment。本 domain 只消费资金事件或发起明确的资金请求。
- 不签发、作废、冻结或核验 Entitlement；这些属于 Entitlement & Ticketing。本 domain 只在条件满足时发出 `IssueEntitlement` 命令，并消费出票结果。
- 不实现供应商 API、签名、重试、限流、熔断、原始报文解析和状态码映射；这些属于 Provider Integration。
- 不计算退改签规则、手续费、差价和售后资格；这些属于 Post Sales、Fare & Pricing、Disruption Recovery。
- 不拥有 Transfer 风险评估、Connection Contract 定义或错过接续事实；这些属于 Transfer Management / Disruption Recovery。本 domain 只根据这些契约选择预订顺序与补偿策略。
- 不发送通知；Notification 消费 Booking/Journey/Payment/Ticketing 事件并独立重试。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| SegmentBooking | 一段 `Segment` 的平台预订执行聚合根，表达供应侧确认进度和执行结果。 | 不代表整单商业成功，也不代表 Entitlement 已签发。 |
| ReservationRequest | 对内部 Capacity 或外部 Provider 发起的一次预留/预订请求。 | 由 `JourneyOrderId + segmentId + travelerId + requestPurpose` 幂等。 |
| BookingPlan | Booking Saga 为一个 Journey 生成的分段执行计划。 | 包含 Segment 顺序、并发组、依赖、超时、补偿策略和保护等级。 |
| BookingSaga | 持久化流程管理器，协调多个 SegmentBooking、CapacityHold、PaymentIntent 和 Entitlement。 | Saga 不拥有业务事实，只记录编排步骤、等待点和恢复动作。 |
| BookingAttempt | 对同一个 SegmentBooking 的一次执行尝试。 | 用于区分初次预订、重试、状态查询恢复、人工恢复。 |
| Provider Booking Handle | Provider Integration 返回的供应商预订引用，如 confirmationNo、PNR、派单号、船票预订号。 | 原始状态留在 Provider Integration，Booking 只保存归一化引用。 |
| Reservation Outcome | 预订结果：Confirmed、Holding、Rejected、Timeout、Unknown、Failed。 | Timeout/Unknown 必须查询最终状态，不能盲目重复创建。 |
| Partial Booking Outcome | 多段或多旅客中部分 Segment 成功、部分失败的结果。 | 需要 JourneyOrder、用户决策、Post Sales 或人工兜底协作。 |
| Booking Compensation | 为收敛失败 Saga 而发起的受控补偿动作。 | 包括 ReleaseHold、CancelProviderReservation、CancelPaymentIntent、RequestRefund、SuspendEntitlement。 |
| Compensation Plan | 针对已成功步骤的逆向或替代处理计划。 | 每一步必须幂等、可重试、可观察。 |
| Booking Timeout | 编排步骤超过 SLA 的事实。 | 不等于失败；外部供应商超时常映射为 Unknown 并进入查询恢复。 |
| Supplier Capability Matrix | 某供应商支持 Reserve、Confirm、Issue、Cancel、Change、QueryStatus 等能力的声明。 | Booking 根据能力选择一阶段或二阶段预订流程。 |
| Booking Idempotency Key | 防止重复执行的业务键。 | 覆盖请求、回调、查询恢复、补偿和人工命令。 |
| Degraded Booking | 某段或某能力失败后，按规则保留可用部分、转人工或提供替代路径。 | 常见于多段联乘、可选接驳、可选附加服务。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Journey Order | `JourneyOrderCreated`、`OrderItemsReadyForBooking`、`JourneyOrderCancelled`、Order Segment Plan、TravelerRef、Order confirmation policy | 触发分段预订、取消预订、汇总执行结果；但订单商业状态仍由 JourneyOrder 拥有。 |
| Offer Management | AcceptedOfferView、OfferItemRef、SegmentRef、AvailabilitySnapshotRef、PriceGuaranteeLevel、RuleSnapshotRef、RiskDisclosure result | 构造 BookingPlan 和 ReservationRequest；Offer 快照不是 Hold，只是下单输入。 |
| Capacity & Availability | `CapacityHeld`、`CapacityHoldFailed`、`CapacityHoldConfirmed`、`CapacityReleased`、`CapacityHoldExpired`、Availability evidence | 内部库存预留、确认和释放结果；Booking 不判断库存内部冲突。 |
| Provider Integration | `ProviderReservationResult`、`ProviderReservationConfirmed`、`ProviderReservationFailed`、`ProviderReservationTimeout`、`ProviderStatusResult`、capability matrix | 外部供应确认、失败、超时和最终状态查询。 |
| Payment | `PaymentIntentCreated`、`PaymentCaptured`、`PaymentFailed`、`PaymentIntentExpired`、`LatePaymentDetected` | 资金事实决定是否继续 ConfirmHold、出票或补偿；Payment 不直接改 Booking。 |
| Entitlement & Ticketing | `EntitlementIssued`、`EntitlementIssueFailed`、`EntitlementVoided` | 出票结果推进 SegmentBooking 到 Ticketed 或触发补偿。 |
| Post Sales | `CancelSegmentBooking`、`ChangeSegmentBooking`、`PostSalesApproved`、replacement booking request | 售后执行需要取消旧 SegmentBooking 或创建 replacement SegmentBooking。 |
| Transfer Management | Connection Contract、TransferRiskEvaluated、protected/self-transfer 标记 | 决定多段预订顺序、部分失败是否可接受和补偿责任。 |
| Disruption Recovery | `RecoveryOptionAccepted`、`DisruptionAffectsSegment`、reaccommodation booking request | 异常恢复可能触发保护性改乘、取消原段或新建替代 SegmentBooking。 |
| Risk & Compliance | booking allow/deny/challenge、duplicate trip warning、fraud hold/release | 阻断风险会拒绝或暂停预订；解除风险后可恢复 Saga。 |
| Customer Service / Admin & Audit | 受控人工恢复、人工取消、人工确认最终状态命令 | 自动恢复失败时进入人工，但必须保留审计和幂等。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Journey Order | `SegmentReservationRequested`、`SegmentReservationConfirmed`、`SegmentReservationFailed`、`AllSegmentReservationsConfirmed`、`JourneyPartiallyConfirmed`、`BookingSagaFailed`、BookingProgress | 订单汇总分段确认、部分确认、失败和用户下一步动作。 |
| Capacity & Availability | `HoldCapacity`、`ConfirmHold`、`ReleaseHold`、`HoldReplacementCapacity`、`ReleaseOriginalAfterChange` | 交易链路对库存写入的受控入口。 |
| Provider Integration | `ReserveSegment`、`ConfirmReservation`、`CancelReservation`、`ChangeReservation`、`QueryReservationStatus` | 外部供应商预订、确认、取消、改签和状态查询。 |
| Payment | `CreatePaymentIntent`、`CancelPaymentIntent`、`RequestRefund`、`CaptureAuthorizedPayment` | 付款、取消未完成付款、失败补偿退款或预授权扣款。 |
| Entitlement & Ticketing | `IssueEntitlement`、`SuspendEntitlement`、ticketing context references | 条件满足后触发出票；异常或冲突时请求冻结。 |
| Post Sales | `SegmentBookingCancelled`、`SegmentBookingChanged`、`CompensationRequired`、`ProviderCancellationFailed` | 售后继续推进、退款或人工处理。 |
| Transfer Management / Disruption Recovery | `SegmentBookingFailed`、`ConnectionSensitiveSegmentDelayed`、`ReplacementBookingConfirmed` | 联乘失败、保护性改乘和恢复方案需要 Booking 结果。 |
| Notification | `BookingProgressChanged`、`SegmentReservationFailed`、`JourneyPartiallyConfirmed` | 通知用户确认中、失败、部分成功或需要选择。 |
| Customer Service | Booking timeline、Saga monitor、Provider reconciliation view | 客服解释预订过程、供应商差异和人工兜底。 |
| Reporting | Booking success/failure/timeout/compensation events | 统计下单成功率、供应商可靠性、超时率和补偿成本。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `SegmentBooking` | 一个 SegmentBooking 只对应一个 `JourneyOrderId + SegmentRef + TravelerRef + bookingPurpose`；供应商确认号与平台 bookingId 必须幂等映射；Confirmed 后不能被重复确认到另一个 ProviderReference；Ticketed 前不表示可履约；取消、变更、失败必须保留原因和来源事件；外部 Unknown 状态不能被当作失败或成功静默处理。 | `RequestSegmentReservation`、`MarkCapacityHolding`、`ConfirmSegmentReservation`、`FailSegmentReservation`、`AttachProviderReference`、`MarkProviderReservationTimeout`、`MarkSegmentTicketed`、`CancelSegmentBooking`、`MarkSegmentBookingCancelled`、`ChangeSegmentBooking`、`MarkSegmentBookingChanged`、`MarkSegmentCompleted`、`ApplyManualBookingCorrection` | `SegmentReservationRequested`、`SegmentCapacityHolding`、`SegmentReservationConfirmed`、`SegmentReservationFailed`、`ProviderReferenceAttached`、`ProviderReservationTimedOut`、`SegmentTicketed`、`SegmentBookingCancelRequested`、`SegmentBookingCancelled`、`SegmentBookingChangeRequested`、`SegmentBookingChanged`、`SegmentBookingCompleted`、`ManualBookingCorrectionApplied` |
| `BookingSaga` | Saga 必须持久化，不能只存在内存；每个 Step 有幂等键、超时、重试上限和补偿动作；Saga 不绕过聚合不变量；Saga 状态必须可查询和可恢复；完成或失败必须有最终收敛事件。 | `StartBookingSaga`、`AdvanceBookingSaga`、`RecordSagaStepSucceeded`、`RecordSagaStepFailed`、`ScheduleSagaTimeout`、`HandleSagaTimeout`、`StartCompensation`、`MarkCompensationStepSucceeded`、`MarkCompensationStepFailed`、`CompleteBookingSaga`、`FailBookingSaga`、`MoveSagaToManualReview` | `BookingSagaStarted`、`BookingSagaAdvanced`、`BookingSagaStepSucceeded`、`BookingSagaStepFailed`、`BookingSagaTimedOut`、`BookingCompensationStarted`、`BookingCompensationStepSucceeded`、`BookingCompensationStepFailed`、`BookingSagaCompleted`、`BookingSagaFailed`、`BookingSagaManualReviewRequired` |
| `ReservationRequestLog` | 同一 booking 幂等键只能产生一个语义等价的外部 Reserve/Confirm 请求；超时后查询恢复不能改变原幂等键；原始请求摘要和响应摘要必须可审计。 | `RegisterReservationRequest`、`RecordReservationResponse`、`RecordReservationTimeout`、`RecordStatusQueryResult`、`RejectDuplicateReservationRequest` | `ReservationRequestRegistered`、`ReservationResponseRecorded`、`ReservationRequestTimedOut`、`ReservationStatusQueryRecorded`、`DuplicateReservationRequestRejected` |
| `CompensationCase` | 每个补偿动作必须引用原成功步骤和原因；补偿可以失败但不能静默丢失；同一业务原因不能重复释放、重复取消供应商预订或重复退款请求；不可自动恢复的补偿进入人工。 | `OpenCompensationCase`、`CancelProviderReservationForCompensation`、`ReleaseHoldForCompensation`、`CancelPaymentForCompensation`、`RequestRefundForCompensation`、`SuspendEntitlementForCompensation`、`CloseCompensationCase`、`EscalateCompensationCase` | `CompensationCaseOpened`、`ProviderReservationCancellationRequested`、`CompensationHoldReleaseRequested`、`CompensationPaymentCancelRequested`、`CompensationRefundRequested`、`CompensationEntitlementSuspendRequested`、`CompensationCaseClosed`、`CompensationCaseEscalated` |

### `SegmentBooking` 关键字段

| 字段 | 说明 |
|---|---|
| segmentBookingId | 平台分段预订唯一标识。 |
| journeyOrderId | 所属 JourneyOrder 引用。 |
| offerItemRef | 下单时接受的 OfferItem 快照引用。 |
| segmentRef | Segment 摘要，包含 mode、serviceDate、from/to、supplierId、connectionSensitive 标记。 |
| travelerRef | 旅客引用和资格快照。 |
| bookingPurpose | initial、replacement、reaccommodation、manualRecovery、waitlistFulfillment。 |
| status | Requested、Holding、Confirmed、Ticketed、InFulfillment、Completed、CancelRequested、Cancelled、ChangeRequested、Changed、Failed。 |
| capacityHoldId | 内部库存 Hold 引用；外部库存权威场景可为空但必须有 provider evidence。 |
| providerReference | 归一化供应商预订引用。 |
| idempotencyKey | orderId + segmentId + travelerId + bookingPurpose。 |
| timeoutPolicy | 该 Segment 的 Reserve/Confirm/Issue 超时策略。 |
| compensationPolicy | 取消、释放、退款、转人工或保留部分行程策略。 |
| auditTrail | 命令来源、事件 ID、correlationId、原因、人工操作者。 |

### 交通方式特化字段

| Mode | SegmentBooking 特化信息 | 编排关注点 |
|---|---|---|
| Rail | trainNo、seatClass、stationInterval、coachNo/seatNo、railReservationRef | 区间库存 Hold、出票失败后退款或人工、停运/晚点恢复。 |
| Air | pnrRef、fareFamily、cabinClass、ticketingDeadline、providerTicketingRequired | PNR 不等于出票；票价和出票时限短；超时后必须 QueryStatus。 |
| Coach | coachLine、boardingPoint、seatNo/seatBucket、ticketCodeRef | 上车点可能非标准车站；供应商可能只确认余座不确认座位。 |
| Ferry | sailingNo、cabinNo、deckSlot、vehicleInfo、ferryBookingRef | 人票与车辆票可能绑定，补偿时需联合取消。 |
| RideHailing | pickupPoint、dropoffPoint、driverAssignmentRef、vehicleRef、eta | 司机接单才是强确认；无固定 CapacityHold；取消费和等待费不归 Booking 决定。 |

## 6. 状态机

Booking Orchestration 拥有 `SegmentBooking` 和 `BookingSaga` 状态机。它不定义 `JourneyOrder`、`CapacityHold`、`PaymentIntent`、`Entitlement`、`PostSalesCase` 的内部状态，只消费这些上下文发布的事实。

### `SegmentBooking` 状态

| 状态 | 含义 |
|---|---|
| Requested | 已创建分段预订请求，正在等待 Capacity 或 Provider 处理。 |
| Holding | 内部资源已临时 Hold，或供应商返回 pending/held，等待支付或最终确认。 |
| Confirmed | 供应侧预订确认成功，已获得内部或外部确认依据。 |
| Ticketed | 对应 Entitlement 已签发，用户具备该段可展示或可核验凭证。 |
| InFulfillment | 该 Segment 已开始履约，例如已登乘、上车、登船或开始行程。 |
| Completed | 该 Segment 履约完成。 |
| CancelRequested | 正在取消供应商预订、释放库存或等待取消确认。 |
| Cancelled | 该 SegmentBooking 已取消。 |
| ChangeRequested | 正在为改签、改程或保护性改乘创建替代 Booking。 |
| Changed | 原 SegmentBooking 已被 replacement SegmentBooking 替代或完成变更。 |
| Failed | 预订失败、供应拒绝、Hold 失败、补偿后终止或人工确认失败。 |

### `SegmentBooking` 转换

| 当前状态 | 触发 | 目标状态 | 规则 |
|---|---|---|---|
| Requested | `CapacityHeld` 或 `ProviderReservationPending` | Holding | 必须记录 holdId 或 provider pending evidence。 |
| Requested | `ProviderReservationConfirmed` 且不需要内部 Hold | Confirmed | 适用于 provider-owned inventory 或一阶段确认。 |
| Requested | `CapacityHoldFailed` / `ProviderReservationRejected` | Failed | 失败原因必须可展示给 JourneyOrder 和客服。 |
| Requested | `ProviderReservationTimeout` | Requested | 保持待确认并进入 QueryStatus；不能直接重试创建。 |
| Holding | `ProviderReservationConfirmed` 或 `CapacityHoldConfirmedByPolicy` | Confirmed | 必须满足供应确认或内部库存确认策略。 |
| Holding | `HoldExpired` / `ProviderRejected` | Failed | 触发 Release/Compensation。 |
| Holding | `CancelSegmentBooking` | CancelRequested | 未支付取消、用户取消或 Saga 补偿。 |
| Confirmed | `EntitlementIssued` | Ticketed | 记录 entitlementId。 |
| Confirmed | `CancelSegmentBooking` | CancelRequested | 退票、未出票取消或异常补偿。 |
| Ticketed | `CancelSegmentBooking` | CancelRequested | 需协同 Entitlement 作废和 Post Sales 规则。 |
| Ticketed | `SegmentBoarded` | InFulfillment | 履约事实来自 Fulfillment 或 Entitlement。 |
| InFulfillment | `SegmentCompleted` | Completed | 完成事实不由 Booking 自己生成。 |
| Confirmed / Ticketed | `ChangeSegmentBooking` | ChangeRequested | 必须绑定 replacement booking plan。 |
| ChangeRequested | `ReplacementBookingConfirmed` | Changed | 新 Booking 成功后按规则作废旧票、释放旧库存。 |
| ChangeRequested | `ReplacementBookingFailed` | Confirmed 或 Ticketed | 旧票未作废时保持旧 Booking；旧票已作废时进入补偿或人工。 |
| CancelRequested | `ProviderCancellationConfirmed` / `CapacityReleased` | Cancelled | 取消所需外部动作完成。 |
| CancelRequested | `ProviderCancellationFailed` | CancelRequested | 进入重试、查询或人工，不静默失败。 |

### `BookingSaga` 状态

| 状态 | 含义 |
|---|---|
| Planned | 已根据 JourneyOrder 和 Offer 生成 BookingPlan。 |
| Reserving | 正在创建 SegmentBooking、Hold Capacity 或调用 Provider Reserve。 |
| AwaitingPayment | 必要 Segment 已达到支付前条件，等待 Payment。 |
| Confirming | Payment 条件满足，正在 ConfirmHold、确认供应预订或触发出票。 |
| Ticketing | 正在请求 Entitlement & Ticketing 签发凭证。 |
| Completed | 所有必要 Segment 完成预订和出票前置条件，Saga 正常结束。 |
| PartiallyConfirmed | 部分 Segment 成功、部分失败，等待用户选择、降级、补齐或补偿。 |
| Compensating | 正在释放库存、取消供应商预订、取消付款或请求退款。 |
| ManualReview | 自动流程无法安全收敛，进入人工处理。 |
| Failed | 自动补偿完成后仍无法形成可接受结果，或人工确认终止。 |

### 状态机边界规则

- `SegmentBooking.Confirmed` 不能自动推进 `JourneyOrder.Confirmed`；JourneyOrder 还需要 Payment、Entitlement、风险等条件。
- `PaymentCaptured` 不能直接推进 `SegmentBooking.Ticketed`；Ticketed 必须来自 `EntitlementIssued`。
- `ProviderReservationTimeout` 不能直接等同于失败；必须先 `QueryReservationStatus` 或进入 `ManualReview`。
- `Cancelled`、`Changed`、`Completed` 是不可随意回退的历史事实；恢复只能创建 replacement booking 或人工纠正事件。
- 多 Segment 场景下，Saga 的 `PartiallyConfirmed` 不覆盖每个 SegmentBooking 的真实状态。

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `StartBookingSaga` | BookingSaga | `BookingSagaStarted` | journeyOrderId + orderVersion + bookingPurpose |
| `BuildBookingPlan` | BookingSaga | `BookingPlanBuilt` | journeyOrderId + acceptedOfferVersion |
| `RequestSegmentReservation` | SegmentBooking | `SegmentReservationRequested` | orderId + segmentId + travelerId + bookingPurpose |
| `RegisterReservationRequest` | ReservationRequestLog | `ReservationRequestRegistered` | segmentBookingId + providerId + operation + attemptNo |
| `HoldCapacityForSegment` | BookingSaga | `CapacityHoldRequested` | segmentBookingId + holdPurpose |
| `MarkCapacityHolding` | SegmentBooking | `SegmentCapacityHolding` | segmentBookingId + holdId |
| `ReserveProviderSegment` | BookingSaga / ReservationRequestLog | `ProviderReservationRequested` | segmentBookingId + providerId + reservationKey |
| `AttachProviderReference` | SegmentBooking | `ProviderReferenceAttached` | providerId + providerReference + segmentBookingId |
| `ConfirmSegmentReservation` | SegmentBooking | `SegmentReservationConfirmed` | segmentBookingId + confirmationEvidenceId |
| `FailSegmentReservation` | SegmentBooking | `SegmentReservationFailed` | segmentBookingId + failureCode + sourceEventId |
| `MarkProviderReservationTimeout` | SegmentBooking / ReservationRequestLog | `ProviderReservationTimedOut` | segmentBookingId + operation + attemptNo |
| `QueryProviderReservationStatus` | BookingSaga | `ProviderReservationStatusQueryRequested` | segmentBookingId + timeoutEventId |
| `RecordReservationStatusQueryResult` | ReservationRequestLog | `ReservationStatusQueryRecorded` | providerId + providerReference + queryAttempt |
| `MarkPendingPaymentForBooking` | BookingSaga | `BookingAwaitingPayment` | journeyOrderId + paymentPurpose + sagaId |
| `RecordPaymentCapturedForBooking` | BookingSaga | `BookingPaymentConditionSatisfied` | sagaId + paymentIntentId + channelTxnId |
| `ConfirmCapacityHoldForBooking` | BookingSaga | `CapacityHoldConfirmationRequested` | holdId + segmentBookingId + confirmationAttempt |
| `RequestEntitlementIssue` | BookingSaga | `SegmentTicketingRequested` | segmentBookingId + travelerId + issuePurpose |
| `MarkSegmentTicketed` | SegmentBooking | `SegmentTicketed` | segmentBookingId + entitlementId |
| `CancelSegmentBooking` | SegmentBooking | `SegmentBookingCancelRequested` | segmentBookingId + cancelReason + sourceCaseId |
| `MarkSegmentBookingCancelled` | SegmentBooking | `SegmentBookingCancelled` | segmentBookingId + cancellationEvidenceId |
| `ChangeSegmentBooking` | SegmentBooking | `SegmentBookingChangeRequested` | postSalesCaseId + oldSegmentBookingId + targetSegmentId |
| `MarkSegmentBookingChanged` | SegmentBooking | `SegmentBookingChanged` | oldSegmentBookingId + replacementSegmentBookingId |
| `StartCompensation` | CompensationCase | `BookingCompensationStarted` | sagaId + compensationReason |
| `ReleaseHoldForCompensation` | CompensationCase | `CompensationHoldReleaseRequested` | holdId + compensationCaseId |
| `CancelProviderReservationForCompensation` | CompensationCase | `ProviderReservationCancellationRequested` | providerReference + compensationCaseId |
| `RequestRefundForCompensation` | CompensationCase | `CompensationRefundRequested` | paymentIntentId + compensationCaseId + refundPurpose |
| `MoveSagaToManualReview` | BookingSaga | `BookingSagaManualReviewRequired` | sagaId + reason + sourceEventId |
| `CompleteBookingSaga` | BookingSaga | `BookingSagaCompleted` | sagaId + completionVersion |
| `FailBookingSaga` | BookingSaga | `BookingSagaFailed` | sagaId + failureReason + finalizationEventId |

### 事件发布要求

- 所有 Booking 写状态事件必须通过本上下文 Outbox 发布，并携带 `eventId`、`occurredAt`、`correlationId`、`causationId`、`journeyOrderId`、`segmentBookingId`、`sagaId`、`schemaVersion`。
- 消费 JourneyOrder、Capacity、Payment、Provider、Entitlement、Post Sales 事件时必须通过 Inbox 按 producer event id 幂等处理。
- `SegmentReservationConfirmed` 是平台 Booking 事实；若 Provider Integration 先收到供应商确认，应由 Booking Orchestration 映射并确认 `SegmentBooking`，而不是让供应商原始事件直接改订单。
- 超时类事件必须记录当前不确定状态和下一步动作，不能用一个模糊失败事件覆盖。

## 8. 策略和 Saga 参与点

### 下单、占位、支付、出票 Saga

| Step | Booking Orchestration 行为 | 输出或等待 |
|---|---|---|
| JourneyOrder 创建 | 消费 `JourneyOrderCreated` / `OrderItemsReadyForBooking`，根据 AcceptedOfferView 生成 `BookingPlan`。 | `BookingSagaStarted`, `BookingPlanBuilt` |
| 分段预订启动 | 为每个必要 Segment 创建 `SegmentBooking`，根据 Connection Contract 决定串行、并行或依赖顺序。 | `SegmentReservationRequested` |
| 内部库存 Hold | 对 internal/mixed inventory Segment 发起 `HoldCapacity`。 | 等待 `CapacityHeld` 或 `CapacityHoldFailed` |
| 外部供应 Reserve | 对 provider-owned 或需要外部确认的 Segment 发起 `ReserveSegment`。 | 等待 provider result；超时后 QueryStatus |
| 支付前条件汇总 | 所有必要 Segment 达到 Holding/Confirmed 且允许收款时，通知 JourneyOrder/Payment。 | `BookingAwaitingPayment` 或 Payment request |
| 支付成功后确认 | 消费 `PaymentCaptured`，发起 `ConfirmHold`、`ConfirmReservation` 或确认供应状态。 | `CapacityHoldConfirmationRequested`，可能触发 Provider confirm |
| 触发出票 | SegmentBooking Confirmed 且资金/库存条件满足时发出 `IssueEntitlement`。 | `SegmentTicketingRequested` |
| 出票结果收敛 | 消费 `EntitlementIssued` / `EntitlementIssueFailed`。 | `SegmentTicketed`、重试、补偿或人工 |
| 整体完成 | 所有必要 Segment 达到确认条件后发布完成事件给 JourneyOrder。 | `AllSegmentReservationsConfirmed`, `BookingSagaCompleted` |

### 多 Segment / Transfer / Multimodal 策略

- 对 Protected Connection：BookingPlan 应优先保证关键接续段的一致性；若后一段失败，前一段是否保留取决于 Connection Contract 和用户选择。
- 对 Self Transfer：每个 Segment 可作为独立预订成功，但下单前风险已由 Offer 披露；失败补偿通常按单段规则执行，不自动承诺整段兜底。
- 对 Platform Assisted：失败时可进入 `PartiallyConfirmed`，提供补齐、保留可用段、取消全单或客服介入。
- 对火车 + 火车换乘：可并行 Hold，但需考虑换乘时间和区间库存过期窗口。
- 对飞机 + 火车：航空 PNR/出票时限可能要求先确认航空再锁后续段，或先短时 Hold 后续固定班次。
- 对网约车首末段接驳：司机接单晚于主交通出票时，可把接驳 Segment 标为可降级 Segment；司机未接单不应阻断主票确认，除非 Offer 声明为必要接驳。
- 对轮船 + 大巴或人车同船：补偿时需要识别人票、车辆票、后续接驳是否绑定取消。

### 失败和补偿策略

| 失败场景 | Booking 策略 | 下游协作 |
|---|---|---|
| `CapacityHoldFailed` | 标记对应 SegmentBooking Failed；若是必要 Segment，Saga 进入失败或部分确认；若可选 Segment，可降级继续。 | JourneyOrder 汇总、Offer 重新报价、Notification 提示。 |
| `ProviderReservationRejected` | 不重试业务拒绝；释放已 Hold 资源，按 BookingPlan 判断是否取消已确认段。 | Capacity Release、JourneyOrder Partial/Failed。 |
| `ProviderReservationTimeout` | 查询供应商最终状态；禁止盲目重复 Reserve。 | Provider Integration `QueryReservationStatus`、客服读模型。 |
| `PaymentIntentExpired` | 取消或释放未确认资源；对 provider pending 状态执行取消或查询。 | Payment cancel、Capacity release、Provider cancel。 |
| `PaymentCaptured` 晚到 | 不恢复已释放资源或直接出票；进入异常补偿。 | Payment LatePaymentCase、JourneyOrder 异常、Post Sales/Customer Service。 |
| `EntitlementIssueFailed` | 对可重试失败保留 Confirming 并重试；对业务拒绝执行退款/释放/取消；对 Unknown 查询最终状态。 | Entitlement、Payment Refund、Capacity、JourneyOrder。 |
| 已确认段取消失败 | 保持 CompensationCase active，重试或人工；不得让订单显示完全取消。 | Provider Integration、Post Sales、Customer Service。 |
| 部分 Segment 成功 | 发布 `JourneyPartiallyConfirmed`，等待用户接受部分、取消全单、补齐替代方案或转人工。 | JourneyOrder、Offer Management、Post Sales、Notification。 |

### 取消、退票、改签参与点

- 未支付取消：JourneyOrder 发布取消后，Booking 取消未确认 SegmentBooking、释放 Hold、取消 Provider pending reservation，并请求 Payment 取消未完成 PaymentIntent。
- 已确认退票：Post Sales 决定规则后调用 `CancelSegmentBooking`；Booking 执行供应取消和库存释放，但退款金额由 Post Sales 决定、退款执行由 Payment 完成。
- 改签：Post Sales/Offer Management 提供目标 ChangeOffer；Booking 先为新 Segment 创建 replacement SegmentBooking 和 Hold；差价处理完成后触发旧 Entitlement 作废、旧 Booking Changed/Cancelled、新 Entitlement 签发。
- 保护性改乘：Disruption Recovery 接收用户或规则选择后，Booking 创建 `bookingPurpose=reaccommodation` 的 replacement SegmentBooking，原 Segment 的释放和退款按恢复策略执行。

### 超时和恢复策略

- 内部 Hold 超时以秒级到分钟级处理，超时后释放资源并推进 Saga。
- 外部 Provider Reserve 超时以秒级到几十秒处理，进入 `Unknown`/query 状态，优先查询最终状态。
- 用户支付超时以分钟级处理，Booking 释放资源或取消 provider pending reservation。
- Ticketing 超时以秒级到分钟级处理，先查询 Entitlement/Provider 最终状态，再重试或转人工。
- Saga 恢复必须从持久化 `BookingSaga`、`ReservationRequestLog`、`SegmentBooking` 和 Inbox/Outbox 重建，不依赖内存线程。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `BookingProgress` | `BookingSagaStarted`、`SegmentReservationRequested`、`SegmentReservationConfirmed`、`SegmentReservationFailed`、`SegmentTicketed`、`BookingSagaCompleted` | JourneyOrder、用户订单详情、支付页、Notification。 |
| `SegmentBookingTimeline` | SegmentBooking lifecycle events、Provider result events、Capacity result events、Entitlement result events | Customer Service、Admin & Audit、供应商排障。 |
| `BookingSagaMonitor` | BookingSaga step events、timeout events、compensation events | 运维、客服、自动恢复任务、Reporting。 |
| `ProviderReservationReconciliationView` | `ProviderReferenceAttached`、`ReservationStatusQueryRecorded`、Provider reconciliation events | Provider Integration、运营对账、人工处理。 |
| `CompensationCaseView` | CompensationCase events、refund/cancel/release result events | Customer Service、Post Sales、Disruption Recovery。 |
| `PartialBookingDecisionView` | `JourneyPartiallyConfirmed`、failed segment events、available alternative refs | JourneyOrder、用户选择页、客服。 |
| `BookingIdempotencyView` | ReservationRequestLog events、duplicate request rejected events | 幂等排障、供应商重复预订排查。 |
| `BookingSlaDashboard` | timeout/failure/success events by provider/mode/segment type | 运营、供应商绩效、Reporting。 |
| `MultimodalBookingMap` | BookingPlanBuilt、SegmentBooking events、Transfer risk references | 联乘订单详情、异常恢复、客服。 |

读模型规则：

1. 读模型可以冗余订单号、旅客脱敏信息、Segment 摘要、ProviderReference 摘要和支付/票证摘要，但不得成为写入来源。
2. `BookingProgress` 只表达预订执行进度，不替代 JourneyOrder 的商业状态。
3. `ProviderReservationReconciliationView` 可以显示供应商原始状态摘要，但核心域只使用 ACL 映射后的状态。
4. 客服人工处理必须通过受控命令进入 `SegmentBooking`、`BookingSaga` 或 `CompensationCase`，不能直接编辑读模型。

## 10. 外部系统和防腐层

Booking Orchestration 不直接实现外部供应商协议。它通过 Provider Integration ACL 使用平台标准能力，并把结果映射为 `SegmentBooking` 事实。

### Provider Integration ACL

| Provider Capability | Booking 使用方式 | 防腐要求 |
|---|---|---|
| `ReserveSegment` | 创建外部预留或一阶段确认。 | 请求必须带平台幂等键和 SegmentBookingId；响应必须映射为 Success/Pending/Rejected/Timeout/Unknown/Failed。 |
| `ConfirmReservation` | 二阶段确认供应商预订。 | Confirm 不能重复创建；失败后需区分业务拒绝、可重试失败和状态冲突。 |
| `CancelReservation` | 补偿、未支付取消、退票或改签取消。 | 取消失败不得静默吞掉；必须查询或转人工。 |
| `ChangeReservation` | 供应商支持原地改签时使用。 | 若供应商不支持，Booking 使用 replacement SegmentBooking 模式。 |
| `QueryReservationStatus` | 超时、Unknown、对账和恢复时使用。 | Query 结果只修复平台状态，不创建新预订。 |
| `IssueCredential` | 某些供应商把出票作为预订接口的一部分时协同 Entitlement。 | Booking 不直接签发 Entitlement；只把供应商出票结果交给 Entitlement ACL。 |

### 交通方式防腐重点

| Mode | 外部语言 | Booking 内部语言 |
|---|---|---|
| Rail | 车次、席别、区间票额、票号、候补 | SegmentRef、CapacityHold、SegmentBooking、EntitlementRef、WaitlistRef。 |
| Air | PNR、ticketing deadline、fare family、航司状态码 | ProviderReference、BookingTimeout、RuleSnapshotRef、SegmentBooking status。 |
| Coach | 班线、上车点、电子票码、座位图 | SegmentRef、PlaceRef、ProviderReference、Entitlement trigger。 |
| Ferry | 船班、舱房、车辆甲板、人车绑定 | SegmentBooking group、CapacityUnitRef、CompensationPolicy。 |
| RideHailing | 司机接单、车辆、ETA、取消费、行程状态 | Reservation Outcome、ProviderReference、Fulfillment events；取消费交给 Post Sales/Payment。 |

### 遗留服务防腐层

迁移期应建立 Legacy Booking ACL：

- 把 `ts-preserve-service` / `ts-preserve-other-service` 的同步订票结果拆成 `JourneyOrderCreated`、`SegmentReservationRequested`、`CapacityHeld`、`SegmentReservationConfirmed`、`BookingSagaCompleted` 等事件。
- 把旧 `seat-service` 的余票和随机座位逻辑隔离到 Capacity & Availability；Booking 只调用 Hold/Confirm/Release。
- 把旧 `order` / `order-other` 中的订单状态写入改成 JourneyOrder 和 SegmentBooking 各自事件。
- 把旧 `inside-payment` 的直接订单更新改成 Payment 事件消费。
- 把旧 `execute` 的取票/进站状态交给 Entitlement & Ticketing / Fulfillment。
- 把高铁/普通车分裂逻辑隐藏在 ACL 内，目标模型只使用 `SegmentRef.mode`、service category 和 provider capability。

### 可靠性组件

本 domain 需要 Outbox、Inbox、idempotency store、Saga state store、timeout scheduler、reservation request log、compensation retry queue、provider reconciliation job 和 manual review queue。这些是交易可靠性基础设施，不改变领域边界。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-preserve-service` | 从“普通车同步订票大编排”迁移为 Booking Orchestration 的输入适配层；订单、库存、支付、附加服务副作用拆出；保留为 Legacy ACL 期间只发起受控 Booking 命令。 |
| `ts-preserve-other-service` | 与 `ts-preserve-service` 合并为统一 Booking 流程；高铁/普通车差异转为 Segment/ServicePlan 属性，不再分裂编排。 |
| `ts-order-service`, `ts-order-other-service` | 供应侧预订字段拆到 `SegmentBooking`；订单只保留商业汇总和 SegmentBookingId 引用；订单 ID 行为差异由迁移 ACL 统一。 |
| `ts-seat-service` | 不再由 preserve 随机分配座位并从订单反推余票；改为 Capacity & Availability 的 `HoldCapacity`、`ConfirmHold`、`ReleaseHold`。 |
| `ts-inside-payment-service`, `ts-payment-service` | Booking 通过 Payment Open Host Service 创建/取消付款或请求退款；支付成功事件只推进 Saga，不直接写订单或出票。 |
| `ts-cancel-service` | 未支付取消的预订释放可由 Booking 处理；已出票退票由 Post Sales 决策后调用 Booking 取消 SegmentBooking。 |
| `ts-rebook-service` | 改签拆为 ChangeOffer、replacement SegmentBooking、差价 Payment、旧 Entitlement 作废和旧库存释放；禁止删除旧订单再创建新订单的不可追踪流程。 |
| `ts-wait-order-service` | 候补兑现不再轮询 preserve；Waitlist 或 Capacity 触发后由 Booking 创建 `bookingPurpose=waitlistFulfillment` 的 SegmentBooking。 |
| `ts-travel-service`, `ts-travel2-service`, `ts-travel-plan-service`, `ts-route-plan-service` | 这些服务不再进入交易编排写路径；只提供 Trip Planning / Service Plan / Offer 的输入，Booking 消费稳定的 SegmentRef 和 OfferItemRef。 |
| `ts-basic-service`, `ts-price-service`, `ts-station-service`, `ts-train-service` | 基础数据、价格、站点和车次信息只通过 Offer/ServicePlan 快照进入 Booking，避免编排时重新散算。 |
| `ts-security-service` | 下单安全检查迁移到 Risk & Compliance；Booking 只消费 allow/deny/challenge 结果并暂停或失败 Saga。 |
| `ts-execute-service` | 取票、进站、使用不再回写 Booking；Booking 只消费 Fulfillment/Entitlement 的履约结果推进 InFulfillment/Completed 投影。 |
| `ts-assurance-service`, `ts-food-service`, `ts-consign-service` | 当前 preserve 中可选附加服务失败不应污染主票 Booking；未来作为 Ancillary Service 独立编排或以可选 Segment/OrderItem 参与。 |
| `ts-notification-service` | 不再被 preserve 同步调用；消费 Booking/Journey 事件发送确认中、失败、部分成功和人工处理通知。 |
| `ts-admin-order-service` | 后台人工确认、取消、补偿和修复必须通过 Customer Service / Admin & Audit 受控命令进入 Booking 聚合和 Saga。 |

迁移建议切片：

1. 建立 `SegmentBooking` 与 `BookingSaga` 读写模型，先旁路记录 preserve 下单过程。
2. 将新订单链路切换为 `JourneyOrderCreated -> BookingSagaStarted -> HoldCapacity -> SegmentReservationConfirmed`。
3. 引入 Outbox/Inbox 和 ReservationRequestLog，保证重复提交和超时查询不会重复预订。
4. 接入 Payment 事件，移除支付成功直接写订单和直接出票的耦合。
5. 接入 Entitlement 出票事件，区分 Confirmed 与 Ticketed。
6. 接入取消、退票、改签补偿 Case，替代 cancel/rebook 的跨服务直接写。
7. 扩展多 Segment 和 Transfer 策略，再接入大巴、轮船、飞机、网约车供应商能力。

## 12. 验收标准

- Booking Orchestration 的聚合所有权明确：`SegmentBooking`、`BookingSaga`、`ReservationRequestLog`、`CompensationCase` 只保护预订执行、Saga、幂等和补偿可恢复性。
- 明确区分 `JourneyOrder` 与 `SegmentBooking`：JourneyOrder 拥有商业订单和 Journey 承诺；Booking Orchestration 拥有 booking execution 和 segment-level booking state。
- 明确区分 `Capacity & Availability` 与 Booking：Capacity 拥有库存快照、可售判断、Hold 内部不变量；Booking 只消费 Availability/Reservation/Hold 结果并发起受控命令。
- 明确区分 Payment、Entitlement & Ticketing、Provider Integration、Post Sales：Booking 不拥有资金、票证、供应商适配或售后规则。
- 覆盖 confirmation、partial confirmation、failure、Compensation、cross-supplier Saga、idempotency 和 timeout 处理。
- 覆盖火车、大巴、网约车、飞机、轮船以及 connected/transfer/multimodal 场景，并说明分段编排、降级和补偿策略。
- `SegmentBooking` 和 `BookingSaga` 状态机只定义本 domain 自有状态，不改写其他 domain 内部状态。
- 命令、事件、幂等键、Outbox/Inbox、ReservationRequestLog 和补偿重试要求明确，可供 reduce agent 提取一致性问题。
- 读模型覆盖 BookingProgress、SegmentBookingTimeline、BookingSagaMonitor、ProviderReservationReconciliationView、CompensationCaseView 和部分成功决策。
- 当前服务迁移影响覆盖 preserve、preserve-other、order、order-other、seat、inside-payment、payment、cancel、rebook、wait-order、travel、execute、security、ancillary、notification 和 admin。
- 跨域冲突和开放问题只记录在第 12 节。
