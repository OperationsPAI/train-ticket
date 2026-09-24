# Notification 领域说明

## 实现与发布状态

本域实现位于 `services/notification`，使用 TypeScript、Fastify、PostgreSQL、Redis Streams 和 Nodemailer。
本文根据仓库当前实现描述模型及运行机制，该实现已在 revision 280 部署。
线上发送在数据库事务之外完成，发送期间不占用数据库连接及收件人事务锁。
durable queue 来自提交 `41917b85`，事务外发送、claim、`Unknown` 和 API 追加字段已在线上生效。

## 领域边界

Notification 将上游事实转换为收件人的通知任务，负责事件映射、模板变量、渠道选择、频控、去重、发送及回执查询。
Notification 不修改 Journey Order、Capacity、Payment、Entitlement 或 Post Sales 的业务状态。
通知发送成功不构成交易完成条件，发送失败不回滚上游事实。

当前本地数据包括联系方式、Preference、业务对象到收件人的引用、任务快照、频控记录、合并记录和 outbox。
联系方式缺少邮箱时，存储适配器使用由收件人引用生成的本地邮箱。
代码没有订阅 Account 或 Traveler Profile 的联系方式变更事件。
当前 HTTP 业务能力为发送记录查询，未提供 Inbox 已读、归档、模板审批、营销管理或供应商回调入口。

## 模型及状态

| 模型 | 当前责任 |
|---|---|
| `NotificationTask` | 保存来源事件、业务引用、收件人、模板、渠道、变量、任务状态及回执。 |
| `DeliveryReceipt` | 追加一次发送结果，保留 provider 信息及记录时间。 |
| `Template` | 保存变量 schema、渠道版本与 `Draft`、`Validated`、`Published`、`Retired` 状态定义。 |
| `RecipientPolicy` | 表达允许渠道、顺序、重试及频控配置；运行时发送链由应用服务构造。 |
| `TemplateRenderer` | 使用代码内置模板生成 subject、title 和 body。 |
| `ChannelFallbackChain` | 以映射指定的首选渠道开始，再按 PUSH、SMS、EMAIL 顺序补充可用渠道。 |
| `NotificationAggregator` | 按收件人和业务引用，在 5 分钟窗口中抑制符合规则的通知。 |

任务通常从 `Planned` 进入 `Delivering`，再由回执进入 `Delivered` 或 `Failed`。
`Authorized` 是允许 dispatch 的状态，当前调度路径直接使用 `Planned`。
普通 Preference 禁用时，应用服务创建任务并发布 `NotificationCancelled`，原因是 `SUPPRESSED_BY_PREFERENCES`。
`transactionRequired` 默认值为 `true`，只跳过普通 Preference 检查，quota 仍然生效。
未发布实现新增 `Unknown`，表示发送结果无法确认；确定失败且仍有重试或后续渠道时，任务保持 `Delivering`。

## 事件输入与收件人

实际订阅为 `events:journey-order`、`events:booking-orchestration`、`events:payment`、`events:entitlement-ticketing`、`events:post-sales`、`events:disruption-recovery`、`events:transfer-management`、`events:ancillary-service`、`events:dispatch`、`events:waitlist` 和 `events:wallet-promotion`。
订阅 stream 不表示其中所有事件都会创建任务，应用服务只处理已定义的映射。
不支持的类型直接确认，已支持类型会校验 envelope 和必要 payload 字段。

| 场景 | 收件人与渠道规则 |
|---|---|
| 订单、支付、退款、售后 | 使用事件中的直接收件人或账户引用，必要时从业务引用缓存及上游 HTTP 查询补充账户。 |
| 票证签发 | 优先 `travelerRef`，首选 EMAIL，模板代码为 `ticket_issued`。 |
| 接续风险、错过、恢复 | 优先直接收件人，否则使用 `connection.travelerRefs` 中所有去重后的旅客引用，首选 PUSH。 |
| 异常恢复 | 使用直接收件人或订单引用，渠道为 IN_APP。 |
| 附加服务、接送 | 优先对应旅客引用，首选 PUSH。 |
| 钱包权益 | 使用 `accountId`，仅映射发放、到期及撤销事件。 |

