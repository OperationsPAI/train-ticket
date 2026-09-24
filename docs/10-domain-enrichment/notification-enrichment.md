# Notification 多渠道发送与持久化调度

## 当前发布状态

服务位于 `services/notification`，使用 TypeScript、Fastify、PostgreSQL、Redis Streams 和 Nodemailer。
线上 revision 279 具备事件映射、模板渲染、渠道选择、频控、合并、延期记录和发送回执，但 SMTP 仍位于 PostgreSQL 消费事务内。
该路径等待 SMTP 时占用数据库连接和收件人事务锁。
durable queue 已在提交 `41917b85` 中实现，尚未部署，新设计在线上未生效。
本文其余处提到的工作区行为均来自该提交。
本文的 claim、事务外 SMTP、稳定 MessageID、`Unknown` 以及 API 追加字段均属于尚未发布的仓库能力。

## 模板与渠道

`TemplateRenderer` 使用 `builtInNotificationTemplates()` 中的模板，为 IN_APP、EMAIL、SMS 和 PUSH 生成不同内容。
模板变量来自事件映射，渲染结果保存在发送变量的 `subject`、`body` 和预览字段中。
运行时未从 `notification_templates` 表加载模板。
SMTP 网关实际使用 `[train-ticket] <templateCode>` 作为邮件主题，并将发送变量渲染为文本邮件。

`ChannelFallbackChain` 以映射指定的渠道开始，再按 PUSH、SMS、EMAIL 顺序补充可用渠道。
设备 token、电话号码和邮箱字段决定外部渠道是否可选。
IN_APP 映射只使用 IN_APP。
当前应用服务没有使用 `preferredChannel` 改写映射指定的首选渠道。

配置 `SMTP_URL` 时，EMAIL 使用 Nodemailer 连接池。
收件地址由 `recipientRef` 推导，默认使用 `passengers.train-ticket.local` 域名。
PUSH、SMS、IN_APP 以及未配置 SMTP 的路径使用 `DirectSuccessGateway`。
当前 EMAIL 回执证明 SMTP 接受，其他渠道的直接成功结果不构成外部触达验证。

## quota 与合并规则

| 渠道 | 每个收件人的 quota | 全局 quota |
|---|---|---|
| PUSH | 每小时 10 次。 | 无额外限制。 |
| SMS | 每 24 小时 5 次。 | 每分钟 1000 次。 |
| EMAIL | 每 24 小时 20 次。 | 无额外限制。 |
| IN_APP | 每小时 50 次。 | 无额外限制。 |

`transactionRequired` 默认值为 `true`，跳过普通 Preference 检查，quota 仍然生效。
PostgreSQL 使用 `notification_rate_limits` 保存窗口内发送记录。
工作区通过 `task_id` 与渠道的唯一索引预留 quota，同一任务在同一渠道重试时复用已有预留。
工作区 SMS 全局 quota 检查使用专用事务 advisory lock，收件人 quota 检查由收件人事务锁协调。
超过 quota 的工作区任务保留 `Ready`，通过 `available_at` 和 `QuotaDeferred` 证据记录下一次允许处理的时间。
代码已经包含延期恢复路径，但 quota 延期恢复尚未专项验收。

`NotificationAggregator` 按收件人和业务引用保存最近的可合并通知。
在 5 分钟窗口内，已有通知优先级更高或相同时抑制当前任务创建。
此规则直接判定是否抑制，不会等待 5 分钟再批量发送。
恢复、接续、附加服务、接送和司机进度等事件不参与该合并规则。
现有 `notification_deferred` 保存事件级延期记录，后台每秒扫描，每次最多处理 8 条到期事件。

## 工作区 durable queue 与事务边界

`006_durable_delivery.sql` 定义 `notification_delivery_queue`、`notification_delivery_attempts` 和 `notification_delivery_evidence`。
每个任务对应一条队列记录，保存渠道计划、当前渠道位置、可处理时间、claim、租约、attempt 编号和稳定 MessageID。

