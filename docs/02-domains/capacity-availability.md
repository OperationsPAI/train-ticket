# Capacity & Availability Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Capacity & Availability |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-capacity-availability |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/domain-glossary.md`, `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/aggregate-model.md`, `docs/01-ddd-high-level/state-machines.md`, `docs/01-ddd-high-level/event-storming.md`, `docs/01-ddd-high-level/consistency-and-saga.md`, `docs/01-ddd-high-level/order-inventory-payment-model.md`, `docs/01-ddd-high-level/train-business-flow-catalog.md` |

## 1. 领域目标

Capacity & Availability 负责回答：在某个时间点、某个销售规则下，某个 `Segment` 或 `Segment` 区间是否还有可被交易链路安全使用的运力资源。它独立于订单、支付、出票、价格和供应商原始预订，因为可用性需要保护自己的强一致不变量：不超卖、不重复 Hold、不漏释放、可解释、可审计。

第一阶段以 Train Ticket 为核心：按车次、运营日期、席别、座位/铺位/无座、站序区间建模库存。同一座位可以在不重叠的站段复用，不能在重叠站段重复 Hold 或 Confirmed Occupancy。后续 General Travel 扩展时，本上下文继续表达通用 `Capacity` 和 `Availability`，通过 `CapacityPolicy` 支持航空舱位库存、大巴座位、轮船舱房/铺位/车辆甲板、网约车动态供给。

本上下文区分六类经常被当前系统混淆的事实：

1. `PhysicalCapacity`：真实物理能力，如座位、铺位、舱位、车辆甲板、司机供给。
2. `Inventory` / `SellableInventory`：按销售维度和规则计算后的可售库存。
3. `Quota`：渠道、代理、企业、候补池或运营策略的销售额度，不是物理座位。
4. `CapacityHold`：有过期时间的临时占用。
5. `Occupancy`：已确认占用，表示资源正式售出或供应侧确认。
6. `AvailabilitySnapshot`：给搜索、报价、运营看板使用的缓存可用性，不是交易锁定。

## 2. 边界

### In Scope

- `AvailabilitySnapshot` 的生成、版本、过期、失效和不可售解释。
- 固定班次 `Inventory` 写模型：按 `Segment`、日期、席别/容量类型、站序区间、座席或容量桶记录可售资源。
- Train Ticket 区间库存：使用半开区间 `[originStopSeq, destinationStopSeq)` 判断重叠；相邻区间不冲突。
- `CapacityHold` 生命周期：创建、确认、释放、过期、失败、替换、必要的受控延长。
- Confirmed Occupancy：支付和预订条件满足后，临时 Hold 转为正式占用；退票、改签、异常和人工调整可释放。
- `Quota` 生命周期：渠道/代理/企业/候补池/运营策略的配额分配、占用、确认、释放、冻结、调拨。
- Sellable Inventory 计算：物理容量扣除停售、限售、维护保留、已确认占用、有效 Hold、Quota 限制后的可销售资源。
- Hold 过期扫描、释放重试、库存差异检测、库存审计账本。
- `CapacityReleased`、`InventoryAdjusted`、`QuotaReleased` 等 `Waitlist` trigger points。
- 与 Provider Integration 的可用性对齐：消费供应商库存变化事件或主动查询结果，发布平台统一 Availability 事件。
- 与 Service Plan 的计划变化联动：新车次发布、停运、经停变更、换编组、停售限售导致库存初始化、冻结、调整或重建。
- 与 Post Sales 的退改联动：旧库存释放、新库存 Hold、改签到站区间变更。
- 面向 Reporting 和 Admin & Audit 的库存审计读模型、差异读模型、受控人工调整命令。

### Out of Scope

- 不拥有 `JourneyOrder`、订单金额、订单项、订单总体状态或用户商业承诺。
- 不拥有 `SegmentBooking` 的供应侧确认状态；只提供内部库存 Hold/Confirm/Release 的事实。
- 不拥有 `PaymentIntent`、支付授权、扣款、退款或资金对账。
- 不拥有 `Entitlement`、票号、乘车凭证、检票、取票、登乘或履约事件。
- 不拥有 `FareRule`、票价、手续费、退改规则和优惠资格；仅消费可售限制和库存相关规则快照。
- 不拥有 Provider booking internals，例如 PNR、铁路供应商订单号、司机派单状态；这些通过 Provider Integration ACL 映射。
- 不拥有 `Waitlist` 队列排序、公平性、支付担保、候补单状态；只发布触发事件并可为 Waitlist 执行受控 Hold。
- 不拥有 Trip Planning 的路线生成、换乘推荐、排序或个性化推荐。
- 不拥有 Offer 的价格/规则冻结；只提供 Offer 所需的可用性证据和库存版本。
- 不拥有 Disruption Recovery 的恢复方案选择；只响应已确认的异常影响命令调整库存。
- 不直接发送 Notification，不直接修改 Reporting 统计结果，不绕过 Admin & Audit 做后台数据修正。

## 3. 统一语言补充

只补充本 domain 内部术语。跨域通用术语沿用 high-level glossary。

