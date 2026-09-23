# notification

Notification 使用 TypeScript、Fastify、PostgreSQL、Redis Streams 和 Nodemailer。
它将业务事件转换为通知任务，保存发送回执，并提供发送记录查询。
通知结果只改变本域状态，不回滚订单、支付、票证或售后事实。

## 发布状态

线上 revision 279 仍使用 SMTP 位于 PostgreSQL 事务内的发送路径。
该路径在消费事件的事务中完成收件人处理、发送和回执保存，等待 SMTP 时仍占用数据库连接及收件人事务锁。

当前工作区的 durable queue 是未提交 WIP，尚未部署，新设计在线上未生效。
工作区包含 `006_durable_delivery.sql`、发送 worker、claim 租约、稳定 MessageID、事务外 SMTP、`Unknown` 结果及迟到结果记录。
`GET /api/v1/notifications/{id}/trail` 的追加字段也属于工作区未发布内容。

## 当前实现

`NotificationTask` 管理单个收件人的任务和追加式 `DeliveryReceipt`。
`Template`、`RecipientPolicy`、`TemplateRenderer`、`ChannelFallbackChain`、`RateLimiter` 和 `NotificationAggregator` 提供模板、渠道选择、频控及合并规则。
运行时使用代码内置模板、PostgreSQL 联系方式与 Preference 表，并按事件类型选择收件人。
`transactionRequired` 默认取 `true`，跳过普通 Preference 检查，仍受渠道 quota 限制。

Redis Streams 订阅覆盖订单、预订、支付、票证、售后、异常恢复、接续、附加服务、接送、候补和钱包。
支持的事件类型及发布载荷见[事件说明](../../docs/08-contracts/events/notification.md)。
接续事件在没有直接收件人时可按 `connection.travelerRefs` 生成多个任务。
事件幂等记录、收件人事务锁和任务唯一索引共同控制重复处理。
业务去重使用 `triggerBusinessRef`、`recipientRef`、`templateCode`，其中 `triggerBusinessRef` 包含事件类型。

配置 `SMTP_URL` 时，EMAIL 通过连接池发送邮件。
SMTP 邮箱由 `recipientRef` 推导为 `passengers.train-ticket.local` 地址，支持 Mailpit 查询。
PUSH、SMS 和 IN_APP 当前使用 `DirectSuccessGateway`；这些渠道返回成功不代表外部供应商已经完成触达。
EMAIL 的 `Delivered` 表示 SMTP 接受邮件，不表示用户已经阅读。

## 工作区 durable queue

消费事务保存任务、`NotificationScheduled` outbox 记录、quota 决策和发送队列后提交。
worker 使用 `FOR UPDATE SKIP LOCKED` 领取任务，并记录 claim token、30 秒租约和 attempt。
开始发送前通过独立事务校验 claim、检查 quota、保存 `Sending`，事务提交后才调用 SMTP。
发送结果通过另一事务保存任务回执、attempt、evidence 和 outbox。

SMTP MessageID 由任务 ID 的 SHA256 生成，同一任务的重试保持一致。
MessageID 用于关联邮件和发送证据，不提供 SMTP 端去重保证。
确定未发送且允许重试的失败在同一渠道最多发送 3 次，再按渠道链继续处理。
发送前 claim 到期可重新领取；发送开始后租约到期或结果无法确认时进入 `Unknown`，停止自动重发和渠道切换。
原 claim 的迟到确定结果可以追加回执并更新状态，已经记录的确定结果不会被较早的超时结果覆盖。

## HTTP 与运行依赖

业务 HTTP 接口为 `GET /api/v1/notifications/{id}/trail`。
通知由业务事件触发，发送、取消和回执命令没有 HTTP 写入口。
`/health`、`/healthz`、`/live`、`/livez`、`/ready`、`/readyz` 和 `/metadata` 提供健康及元数据查询。
字段和探针语义见[API 说明](../../docs/08-contracts/api/notification.md)。

持久化运行需要 `DATABASE_URL` 和 `REDIS_URL`。
`SMTP_MAX_CONNECTIONS` 默认值为 8，允许 1 至 128，同时作为工作区发送 worker 的默认并发数。
schema 应用状态及数据库探测参与 readiness，outbox relay 将已提交事件发送到 Redis Streams。

## 已有验证记录

最新通知验证记录为 compile PASS。
PostgreSQL/Mailpit durable delivery 验证包含 10 个子项及父项，共 11 PASS，耗时 20.8 秒。
验证涉及并发 claim、发送前租约恢复、SMTP 接受后的结果丢失、稳定 MessageID 重试、迟到结果、32 个任务并发发送、连接中断和有界重试。
完整 `npm test` 尚未运行，quota 延期后的恢复尚未专项验收。
这些结果属于工作区验证，不代表 revision 279 已启用 durable queue。

领域边界见[领域说明](../../docs/02-domains/notification.md)，发送机制见[能力说明](../../docs/10-domain-enrichment/notification-enrichment.md)。
待办统一见 [docs/TODO.md](../../docs/TODO.md)。
