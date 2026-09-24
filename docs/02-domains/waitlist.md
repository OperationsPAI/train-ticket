# Waitlist Domain Design

更新日期：2026 年 9 月 24 日。
当前服务位于 `services/waitlist`，使用 TypeScript、Fastify、PostgreSQL 和 Redis Streams。

## 领域职责与边界

Waitlist 管理候补请求、优先分数、排队、匹配尝试、关联订单、确认和归档。
Capacity 拥有库存和 Hold，Journey Order 拥有商业订单，Payment 拥有资金操作，Entitlement 拥有票证。
当前服务已实现持久化队列、匹配尝试和订单确认处理。
剩余工作统一见 [docs/TODO.md](../TODO.md)。

当前 `WaitlistEntry` 表达一个 segment、席别、日期字段及旅客集合。
它不是多个互斥车次方案的完整组合模型。
`paymentGuaranteeRef` 为必填记录字段，创建请求会发布 `WaitlistPaymentAuthorizationRequested`，但当前流程不建立或结算实际支付授权。

## 请求与队列

请求以 `wlr-` 标识，保存账户、旅客、segment、席别、截止时间、意图指纹及下游操作的 UUID v7 幂等键。
数据库对首位旅客与 `intentFingerprint` 在 active 状态下建立唯一约束。
该约束没有分别保护全部同行旅客的互斥关系。
公共创建请求和资源以单个 `travelerRef` 为主要字段，内部数组支持不能视为全旅客流程已经完整。

队列优先级按分数降序、创建时间升序、entry 标识升序排序。
分数由会员等级、历史乘车次数、提前天数、同行人数、席别和特殊身份组成，并限制在 0 至 100。
分数来自请求输入，不能当作已经向会员或身份服务验证的资格结果。

当前 PostgreSQL 查询按 `segment_ref` 和可选 `seat_class` 过滤，忽略传入的 `departureDate`。
缺少请求日期时，应用从 deadline 提取日期。
当前席别类型包含 `BUSINESS`、`FIRST`、`SECOND`、`SECOND_CLASS` 和 `STANDING`。
报价请求未传递该席别，释放库存的 class 与候补 class 也没有完整规范化保证。

## 匹配与确认

有效释放触发到达后，匹配器选择队首未过期请求，调用 Fare Pricing 获得 `quoteId`，调用 Offer Management 获得 `offerId` 与 `offerVersion`。
应用记录 Offer，将请求改为 `MATCHING`，发布 `WaitlistMatchStarted`，并创建实际 Journey Order。
当前匹配路径不直接调用 Capacity Hold client。
普通订单链路负责后续容量和出票操作，候补服务不能因创建订单成功就宣称兑现成功。

| 当前状态与输入 | 当前结果 |
|---|---|
| 创建请求 | 直接进入 `QUEUED`，发布请求创建、支付授权请求和排队事件。 |
| `QUEUED` 匹配有效释放 | 保存报价和 Offer，进入 `MATCHING` 并关联订单。 |
| `MATCHING` 收到匹配的 `JourneyOrderConfirmed` | 进入 `FULFILLED`，发布 `WaitlistFulfilled`。 |
| `MATCHING` 收到 `JourneyOrderCancelled` | 清除本次订单和 Offer，更新尝试键；截止时间内回到 `QUEUED`，否则进入 `EXPIRED`。 |
| 未关联处理中的订单且超过截止时间或 Offer 窗口 | 过期扫描进入 `EXPIRED`。 |
| `DRAFT`、`QUEUED`、`SUSPENDED` 收到取消命令 | 进入 `CANCELLED`；`MATCHING` 不允许直接取消。 |
| `FULFILLED`、`EXPIRED`、`CANCELLED` | 归档扫描进入 `CLOSED`，保留记录引用。 |

`DRAFT` 和 `SUSPENDED` 存在于类型中，当前公开应用流程没有完整的草稿或暂停操作。
已关联订单的 `MATCHING` 请求不会被普通 Offer 过期扫描直接结束。
已兑现或已归档请求重放订单确认不再发布成功事件。

## 消息边界

订阅流为 `events:capacity-availability` 和 `events:journey-order`。
`WaitlistCapacityFreed` 必须包含 segment、日期和释放名额。
`CapacityReleased` 只在具有匹配字段时触发候补；缺少日期会被确认消费并跳过。
当前普通 Capacity 释放事实缺少 `departureDate`，自动从这些释放兑现候补尚未得到完整验证。
Capacity 的联合 supply 计算已在提交 `caafa668` 中实现，尚未部署，Booking coordinated 未启用。

订单确认和取消通过 `journeyOrderRef` 的数据库表达式索引定位请求。
事件去重、请求保存和 outbox 追加通过存储事务执行。
可重试数据库或上游连接错误交给消息重试处理。
Notification 消费 `WaitlistFulfilled`，候补服务不直接发送成功通知。

## HTTP 与运行方式

| 接口 | 用途 |
|---|---|
| `POST /api/v1/waitlist-requests` | 创建并排队。 |
| `GET /api/v1/waitlist-requests` | 按旅客查询和分页。 |
| `GET /api/v1/waitlist-requests/{waitlistRequestId}` | 读取候补请求。 |
| `POST /api/v1/waitlist-requests/{waitlistRequestId}/cancel` | 取消请求。 |
| `POST /api/v1/waitlist/entries/{entryId}/accept` | 对当前有效匹配尝试创建或重放订单关联。 |
| `GET /api/v1/waitlist/segments/{segmentRef}/{departureDate}/queue` | 查询队列信息；当前数据库不按日期过滤。 |
| `POST /api/v1/waitlist/archive-sweep` | 归档可归档请求。 |

运行进程包含 Offer 过期扫描、归档扫描和消息订阅恢复。
公共字段见 [Waitlist HTTP API](../08-contracts/api/waitlist.md)。
优先分数、已有确认回归与验证边界见 [waitlist-impl.md](../10-domain-enrichment/waitlist-impl.md)。
