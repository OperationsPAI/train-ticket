# Waitlist Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Waitlist |
| Status | accepted-ddd-baseline |
| Phase | future-scope |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/03-ddd-final/decision-record.md`, `docs/03-ddd-final/domain-reduce-status.md`, `docs/01-ddd-high-level/consistency-and-saga.md` |

## 1. 领域目标

Waitlist 负责无票或库存不足时的候补请求、队列排序、公平性、支付担保、互斥方案和自动兑现。它不属于 Capacity & Availability，也不属于 Booking Orchestration。

独立建模的原因是：候补不是普通库存释放后的即时抢占，而是带有排队、公平、截止时间、支付授权和互斥方案的业务承诺。如果把候补塞进 Capacity，会污染库存不变量；如果塞进 Booking，会让下单 Saga 承担长期队列状态。

## 2. 边界

### In Scope

- `WaitlistRequest` 创建、取消、过期、冻结和兑现。
- 候补队列排序、公平性、优先窗口和库存回流策略。
- 多车次、多席别、互斥候补方案。
- 候补支付担保或预授权引用。
- 释放库存后的候补匹配和 Hold 授权。

### Out of Scope

- 真实库存数量和座席区间冲突，归 Capacity & Availability。
- 用户商业订单，归 Journey Order。
- 支付授权和扣款，归 Payment。
- 出票，归 Entitlement & Ticketing。

## 3. 聚合

| Aggregate | Invariants |
|---|---|
| WaitlistRequest | 同一旅客、同一出行意图、互斥方案只能有一个 active request；必须有截止时间、候补策略和支付担保策略。 |
| WaitlistQueue | 同一 queue partition 内排序规则稳定；人工调整必须有审计引用；释放库存优先窗口不可跳过队列公平性。 |

## 4. 上游和下游契约

| Direction | Context | Contract |
|---|---|---|
| Upstream | Trip Planning / Offer Management | 候补候选方案、可候补席别、用户确认的候补条件。 |
| Upstream | Payment | `PaymentAuthorized`、`AuthorizationExpired`、`PaymentCaptured`。 |
| Upstream | Capacity & Availability | `CapacityReleased`、`CapacityAdjusted`、可候补库存信号。 |
| Downstream | Capacity & Availability | `AuthorizeWaitlistHold`，请求为队首候补锁库存。 |
| Downstream | Booking Orchestration | `StartWaitlistBooking`，候补命中后复用正常 Booking 链路。 |
| Downstream | Notification | 候补成功、失败、过期、需要用户确认。 |

## 5. 状态机

| State | Meaning | Next |
|---|---|---|
| Draft | 用户正在选择候补方案。 | Queued, Cancelled |
| Queued | 已进入候补队列。 | Matching, Expired, Cancelled, Suspended |
| Matching | 已收到可用库存信号并尝试兑现。 | Fulfilled, Queued, Failed |
| Fulfilled | 已成功锁库存并进入 Booking。 | Closed |
| Expired | 截止时间到达未兑现。 | Closed |
| Cancelled | 用户取消或业务取消。 | Closed |
| Suspended | 支付担保、风控或资料问题导致暂停。 | Queued, Cancelled |
| Closed | 终态。 | - |

## 6. 命令和事件

| Command | Event |
|---|---|
| CreateWaitlistRequest | WaitlistRequestCreated |
| AuthorizeWaitlistPayment | WaitlistPaymentAuthorizationRequested |
| EnqueueWaitlist | WaitlistQueued |
| MatchReleasedCapacity | WaitlistMatchStarted |
| AuthorizeWaitlistHold | WaitlistHoldAuthorized |
| FulfillWaitlist | WaitlistFulfilled |
| CancelWaitlist | WaitlistCancelled |
| ExpireWaitlist | WaitlistExpired |

## 7. Final DDD Decision

Waitlist 已裁定为独立 future-scope 上下文，不进入第一阶段火车主票现金支付闭环。第一阶段 Capacity 只需要保留 `CapacityReleased` 等事件，不实现候补队列。
