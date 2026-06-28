# 出行平台聚合模型设计

Last updated: 2026-06-28

## 目的

这份文档把业务流和上下文地图进一步落到 DDD 聚合层。它不定义数据库表，也不绑定编程语言，只定义哪些对象应该作为聚合根、它们保护什么不变量、可以接收哪些命令、产生哪些领域事件，以及哪些职责必须留给其他上下文。

本文使用：

1. `docs/01-ddd-high-level/domain-glossary.md` 的统一语言。
2. `docs/01-ddd-high-level/context-map.md` 的上下文边界。
3. `docs/01-ddd-high-level/general-travel-ddd.md` 的通用出行抽象。
4. `docs/01-ddd-high-level/train-business-flow-catalog.md` 的火车业务细节。

## 聚合设计原则

| 原则 | 说明 |
|---|---|
| 一个聚合只保护自己的强一致不变量 | 不把订单、库存、支付、票证塞进一个大对象。 |
| 跨聚合通过领域事件和 Saga 协作 | 下单、占座、支付、出票是长事务，不是单数据库事务。 |
| 聚合内只保存必要引用 | 订单可以保存 `OfferId`、`SegmentBookingId`，不复制完整供应商状态。 |
| 读模型可以冗余，写模型不要乱冗余 | 客服时间线可以汇总所有状态，但不能反向推进业务。 |
| 交通方式差异进入特化聚合或策略 | 火车区间库存、航空 PNR、网约车派单不要污染通用 Journey Order。 |
| 状态推进必须由命令触发 | 不允许外部直接改状态字段。 |

## 聚合总览

| 聚合根 | 所属上下文 | 保护的不变量 | 典型事件 |
|---|---|---|---|
| TripIntent | Trip Planning | 查询条件、人数、偏好、约束完整且可解释 | TripIntentCreated |
| Itinerary | Trip Planning | Segment 和 Transfer 组合满足基本可达性 | ItineraryProposed |
| Offer | Offer Management | 报价、规则、可售性、风险和有效期快照不可被随意重算 | OfferQuoted, OfferExpired |
| JourneyOrder | Journey Order | 用户商业订单金额、订单项、旅客、总体状态一致 | JourneyOrderCreated, JourneyOrderConfirmed |
| SegmentBooking | Booking Orchestration | 某段供应侧确认状态和供应引用一致 | SegmentReservationConfirmed, SegmentBookingFailed |
| CapacityHold | Capacity & Availability | 某资源在有效期内不能被重复售卖 | CapacityHeld, CapacityReleased |
| PaymentIntent | Payment | 应收金额、授权、扣款状态幂等一致 | PaymentAuthorized, PaymentCaptured |
| Refund | Payment | 应退金额、渠道退款状态和原因一致 | RefundRequested, RefundSettled |
| Entitlement | Entitlement & Ticketing | 票证或权益凭证生命周期不可逆地推进 | EntitlementIssued, EntitlementVoided |
| FulfillmentRecord | Fulfillment | 登乘、到达、完成等履约事实不可篡改 | SegmentBoarded, SegmentCompleted |
| TransferPlan | Transfer Management | 两段之间的连接时间、风险和保障契约一致 | TransferRiskEvaluated, TransferAtRisk |
| PostSalesCase | Post Sales | 退改申请、规则判定、差价和补偿步骤一致 | PostSalesRequested, SegmentChanged |
| DisruptionCase | Disruption Recovery | 异常影响范围、恢复选项和处理进度一致 | DisruptionPublished, ReaccommodationProposed |
| AncillaryOrder | Ancillary Service | 附加服务购买、确认、履约和退订状态一致 | AncillaryServiceConfirmed |
| NotificationTask | Notification | 通知内容、渠道、发送状态和重试次数一致 | NotificationScheduled, NotificationSent |

## 值对象