| Term | Definition | Notes |
|---|---|---|
| PhysicalCapacity | 运输服务实际可承载的物理能力，例如座位、铺位、舱位、舱房、车辆甲板、司机供给。 | 不是全部可售；可能受维修、保留、管控影响。 |
| CapacityUnit | 可被占用的最小或策略性容量单元。火车可为具体座位/铺位/无座桶；航空可为舱位桶；网约车可为动态供给单位。 | 通用值对象；不同交通方式有特化字段。 |
| Inventory | 已经按销售维度建模的可售库存集合。 | 包含站段、席别、容量类型、版本和约束。 |
| SellableInventory | 在某个渠道、时间、规则和旅客条件下真正允许销售的库存。 | 由 PhysicalCapacity、限制、Quota、Hold、Occupancy 共同计算。 |
| Occupancy | 已确认占用，表示容量已经转为正式售出或供应侧确认。 | 不等于 Entitlement；出票失败仍可能需要人工释放或退款。 |
| CapacityHold | 有过期时间的临时库存占用。 | 下单、改签、候补兑现都会创建 Hold。 |
| HoldScope | Hold 覆盖的资源范围。 | 包括 Segment、serviceDate、seatClass、capacityUnit、stationInterval、travelerRef/orderRef。 |
| StationInterval | 火车库存区间，使用站序半开区间 `[fromSeq, toSeq)`。 | `[A,C)` 与 `[C,E)` 不重叠；`[A,C)` 与 `[B,D)` 重叠。 |
| AvailabilitySnapshot | 面向查询/报价的可用性快照。 | 可缓存、可过期；不能替代下单时 Hold。 |
| AvailabilityVersion | 快照或库存计算的版本标识。 | Offer 应记录版本，用于解释下单失败或重新报价。 |
| Quota | 分配给渠道、代理、企业、运营策略或候补池的销售额度。 | 不是物理库存，必须与库存占用分开。 |
| QuotaBucket | Quota 的具体分区。 | 例如 APP 渠道、窗口渠道、B2B 渠道、候补预留。 |
| ReleaseReason | 库存释放原因。 | 订单取消、支付超时、退票、改签替换、异常停运、人工调整等。 |
| InventoryAdjustment | 受控库存调整。 | 必须有原因、操作者或来源事件、审批/审计引用。 |
| WaitlistTrigger | 因库存释放或调增产生的候补触发事实。 | 本上下文发布事件，Waitlist 决定是否兑现。 |
| AvailabilityExplanation | 不可售或低余量原因解释。 | 如未开售、已停售、无票、配额不足、Hold 冲突、供应未知。 |
| CapacityPolicy | 特定交通方式或销售策略的库存计算规则。 | 例如火车区间复用、航空舱位桶、大巴固定座位、网约车动态供给。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Service Plan | `ServiceSegmentPublished`, `ServiceSegmentUpdated`, `ServiceSegmentCancelled`, `StopSequence`, `SeatLayout`, `ServiceCalendar`, `SalesWindow`, `SaleRestriction` | 初始化或调整固定班次库存；站序是区间重叠判断的权威来源。 |
| Provider Integration | `ProviderAvailabilityReported`, `ProviderInventoryChanged`, `ProviderReservationReleased`, `ProviderCapacityBlocked`, provider availability query result | 对外部供应商或混合库存做可用性归一；供应商原始状态必须经 ACL 映射。 |
| Fare & Pricing | `FareProductPublished`, `FareRuleAvailabilityConstraint`, `RefundabilityWindow`, `ChangeAvailabilityConstraint` | 某些库存是否可售取决于票种、席别、优惠、退改窗口和销售限制。 |
| Trip Planning | `AvailabilityQuery`, `SegmentRef`, `StationInterval`, traveler count and preference hints | 查询可用性和余票概览，不创建 Hold。 |
| Offer Management | `AvailabilityCheckRequest`, `OfferAvailabilityEvidenceRequested`, `OfferExpired` | 报价前需要可用性证据；Offer 过期可释放未确认的预报价资源（若未来启用）。 |
| Booking Orchestration | `HoldCapacity`, `ConfirmHold`, `ReleaseHold`, `ReplaceHold`, `HoldCapacityForWaitlist` | 交易链路对内部库存的唯一写入口。 |
| Post Sales | `ReleaseCapacityAfterVoid`, `HoldReplacementCapacity`, `ReleaseOriginalAfterChange`, `ApplyPostSalesCapacityAdjustment` | 退票释放、改签锁新库存、旧库存释放和例外处理。 |
| Disruption Recovery | `ApplyCapacityDisruption`, `FreezeCapacityForDisruption`, `RestoreCapacityAfterRecovery` | 停运、经停变化、保护性改乘会冻结或释放库存。 |
| Admin & Audit | `ApproveInventoryAdjustment`, `ApplyManualCapacityAdjustment`, `ConfigureQuota`, `FreezeInventory`, `UnfreezeInventory` | 后台高风险操作必须受权限、审批和审计约束。 |
| Risk & Compliance | `CapacityAccessDecision`, `ChannelThrottleDecision` | 风控可能限制查询、Hold 或渠道配额使用。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Trip Planning | `AvailabilitySnapshot`, `AvailabilityExplanation`, `LowAvailabilitySignal` | 搜索和推荐展示可用性、余票概览、候补入口提示。 |
| Offer Management | `AvailabilityEvidence`, `AvailabilityVersion`, `AvailabilityChanged`, `AvailabilityBecameUnavailable` | Offer 冻结可用性证据并在可用性变化时失效或重报价。 |
| Booking Orchestration | `CapacityHeld`, `CapacityHoldFailed`, `CapacityHoldConfirmed`, `CapacityReleased`, `CapacityHoldExpired` | Booking Saga 根据库存事实推进或补偿。 |
| Journey Order | `CapacityReservationProgress` read projection only | 订单只读取库存进度摘要，不直接改库存。 |
| Post Sales | `CapacityReleased`, `ReplacementCapacityHeld`, `CapacityReleaseFailed` | 售后流程确认释放或改签新库存状态。 |
| Waitlist | `CapacityReleased`, `CapacityAdjusted`, `WaitlistTriggerRaised`, `WaitlistHoldResult` | 库存释放或调增后触发候补匹配；候补兑现仍通过 Hold 命令。 |
| Disruption Recovery | `CapacityFrozenForDisruption`, `AffectedInventoryIdentified`, `DisruptionCapacityReleased` | 异常恢复需要知道受影响库存和可恢复资源。 |
| Provider Integration | `ProviderInventoryRecheckRequested`, `ProviderHoldReleaseRequested` | 对外部库存不确定或释放失败时请求供应商重查/释放。 |
| Reporting | `InventoryLedgerEvent`, `QuotaUsageChanged`, `AvailabilitySnapshotPublished`, `InventoryDiscrepancyDetected` | 销售、上座率、库存差异、候补成功率等分析。 |
| Admin & Audit | `InventoryAdjustmentApplied`, `ManualCapacityAdjustmentRejected`, `InventoryAuditTrail` | 后台审计和人工干预追踪。 |
| Notification | `LowAvailabilityNotificationRequested` only via policy if configured | 本上下文不直接发消息，可发布事件供 Notification 决定。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `InventoryPool` | 同一 `Segment`、日期、容量类型、站序区间和 `CapacityUnit` 的 Confirmed Occupancy 不能重叠；物理容量、停售信息和可售库存计算版本一致；任何调整必须有原因和来源。 | `InitializeInventoryPool`, `RebuildInventoryFromServicePlan`, `ApplyServicePlanChange`, `ApplyInventoryAdjustment`, `FreezeInventory`, `UnfreezeInventory`, `RecordOccupancy`, `ReleaseOccupancy` | `InventoryPoolInitialized`, `InventoryRebuilt`, `InventoryAdjusted`, `InventoryFrozen`, `InventoryUnfrozen`, `OccupancyRecorded`, `OccupancyReleased`, `InventoryDiscrepancyDetected` |
| `CapacityHold` | Hold 必须有 `HoldScope`、过期时间、幂等键、状态；有效 Hold 与有效 Hold/Occupancy 在重叠区间不能冲突；Confirmed Hold 不能被普通过期任务释放。 | `HoldCapacity`, `ConfirmHold`, `ReleaseHold`, `ExpireHold`, `ReplaceHold`, `ExtendHold` | `CapacityHeld`, `CapacityHoldFailed`, `CapacityHoldConfirmed`, `CapacityReleased`, `CapacityHoldExpired`, `CapacityHoldReplaced`, `CapacityHoldExtended` |
| `QuotaAllocation` | Quota 不是物理库存；同一 QuotaBucket 的已用额度不能超过分配额度；Quota 占用和释放必须与 Hold/Occupancy 幂等关联；冻结 Quota 不能继续占用。 | `ConfigureQuota`, `AllocateQuota`, `HoldQuota`, `ConfirmQuotaUsage`, `ReleaseQuota`, `FreezeQuota`, `RebalanceQuota` | `QuotaConfigured`, `QuotaAllocated`, `QuotaHeld`, `QuotaUsageConfirmed`, `QuotaReleased`, `QuotaFrozen`, `QuotaRebalanced`, `QuotaExceededRejected` |
| `AvailabilitySnapshot` | Snapshot 必须有来源版本、生成时间、有效期、适用渠道和解释；Snapshot 只能用于读，不能证明下单成功；过期或底层版本变化后必须失效或降级。 | `PublishAvailabilitySnapshot`, `ExpireAvailabilitySnapshot`, `MarkAvailabilityStale`, `ExplainAvailability` | `AvailabilitySnapshotPublished`, `AvailabilitySnapshotExpired`, `AvailabilityMarkedStale`, `AvailabilityExplanationGenerated` |
| `CapacityLedger` | 每个库存变更事件必须追加到账本；同一业务幂等键不能重复入账；账本不可变更，只能追加纠正事件。 | `AppendInventoryLedgerEvent`, `AppendCorrectionEvent`, `DetectInventoryDiscrepancy` | `InventoryLedgerAppended`, `InventoryCorrectionAppended`, `InventoryDiscrepancyDetected` |

