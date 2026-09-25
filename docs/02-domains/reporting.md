# Reporting 当前设计

更新日期：2026年9月25日。
实现基线包含权威收入事实表与 007/008 迁移，服务使用 Python、FastAPI、PostgreSQL 和 Redis。
统一待办见 [TODO](../TODO.md)。

## 领域边界

Reporting 消费已配置业务事件，保存指标定义、dashboard 快照、漏斗模型、操作事件、刷新请求和异常结果。
主要模型为 `MetricDefinition`、`DashboardReadModel`、`FunnelView`、`OperationalEvent`、`MetricAggregator` 和 `AnomalyDetector`。
服务对外提供查询 API，分析结果不回写订单、支付、票证、库存或财务聚合。
Finance Settlement 保存收入确认和冲减，Reporting 的金额指标采用下述独立统计口径。

## 金额口径

`revenue`、`totalRevenue`、`routeRevenue` 和 `revenue_per_hour` 均只统计 `PaymentCaptured`，含义为 gross captured payment amount。
退款、`RevenueRecognized`、`RevenueRecognitionReversed` 和 `PaymentSucceeded` 不增减这些金额。
同一支付与 Finance 收入确认事件不会重复贡献 gross amount。
其他事件仍可参与事件计数、dashboard 刷新和异常规则。

金额报表支持 `route`、`seat_class`、`channel`、`traveler_type` 和 `time_period` 五个维度；旧 `passenger_type` 不再接受。
PostgreSQL 分组始终包含币种，缺失维度使用 `unknown`，缺失币种使用 USD，时间按 UTC 小时归集。
`totalRevenue`、`routeRevenue` 和 `revenue_per_hour` 均返回按 ISO 币种代码排序的 Money 数组；不同币种不相加、不隐式换算，空结果返回空数组。
金额来自 Payment 的 `PaymentCaptured`，但路线、旅客类型、席别和 attachment 不信任支付事件携带的同名字段。
路线和旅客来自 Journey Order；改签支付通过 Post Sales case scope 关联订单；席别来自 Booking Orchestration 并由 Seat Assignment 的已接受 `requestedClassRef` 优先校验；附加服务与保险来自各自业务事实。
多值维度按最小货币单位稳定等分，余数按稳定键顺序分配，因此每个维度按币种求和都严格等于 `totalRevenue`。
这些指标不表示净收入或会计确认收入。

## 投影与刷新

事件去重、操作事件保存和刷新请求在数据库事务中完成。
后台 worker 根据持久化 generation 处理 dashboard 重建、异常计算和收入视图刷新。
刷新开始后新接收的事件保留为下一次请求。
worker 每轮结束后等待30秒，失败请求继续保持待处理状态。

`reporting_revenue_breakdowns` 将 captured payment 与 Reporting 自有的权威事实表关联并计算五个维度。
`reporting_revenue_by_route` 与 `reporting_revenue_by_seat_class` 从 breakdown 派生，刷新顺序固定为 breakdown、route、seat class。
报表读取最近完成刷新的物化结果。
005/006 已建立 gross captured 口径，007/008 负责权威维度事实与原子视图切换。
SQL 保留源事件表，物化视图是可重新计算的派生结果。

## 当前查询边界

`totalRevenue` 按币种独立汇总完整筛选结果，不受 `limit` 和 `offset` 影响；只有 `items` 分页。
响应中的 `total` 是分页前的分组行数，越界页返回空 `items`，但保留相同的完整总额和 `total`。
按来源或事件类型查询时，`eventCount` 另表示分页前的源事件总数。
缺少 amount 的 `PaymentCaptured` 仍保留在源事件表用于操作审计，但 PostgreSQL 与内存聚合器都不将其计入金额或 payment count。
2026年9月25日只读核对生产源表时，2,904,448 条 `PaymentCaptured` 均有 amount 和 currency，币种均为 CNY；缺失规则通过录制生产数据追加对照样本验证。
默认目录说明只用于初始化缺失指标，已有数据库指标描述保留原值。

操作指标、路线指标和异常规则使用 `MetricAggregator`。
PostgreSQL 加载聚合器时最多读取符合条件的最新10000条事件；聚合器按币种独立保存金额。
其时间窗口结果受这一输入数量限制，不能据此保证覆盖高流量下完整历史窗口。
`orders_per_second` 为最近60秒订单事件数除以60所得的平均每秒速率，保留四位小数。
`trends` 返回当前快照的单个时间点。
完整计算口径见 [指标与投影](../10-domain-enrichment/reporting-enrichment.md)。

## API 与事件

| 入口 | 用途 |
|---|---|
| `GET /api/v1/metrics` | 分页查询指标定义。 |
| `GET /api/v1/metrics/{metricId}` | 查询指标定义。 |
| `GET /api/v1/dashboards/{dashboardId}` | 查询 dashboard 快照。 |
| `GET /api/v1/dashboards/{dashboardId}/rebuilds` | 查询重建记录。 |
| `GET /api/v1/metrics/operational` | 查询当前操作指标。 |
| `GET /api/v1/metrics/routes` | 查询按路线和服务日期组织的指标。 |
| `GET /api/v1/metrics/revenue` | 按五个维度查询 gross captured 金额，或按来源和事件类型查询计数。 |
| `GET /api/v1/metrics/anomalies` | 查询当前有效异常。 |
| `GET /api/v1/metrics/trends` | 查询当前指标时间点。 |

服务发布 `ReadModelRebuilt` 和 `AnomalyDetected`，严重收入下降或支付错误异常可产生通知请求。
通知的实际发送由 Notification 处理。
查询中的 `generatedAt` 是响应生成时间，不能作为物化结果的最新业务时间。

实现入口见 [服务说明](../../services/reporting/README.md)、[领域模型](../../services/reporting/src/reporting/domain.py)、[PostgreSQL 适配器](../../services/reporting/src/reporting/adapters/storage/postgres.py)、[HTTP API](../08-contracts/api/reporting.md)、[事实表 SQL](../../services/reporting/migrations/007_authoritative_revenue_facts.sql) 和 [权威维度视图 SQL](../../services/reporting/migrations/008_authoritative_revenue_views.sql)。