| 值对象 | 说明 | 关键字段 |
|---|---|---|
| Money | 金额 | currency、amount、scale |
| TimeWindow | 时间窗口 | startAt、endAt、timezone |
| PlaceRef | 地点引用 | placeId、nodeType、displayName |
| TravelerRef | 旅客引用 | travelerId、documentType、maskedDocumentNo |
| SegmentRef | 出行段引用 | segmentId、mode、supplierId、serviceDate |
| FareBreakdown | 价格明细 | baseFare、taxes、fees、discounts、total |
| RuleSnapshot | 规则快照 | refundRule、changeRule、eligibilityRule、version |
| CapacityUnit | 容量单元 | seat、cabin、berth、deckSlot、driverSupply 等 |
| TransferConstraint | 中转约束 | minConnectionTime、baggageRequired、securityRequired |
| ProviderReference | 供应商引用 | provider、confirmationNo、rawStatus、mappedStatus |

值对象可以在多个聚合中复制快照，但必须能说明来源版本。

## Offer 聚合

### 职责

Offer 是下单前的报价承诺快照。它把 Itinerary、可售性、价格、票规、风险提示和有效期绑定在一起。

### 保护的不变量

1. Offer 必须引用一个 Itinerary。
2. Offer 必须有有效期。
3. Offer 的价格、税费、手续费和优惠必须能合计为总价。
4. Offer 必须记录规则版本，后续订单不能无依据地重新计算历史价格。
5. 如果任一关键 Segment 不可售，Offer 不能进入 `Quoted`。
6. 保障联乘和非保障联乘必须在 Offer 中显式标注。

### 命令和事件

| 命令 | 前置条件 | 事件 |
|---|---|---|
| QuoteOffer | Itinerary 可达，价格和可售性可获取 | OfferQuoted |
| ExpireOffer | 当前时间超过有效期或供应商通知价格变化 | OfferExpired |
| RefreshOffer | 原 Offer 失效且用户仍要购买 | OfferRequoted |
| MarkOfferUnavailable | 任一关键 Segment 不再可售 | OfferUnavailable |

### 不承担的职责

1. 不锁库存。
2. 不创建订单。
3. 不确认供应商预订。
4. 不执行支付。

## JourneyOrder 聚合

### 职责

JourneyOrder 是用户视角的一次商业购买。它汇总订单项、旅客、价格快照、支付意图、分段预订和总体状态。

### 保护的不变量

1. 订单必须来自有效 Offer 或受控人工命令。
2. 订单总金额必须等于订单项金额、税费、手续费、优惠的汇总。
3. 一个订单项必须能追溯到 Segment、Ancillary 或 Service。
4. 订单内旅客必须通过基础资格校验。
5. 订单状态只能按定义的状态机推进。
6. 订单的 `Confirmed` 不能早于所有必要 Segment Booking 确认和必要 Entitlement 签发。
7. 部分成功订单必须明确用户选择：保留、补齐、取消或人工处理。

### 命令和事件

| 命令 | 前置条件 | 事件 |
|---|---|---|
| CreateJourneyOrder | Offer 有效，旅客和账号可用 | JourneyOrderCreated |
| AttachSegmentBooking | SegmentBooking 属于该订单 | JourneyOrderBookingAttached |
| MarkPendingPayment | 至少一个收费项需要支付 | JourneyOrderPendingPayment |
| ConfirmJourneyOrder | 必要 Booking、Payment、Entitlement 满足确认条件 | JourneyOrderConfirmed |
| MarkPartiallyConfirmed | 部分 Segment 成功，部分失败 | JourneyOrderPartiallyConfirmed |
| CancelJourneyOrder | 未出票或规则允许取消 | JourneyOrderCancelled |
| MarkDisrupted | 任一关键 Segment 被异常影响 | JourneyOrderDisrupted |
| CompleteJourney | 所有必需 Segment 完成 | JourneyCompleted |

### 不承担的职责

1. 不直接锁库存。
2. 不直接调用供应商。
3. 不计算复杂退改规则。
4. 不直接发起渠道退款。
5. 不记录完整履约明细，只保存引用和汇总状态。

## SegmentBooking 聚合

### 职责

