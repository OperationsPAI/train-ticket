# 订单、库存、支付、出票核心一致性模型

Last updated: 2026-06-28

## 目的

订单、库存、支付、出票是 Train Ticket 第一阶段重构最核心的业务三角。严格来说它不是三角，而是四个独立生命周期：

1. `JourneyOrder`：用户买了什么。
2. `CapacityHold`：资源是否被锁定或正式占用。
3. `PaymentIntent`：钱是否收到了。
4. `Entitlement`：用户是否拿到了可使用的票证权益。

这四个生命周期不能再混成一个订单状态字段。本文件把它们的边界和协作方式单独定清楚。

## 核心对象

| 对象 | 所属上下文 | 代表的业务事实 | 不代表什么 |
|---|---|---|---|
| JourneyOrder | Journey Order | 用户提交并购买一个 Journey | 不代表库存已锁，不代表支付成功，不代表已出票。 |
| SegmentBooking | Booking Orchestration | 某一段服务正在或已经被供应确认 | 不代表整单成功。 |
| CapacityHold | Capacity & Availability | 某个容量资源被临时锁定或正式占用 | 不代表已收款。 |
| PaymentIntent | Payment | 用户对订单或差价的支付请求和渠道状态 | 不代表可乘车。 |
| Refund | Payment | 对原支付或补偿的退款状态 | 不代表票已作废。 |
| Entitlement | Entitlement & Ticketing | 用户可使用的票证或权益 | 不代表资金已经结算完成。 |

## 正常下单链路

| Step | 聚合 | 状态变化 | 领域事件 |
|---|---|---|---|
| 1 | Offer | Quoted | OfferQuoted |
| 2 | JourneyOrder | Draft -> PendingConfirmation | JourneyOrderCreated |
| 3 | SegmentBooking | Requested -> Holding/Confirmed | SegmentReservationConfirmed |
| 4 | CapacityHold | Requested -> Held | CapacityHeld |
| 5 | JourneyOrder | PendingConfirmation -> PendingPayment | JourneyOrderPendingPayment |
| 6 | PaymentIntent | Created -> PendingAction -> Captured | PaymentCaptured |
| 7 | CapacityHold | Held -> Confirmed | CapacityHoldConfirmed |
| 8 | Entitlement | PendingIssue -> Issued | EntitlementIssued |
| 9 | SegmentBooking | Confirmed -> Ticketed | SegmentTicketed |
| 10 | JourneyOrder | Confirming -> Confirmed | JourneyOrderConfirmed |

## 订单确认条件

JourneyOrder 进入 `Confirmed` 必须满足：

1. 所有必需 SegmentBooking 都是 `Confirmed` 或 `Ticketed`。
2. 所有必需付款对应的 PaymentIntent 已 `Captured`，或规则允许后付/担保。
3. 所有必需 Entitlement 已 `Issued`。
4. 所有必需 CapacityHold 已 `Confirmed`，或外部供应商确认替代内部库存确认。
5. 没有未解决的阻断型风险或风控冻结。

不满足上述条件时，订单只能停留在 `PendingConfirmation`、`PendingPayment`、`Confirming` 或 `PartiallyConfirmed`。

## 库存模型

### 固定班次库存

| 维度 | 火车 | 大巴 | 轮船 | 飞机 |
|---|---|---|---|---|
| 服务 | 车次 | 班次 | 船班 | 航班 |
| 日期 | serviceDate | serviceDate | sailingDate | flightDate |
| 容量单元 | 座位/铺位/无座 | 座位/余座 | 舱房/铺位/车辆甲板 | 舱位/座位 |
| 销售范围 | 区间 | 起终点/站点段 | 航段/港口段 | 航段 |
| 复用规则 | 区间不重叠可复用 | 通常较简单 | 舱房和车辆空间复杂 | 由航司库存规则控制 |

### 火车区间库存不变量

同一座位可以售卖给多个不重叠区间，但不能售卖给重叠区间。

示例：

| 已占用区间 | 新请求区间 | 是否允许 |
|---|---|---|
| A -> C | C -> E | 允许 |
| A -> C | B -> D | 不允许 |
| B -> D | A -> B | 允许 |
| B -> D | D -> E | 允许 |
| B -> D | C -> D | 不允许 |

这要求库存检查使用站序和区间重叠判断，不能只用 `seatId` 或 `leftTicketCount`。

## 支付模型

### 支付和订单的关系

| 场景 | 处理 |
|---|---|
| 用户首次下单支付 | JourneyOrder 发布待支付商业事实，Booking Orchestration 或 Payment Open Host Service 创建 PaymentIntent。 |
| 改签补差价 | PostSalesCase 发布差价支付请求，Payment 创建新的 PaymentIntent。 |
| 候补担保 | WaitlistRequest 发布担保请求，Payment 创建 Authorization 或 PaymentIntent。 |
| 网约车预授权 | Ride Segment 创建 Authorization，行程结束后 Capture。 |
| 退款 | PostSalesCase 或 DisruptionCase 创建 Refund。 |

