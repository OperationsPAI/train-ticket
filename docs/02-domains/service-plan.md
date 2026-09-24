# Service Plan Domain Design

更新日期：2026 年 9 月 24 日。
当前服务使用 Go，入口为 `services/service-plan/cmd/service-plan/main.go`。

## 领域职责与范围

Service Plan 保存计划服务、服务段、到发时间、运行日期、季节信息、临时服务记录、延误和按日期取消或恢复的事实。
Place & Network 拥有节点和地点权威信息，Seat Assignment 拥有编组与座位，Capacity 拥有容量准入，Fare 拥有价格。
本服务的 `bookable` 表达计划侧状态，不能保证余票、价格或交易成功。
剩余工作统一见 [docs/TODO.md](../TODO.md)。

领域代码包含 `ServicePlan`、`PlanVersion`、`ServicePattern`、`Calendar`、`Timetable`、`ScheduledService` 和 `ServiceSegment`。
领域模型对站序、日历、跨日时间和发布版本进行校验。
当前 HTTP 工作流没有分别暴露全部领域对象的编辑、审批、撤回和修订操作。
创建 scheduled service 时，应用内部构建并发布一个计划版本，再建立服务实例。

## 当前计划与站序

`POST /api/v1/scheduled-services` 接受 `serviceRef`、承运商、车次号、起终节点、出发和到达时间及状态。
当前创建入口只构建两个 stop，领域内部顺序为 1 和 2。
它使用 UTC 时刻及出发日期，创建该日期的 Calendar 和相应 Timetable。
输入包含起终点，不能提交任意多站停靠序列。

`POST /api/v1/service-segments` 将持久化服务的起终点和时间保存为独立 segment。
创建时校验 segment 起终节点属于 scheduled service，并保存请求提供的到发时间。
内部 supply 读取进一步要求 segment 到发时间与 scheduled service 完全一致，不一致时返回错误。
领域层存在多站 segment 推导和延误传播算法，但当前 HTTP 创建路径不能提供沿途多个销售区间。

`GET /api/v1/internal/service-segments/{segmentRef}/supply` 读取持久化 segment 和 scheduled service。
它检查节点、日期来源和时间一致性，返回 `scheduledServiceRef`、`serviceDate`、`stops`、`interval`、到发时间及 `stopSequenceVersion`。
对当前两个 stop 的服务，接口使用从零开始的站序，区间明确为 `[0,1)`。
`stopSequenceVersion` 是服务引用及有序站点 JSON 的 SHA256 摘要。
Capacity 联合 supply 路径使用该接口校验区间和历史 Hold；该 Capacity 改动已在提交 `caafa668` 中实现，尚未部署，Booking coordinated 尚未启用。

## 季节与运营状态

| 能力 | 当前实现与边界 |
|---|---|
| 季节周期 | 内置 `REGULAR`、`SPRING_RUSH`、`SUMMER_RUSH`、`NATIONAL_DAY`、`LABOR_DAY`，服务视图保存周期及 multiplier。 |
| 临时服务 | 向基础服务追加临时车次、周期、停站子集和席别记录，发布 `TemporaryServiceAdded`。 |
| 延误 | 接收服务段及分钟数，保存 `DelayRecord` 并发布 `TrainDelayed`。 |
| 日期取消 | 保存取消日期和原因；日期等于该实例出发日期时设置 `status=CANCELLED`、`bookable=false`。 |
| 日期恢复 | 恢复已有有效取消；日期等于实例出发日期时设置 `status=ACTIVE`、`bookable=true`。 |
| 临时服务到期 | 领域及应用存在 `ExpireTemporaryServices`，当前进程入口没有周期扫描调用。 |

季节 multiplier 是计划元数据。
Capacity 当前联合计算读取 Seat Assignment 的已发布供给，不能据此声称季节系数已经增加可售座位。
新增临时服务保存在基础服务的 `temporaryServices` 中，操作本身不会创建独立 scheduled service 或 searchable segment。
延误算法对后续站点每站减少两分钟，最低为零；当前两个 stop 的创建范围不能证明多站延误已经通过完整 HTTP 链路传播。
延误记录不改写原始计划时刻，也不替代 Disruption Recovery 的改乘和补偿。

## HTTP 与发布消息

| 接口 | 当前作用 |
|---|---|
| `POST /api/v1/scheduled-services` | 创建计划实例，发布 `ServicePlanPublished`。 |
| `GET /api/v1/scheduled-services` | 通过 `limit`、`offset` 和可选 `carrierId` 分页查询。 |
| `GET /api/v1/scheduled-services/{serviceRef}` | 读取实例和保存的运营状态。 |
| `POST /api/v1/service-segments` | 创建服务段，发布带 segment 字段的 `ServicePlanChanged`。 |
| `GET /api/v1/service-segments` | 按 `scheduledServiceRef` 分页读取，消费者需要读取全部页。 |
| `GET /api/v1/service-segments/{segmentRef}` | 读取持久化服务段。 |
| `GET /api/v1/internal/service-segments/{segmentRef}/supply` | 读取经过校验的日期、站序和区间事实。 |
| scheduled service 下的 `POST /temporary-services` | 保存临时服务记录，发布 `TemporaryServiceAdded`。 |
| scheduled service 下的 `POST /delays` | 保存延误事实，发布 `TrainDelayed`。 |
| scheduled service 下的 `POST /cancellations`、`POST /restorations` | 保存日期状态，发布 `TrainCancelled` 或 `TrainRestored`。 |

写接口使用持久化 HTTP 幂等存储。
普通查询及 supply 查询不发布事件。
`TemporaryServiceExpired` 由显式调用的应用到期操作发布。
领域对象中的其他事件类型不能直接视为当前进程已经对外发布的消息。

## 下游关系

Trip Planning Rust 消费带服务段信息的 `ServicePlanChanged`，持久化并更新日期与出发节点索引。
其当前事件过滤器没有处理 `TrainDelayed`、`TrainCancelled`、`TrainRestored` 或 `TemporaryServiceAdded`。
所以本服务记录取消、恢复或临时服务，不等于搜索结果已经同步改变。

Seat Assignment 的编组创建和发布通过独立接口完成。
Service Plan 不生成 standing 单元，也不决定 standing 10% 限额。
已发布站票政策见 [Standing capacity policy](../08-contracts/standing-capacity-policy.md)。

## 存储与证据

生产入口配置 PostgreSQL repository、事务 UnitOfWork、持久化幂等存储和 Redis outbox relay。
`scheduled_service_snapshots` 保存服务及 temporary services、cancellations、delay records。
`service_segment_snapshots` 保存独立的服务段。
状态变更与 outbox 追加在同一数据库事务内执行，应用重建时恢复保存的运营信息。

当前源码和已有测试能分别说明领域校验、HTTP 操作、持久化及 supply 读取。
领域算法测试不能作为所有下游行为已经完成的依据。
具体季节参数与证据入口见 [service-plan-seasonal.md](../10-domain-enrichment/service-plan-seasonal.md)。