SegmentBooking 表达某一段运输服务的供应侧确认状态。它隔离不同交通方式和供应商确认逻辑。

### 保护的不变量

1. 一个 SegmentBooking 只对应一个 Segment。
2. 必须绑定 JourneyOrderId 和 TravelerRef。
3. 供应商确认号和平台 BookingId 必须幂等映射。
4. 已确认 Booking 取消或变更必须产生可追踪事件。
5. 外部供应商状态必须映射为平台内部状态，不能直接泄漏。
6. SegmentBooking 成功不代表整个 JourneyOrder 成功。

### 命令和事件

| 命令 | 前置条件 | 事件 |
|---|---|---|
| RequestSegmentReservation | OfferItem 有效 | SegmentReservationRequested |
| ConfirmSegmentReservation | 内部库存锁定或供应商确认成功 | SegmentReservationConfirmed |
| FailSegmentReservation | 库存不足或供应商拒绝 | SegmentReservationFailed |
| CancelSegmentBooking | 订单取消、售后或异常触发 | SegmentBookingCancelled |
| ChangeSegmentBooking | 改签或保护性改乘触发 | SegmentBookingChanged |
| MarkSegmentTicketed | Entitlement 已签发 | SegmentTicketed |

### 交通方式特化

| 交通方式 | 特化字段 | 注意事项 |
|---|---|---|
| 火车 | trainNo、seatClass、coachNo、seatNo、section | 区间库存和席别是关键。 |
| 飞机 | pnr、ticketNo、fareFamily、cabinClass | PNR 不是平台订单号。 |
| 大巴 | coachLine、boardingPoint、seatNo、ticketCode | 上车点可能是非标准车站。 |
| 网约车 | driverId、vehicleId、eta、pickupPoint | 司机接单后才是强确认。 |
| 轮船 | sailingNo、cabinNo、deckSlot、vehicleInfo | 人票和车辆票可能绑定。 |

## CapacityHold 聚合

### 职责

CapacityHold 保护可售资源在短时间内不被重复售卖。固定班次和动态调度可以有不同实现，但对外都表达“资源临时保留”。

### 保护的不变量

1. Hold 必须有资源范围、旅客或订单引用和过期时间。
2. 同一 CapacityUnit 在重叠销售范围内不能被重复 Hold。
3. Hold 过期后必须可释放。
4. 已 Confirmed 的 Hold 不能被普通过期任务释放。
5. 火车区间座席必须检查区间重叠，而不是只检查座位号。
6. 渠道配额和物理库存必须分别扣减和释放。

### 命令和事件

| 命令 | 前置条件 | 事件 |
|---|---|---|
| HoldCapacity | 资源可售，未被冲突占用 | CapacityHeld |
| ConfirmHold | 支付或供应确认满足条件 | CapacityHoldConfirmed |
| ReleaseHold | 订单取消、支付超时或预订失败 | CapacityReleased |
| ExpireHold | 当前时间超过 Hold 期限 | CapacityHoldExpired |
| AdjustCapacity | 运营调配或异常恢复 | CapacityAdjusted |

### 不承担的职责

1. 不创建订单。
2. 不收款。
3. 不决定退改手续费。
4. 不发通知。

## PaymentIntent 和 Refund 聚合

### PaymentIntent

PaymentIntent 保护应收金额和支付渠道状态。

| 不变量 | 说明 |
|---|---|
| 金额不可随意变化 | 变价必须创建新的 PaymentIntent 或 Adjustment。 |
| 渠道回调必须幂等 | 同一渠道交易号只能确认一次。 |
| Captured 后不能回到 Authorized | 资金状态不可逆。 |
| 订单取消必须取消未完成支付 | 防止晚到回调错误推进订单。 |

| 命令 | 事件 |
|---|---|
| CreatePaymentIntent | PaymentIntentCreated |
| AuthorizePayment | PaymentAuthorized |
| CapturePayment | PaymentCaptured |
| FailPayment | PaymentFailed |
| CancelPaymentIntent | PaymentIntentCancelled |
| ExpirePaymentIntent | PaymentIntentExpired |

