# Notification 事件与内部命令

## 发布状态与传输

Notification 通过 Redis Streams 消费上游业务事件，向 `events:notification` 发布通知事实。
consumer group 为 `notification`，consumer name 为 `notification-<HOSTNAME 或进程 ID>`。
持久化运行时，任务及 outbox 在 PostgreSQL 事务内提交，relay 再发布事件。

线上 revision 279 仍在消费事务内调用 SMTP。
durable queue 已在提交 `41917b85` 中实现，尚未部署，新设计在线上未生效。
`NotificationDeliveryUnknown`、`Unknown` 结果以及 attempt 关联字段属于该提交中尚未发布的内容。
`NotificationScheduled` 表示任务已经创建，该提交中可发送任务的队列记录与该事件在同一事务中提交。
普通 Preference 抑制的任务会记录 scheduled 和 cancelled 事件，不进入发送队列。
未发布实现的 `NotificationDispatched` 在 worker 开始发送的事务中生成。

## 发布事件

公共 envelope 包含 `eventId`、`eventType`、`schemaVersion`、`occurredAt`、`correlationId`、可选 `causationId`、`producer` 和 `payload`。
本域当前发布的 `schemaVersion` 为 1，`producer` 为 `notification`。
时间序列化为 RFC3339 UTC 字符串，任务 ID 为 `nt-<uuid>`，回执 ID 为 `rct-<uuid>`。
事件载荷不直接携带完整任务变量。

### NotificationScheduled

| payload 字段 | 类型 | 必须 | 说明 |
|---|---|---|---|
| `notificationTaskId` | string | 是 | 通知任务 ID。 |
| `templateCode` | string | 是 | 实际模板代码。 |
| `recipientRef` | string | 是 | 收件人引用，可以是账户、旅客或映射使用的业务引用。 |
| `channel` | enum | 是 | `EMAIL`、`SMS`、`PUSH`、`IN_APP`。 |
| `intent` | string | 是 | 通知目的。 |
| `transactionRequired` | boolean | 是 | 是否跳过普通 Preference 检查。 |
| `scheduledAt` | RFC3339 UTC | 是 | 任务创建时间。 |
| `templateType` | string | 否 | `templateCode` 匹配内置模板类型时追加。 |

`ticket_issued` 使用 `TICKET_ISSUED` 渲染模板，但事件保留小写 `templateCode`，此时不追加 `templateType`。

### NotificationDispatched

| payload 字段 | 类型 | 必须 | 说明 |
|---|---|---|---|
| `notificationTaskId` | string | 是 | 通知任务 ID。 |
| `channel` | enum | 是 | 任务初始发送渠道。 |
| `dispatchedAt` | RFC3339 UTC | 是 | 开始 dispatch 的记录时间。 |

此事件表示开始发送处理，不证明渠道已经接受。
后续重试及渠道切换通过回执和未发布队列 attempt 记录。

### NotificationDelivered

| payload 字段 | 类型 | 必须 | 说明 |
|---|---|---|---|
| `notificationTaskId` | string | 是 | 通知任务 ID。 |
| `channel` | enum | 是 | 本次回执对应渠道。 |
| `receiptId` | string | 是 | 回执 ID。 |
| `outcome` | enum | 是 | `Delivered`。 |
| `deliveredAt` | RFC3339 UTC | 是 | 接受结果记录时间。 |

EMAIL 的 `Delivered` 表示 SMTP 已接受邮件，不表示用户阅读或最终邮箱送达。
PUSH、SMS 和 IN_APP 当前使用 `DirectSuccessGateway` 返回成功。

### NotificationFailed

| payload 字段 | 类型 | 必须 | 说明 |
|---|---|---|---|
| `notificationTaskId` | string | 是 | 通知任务 ID。 |
| `channel` | enum | 是 | 失败回执对应渠道。 |
| `receiptId` | string | 是 | 回执 ID。 |
| `outcome` | enum | 是 | `Bounced`、`Rejected`、`Timeout`、`Expired`。 |
| `providerCode` | string | 否 | provider 错误代码。 |
| `providerMessage` | string | 否 | provider 结果说明；未发布实现 SMTP 错误使用经过处理的原因及 fingerprint。 |
| `failedAt` | RFC3339 UTC | 是 | 失败结果记录时间。 |

一次确定失败的回执可以发布此事件，即使任务仍有重试或后续渠道。
该 payload 不包含 `finalFailure`，不能仅凭事件类型判定任务已经进入最终 `Failed`。
未发布实现的最终状态可通过发送记录 API 查询。

### NotificationDeliveryUnknown，未发布

| payload 字段 | 类型 | 必须 | 说明 |
|---|---|---|---|
| `notificationTaskId` | string | 是 | 通知任务 ID。 |
| `channel` | enum | 是 | 结果无法确认的渠道。 |
| `receiptId` | string | 是 | 本次未知结果回执 ID。 |
| `outcome` | enum | 是 | `Unknown`。 |
| `providerCode` | string | 否 | 例如 `SMTP_DEADLINE_EXCEEDED` 或 `SEND_LEASE_EXPIRED`。 |
| `providerMessage` | string | 否 | 结果说明。 |
| `failedAt` | RFC3339 UTC | 是 | 代码沿用此字段记录未知结果的时间。 |

SMTP 等待期限结束、发送开始后租约到期或其他无法确认发送结果的情况触发此事件。
队列进入 `Unknown` 后停止自动重发和渠道切换。
原 claim 的迟到确定结果可以追加 `NotificationDelivered` 或 `NotificationFailed`，此前的未知回执保留。

