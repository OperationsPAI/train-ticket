# Trip Planning Domain Design

更新日期：2026 年 9 月 24 日。
当前服务为 [services/trip-planning-rs](../../services/trip-planning-rs/README.md)，使用 Rust、Axum、PostgreSQL 和 Redis Streams。
部署镜像名为 `train-ticket/trip-planning`，构建入口为 `deploy/docker/trip-planning/Dockerfile`。

## 领域职责与当前范围

Trip Planning 接受出行条件，从已消费的计划供给中生成候选，执行约束检查和排序，保存 itinerary snapshot。
它读取上游计划与地点引用，不创建 Capacity Hold、Offer、订单或支付。
当前 HTTP 搜索返回同日期、匹配起终地点的直达火车服务段。
每个索引命中转换成只有一个 leg 的 itinerary。
剩余工作统一见 [docs/TODO.md](../TODO.md)。

领域类型允许 itinerary 包含多个 legs，并能检查连接时间和换乘次数。
当前 `PlanIndex::candidates` 没有组合多段路线，不能据此描述为已实现跨方式联乘或自动中转规划。
Service Plan 的现有创建入口也是两个 stop，对外 supply 区间为 `[0,1)`。

## 查询模型与排序

| 对象 | 当前用途 |
|---|---|
| `TripIntent` | 起终引用、UTC 出发时间窗口、人数和 `PreferenceConstraints`。 |
| `LegCandidate` | 保存计划服务、segment、起终 stop、计划时间及交通方式。 |
| `Itinerary` | 保存 legs、提示信息、规划来源和稳定引用。 |
| `PlanningScore` | 根据行程时长、连接次数、价格与可用性提示等生成可解释分数。 |
| `SearchResult` | 内部保存 intent 引用、接受的候选及分数。 |

HTTP 请求将 `departureDate` 转换为 UTC 当天时间窗口，人数来自 `travelerRefs`。
该入口使用默认 preferences。
领域校验包括起终点、出发窗口、允许方式、连接次数与时间、价格上限及不可用提示。
默认最短连接间隔为五分钟，但当前索引生成的直达 itinerary 没有连接需要检查。
领域没有消费 Transfer Management 的 `MctRulePublished` 或 `MctRuleRetired` 来建立运营方最短换乘时间表。

排序后的候选按 `maxResults` 截取。
HTTP 响应包含 `intentRef`、`itineraries` 和 `planningSnapshotRefs`，不会返回内部的分数解释。
校验函数返回排除原因，但搜索聚合结果只保留通过校验的候选，排除原因没有持久化或返回给客户端。
无匹配 segment 时返回空候选，不生成新的供给引用。

## 日期与出发节点索引

`PlanIndex` 通过 `by_ref` 保存每个 segment 的最新记录。
`by_date_origin` 按出发日期和出发节点定位 segment 引用集合。
查询只读取匹配日期和出发引用的集合，再检查终点。

地点索引同时维护 `place_by_node` 与 `nodes_by_place`。
搜索时解析 node、place 及同一 place 下的节点引用。
节点映射更新后，已有 segment 在下一次搜索中使用当前映射。
segment 日期或出发节点改变时，更新逻辑移除旧索引位置并清理空集合。
相同 segment 的重复 upsert 不增加候选数量。

## 输入事件与持久化

| 消费事件 | 当前动作 |
|---|---|
| `ServicePlanChanged`、`ServiceSegmentCreated` | 从 payload 读取 segment、服务、起终 stop 和时间，更新 segment 索引。 |
| `TransportNodeRegistered`、`TransportNodeAdded`、`TransportNodeUpdated` | 更新 node 与 place 映射。 |

上述事件是当前 `PlanIndex::handles_event` 允许的类型。
`ServicePlanPublished`、`ScheduledServiceCreated` 和 `PlaceNetworkUpdated` 不生成索引更新。
`TrainDelayed`、`TrainCancelled`、`TrainRestored` 和 `TemporaryServiceAdded` 也没有当前索引处理。
不能将 Service Plan 的相关运营操作描述为搜索已自动隐藏、恢复或新增车次。

索引变更分别写入 PostgreSQL 的 `plan_segments` 和 `plan_nodes`。
进程启动时读取两张表并重建内存索引。
查询结果保存到 `itinerary_snapshots`，重复引用使用现有快照。
搜索请求通过 outbox 追加 `ItineraryProposed`。
当前 snapshot 写入与 outbox 追加是独立数据库操作；发布函数记录追加错误，因此 HTTP 成功不能单独证明事件已经持久化。

## 价格与可用性边界

索引生成的 `PriceHint` 为 `amount_minor=0`、`currency=CNY` 的提示。
`AvailabilityHint` 的状态为 `UNKNOWN`。
这些对象引用 segment 派生的 snapshot 标识，当前搜索没有从 Fare 或 Capacity 获取最新交易结果。
零金额不是已报价票价，`UNKNOWN` 不是可售证明。
最终商业报价和容量校验由下游完成。

当前持久化重点是计划索引及 itinerary snapshot。
没有独立的 SearchSession 生命周期 API、PlanningRuleSet 管理 API 或完整规划审计读模型。
这些概念不能作为当前已经开放的服务能力。

## HTTP 与验证记录

| 接口 | 当前作用 |
|---|---|
| `POST /api/v1/itineraries/search` | 校验请求，查找并排序候选，保存结果，尝试追加提案事件。 |
| `GET /api/v1/itineraries/{itineraryRef}` | 读取已保存 itinerary snapshot。 |

[搜索性能记录](../../services/trip-planning-rs/docs/search-performance-20260923.md) 记录了日期与出发节点索引部署至 Helm revision 277 的结果。
固定记录包含 394838 个 segment、389797 个节点映射和 45816 条查询。
本地完整记录测试核对了候选、排序和 HTTP 响应，并经过实际 Axum router 与 PostgreSQL。
这些结果只支持该记录、固定并发和已记录部署检查的结论，不构成完整生产高峰周期或所有跨服务流程的验收。
本次文档核对没有重新执行测试。