| 操作 | PostgreSQL 事务内工作 | 事务外工作 |
|---|---|---|
| 接收事件 | 消费去重、解析收件人、取得收件人锁、保存任务及 `NotificationScheduled` outbox、quota 决策、发送队列。 | 事务提交后确认事件处理成功。 |
| 领取任务 | `FOR UPDATE SKIP LOCKED` 选择到期 `Ready` 任务，写入 claim token、30 秒租约、attempt 和证据。 | worker 保留任务 ID 与 claim token。 |
| 开始发送 | 校验 claim 及租约、检查 quota、保存 `Sending`、发送开始证据及首次 `NotificationDispatched`。 | 调用 SMTP，等待结果时不占用该数据库连接或收件人锁。 |
| 保存结果 | 校验 claim 所有权，保存追加回执、attempt、evidence、队列状态和通知 outbox。 | outbox relay 发布已提交事件。 |

`SMTP_MAX_CONNECTIONS` 默认值为 8，允许 1 至 128。
工作区发送 worker 默认使用同一并发配置，空队列轮询等待 250 毫秒，租约恢复循环每秒执行。
结果持久化最多进行 5 次尝试，期间不会重新调用 SMTP。

## 失败分类与恢复

| 条件 | 工作区行为 |
|---|---|
| SMTP 接受至少一个收件人 | 保存 `Delivered`。 |
| 明确连接拒绝、DNS 错误或 SMTP 4xx | 记录确定失败并允许重试，同一渠道最多发送 3 次，等待时间为 1 秒、2 秒。 |
| SMTP 5xx 或没有接受的收件人 | 当前渠道不重试，有后续渠道时继续处理，否则保存 `Failed`。 |
| 5 秒等待期限结束、发送中连接中断或其他结果无法确认的异常 | 保存 `Unknown`，停止自动重发和渠道切换。 |
| `Claimed` 租约到期 | attempt 记录 `AbandonedBeforeSend`，队列返回 `Ready`，旧 claim 无法开始发送。 |
| `Sending` 租约到期 | 保存 `Unknown` 和 `SendLeaseExpired` 证据。 |
| 原 claim 的迟到确定结果 | 追加回执及结果证据，更新任务及队列状态；已经保存的确定结果不会被较早的未知结果覆盖。 |

稳定 MessageID 格式为 `<SHA256(taskId)@notification.train-ticket.local>`。
它用于关联 SMTP 邮件、任务、attempt 和 evidence，不提供 SMTP 端去重保证。
当前没有人工处理 `Unknown` 的 HTTP 写接口。

## 查询与事件

`GET /api/v1/notifications/{id}/trail` 已有任务状态及回执查询。
工作区追加 `resultUnknown`、`delivery`、`attemptId`、`messageId`、`retryAt` 和 `Unknown` 相关状态值，均未发布。
工作区查询通过 `REPEATABLE READ READ ONLY` 事务读取任务与发送证据。
顶层 `attempts` 由回执生成，`delivery.attempts` 则记录实际领取与发送过程。
字段详见[API 说明](../08-contracts/api/notification.md)。

现有发布事件为 `NotificationScheduled`、`NotificationDispatched`、`NotificationDelivered`、`NotificationFailed` 和 `NotificationCancelled`。
工作区新增 `NotificationDeliveryUnknown`。
确定失败的单次回执可以发布 `NotificationFailed`，任务是否最终失败需结合状态判断。
完整输入映射及载荷见[事件说明](../08-contracts/events/notification.md)。

## 已有验证记录

最新 compile 结果为 PASS。
PostgreSQL/Mailpit durable delivery 包含 10 个子项及父项，共 11 PASS，耗时 20.8 秒。
该专项测试覆盖入队后发送、并发 claim、旧 claim 失效、SMTP 接受后的结果丢失、稳定 MessageID 重试、事务外 SMTP、迟到结果竞争、32 个任务发送、连接中断、3 次有界重试及来源事件去重。
完整 `npm test` 尚未运行，quota 延期恢复未专项验收。
上述结果属于工作区验证，线上 revision 279 仍使用事务内 SMTP 路径。
待办统一见 [docs/TODO.md](../TODO.md)。