### `InventoryPool`

`InventoryPool` 是固定班次或供给单元的库存根。Train Ticket 第一阶段建议以 `serviceSegmentId`、`serviceDate`、`seatClass/capacityType`、`stopSequenceVersion`、`capacityPolicyType` 为粒度建立。它不保存订单详情，只保存与容量相关的引用：`orderId`、`segmentBookingId`、`travelerRef`、`holdId`、`releaseReason`。具体订单、支付、票证由其他上下文拥有。

### `CapacityHold`

`CapacityHold` 是交易链路最关键的强一致聚合。它保护短时间内资源不被重复售卖。每个 Hold 必须包含 `holdId`、`inventoryPoolId`、`holdScope`、`capacityUnitRef` 或容量桶数量、`stationInterval`、`quotaBucketRef`（如适用）、`orderId`、`segmentBookingId`、`travelerRef`、`expiresAt`、`state`、`idempotencyKey`、`sourceContext`、`reason`。

火车座位区间冲突判断使用站序半开区间：

```text
existing = [existingFromSeq, existingToSeq)
request  = [requestFromSeq, requestToSeq)
conflict = existingFromSeq < requestToSeq && requestFromSeq < existingToSeq
```

| 已 Hold/Occupancy | 新请求 | 结果 | 原因 |
|---|---|---|---|
| A -> C | C -> E | 允许 | 半开区间相邻，不重叠。 |
| A -> C | B -> D | 拒绝 | B-C 重叠。 |
| B -> D | A -> B | 允许 | 到达 B 后座位可复用。 |
| B -> D | D -> E | 允许 | D 后座位可复用。 |
| B -> D | C -> D | 拒绝 | C-D 重叠。 |
| A -> E | B -> C | 拒绝 | 新请求完全落入已占用区间。 |

