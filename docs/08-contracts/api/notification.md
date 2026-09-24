# Notification HTTP API

## 发布状态与入口

Notification 通过业务事件创建通知，HTTP 提供发送记录及健康查询。
线上 revision 279 仍使用事务内 SMTP 发送路径。
durable queue 已在提交 `41917b85` 中实现，尚未部署，新设计在线上未生效。
本文标注为“未发布”的状态值和追加字段来自该提交，不属于 revision 279 的响应保证。

当前路由由 `services/notification/src/app.ts` 注册。
发送、dispatch、取消及记录回执属于内部方法，没有 HTTP 写接口，也没有独立的命令 stream 订阅入口。

## GET /api/v1/notifications/{id}/trail

`id` 为 `nt-<uuid>` 形式的通知任务 ID。
成功返回 200，任务不存在或没有配置查询存储时返回 404，错误代码为 `NOT_FOUND`。
存储异常返回 500，错误代码为 `INTERNAL_ERROR`。

| 字段 | 类型 | 发布状态 | 说明 |
|---|---|---|---|
| `notificationId` | string | 已有字段 | 通知任务 ID。 |
| `status` | string | 已有字段 | `Planned`、`Authorized`、`Delivering`、`Delivered`、`Failed`、`Cancelled`；`Unknown` 是未发布追加值。 |
| `attempts` | array | 已有字段 | 根据任务回执生成的历史记录，初始可以为空。 |
| `resultUnknown` | boolean | 未发布 | 任务状态或队列状态为 `Unknown` 时为 `true`，未发布实现成功响应始终包含此字段。 |
| `delivery` | object | 未发布 | 存在 durable queue 记录时返回，不存在队列记录时省略。 |

`attempts` 中的每一项对应一条回执。
同一发送 attempt 可以先产生 `UNKNOWN` 回执，再追加迟到的确定结果。
因此顶层 `attempts` 的数量不等于实际 SMTP 调用次数。

| `attempts[]` 字段 | 类型 | 发布状态 | 说明 |
|---|---|---|---|
| `channelUsed` | string | 已有字段 | `EMAIL`、`SMS`、`PUSH`、`IN_APP`。 |
| `attemptedAt` | RFC3339 UTC string | 已有字段 | 回执的 `recordedAt`，不等于精确发送开始时间。 |
| `status` | string | 已有字段 | `DELIVERED`、`FAILED`、`BOUNCED`；`UNKNOWN` 是未发布追加值。 |
| `reason` | string，可省略 | 已有字段 | 回执包含 `providerCode` 时返回该代码。 |
| `attemptId` | string，可省略 | 未发布 | 本次 claim token。 |
| `messageId` | string，可省略 | 未发布 | 稳定 SMTP MessageID。 |
| `retryAt` | RFC3339 UTC string，可省略 | 未发布 | 确定失败后安排的同渠道重试时间。 |

`Delivered` 和 `DELIVERED` 在 EMAIL 路径中表示 SMTP 已接受邮件，不表示用户阅读或邮箱最终送达。
当前 PUSH、SMS、IN_APP 由 `DirectSuccessGateway` 返回成功。

## 未发布的 delivery 对象

未发布实现通过一个 `REPEATABLE READ READ ONLY` 事务查询任务快照、队列、发送 attempt 和 evidence。
以下对象全部属于未发布追加内容。

| 字段 | 类型 | 说明 |
|---|---|---|
| `state` | string | `Ready`、`Claimed`、`Sending`、`Unknown`、`Delivered`、`Failed`。 |
| `messageId` | string | 由任务 ID 生成，同一任务的重试保持一致。 |
| `availableAt` | RFC3339 UTC string | 队列下一次允许领取的时间。 |
| `attempts` | array | 按 `attempt_number` 排序的数据库发送 attempt。 |
| `evidence` | array | 按 `seq` 排序的追加证据。 |

`delivery.attempts[]` 保留数据库字段名称：`claim_token`、`attempt_number`、`channel`、`state`、`claimed_at`、`started_at`、`completed_at`、`message_id`、`result`。
`started_at`、`completed_at` 和 `result` 可以为 null。
attempt 状态包括 `Claimed`、`Sending`、`QuotaDeferred`、`AbandonedBeforeSend`、`Unknown`、`Delivered`、`Failed`。

`delivery.evidence[]` 包含 `seq`、`claim_token`、`kind`、`recorded_at` 和 `evidence`。
`seq` 来自 PostgreSQL bigserial，当前通过 pg 返回字符串；首次入队证据可以没有 claim token。
证据类型包括 `QuotaReserved`、`QuotaDeferred`、`Claimed`、`SendStarted`、`SendResult`、`LateResult`、`ClaimExpiredBeforeSend` 和 `SendLeaseExpired`。
`result` 与 `evidence` 保留对应渠道结果及调度决策的 JSON 内容。

`Unknown` 表示服务无法确认发送结果，队列停止自动重发和渠道切换。
原 claim 的迟到确定结果可以更新状态并追加回执及证据。
稳定 MessageID 用于关联邮件，不代表 SMTP 端提供幂等发送。

## 健康与元数据

| GET 路径 | 响应含义 |
|---|---|
| `/health` | 服务基本信息，`status: "ok"`。 |
| `/healthz`、`/live`、`/livez` | liveness 正常返回 200，注册组件持续不健康超过宽限期时返回 503。 |
| `/ready`、`/readyz` | 配置存储后检查 schema 应用状态及数据库可用性，未就绪返回 503。 |
| `/metadata` | 服务元数据及 tracing 配置说明。 |

readiness 在 schema 尚未应用时可追加 `schema` 诊断对象。
所有响应通过请求钩子返回 `x-request-id` 与 `x-correlation-id`。
错误响应使用共享错误 envelope。

未发布实现最新 compile PASS，PostgreSQL/Mailpit 验证为 10 个子项加父项共 11 PASS，20.8 秒。
完整 `npm test` 未运行，quota 延期恢复未专项验收。
待办统一见 [docs/TODO.md](../../TODO.md)。