### NotificationCancelled

| payload 字段 | 类型 | 必须 | 说明 |
|---|---|---|---|
| `notificationTaskId` | string | 是 | 通知任务 ID。 |
| `reason` | string | 是 | 取消原因，普通 Preference 禁用时为 `SUPPRESSED_BY_PREFERENCES`。 |
| `cancelledAt` | RFC3339 UTC | 是 | 取消记录时间。 |

`transactionRequired` 通知跳过普通 Preference 抑制。
合并规则命中时直接跳过任务创建，不产生取消事件。

## 消费事件

| stream | 当前映射的事件 |
|---|---|
| `events:journey-order` | `JourneyOrderCreated`、`JourneyOrderPendingPayment`、`JourneyOrderPaymentRecorded`、`JourneyOrderConfirmed`、`JourneyOrderCancelled`、`JourneyOrderPostSalesAdjusted`、`JourneyOrderAdjusted`。 |
| `events:booking-orchestration` | 已订阅，当前映射没有直接以预订事件创建通知的分支。 |
| `events:payment` | `PaymentCaptured`、`PaymentFailed`、`PaymentIntentFailed`、`PaymentIntentExpired`、`PaymentExpired`、`RefundSettled`。 |
| `events:entitlement-ticketing` | `EntitlementIssued`。 |
| `events:post-sales` | `PostSalesEligibilityEvaluated`、`PostSalesDecisionQuoted`、`PostSalesExecutionStarted`、`PostSalesApplied`、`PostSalesFailed`、`ChangeApplied`。 |
| `events:disruption-recovery` | `ServiceAlertPublished`、`RecoveryCaseOpened`、`RecoveryOptionsGenerated`、`RecoveryOptionSelected`、`RecoveryExecutionStarted`、`RecoveryCompleted`、`RecoveryFailed`。 |
| `events:transfer-management` | `TransferAtRisk`、`ConnectionMissed`、`ConnectionRecovered`。 |
| `events:ancillary-service` | `AncillaryOfferQuoted`、`AncillaryOfferExpired`、`AncillaryOrderItemSelected`、`AncillaryOrderItemPendingConfirmation`、`AncillaryOrderItemConfirmed`、`AncillaryOrderItemFulfillmentReady`、`AncillaryOrderItemFulfilled`、`AncillaryOrderItemFailed`、`AncillaryOrderItemCancelled`、`AncillaryOrderItemRefundPending`、`AncillaryOrderItemRefunded`、`AncillaryFulfillmentFactRecorded`。 |
| `events:dispatch` | `DispatchRequested`、`DriverAssigned`、`DriverEtaUpdated`、`DriverArrived`、`RideStarted`、`RideEnded`、`DriverCancelled`、`DispatchUserCancelled`、`DispatchNoShowRecorded`、`DispatchFailed`。 |
| `events:waitlist` | `WaitlistFulfilled`。 |
| `events:wallet-promotion` | `BenefitIssued`、`BenefitExpired`、`BenefitRevoked`。 |

映射按 `eventType` 选择，支持的类型会校验 envelope 及必要 payload 字段。
不支持的事件类型直接确认；校验失败通过 fatal handling 交给共享 subscriber 处理。
处理异常保留消息重试，成功提交后才确认消费。

接续事件没有直接收件人时，使用 `connection.travelerRefs` 中全部去重后的引用。
订单映射通常选择账户或单个直接收件人；票证与附加服务映射优先对应旅客。
持久化收件人解析可以查询订单、售后案件或支付对象，以补充账户引用。
`transactionRequired` 未提供时默认值为 `true`。

## 内部命令

下列名称对应领域或应用内部操作，不是公开 HTTP 写入口，也没有独立的外部命令订阅路由。

| 命令 | 必要输入 | 可选输入 |
|---|---|---|
| `ScheduleNotification` | `notificationTaskId`、`triggerEventId`、`triggerEventType`、`correlationId`、`recipientRef`、`templateCode`、`channel`、`intent`、`transactionRequired`、`variables`、`scheduledAt`。 | `causationId`、`triggerBusinessRef`。 |
| `DispatchNotification` | `notificationTaskId`、`dispatchedAt`。 | 无。 |
| `RecordDeliveryReceipt` | `receiptId`、`notificationTaskId`、`channel`、`outcome`、`recordedAt`。 | `providerCode`、`providerMessage`、`finalFailure`；未发布追加 `attemptId`、`messageId`、`retryAt`。 |
| `CancelNotification` | `notificationTaskId`、`reason`、`cancelledAt`。 | 无。 |

`variables` 是 string 到 string 的映射，内部命令时间使用 `Date`。
`finalFailure=false` 允许确定失败后的任务保持 `Delivering`。
`attemptId`、`messageId` 和 `retryAt` 保存在回执及未发布 trail API 中，当前发布事件 payload 不携带这些字段。

## 幂等与验证记录

发送幂等键格式为 `idem-<triggerEventId>:<recipientRef>:<templateCode>`。
PostgreSQL 另有按 `triggerBusinessRef`、`recipientRef`、`templateCode` 的唯一索引，以及 `processed_events` 消费记录。
重试引用同一个任务，未发布队列为每次领取创建新的 claim token。
稳定 MessageID 关联同一任务的邮件与证据，不保证 SMTP 端去重。

未发布实现最新 compile PASS，PostgreSQL/Mailpit durable delivery 为 10 个子项及父项共 11 PASS，20.8 秒。
完整 `npm test` 未运行，quota 延期恢复未专项验收。
待办统一见 [docs/TODO.md](../../TODO.md)。