### `QuotaAllocation`

`QuotaAllocation` 控制销售额度，不代表座位本身。Hold 时必须同时检查 Inventory 是否有可用容量、渠道/策略 Quota 是否还有额度、Quota 和 Inventory 的占用是否使用同一个业务幂等键。Quota 的释放与库存释放可以最终一致，但必须有重试和差异检测。配额不足时返回 `QuotaExceededRejected`，不能伪装成无物理库存。

### `AvailabilitySnapshot`

`AvailabilitySnapshot` 是售前缓存读模型的写侧权威，不是交易占座。Snapshot 可以记录余量等级而非精确座位号：`Available`、`LowAvailability`、`WaitlistOnly`、`SoldOut`、`NotYetOnSale`、`SalesClosed`、`Suspended`、`ProviderUnknown`。搜索结果可过期；Offer 可引用 Snapshot 版本；最终下单必须执行 `HoldCapacity`。

## 6. 状态机

列出本 domain 拥有的状态机。不要定义其他 domain 的状态。

### `CapacityHold` 状态机

| 状态 | 含义 |
|---|---|
| `Requested` | 已收到 Hold 命令，正在检查 Inventory、Quota 和策略。 |
| `Held` | 资源已临时锁定，有明确过期时间。 |
| `Confirmed` | Hold 已转为正式 Occupancy。 |
| `Released` | Hold 或 Occupancy 已释放，可再次销售或触发 Waitlist。 |
| `Expired` | Hold 超时失效，并应释放相关 Inventory/Quota。 |
| `Failed` | 库存不足、配额不足、规则不允许或冲突导致 Hold 失败。 |
| `Replacing` | 改签或换座场景中，新旧 Hold 正在受控替换。 |

| 当前状态 | 触发事件 | 目标状态 | 规则 |
|---|---|---|---|
| `Requested` | `CapacityHeld` | `Held` | Inventory 与 Quota 均通过检查。 |
| `Requested` | `CapacityHoldFailed` | `Failed` | 不写入有效占用，但要记录失败原因。 |
| `Held` | `CapacityHoldConfirmed` | `Confirmed` | 由 Booking Orchestration 在支付/供应确认满足后触发。 |
| `Held` | `CapacityReleased` | `Released` | 订单取消、预订失败、改签放弃等。 |
| `Held` | `CapacityHoldExpired` | `Expired` | 当前时间超过 `expiresAt` 且未 Confirmed。 |
| `Held` | `CapacityHoldExtended` | `Held` | 仅允许受控延长，必须记录原因和最大延长期限。 |
| `Confirmed` | `CapacityReleased` | `Released` | 退票、改签、异常释放或人工调整。 |
| `Confirmed` | `CapacityHoldExpired` | 禁止 | Confirmed 不能被普通过期任务释放。 |
| `Held` | `CapacityHoldReplaced` | `Released` | 新 Hold 成功后释放旧 Hold，或按 Saga 规则保持旧 Hold。 |
| `Replacing` | `ReplacementCapacityHeld` | `Held` 或 `Released` | 取决于旧票是否已确认切换。 |

### `InventoryPool` 状态机

| 状态 | 含义 |
|---|---|
| `Draft` | Service Plan 已存在但库存尚未初始化。 |
| `Initialized` | 物理容量和站序已生成库存池。 |
| `OpenForSale` | 在销售窗口内且允许普通销售。 |
| `Restricted` | 可售但受限，例如渠道配额、席别限制、候补优先。 |
| `Frozen` | 暂停 Hold 和普通销售，可用于异常、人工检查或供应未知。 |
| `ClosedForSale` | 销售窗口关闭或停售。 |
| `Cancelled` | Segment 停运或服务取消，普通销售永久关闭。 |
| `Archived` | 活跃交易结束，仅保留查询和审计。 |

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| `Draft` | `InventoryPoolInitialized` | `Initialized` |
| `Initialized` | `SalesWindowOpened` | `OpenForSale` |
| `OpenForSale` | `SaleRestrictionApplied` | `Restricted` |
| `Restricted` | `SaleRestrictionRemoved` | `OpenForSale` |
| `OpenForSale`/`Restricted` | `InventoryFrozen` | `Frozen` |
| `Frozen` | `InventoryUnfrozen` | `OpenForSale` 或 `Restricted` |
| `OpenForSale`/`Restricted`/`Frozen` | `SalesWindowClosed` | `ClosedForSale` |
| 任意活跃状态 | `ServiceSegmentCancelled` | `Cancelled` |
| `ClosedForSale`/`Cancelled` | `InventoryArchived` | `Archived` |

### `QuotaAllocation` 状态机

| 状态 | 含义 |
|---|---|
| `Draft` | 配额配置草稿，尚未生效。 |
| `Active` | 可被 Hold 使用。 |
| `Exhausted` | 已用额度达到上限。 |
| `Frozen` | 暂停使用。 |
| `Rebalanced` | 发生跨渠道调配，版本已更新。 |
| `Closed` | 销售期结束或策略关闭。 |

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| `Draft` | `QuotaConfigured` | `Active` |
| `Active` | `QuotaHeld` 且剩余额度为 0 | `Exhausted` |
| `Exhausted` | `QuotaReleased` 或 `QuotaRebalanced` | `Active` |
| `Active`/`Exhausted` | `QuotaFrozen` | `Frozen` |
| `Frozen` | `QuotaRebalanced` | `Rebalanced` |
| `Rebalanced` | `QuotaActivated` | `Active` |
| 任意状态 | `QuotaClosed` | `Closed` |

### `AvailabilitySnapshot` 状态机

