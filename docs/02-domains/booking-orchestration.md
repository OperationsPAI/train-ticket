# Booking Orchestration 当前设计

更新日期：2026年9月24日。
运行基线为 Helm revision 279，服务使用 Java、PostgreSQL 和 Redis 事件处理。
统一待办见 [TODO](../TODO.md)。

## 领域边界

Booking Orchestration 管理 `SegmentBooking`、`BookingSaga`、预订请求记录和失败补偿状态。
每个预订绑定订单、行程段、旅客、用途及 Capacity Hold，确认后关联供应商结果和票证。
订单商业金额由 Journey Order 管理，库存与物理席位由 Capacity 和 Seat Assignment 管理，渠道资金由 Payment 管理，票证由 Entitlement Ticketing 管理。
Provider Integration 提供归一化供应商结果，Post Sales 决定退改范围及金额。

## 当前编排

`JourneyOrderCreated` 或内部 HTTP 请求启动 Saga，并发布风险评估请求。
风险结果 `PASS` 推进预订，`BLOCK` 使 Saga 失败，`CHALLENGE` 和 `HOLD` 进入人工审核。
服务通过预订请求和 Capacity 事件记录 Hold 与确认事实。
`SeatAllocated` 和 `StandingAssigned` 必须与预订的订单、旅客、行程段及 Hold 引用一致，随后保存 allocation 引用。
支付事实根据持久化的 PaymentIntent 与 Saga 关联推进流程。
`EntitlementIssued` 将预订标记为 `TICKETED`；票证事件早于必要预订确认时进入重试处理。
全部预订完成出票且 Saga 处于 `TICKETING` 时，进入 `INVOICING` 并发布 `InvoiceRequested`，对应 `InvoiceGenerated` 推进 Saga 完成。

`SegmentBooking` 的主要状态为 `REQUESTED`、`HOLDING`、`CONFIRMED`、`TICKETED`，并包含取消、变更、履约及失败状态。
`BookingSaga` 记录每个步骤的幂等键、超时、重试限制、结果和补偿动作。
HTTP Saga 状态是内部状态的公开映射，详情中的步骤结果提供具体执行信息。
领域模型中的超时和补偿字段不代表所有供应商查询恢复或多交通方式流程已被运行验证。

## accepted supply 引用

当前源码已经实现 accepted supply 解析与持久化，Helm revision 279 的生产 Booking 与 Capacity 尚未联合启用该集成。
这一运行边界适用于下述供应引用行为。

初始预订 HTTP 入口通过 `ReservationFactsResolver` 读取订单、已接受 Offer、准确的 fare quote 和 itinerary。
解析器核对账户、旅客集合、行程段、出发时刻和端点，取得 `classRef`、`offerItemRef`、`scheduledServiceRef` 和 `serviceDate`。
`serviceDate` 当前按出发时刻的 UTC 日期生成。
替代预订从已批准改签的目标 Offer 获取这些字段。
字段保存到预订快照，详情提供已保存供应引用，后续订单变化不替换该预订的供应来源。

预订业务键由订单、行程段、旅客和用途组成。
同一业务键的并发请求复用已有预订，商业引用冲突会被拒绝。
调用方必须使用返回的 `segmentBookingId`。
历史预订可能缺少这些字段，不能据此推断其库存归属或完整占用。
相关核对范围统一记录在 TODO。

## 改签与失败补偿

`POST /api/v1/internal/change-reservations` 接收 `caseId`、`segmentRef` 和 `travelerRef`。
入口核对已批准改签、原订单确认与风险条件、目标 Offer 的旅客和报价，使用独立改签 Saga 保存替代预订。
每个 Case、行程段和旅客复用同一替代预订，重复请求若改变 Offer item 或席别则被拒绝。

`POST /api/v1/internal/change-compensations/{bookingId}` 依据 Post Sales 保存的 `capacity-rejected` 证据取消替代预订。
原票作废或订单应用已经开始时，拒绝该补偿操作。
已签发替代票要求先确认票证作废。
成功取消保存 `CHANGE_CAPACITY_UNAVAILABLE` 并发布一次 `SegmentBookingCancelled`，重复调用复用终态。
原预订保留，Hold 释放、差价退款结算和原票验证由 Post Sales 在所属服务中继续核验。
这一改签失败补偿与发放钱包额度的 `COMPENSATION` Case 分属不同执行流程。

## API 与持久化

| 入口 | 用途 |
|---|---|
| `POST /api/v1/internal/booking-sagas` | 启动 Saga。 |
| `GET /api/v1/internal/booking-sagas/{sagaId}` | 查询 Saga 和步骤。 |
| `GET /api/v1/internal/booking-sagas/by-order/{orderId}` | 根据订单查询 Saga。 |
| `POST /api/v1/internal/booking-sagas/{sagaId}/request-reservation` | 请求单个旅客的行程段预订。 |
| `GET /api/v1/internal/segment-bookings/{segmentBookingId}` | 查询预订、Hold、票证和供应引用。 |
| `POST /api/v1/internal/booking-sagas/{sagaId}/mark-ticketed` | 记录票证签发结果。 |
| `POST /api/v1/internal/change-reservations` | 请求替代预订。 |
| `POST /api/v1/internal/change-reservations/{bookingId}/cancel` | 取消符合条件的替代预订。 |
| `POST /api/v1/internal/change-compensations/{bookingId}` | 根据容量拒绝证据执行替代预订补偿。 |

Saga、预订、消费记录和 Outbox 使用 PostgreSQL 持久化。
服务消费 Capacity、Seat Assignment、Payment、Provider、Entitlement、风险和发票结果，发布预订请求、确认、失败、出票、取消及 Saga 结果事件。
同一外部事件通过消费记录去重，晚到事件按当前聚合状态处理。

实现入口见 [服务说明](../../services/booking-orchestration/README.md)、[应用服务](../../services/booking-orchestration/src/main/java/com/trainticket/bookingorchestration/application/BookingOrchestrationService.java)、[供应引用解析](../../services/booking-orchestration/src/main/java/com/trainticket/bookingorchestration/application/ReservationFactsResolver.java) 和 [HTTP API](../08-contracts/api/booking-orchestration.md)。