### Refund

Refund 保护应退金额、退款原因和退款渠道结果。

| 不变量 | 说明 |
|---|---|
| Refund 必须引用原 Payment 或可解释的补偿原因 | 防止无来源退款。 |
| 应退金额来自 Post Sales 或 Disruption 规则快照 | Payment 不自行决定退多少。 |
| 同一业务原因不能重复退款 | 通过 businessKey 幂等。 |
| 退款失败不代表售后失败 | 可能进入人工或重试。 |

| 命令 | 事件 |
|---|---|
| RequestRefund | RefundRequested |
| AcceptRefund | RefundAccepted |
| SettleRefund | RefundSettled |
| FailRefund | RefundFailed |
| CancelRefund | RefundCancelled |

## Entitlement 聚合

### 职责

Entitlement 表达用户可用于履约的权益凭证。它可以表现为火车票、机票、船票、大巴电子票、网约车订单码、登机牌或服务券。

### 保护的不变量

1. Entitlement 必须绑定 SegmentBooking 或 AncillaryOrder。
2. 已签发凭证必须有唯一凭证号或核验标识。
3. 已使用凭证不能再次使用。
4. 已作废凭证不能登乘。
5. 凭证状态变化必须保留审计事件。
6. 凭证展示信息可以更新，但业务身份不能无审计地变更。

### 命令和事件

| 命令 | 前置条件 | 事件 |
|---|---|---|
| IssueEntitlement | Booking 确认，支付条件满足 | EntitlementIssued |
| VoidEntitlement | 退票、改签、异常取消 | EntitlementVoided |
| CheckInEntitlement | 规则允许值机或签到 | EntitlementCheckedIn |
| MarkBoarded | 检票、登机、登船或上车 | EntitlementBoarded |
| MarkUsed | Segment 完成 | EntitlementUsed |
| SuspendEntitlement | 风控、争议或人工冻结 | EntitlementSuspended |

## PostSalesCase 聚合

### 职责

PostSalesCase 管理取消、退票、改签、改程、升降级、变更到站、部分退改和人工例外。

### 保护的不变量

1. 售后申请必须绑定原 JourneyOrder 和目标 SegmentBooking 或 Entitlement。
2. 规则判定必须记录规则版本和时间点。
3. 改签必须同时表达旧 Segment 和目标 Segment。
4. 差价、手续费和应退金额必须可追溯。
5. 售后步骤失败时必须进入可补偿状态，而不是静默半成功。
6. 人工例外必须有权限、原因和审计记录。

### 命令和事件

| 命令 | 前置条件 | 事件 |
|---|---|---|
| RequestCancellation | 订单或票项可取消 | CancellationRequested |
| RequestRefundByRule | 票项满足退票规则 | RefundByRuleRequested |
| RequestChange | 原票可改，目标方案可报价 | ChangeRequested |
| ApprovePostSalesCase | 规则或人工审批通过 | PostSalesApproved |
| ApplyPostSalesResult | 库存、票证、资金操作完成 | PostSalesApplied |
| FailPostSalesCase | 任一步骤不可补偿失败 | PostSalesFailed |

## TransferPlan 聚合

### 职责

TransferPlan 管理两个 Segment 之间的连接可达性、风险状态和保障契约。

### 保护的不变量

1. Transfer 必须连接前后两个 Segment。
2. 必须记录计划连接时间和最短连接时间。
3. 必须明确是否需要换站、取行李、重新安检、出入境或接驳。
4. 必须有 Connection Contract。
5. 风险状态变化必须可追溯。

### 命令和事件

| 命令 | 前置条件 | 事件 |
|---|---|---|
| EvaluateTransferRisk | Itinerary 生成或履约事件变化 | TransferRiskEvaluated |
| MarkTransferAtRisk | 实际到达或延误导致连接紧张 | TransferAtRisk |
| MarkConnectionMissed | 下一段已不可赶上 | ConnectionMissed |
| MarkTransferRecovered | 用户接受恢复方案 | TransferRecovered |
| MarkSelfHandled | 非保障联乘用户自行处理 | TransferSelfHandled |