| 状态 | 含义 |
|---|---|
| `Fresh` | 仍在有效期内且底层版本未变化。 |
| `Stale` | 底层库存或计划变化，仍可展示但必须提示重新确认。 |
| `Expired` | 超过有效期，不能用于 Offer。 |
| `Invalidated` | 因停售、停运、供应商冲突或人工调整被强制失效。 |

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| `Fresh` | `AvailabilityMarkedStale` | `Stale` |
| `Fresh`/`Stale` | `AvailabilitySnapshotExpired` | `Expired` |
| `Fresh`/`Stale`/`Expired` | `AvailabilityInvalidated` | `Invalidated` |
| `Stale`/`Expired`/`Invalidated` | `AvailabilitySnapshotPublished` | `Fresh` |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `InitializeInventoryPool` | `InventoryPool` | `InventoryPoolInitialized` | `serviceSegmentId + serviceDate + capacityType + stopSequenceVersion` |
| `RebuildInventoryFromServicePlan` | `InventoryPool` | `InventoryRebuilt` | `servicePlanVersion + inventoryPoolId` |
| `ApplyServicePlanChange` | `InventoryPool` | `InventoryAdjusted` / `InventoryFrozen` / `InventoryPoolCancelled` | `servicePlanEventId + inventoryPoolId` |
| `FreezeInventory` | `InventoryPool` | `InventoryFrozen` | `inventoryPoolId + freezeReason + sourceEventId` |
| `UnfreezeInventory` | `InventoryPool` | `InventoryUnfrozen` | `inventoryPoolId + unfreezeReason + sourceEventId` |
| `ApplyInventoryAdjustment` | `InventoryPool` | `InventoryAdjusted` | `approvalId + inventoryPoolId + adjustmentVersion` |
| `HoldCapacity` | `CapacityHold` | `CapacityHeld` / `CapacityHoldFailed` | `orderId + segmentId + travelerId + holdPurpose` |
| `HoldReplacementCapacity` | `CapacityHold` | `ReplacementCapacityHeld` / `CapacityHoldFailed` | `postSalesCaseId + oldEntitlementId + targetSegmentId + travelerId` |
| `HoldCapacityForWaitlist` | `CapacityHold` | `WaitlistCapacityHeld` / `CapacityHoldFailed` | `waitlistRequestId + inventoryPoolId + travelerId + attemptNo` |
| `ConfirmHold` | `CapacityHold` | `CapacityHoldConfirmed` | `holdId + segmentBookingId + confirmationAttempt` |
| `ReleaseHold` | `CapacityHold` | `CapacityReleased` | `holdId + releaseReason + sourceEventId` |
| `ExpireHold` | `CapacityHold` | `CapacityHoldExpired` | `holdId + expiresAt` |
| `ExtendHold` | `CapacityHold` | `CapacityHoldExtended` | `holdId + extensionReason + extensionAttempt` |
| `ReplaceHold` | `CapacityHold` | `CapacityHoldReplaced` | `postSalesCaseId + oldHoldId + newHoldId` |
| `ConfigureQuota` | `QuotaAllocation` | `QuotaConfigured` | `quotaPolicyId + version` |
| `HoldQuota` | `QuotaAllocation` | `QuotaHeld` / `QuotaExceededRejected` | `quotaBucketId + holdId` |
| `ConfirmQuotaUsage` | `QuotaAllocation` | `QuotaUsageConfirmed` | `quotaBucketId + holdId + confirmationAttempt` |
| `ReleaseQuota` | `QuotaAllocation` | `QuotaReleased` | `quotaBucketId + holdId + releaseReason` |
| `FreezeQuota` | `QuotaAllocation` | `QuotaFrozen` | `quotaBucketId + freezeReason + sourceEventId` |
| `RebalanceQuota` | `QuotaAllocation` | `QuotaRebalanced` | `quotaPolicyId + rebalanceVersion + approvalId` |
| `PublishAvailabilitySnapshot` | `AvailabilitySnapshot` | `AvailabilitySnapshotPublished` | `segmentId + queryScope + inventoryVersion + generatedAtBucket` |
| `ExpireAvailabilitySnapshot` | `AvailabilitySnapshot` | `AvailabilitySnapshotExpired` | `snapshotId + expiresAt` |
| `MarkAvailabilityStale` | `AvailabilitySnapshot` | `AvailabilityMarkedStale` | `snapshotId + sourceInventoryEventId` |
| `ExplainAvailability` | `AvailabilitySnapshot` | `AvailabilityExplanationGenerated` | `queryId + segmentId + inventoryVersion` |
| `DetectInventoryDiscrepancy` | `CapacityLedger` | `InventoryDiscrepancyDetected` | `inventoryPoolId + detectionRunId` |
| `AppendCorrectionEvent` | `CapacityLedger` | `InventoryCorrectionAppended` | `approvalId + discrepancyId` |

### 事件语义要求

- `CapacityHeld` 表示资源临时锁定，不表示订单已支付或票已出票。
- `CapacityHoldConfirmed` 表示库存转为正式 Occupancy，不表示 Entitlement 已签发。
- `CapacityReleased` 表示资源已回到可售或可再分配状态，并可触发 Waitlist。
- `AvailabilitySnapshotPublished` 表示读模型更新，不提供交易强一致保证。
- `QuotaExceededRejected` 必须和 `CapacityHoldFailed` 区分，避免把渠道配额不足误报为无物理票。
- `InventoryAdjusted` 必须携带 `adjustmentReason`、`sourceContext`、`approvalRef` 或 `systemPolicyRef`。

## 8. 策略和 Saga 参与点

说明本 domain 如何响应外部事件，以及会触发哪些跨域命令。

### 售前查询策略