PaymentIntent 不应该修改订单状态。它只发布 `PaymentCaptured`、`PaymentFailed`、`PaymentExpired` 等事件，由 Saga 推进订单。

### 晚到支付

| 订单状态 | 晚到 PaymentCaptured 处理 |
|---|---|
| PendingPayment | 正常推进。 |
| Cancelled | 创建 LatePaymentCase，自动退款或人工确认。 |
| Failed | 创建 PaymentMismatchCase，人工处理。 |
| Confirmed | 幂等忽略或标记重复回调。 |

## 出票模型

Entitlement 的签发条件：

1. SegmentBooking 已 Confirmed。
2. Payment 条件满足。
3. CapacityHold 已 Confirmed，或供应商确认已替代内部库存。
4. Traveler 资格仍有效。
5. 未命中阻断型风控。

Entitlement 签发失败时：

1. 可重试错误：保留订单 Confirming，重试出票。
2. 业务拒绝：释放库存并退款，订单 PartiallyConfirmed 或 Failed。
3. 供应商未知：查询最终状态，禁止重复出票。
4. 长时间无法确认：进入人工工单。

## 取消未支付

| 对象 | 目标状态 |
|---|---|
| JourneyOrder | Cancelled |
| SegmentBooking | Cancelled |
| CapacityHold | Released 或 Expired |
| PaymentIntent | Cancelled 或 Expired |
| Entitlement | 不应存在；若已存在则说明流程异常 |
| Refund | 不应存在，除非有晚到支付 |

## 退票

| 对象 | 目标状态 |
|---|---|
| PostSalesCase | Applied |
| Entitlement | Voided |
| SegmentBooking | Cancelled |
| CapacityHold | Released |
| Refund | Requested -> Settled 或 Failed |
| JourneyOrder | Cancelled、PartiallyConfirmed 或保持 Confirmed 但订单项取消 |

退票不能先退款再作废票证。否则可能出现用户拿着有效票且已经收到退款。

## 改签

改签涉及旧票和新票两个生命周期。

| 对象 | 旧票 | 新票 |
|---|---|---|
| SegmentBooking | Changed 或 Cancelled | Confirmed/Ticketed |
| CapacityHold | Released | Held -> Confirmed |
| Entitlement | Voided | Issued |
| PaymentIntent/Refund | 补差价或退差价 | 同一 PostSalesCase 下关联 |

保守顺序：

1. 校验旧票可改。
2. 为新方案创建 Offer。
3. 锁新库存。
4. 处理差价。
5. 作废旧票。
6. 确认新 Booking。
7. 签发新 Entitlement。
8. 释放旧库存。

## 关键失败场景

| 失败场景 | 不能做什么 | 应该做什么 |
|---|---|---|
| 库存 Hold 成功，支付失败 | 不能保留库存 | 释放 Hold，订单回到可支付或取消。 |
| 支付成功，出票失败 | 不能显示已出票 | 重试出票；失败后退款或人工。 |
| 出票成功，订单确认失败 | 不能重复出票 | 重放汇总事件或人工修复订单状态。 |
| 退票作废成功，退款失败 | 不能恢复票 | 退款重试或人工处理。 |
| 改签新票失败，旧票未作废 | 不能取消旧票 | 释放新库存，保留旧票。 |
| 改签新票失败，旧票已作废 | 不能静默失败 | 尝试恢复旧票、重试新票或人工补偿。 |

## 读模型

| 读模型 | 来源 | 用途 |
|---|---|---|
| OrderSummary | JourneyOrder + SegmentBooking + Payment + Entitlement | 用户订单列表。 |
| OrderTimeline | 所有相关领域事件 | 客服和用户查看过程。 |
| PaymentStatusView | PaymentIntent + Refund | 支付和退款详情。 |
| TicketView | Entitlement + SegmentBooking | 票证展示和核验。 |
| InventoryAuditView | CapacityHold + Booking + PostSales | 库存差异排查。 |

读模型允许冗余，但所有写入必须回到聚合命令。

## 第一阶段验收标准

第一阶段重构完成时，至少应满足：

1. 下单必须引用 Offer 快照。
2. 订单状态、支付状态、票证状态、库存状态分开存储。
3. 库存锁有过期时间和释放机制。
4. 支付回调幂等。
5. 支付成功但出票失败可观察、可重试、可人工。
6. 未支付取消不会创建退款。
7. 已出票退票必须先作废票证再退款。
8. 改签失败时旧票和新票状态可解释。
9. 所有跨上下文事件走 Outbox/Inbox。
10. 客服能看到完整 OrderTimeline。