## DisruptionCase 聚合

### 职责

DisruptionCase 管理延误、取消、停运、停航、司机取消、站点变更等异常的影响范围和恢复进度。

### 保护的不变量

1. 必须有异常来源、影响范围、发布时间和严重程度。
2. 受影响 Segment、JourneyOrder、Entitlement 必须可追溯。
3. Recovery Option 必须记录成本、责任方和用户权益。
4. 批量退款或保护性改乘必须幂等。
5. 异常关闭前必须处理或明确遗留所有受影响订单。

### 命令和事件

| 命令 | 前置条件 | 事件 |
|---|---|---|
| PublishDisruption | 供应商或运营确认异常 | DisruptionPublished |
| AttachAffectedJourney | 订单受影响 | JourneyAffectedByDisruption |
| ProposeRecoveryOptions | 有可行替代或退款方案 | ReaccommodationProposed |
| AcceptRecoveryOption | 用户或规则选择方案 | ReaccommodationAccepted |
| CloseDisruptionCase | 影响处理完成或转人工 | DisruptionCaseClosed |

## 聚合间引用规则

| 来源聚合 | 可保存的引用 | 不应保存 |
|---|---|---|
| JourneyOrder | OfferId、TravelerRef、SegmentBookingId、PaymentIntentId、EntitlementId | 完整库存明细、供应商原始报文。 |
| SegmentBooking | JourneyOrderId、SegmentRef、ProviderReference、CapacityHoldId | 完整订单总价、支付渠道流水。 |
| CapacityHold | JourneyOrderId、SegmentBookingId、CapacityUnit | 旅客完整证件、支付状态。 |
| PaymentIntent | JourneyOrderId、businessKey、amount | 订单项明细、票证状态。 |
| Entitlement | SegmentBookingId、TravelerRef、credentialNo | 订单全量状态、支付渠道详情。 |
| PostSalesCase | JourneyOrderId、SegmentBookingId、EntitlementId、RuleSnapshot | 可售库存内部结构、渠道原始回调。 |

## 仓储边界

| Repository | 聚合根 | 查询方式 |
|---|---|---|
| OfferRepository | Offer | 按 OfferId、有效期、用户会话查询。 |
| JourneyOrderRepository | JourneyOrder | 按 OrderId、AccountId、TravelerId 查询。 |
| SegmentBookingRepository | SegmentBooking | 按 BookingId、OrderId、ProviderReference 查询。 |
| CapacityHoldRepository | CapacityHold | 按 Segment、CapacityUnit、OrderId、过期时间查询。 |
| PaymentRepository | PaymentIntent、Refund | 按 PaymentId、OrderId、channelTxnId、businessKey 查询。 |
| EntitlementRepository | Entitlement | 按 EntitlementId、BookingId、CredentialNo 查询。 |
| PostSalesRepository | PostSalesCase | 按 CaseId、OrderId、EntitlementId 查询。 |
| TransferRepository | TransferPlan | 按 JourneyId、前后 SegmentId 查询。 |
| DisruptionRepository | DisruptionCase | 按 DisruptionId、SegmentId、影响状态查询。 |

复杂列表、客服时间线、报表和搜索结果使用读模型，不通过聚合仓储拼装。

## 第一阶段聚合落地顺序

1. Offer：先让下单基于报价快照，而不是实时散落计算。
2. JourneyOrder：建立订单状态中心。
3. CapacityHold：建立可信占座和释放机制。
4. PaymentIntent：隔离支付状态和订单状态。
5. Entitlement：把出票和履约凭证从订单里拆出来。
6. PostSalesCase：把取消、退票、改签和退款规则集中。
7. SegmentBooking：为未来多交通方式和供应商接入打基础。
8. TransferPlan 和 DisruptionCase：支撑联乘和异常恢复。