Trip Planning 发起 `AvailabilityQuery`；Capacity & Availability 查询 `AvailabilitySnapshot`，若 Snapshot 缺失或过期，可同步计算或返回 `Stale` 解释。返回内容包括可售状态、余量等级、Quota 限制、候补入口建议、版本和有效期。此流程不触发跨域写命令，不创建 Hold。

### Offer 报价参与策略

Offer Management 请求 `AvailabilityEvidence`。本上下文返回库存版本、可售解释、最晚确认时间和是否需要下单强校验。Offer 记录 `availabilityVersion`，但不能认为库存已锁定。如果底层库存变化导致原 Offer 风险升高，本上下文发布 `AvailabilityChanged` 或 `AvailabilityBecameUnavailable`，由 Offer Management 决定失效或重新报价。

### 下单 Saga 参与点

| Saga Step | 本上下文行为 | 对外事件/命令 |
|---|---|---|
| `JourneyOrderCreated` 后 | Booking Orchestration 发起 `HoldCapacity`。 | 成功发布 `CapacityHeld`；失败发布 `CapacityHoldFailed`。 |
| 所有必要 Hold 成功 | 等待 Booking Orchestration 和 Payment 继续推进。 | 不直接创建 PaymentIntent。 |
| `PaymentCaptured` 或供应确认满足 | Booking Orchestration 发起 `ConfirmHold`。 | 发布 `CapacityHoldConfirmed`。 |
| 支付超时或订单取消 | Booking Orchestration 发起 `ReleaseHold`。 | 发布 `CapacityReleased`，并触发 Waitlist policy。 |
| Hold 到期 | 定时策略执行 `ExpireHold`。 | 发布 `CapacityHoldExpired` 和必要的 `CapacityReleased`。 |

### 取消未支付 Saga 参与点

- 消费或接收 Booking Orchestration 的 `ReleaseHold` 命令。
- 对 `Held` 状态释放资源；对 `Confirmed` 状态必须拒绝普通未支付取消释放，避免错误释放已支付票。
- 发布 `CapacityReleased` 后，Waitlist policy 可被触发。
- 如果释放失败，发布 `CapacityReleaseFailed` 并进入释放重试队列；不得让订单静默完成取消。

### 退票 Saga 参与点

Post Sales 在 Entitlement 作废或供应商取消满足条件后发起 `ReleaseHold`/`ReleaseOccupancy`。本上下文将 Confirmed Occupancy 转为 Released，发布 `CapacityReleased`，包含原区间、席别、容量单元、释放原因 `Refund`。本上下文不判断退票手续费，也不决定退款金额。

### 改签 Saga 参与点

保守顺序：Post Sales 请求目标 `Segment` 的 `HoldReplacementCapacity`；本上下文对新目标区间执行 Hold 并返回 `ReplacementCapacityHeld`；差价处理失败时释放新 Hold；旧票作废且新票确认后释放旧 Confirmed Occupancy；若旧票未作废、新票 Hold 失败，旧 Occupancy 不受影响。本上下文不得自行作废旧票，也不得自行创建新 Entitlement。

### Waitlist 参与点


### Service Plan 变化策略

| Service Plan 事件 | Capacity & Availability 行为 |
|---|---|
| 新车次/服务发布 | 初始化 `InventoryPool`、站序版本和默认 Quota。 |
| 开售窗口打开 | 将可售库存从 `Initialized` 推进到 `OpenForSale`。 |
| 停售/限售 | 冻结或限制对应 Inventory/Quota，发布 Snapshot stale。 |
| 经停站变化 | 若影响站序，冻结相关 Inventory，识别已 Hold/Occupancy，并通知 Disruption Recovery。 |
| 换编组/加挂 | 调整 PhysicalCapacity，增加或减少可售库存，记录调整账本。 |
| 停运 | 关闭普通销售，冻结未处理 Hold，发布受影响库存事件给 Disruption Recovery。 |

### Provider Integration 策略

- 内部库存权威场景：Provider Integration 只提供运行计划或外部确认，本上下文是库存占用权威。
- 外部库存权威场景：本上下文只保存平台可用性镜像和 Hold 结果，不能伪造供应商确认；Booking Orchestration 必须经 Provider Integration 完成预订。
- 混合场景：内部 Quota 和供应商库存同时约束，Hold 必须同时记录平台 Hold 和 provider reservation reference。

### Admin & Audit 策略