收件人解析与任务创建保持在数据库事务中。
上游 HTTP 查询只用于补充发送对象，不判定上游业务结果。
模板和事件载荷见[事件说明](../08-contracts/events/notification.md)。

## 持久化与幂等

`processed_events` 按 `eventId` 防止重复消费。
`notification_task_snapshots` 通过 version 保存并发更新，并设置来源事件加收件人加模板的唯一索引。
另一个唯一索引使用 `triggerBusinessRef`、`recipientRef`、`templateCode`。
`triggerBusinessRef` 由事件类型及业务对象引用组成，允许同一业务对象的不同事件分别通知。
多收件人按排序后的引用取得事务 advisory lock，每个收件人处理使用 savepoint。
任务保存与通知 outbox 记录在同一事务中提交，relay 异步发布到 `events:notification`。

`notification_deferred` 保存事件延期信息，`notification_aggregation` 保存合并窗口信息。
延期扫描每秒运行一次，每次最多处理 8 条到期事件。
未发布实现新增 `notification_delivery_queue`、`notification_delivery_attempts` 和 `notification_delivery_evidence`，并增加按任务及渠道唯一的 quota 预留记录。

## 未发布的 durable queue

消费事务保存 `Planned` 任务、`NotificationScheduled` outbox、渠道计划、quota 决策和队列记录后提交。
队列领取使用 `FOR UPDATE SKIP LOCKED`，每次创建独立 claim token、attempt 编号和 30 秒租约。
开始发送的事务检查 claim 所有权及租约、取得收件人锁、检查 quota，并写入 `Sending` 和发送开始证据。
该事务提交后调用 SMTP，SMTP 等待期间不持有该数据库连接或收件人锁。
结果事务保存追加回执、队列状态、attempt 结果、证据和通知 outbox。

| 队列状态 | 含义与后续处理 |
|---|---|
| `Ready` | 等待 `available_at`，包括 quota 延期、确定失败重试及后续渠道。 |
| `Claimed` | worker 已领取且尚未开始发送，租约到期后可返回 `Ready`。 |
| `Sending` | 已记录发送开始，等待渠道结果。 |
| `Unknown` | 发送结果无法确认，停止自动重发及渠道切换，接受原 claim 的迟到确定结果。 |
| `Delivered` | 渠道确认接受，完成本任务发送。 |
| `Failed` | 确定失败且可用尝试与渠道已经耗尽。 |

确定可重试失败在同一渠道最多发送 3 次，等待时间为 1 秒、2 秒，再考虑后续渠道。
`Sending` 租约到期会记录 `Unknown`；`Claimed` 租约到期则记录 `AbandonedBeforeSend`，旧 claim 无法开始发送。
稳定 MessageID 由任务 ID 的 SHA256 生成，用于关联证据，不保证 SMTP 供应商去重。
SMTP 的 5 秒期限表示本次等待结束，无法确定发送结果时使用 `Unknown`。
迟到结果追加记录，先保存的确定结果不会被较早产生的 `Unknown` 覆盖。

## 查询与验证边界

`GET /api/v1/notifications/{id}/trail` 返回任务状态和按回执生成的发送记录。
未发布实现使用 `REPEATABLE READ READ ONLY` 查询任务、队列、attempt 和 evidence，追加 `resultUnknown` 与 `delivery` 等字段。
字段的发布状态见[API 说明](../08-contracts/api/notification.md)。
EMAIL 的 `Delivered` 表示 SMTP 接受邮件；PUSH、SMS、IN_APP 使用直接成功适配器，当前没有外部供应商送达验证。

最新记录为 compile PASS，PostgreSQL/Mailpit durable delivery 共 10 个子项及父项 11 PASS，20.8 秒。
完整 `npm test` 尚未运行，quota 延期恢复未专项验收。
线上 revision 279 仍使用事务内 SMTP 路径，该验证不构成部署完成证明。
待办统一见 [docs/TODO.md](../TODO.md)。
