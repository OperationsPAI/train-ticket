# Reporting 指标与投影

更新日期：2026年9月25日。
本文描述 Helm revision 279 文档核对时的 Python 实现。
领域边界见 [Reporting](../02-domains/reporting.md)，统一待办见 [TODO](../TODO.md)。

## 操作指标

| 字段 | 当前计算 |
|---|---|
| `orders_per_second` | 最近60秒的订单事件数量，未除以60。 |
| `revenue_per_hour` | 最近1小时 `PaymentCaptured` 按币种分别求和，返回 Money 数组。 |
| `fill_rate_by_route` | 路线和服务日期的 confirmed 与 capacity 比值。 |
| `avg_booking_latency` | 已加载事件中存在 booking latency 的样本 p50、p95、p99。 |
| `refund_rate` | 最近24小时退款事件数量除以订单事件数量。 |
| `scalper_block_rate` | 最近1小时拦截事件数量除以拦截与订单事件总数。 |
| `routeRevenue` | 路线和服务日期下 `PaymentCaptured` 按币种分别求和，返回 Money 数组。 |
| `routeDemand` | 路线和服务日期下搜索事件数量与订单事件数量之和。 |

路线聚合读取最新的 confirmed、capacity 和席别使用字段。
缺少非零 confirmed 值时，当前代码使用订单事件数量，缺少 capacity 时使用零。
这些指标依赖输入事件提供的维度与计数，不能代替 Capacity 的实时可售判断。

PostgreSQL 的操作聚合最多加载最新10000条符合条件的事件。
异常评估另加检测时间范围过滤，仍受条数限制。
聚合器按币种独立汇总金额，绝不对不同币种执行相加或隐式换算；超出加载范围的事件不会贡献相应指标。
`trends` 当前返回 orders、gross captured 和 refund rate 的快照时间点，没有保存完整历史曲线。

## gross captured 报表

金额报表仅接受 `PaymentCaptured`。
退款、收入确认和收入冲减对 gross captured 金额没有贡献。
`groupBy` 支持 `route`、`seat_class`、`channel`、`passenger_type` 和 `time_period`。
`source_context` 与 `event_type` 返回事件计数报表，金额字段为零。

`reporting_revenue_breakdowns` 对五个维度使用 `GROUPING SETS`，每组包含币种。
两个独立的路线、席别物化视图各自扫描源表，因此一次完整刷新共进行三次基础关系扫描。
缺失维度为 `unknown`，缺失币种为 USD，时间维度按 UTC 小时归集。
`yieldPerKm` 为 captured 金额除以记录的距离总和，附加服务和保险附加率使用 captured 行的对应标记计数。

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

gross captured 及 grouping sets SQL 已在 Helm revision 279 的生产基线中生效。
数据库变更保留源事件表，并保存视图原有名称、列、索引、所有权和授权。

实现依据为 [领域计算](../../services/reporting/src/reporting/domain.py)、[PostgreSQL 存储](../../services/reporting/src/reporting/adapters/storage/postgres.py)、[查询入口](../../services/reporting/src/reporting/api.py)、[gross captured SQL](../../services/reporting/migrations/005_gross_captured_revenue.sql) 和 [缺失金额一致性 SQL](../../services/reporting/migrations/006_exclude_incomplete_captures.sql)。