所有人工库存调整必须先由 Admin & Audit 产生审批或受控命令，携带操作者、原因、证据、影响范围、审批引用；本上下文执行聚合不变量检查，发布 `InventoryAdjustmentApplied` 或 `ManualCapacityAdjustmentRejected`，并追加 `CapacityLedger`。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `AvailabilitySearchIndex` | `AvailabilitySnapshotPublished`, `AvailabilityMarkedStale`, `AvailabilitySnapshotExpired`, `InventoryAdjusted`, `QuotaUsageChanged` | Trip Planning、搜索页、Offer Management 初筛。 |
| `SegmentAvailabilityView` | `InventoryPoolInitialized`, `CapacityHeld`, `CapacityHoldConfirmed`, `CapacityReleased`, `QuotaHeld`, `QuotaReleased` | 车次详情、席别余票展示、客服查询。 |
| `InventoryOccupancyMap` | `CapacityHeld`, `CapacityHoldConfirmed`, `CapacityReleased`, `CapacityHoldExpired`, `OccupancyRecorded`, `OccupancyReleased` | Booking Orchestration、库存排障、Admin。 |
| `RailIntervalSeatMap` | `InventoryPoolInitialized`, `CapacityHeld`, `CapacityHoldConfirmed`, `CapacityReleased`, `InventoryAdjusted` | Train Ticket 座席区间冲突检查、运营查看上座区间。 |
| `QuotaUsageView` | `QuotaConfigured`, `QuotaHeld`, `QuotaUsageConfirmed`, `QuotaReleased`, `QuotaRebalanced`, `QuotaFrozen` | 渠道运营、B2B、Admin & Audit、Reporting。 |
| `HoldExpirationQueue` | `CapacityHeld`, `CapacityHoldExtended`, `CapacityHoldConfirmed`, `CapacityReleased` | Hold 过期任务、释放重试任务。 |
| `WaitlistTriggerView` | `CapacityReleased`, `InventoryAdjusted`, `QuotaReleased`, `InventoryUnfrozen` | Waitlist 匹配器。 |
| `InventoryAuditView` | `InventoryLedgerAppended`, `InventoryCorrectionAppended`, `InventoryDiscrepancyDetected`, `InventoryAdjustmentApplied` | Admin & Audit、Customer Service、Reporting。 |
| `InventoryDiscrepancyView` | `InventoryDiscrepancyDetected`, `ProviderInventoryChanged`, reconciliation events | 运营排障、Provider Integration 对账、人工兜底。 |
| `AvailabilityExplanationView` | `AvailabilityExplanationGenerated`, `SaleRestrictionApplied`, `QuotaExceededRejected`, `CapacityHoldFailed` | 搜索不可售解释、客服话术、Offer 失败解释。 |
| `CapacityUtilizationReportFeed` | `CapacityHoldConfirmed`, `OccupancyReleased`, `InventoryAdjusted`, `ServiceSegmentCompleted` | Reporting、运力分析、上座率分析。 |

读写分离规则：搜索和详情页读取 `AvailabilitySearchIndex` 或 `SegmentAvailabilityView`；Booking Orchestration 执行 Hold 必须进入写模型；Admin 看板可以展示库存差异但修正必须发起受控命令；Reporting 只能消费事件和读模型，不反向修改库存。

## 10. 外部系统和防腐层

说明是否需要供应商适配、支付渠道、通知渠道、后台系统或遗留服务防腐层。

### Provider Integration ACL

Capacity & Availability 不直接调用外部供应商原始 API。所有外部铁路、航司、船司、大巴平台、网约车平台库存和供给信息通过 Provider Integration 映射为统一契约。

| Provider 语言 | 平台语言 | ACL 要求 |
|---|---|---|
| 铁路余票、席别票额、候补状态 | `ProviderAvailabilityReported`, `InventoryAdjustment`, `WaitlistTrigger` | 不把供应商状态码暴露给核心域；要映射不可售原因。 |
| 航司舱位 RBD、NDC offer availability | `AirCabinInventorySnapshot`, `ProviderAvailabilityReported` | 航司可能是库存权威，平台 Snapshot 必须标记 provider-owned。 |
| 大巴座位图、班线余座 | `CoachSeatInventorySnapshot` | 可复用固定班次座位策略，但上车点可能非标准站序。 |
| 轮船舱房、铺位、车辆甲板 | `FerryCapacitySnapshot` | 同一船班存在多类容量单元，舱房和车辆空间可能联动。 |
| 网约车司机供给、ETA、热区供给 | `RideDynamicSupplySnapshot` | 动态供给不是可锁定固定座位；Hold 语义应是短时派单能力预占或报价可用性。 |

### 遗留服务防腐层

第一阶段迁移时，需要建立 Legacy ACL：

- 从 `ts-travel*` 和 `ts-train-service` 读取车次、车型、容量时，映射为 `ServiceSegment` 和 `PhysicalCapacity`，不保留高铁/普通车服务边界。
- 从 `ts-seat-service` 的余票计算迁移到 `InventoryPool` 和 `CapacityHold`；旧服务只能作为旁路校验或兼容查询。
- 从 `ts-order*` 反推已售座位只能用于迁移初始化和差异检测，不能成为新库存写模型权威。
- `tripId.startsWith("G") || tripId.startsWith("D")` 只能在 Legacy ACL 中暂时存在，目标模型必须使用 Service Plan 的 train category 或 service type。

### 后台系统防腐层

Admin & Audit 是后台操作入口。本上下文只接受已经通过权限和审批的命令。禁止后台直接改库存表、Hold 状态、Quota 使用量或 Snapshot 状态。

### 定时任务和可靠性组件

