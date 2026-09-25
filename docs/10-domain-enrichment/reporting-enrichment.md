# Reporting 指标与投影

更新日期：2026年9月25日。
本文描述包含权威收入事实与 007/008 迁移的 Python 实现。
领域边界见 [Reporting](../02-domains/reporting.md)，统一待办见 [TODO](../TODO.md)。

## 操作指标

| 字段 | 当前计算 |
|---|---|
| `orders_per_second` | 最近60秒订单事件数除以60所得的平均每秒速率，保留四位小数。 |
| `revenue_per_hour` | 最近1小时 `PaymentCaptured` 按币种分别求和，返回 Money 数组。 |
| `fill_rate_by_route` | 路线和服务日期的 confirmed 与 capacity 比值。 |
| `avg_booking_latency` | 最近1小时 booking latency 样本的 p50、p95、p99。 |
| `refund_rate` | 最近24小时退款事件数量除以订单事件数量。 |
| `scalper_block_rate` | 最近1小时拦截事件数量除以拦截与订单事件总数。 |
| `routeDemand` | 路线和服务日期下搜索事件数量与订单事件数量之和。 |

路线聚合读取最新的 confirmed、capacity 和席别使用字段。
缺少非零 confirmed 值时，当前代码使用订单事件数量，缺少 capacity 时使用零。
这些指标依赖输入事件提供的维度与计数，不能代替 Capacity 的实时可售判断。
生产 `/metrics/routes` 只聚合最近24小时，在 PostgreSQL 内先形成完整路线/服务日期分组，再按 `serviceDate DESC, routeId ASC` 分页；它不返回路线收入。

PostgreSQL 在数据库内按时间索引聚合完整窗口，不使用行数上限。
`/metrics/operational` 的订单速率为60秒、收入/延迟/风控为1小时、退款率为24小时、context rollup 为5分钟。
异常评估也使用规则自身的完整时间范围，不受最新10000条事件限制。
内存聚合器按币种独立汇总金额，绝不对不同币种执行相加或隐式换算。
`trends` 当前返回 orders、gross captured 和 refund rate 的快照时间点，没有保存完整历史曲线。

## gross captured 报表

金额报表仅接受 `PaymentCaptured`。
退款、收入确认和收入冲减对 gross captured 金额没有贡献。
`groupBy` 支持 `route`、`seat_class`、`channel`、`traveler_type` 和 `time_period`；不保留旧 `passenger_type` 格式。
`source_context` 与 `event_type` 返回事件计数报表，金额字段为零。

`reporting_revenue_breakdowns` 关联 capture reference、订单路线/旅客、业务 scope、席别和 attachment 事实后生成五个维度；路线与席别视图从该 breakdown 派生。
缺失维度为 `unknown`，缺失币种为 USD，时间维度按 UTC 小时归集。
`route` 使用 Journey Order 的 segmentRef；`traveler_type` 使用 Journey Order traveler snapshot；`seat_class` 使用 Booking `classRef` 并由 Seat Assignment `requestedClassRef` 高优先级覆盖。
主票、改签和附加服务支付分别通过 order、Post Sales case scope 和 ancillary item scope 解析。
多值维度按分等分并稳定分配余数，避免重复收入；`yieldPerKm` 使用同样比例分配距离。
附加服务与保险附加率使用对应业务上下文的权威订单事实，不读取 PaymentCaptured 上的布尔标记。

缺少 amount 的 `PaymentCaptured` 保留在源事件表用于审计，但 PostgreSQL 与内存金额报表都排除该行，金额与 payment count 均不增加。
`totalRevenue` 返回分页前完整筛选结果、按 ISO 币种代码排序的 Money 数组，不执行汇率换算；空结果返回空数组。
`items` 使用 `limit`/`offset` 分页，`total` 表示分页前分组行数；越界页不改变完整总额。

## 异常规则

| 规则 | 当前触发条件 |
|---|---|
| `REVENUE_DROP` | 最近1小时 gross captured 小于前一天对应1小时的50%，且前一天金额为正。 |
| `ORDER_SPIKE` | 最近1分钟订单数大于此前5分钟平均每分钟订单数的3倍，且此前平均值为正。 |
| `ERROR_RATE_SPIKE` | 最近5分钟支付失败占支付失败与 `PaymentCaptured` 事件总数的比例超过10%。 |
| `CAPACITY_EXHAUSTION` | 超过5个路线和日期聚合项满足容量为正且 confirmed 不小于 capacity。 |
| `REFUND_SURGE` | 最近1小时退款事件数与订单事件数的比例超过20%，且订单数为正。 |

异常记录包括规则、当前值、阈值、严重程度和检测时间。
后台计算更新有效异常，条件解除后将其关闭。
`REVENUE_DROP` 和 `ERROR_RATE_SPIKE` 标记为 urgent，并产生通知请求。
异常不直接执行交易变更。

## 持久化与运行边界

事件消费在事务中写入去重记录、`reporting_metric_events` 和刷新 generation。
后台 worker 使用数据库 advisory lock 协调刷新，处理开始时可见的请求 generation，保留期间新增请求。
每轮 worker 结束后等待30秒。
报表读取最后完成的物化视图，`generatedAt` 表示响应生成时刻。

gross captured 基础口径由 005/006 建立，权威维度由 007 事实表、历史回填和 008 视图切换实现。
数据库变更保留源事件表，并保存视图原有名称、列、索引、所有权和授权。

实现依据为 [领域计算](../../services/reporting/src/reporting/domain.py)、[PostgreSQL 存储](../../services/reporting/src/reporting/adapters/storage/postgres.py)、[查询入口](../../services/reporting/src/reporting/api.py)、[事实表 SQL](../../services/reporting/migrations/007_authoritative_revenue_facts.sql) 和 [权威维度视图 SQL](../../services/reporting/migrations/008_authoritative_revenue_views.sql)。