本上下文需要 Hold expiration scheduler、Release retry queue、Inventory reconciliation job、Outbox/Inbox、Idempotency store、CapacityLedger append-only store。这些是技术设施，不是独立业务事实。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-seat-service` | 最大迁移对象。当前通过订单反推余票和随机分配座位，目标改为 `InventoryPool`、`CapacityHold`、`RailIntervalSeatMap` 和过期释放。旧 seat API 应被拆成查询 Snapshot、Hold、Confirm、Release 四类能力。 |
| `ts-travel-service` | 车次查询中的余票依赖迁移到 `AvailabilitySnapshot`；车次本身进入 Service Plan。高铁/普通车分裂不应继续影响库存模型。 |
| `ts-travel2-service` | 同 `ts-travel-service`，与其合并为统一 Service Plan + Trip Planning 供给；库存只按 Segment 和 capacity type 建模。 |
| `ts-travel-plan-service` | 查询方案补余票应改为消费 `AvailabilitySearchIndex`；不能直接调 seat-service 进行实时余票拼装。参数反置风险迁移时需通过契约测试覆盖。 |
| `ts-route-plan-service` | 只生成候选方案或排序，不应拥有库存判断；未来由 Trip Planning 消费 Availability。 |
| `ts-preserve-service` | 当前同步订票编排改为 Booking Orchestration 调用 `HoldCapacity`；保险、餐饮、托运失败不应影响库存释放语义。 |
| `ts-preserve-other-service` | 同 `ts-preserve-service`，迁移时消除复制服务边界，使用统一 Hold 命令和幂等键。 |
| `ts-order-service` | 不再作为库存事实来源；历史订单可用于迁移初始化已售 Occupancy 和差异检测。 |
| `ts-order-other-service` | 同 `ts-order-service`；迁移时需要统一订单 ID 和 SegmentBooking 引用，避免双库重复占用。 |
| `ts-inside-payment-service` | 支付成功后不直接改库存；Payment 发布事件，由 Booking Orchestration 发 `ConfirmHold`。支付超时触发 Release。 |
| `ts-payment-service` | 外部支付模拟不影响库存；晚到支付不能直接恢复 Hold，需按 Saga 处理。 |
| `ts-cancel-service` | 取消/退票释放库存迁移到 Post Sales 发起 `ReleaseHold`；退款规则不在本上下文。 |
| `ts-rebook-service` | 改签迁移为先 Hold 新库存，再处理差价，再释放旧库存；删除旧订单再新建订单的方式必须停止。 |
| `ts-wait-order-service` | 轮询 preserve 改为消费 `WaitlistTriggerRaised`/`CapacityReleased`，再发起 `HoldCapacityForWaitlist`。队列归 Waitlist，不归本上下文。 |
| `ts-config-service` | `DirectTicketAllocationProportion` 等配置应迁移为版本化 `QuotaPolicy` 或 `CapacityPolicy`；配置变更需审计。 |
| `ts-admin-travel-service` | 后台车次调整影响库存时必须通过 Service Plan 事件和 Admin & Audit 命令触发，不可直接改库存。 |
| `ts-admin-basic-info-service` | 车型容量、席别配置迁移为 Service Plan/Supplier Catalog 输入；库存初始化消费稳定契约。 |
| `ts-admin-order-service` | 只能读取库存审计和订单时间线；不能直接修库存。人工修正走 `ApplyManualCapacityAdjustment`。 |
| `ts-security-service` | 下单限流和黄牛检查迁移到 Risk & Compliance；Capacity 只消费允许/拒绝或限额决策。 |
| `ts-execute-service` | 取票、进站、履约不修改库存；履约完成可供 Reporting 计算上座率，但不释放已使用库存。 |
| `ts-notification-service` | 只消费库存相关通知事件，不参与库存决策。 |
| `ts-food-service`, `ts-consign-service`, `ts-assurance-service` | 附加服务不占用主票库存；如未来餐饮/托运有容量限制，应作为 Ancillary Service 自己的 capacity variant，不能混入主票 `InventoryPool`。 |

### 迁移切片建议

1. 建立 `InventoryPool` 和 `CapacityHold` 新写模型，先旁路记录，不切流。
2. 从历史订单构建初始 Occupancy，生成 `InventoryDiscrepancyView` 与旧 seat 余票对比。
3. 将新订单 Hold 切到 `HoldCapacity`，旧 seat-service 只做兼容读。
4. 接入支付超时和取消释放，验证 Hold 不泄漏。
5. 接入退票和改签释放/替换流程。
6. 建立 `AvailabilitySearchIndex` 替换查询链路实时调用 seat-service。
7. 接入 Waitlist trigger，替换 wait-order 轮询 preserve。
8. 最后移除高铁/普通车复制库存逻辑。

## 12. 验收标准

- 本 domain 的聚合所有权明确：`InventoryPool`、`CapacityHold`、`QuotaAllocation`、`AvailabilitySnapshot`、`CapacityLedger` 归 Capacity & Availability；`JourneyOrder`、`PaymentIntent`、`Entitlement`、`FareRule`、Provider booking internals 不归本上下文。
- 本 domain 发布和消费的事件明确：对外发布 `AvailabilitySnapshotPublished`、`CapacityHeld`、`CapacityHoldConfirmed`、`CapacityReleased`、`CapacityHoldExpired`、`QuotaUsageChanged`、`WaitlistTriggerRaised`、`InventoryAdjustmentApplied` 等事件；消费 Service Plan、Booking Orchestration、Post Sales、Disruption Recovery、Provider Integration 和 Admin & Audit 的受控契约。
- 不变量和状态机没有依赖其他 domain 内部状态：库存冲突、Quota 上限、Hold 过期、Confirmed Occupancy 释放均在本上下文内部判断；支付、出票、退改规则只作为外部命令或事件输入。
- Train Ticket 区间库存建模正确：同一座位在不重叠站段可复用，重叠站段不能重复 Hold 或 Confirmed Occupancy；区间判断使用站序半开区间。
- 明确区分 PhysicalCapacity、SellableInventory、Quota、CapacityHold、Occupancy、AvailabilitySnapshot：文档中分别定义其生命周期、读写用途和误用风险。
- 搜索可用性与交易锁库存分离：`AvailabilitySnapshot` 可缓存可过期，`HoldCapacity` 才是下单强一致入口。
- Saga 参与点可实现：下单、支付超时、取消、退票、改签、候补、异常停运均有明确库存命令、事件和补偿方向。
- 上下游协作完整覆盖 Service Plan、Fare & Pricing、Trip Planning、Offer Management、Booking Orchestration、Post Sales、Waitlist、Disruption Recovery、Provider Integration、Reporting、Admin & Audit。
- 当前服务迁移影响明确：`ts-seat-service` 从订单反推库存迁移到 `CapacityHold`；`ts-wait-order-service` 从轮询 preserve 迁移到事件驱动；高铁/普通车复制服务不再成为目标库存边界。
- General Travel 扩展已预留：航空舱位库存、大巴座位、轮船舱房/车辆甲板、网约车动态供给以 `CapacityPolicy` 和 `CapacityUnit` 特化承接。
