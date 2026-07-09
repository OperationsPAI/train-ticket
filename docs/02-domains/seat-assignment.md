# Seat Assignment Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Seat Assignment |
| Status | proposed-ddd-baseline |
| Owner Agent | agentm-seat-assignment |
| Last Updated | 2026-07-10 |
| High-Level Inputs | `docs/adr/0003-commercial-realism-scope.md`, `docs/02-domains/capacity-availability.md`, `docs/02-domains/entitlement-ticketing.md`, `docs/02-domains/service-plan.md` |

## 1. 领域目标

Seat Assignment 负责在出票时刻回答“具体哪个座/铺”。Capacity & Availability 管“有多少张”、段级 Hold/Confirmed Occupancy 和超卖不变量；本域只在已有容量预留之上，为旅客分配具体 SeatUnit、卧铺铺位或明确的 `standing` 无座语义，并在退票、改签、出票失败和取消时释放回座位池。

独立建模原因：座位图随车辆编组版本变化，连座与卧铺偏好需要自己的求解和降级解释；Entitlement 需要稳定座位展示事实但不应拥有座位冲突判断；Service Plan 拥有计划和站序但不拥有座位占用。

## 2. 边界

### In Scope

- 版本化 `SeatMap`：按 `scheduledServiceRef`、运行日期、编组版本、车厢、席别和 SeatUnit 建模。
- `SeatAllocation`：绑定 `segmentBookingId`、`travelerRef`、Capacity hold/occupancy 引用和站序半开区间 `[fromSeq,toSeq)`。
- 同一 SeatUnit 在重叠站段不可重复分配；相邻不重叠站段可复用。
- `AdjacencyGroup` 连座/同车厢/同排/相邻铺偏好及降级解释。
- 卧铺上/中/下铺、同隔间、照护关系等偏好求解。
- `StandingAllocation`：有容量预留但无具体座位时的无座票语义。
- 退票、改签、取消、出票失败、Entitlement 作废后的座位释放、重分配和审计账本。
- 座位图、座位占用区间、分配结果、连座结果、铺位偏好结果和客服时间线读模型。

### Out of Scope

- 不计算余票、Quota、AvailabilitySnapshot、Hold 或超卖保护；归 Capacity & Availability。
- 不拥有 Service Plan、ScheduledService、ServiceSegment、StopSequence 或计划发布。
- 不创建 JourneyOrder、Booking Saga、PaymentIntent 或 SegmentBooking 商业状态。
- 不签发、作废、冻结 Entitlement；只提供座位展示事实。
- 不定价、不判断退改手续费、实名资格、风控或通知渠道。
- 不接真实铁路/第三方选座网络；外部方一律 SIM/ACL。
- 本任务只写设计文档，不新增 `docs/08-contracts` 或代码。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| `SeatMap` | 某计划服务、运行日、编组版本下的座位/铺位拓扑。 | 发布版本不可原地覆盖。 |
| `SeatMapVersion` | SeatMap 不可变版本。 | 历史分配必须可用旧版本解释。 |
| `VehicleComposition` | 车厢序列、车型、席别、SeatUnit 摘要。 | 来源 Service Plan/SIM ACL。 |
| `Coach` | 编组中的车厢。 | 含 `coachNo`、席别、可分配标记。 |
| `SeatUnit` | 可分配座位或铺位。 | 无座不用虚拟 SeatUnit。 |
| `BerthPosition` | 卧铺位置。 | `UPPER`、`MIDDLE`、`LOWER`、`SIDE_UPPER`、`SIDE_LOWER`。 |
| `SeatAllocation` | 旅客在站段上的座位/铺位分配。 | 必须绑定容量引用。 |
| `StandingAllocation` | 无座分配。 | 是成功语义，不是失败。 |
| `AllocationInterval` | 分配覆盖的站序半开区间。 | 与 Capacity `StationInterval` 一致。 |
| `AdjacencyGroup` | 同行旅客连座/相邻铺请求和结果。 | 记录满足或降级原因。 |
| `PreferenceProfile` | 座位/铺位偏好快照。 | 偏好不是保证。 |
| `ReleaseReason` | 释放原因。 | `VOIDED`、`CHANGED`、`ISSUE_FAILED`、`DISRUPTION` 等。 |

## 4. 上下游契约

仅引用 `docs/08-contracts/` 已存在的上下文、事件、命令或端点；Seat Assignment 新事件/命令只在第 7 节出现。

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Service Plan | `ServicePlanPublished`, `ServicePlanChanged`; `POST /api/v1/scheduled-services`; `POST /api/v1/service-segments`; `GET /api/v1/scheduled-services/{serviceRef}` | 初始化 SeatMap，取得计划服务、服务段和站点时间。 |
| Capacity & Availability | `CapacityHeld`, `CapacityHoldConfirmed`, `CapacityReleased`, `CapacityHoldExpired`; `POST /api/v1/capacity-holds`; `POST /api/v1/capacity-holds/{holdId}/confirm`; `POST /api/v1/capacity-holds/{holdId}/release`; `GET /api/v1/capacity-holds/{holdId}` | 分配必须绑定容量预留；释放/过期驱动座位回收。 |
| Booking Orchestration | `SegmentReservationRequested`, `SegmentCapacityHolding`, `SegmentReservationConfirmed`, `SegmentReservationFailed`, `SegmentBookingCancelled`; `POST /api/v1/internal/booking-sagas`; `POST /api/v1/internal/booking-sagas/{sagaId}/request-reservation`; `POST /api/v1/internal/booking-sagas/{sagaId}/mark-ticketed` | 提供 segmentBookingId、travelerRef 和 Saga 取消/失败事实。 |
| Entitlement & Ticketing | `EntitlementIssued`, `EntitlementVoided`, `EntitlementIssueFailed`; `POST /api/v1/entitlements`; `POST /api/v1/entitlements/{entitlementId}/void`; `GET /api/v1/entitlements/{entitlementId}` | 出票确认分配；作废/失败释放分配。 |
| Provider Integration | `ProviderReservationConfirmed`, `ProviderReservationFailed`, `ProviderReservationTimedOut`, `ProviderTicketIssued` | 外部席位/出票结果必须经既有 ACL 归一。 |

### Downstream

| Downstream Context | Published/Used Existing Contract | Reason |
|---|---|---|
| Entitlement & Ticketing | `IssueEntitlement` command; `EntitlementIssued` | 出票需要座位展示输入；字段后续契约化，本文不改契约。 |
| Notification | `ScheduleNotification`, `NotificationScheduled`, `NotificationDispatched`, `NotificationDelivered`, `NotificationFailed`, `NotificationCancelled` | 座位号、无座、连座降级可触发通知；发送归 Notification。 |
| Booking Orchestration | `SegmentReservationConfirmed`, `SegmentTicketed`, `BookingSagaCompleted`, `BookingSagaFailed` | 分配结果作为 Saga 前置/补偿输入；Saga 状态不归本域。 |
| Capacity & Availability | `ReleaseHold`, `CapacityReleased`, `CapacityHoldExpired`; `GET /api/v1/capacity-holds/{holdId}` | 座位释放不替代容量释放。 |
| Customer Service | `AppendTimelineEntry`, `CaseTimelineEntryAppended`; `GET /api/v1/support-cases/{caseId}` | 客服可把座位分配事实追加到工单时间线并查询工单；客服不直接修改本域状态。 |
| Reporting | `DefineMetric`, `PublishMetricVersion`, `RebuildReadModel`, `ReadModelRebuilt`; `GET /api/v1/metrics/{metricId}`, `GET /api/v1/dashboards/{dashboardId}` | 报表可定义和重建座位相关指标；只读查询和指标定义不反向修改 Seat Assignment。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `SeatMap` | 同一 `scheduledServiceRef + serviceDate + compositionVersion` 只有一个 published 版本；SeatUnit 唯一；不可分配 SeatUnit 不能分配。 | `BuildSeatMap`, `PublishSeatMapVersion`, `RetireSeatMapVersion`, `MarkSeatUnitUnavailable`, `ReopenSeatUnit` | `SeatMapBuilt`, `SeatMapVersionPublished`, `SeatMapVersionRetired`, `SeatUnitUnavailableMarked`, `SeatUnitReopened` |
| `SeatAllocation` | active 分配必须有容量引用；同 SeatUnit 重叠区间不可重复；Standing 不占 SeatUnit；终态不可普通回退。 | `AllocateSeat`, `AssignStanding`, `ConfirmSeatAllocation`, `ReleaseSeatAllocation`, `ExpireSeatAllocation`, `FailSeatAllocation`, `ReassignSeat` | `SeatAllocated`, `StandingAssigned`, `SeatAllocationConfirmed`, `SeatAllocationReleased`, `SeatAllocationExpired`, `SeatAllocationFailed`, `SeatReassigned` |
| `AdjacencyGroup` | 成员来自同订单/同行关系；求解记录成功、部分成功或降级；不能越过容量边界。 | `CreateAdjacencyGroup`, `SolveAdjacentAllocation`, `AcceptAdjacencyDegradation`, `CancelAdjacencyGroup` | `AdjacencyGroupCreated`, `AdjacentAllocationSolved`, `AdjacencyDegradationAccepted`, `AdjacencyGroupCancelled` |
| `BerthPreferenceRequest` | 只适用于 berth SeatMap；同旅客同 segmentBooking 只有一个 active preference；必须记录匹配/降级。 | `RecordBerthPreference`, `ApplyBerthPreference`, `CancelBerthPreference` | `BerthPreferenceRecorded`, `BerthPreferenceApplied`, `BerthPreferenceCancelled` |
| `SeatAllocationLedger` | 分配、确认、释放、失败、人工修正均追加；同幂等材料只入账一次；账本不可改写。 | `AppendSeatAllocationLedgerEvent`, `AppendSeatAllocationCorrection`, `DetectSeatAllocationDiscrepancy` | `SeatAllocationLedgerAppended`, `SeatAllocationCorrectionAppended`, `SeatAllocationDiscrepancyDetected` |

区间冲突判断与 Capacity 一致：`existingFromSeq < requestToSeq && requestFromSeq < existingToSeq`。A-C 与 C-E 允许复用；A-C 与 B-D 拒绝；A-E 与 B-C 拒绝。

## 6. 状态机

### `SeatMap` 状态机

| State | Meaning | Next |
|---|---|---|
| `Draft` | 构建中，不可分配。 | `Validating`, `Failed` |
| `Validating` | 校验 SeatUnit、车厢、席别和站序兼容。 | `Published`, `Failed` |
| `Published` | 新分配权威版本。 | `Superseded`, `Retired` |
| `Superseded` | 被新版本替代，解释历史分配。 | `Retired` |
| `Retired` | 服务结束或撤回，仅查询安息。 | - |
| `Failed` | 构建/校验失败，不可逆。 | - |

### `SeatAllocation` 状态机

| State | Meaning | Next |
|---|---|---|
| `Requested` | 收到分配命令，校验容量引用和偏好。 | `Allocated`, `Standing`, `Failed`, `Missed` |
| `Allocated` | 已临时占用具体 SeatUnit。 | `Confirmed`, `Released`, `Expired`, `Allocated`, `Missed` |
| `Standing` | 已分配无座。 | `Confirmed`, `Released`, `Expired`, `Missed` |
| `Confirmed` | 出票后成为凭证展示事实。 | `Released` |
| `Released` | 已释放回座位池，终态可查询安息。 | - |
| `Expired` | Hold/TTL 过期，终态可查询安息。 | - |
| `Failed` | 分配失败，终态不可逆。 | - |
| `Missed` | 晚到事实、已过窗口或 Saga 已终结，终态不可逆。 | - |

规则：`Confirmed` 禁止被普通过期任务释放；`Released`/`Expired`/`Failed`/`Missed` 需要新 allocationId 才能重新开始。Failed/Missed/Cancelled 类不可逆，不通过人工 SQL 回滚；人工恢复创建新事实并引用原失败事件。

### `AdjacencyGroup` 状态机

| State | Meaning | Next |
|---|---|---|
| `Open` | 请求已创建。 | `Solving`, `Cancelled` |
| `Solving` | 正在求解。 | `Satisfied`, `PartiallySatisfied`, `Failed` |
| `Satisfied` | 全部满足。 | `Closed` |
| `PartiallySatisfied` | 部分满足并记录降级。 | `Closed` |
| `Failed` | 无可接受组合，不可逆。 | - |
| `Cancelled` | 上游取消，不可逆。 | - |
| `Closed` | 归档，仅查询。 | - |

## 7. 命令和领域事件

以下为本域新增命令/事件，尚未写入 `docs/08-contracts/`。后续契约化必须使用事件 envelope、camelCase JSON、RFC3339 UTC、SCREAMING_SNAKE 枚举和 UUID-v7 ID。

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `BuildSeatMap` | `SeatMap` | `SeatMapBuilt` / `SeatMapBuildFailed` | `scheduledServiceRef + serviceDate + compositionVersion + buildSourceRef` |
| `PublishSeatMapVersion` | `SeatMap` | `SeatMapVersionPublished` | `seatMapId + seatMapVersion + publishRequestId` |
| `RetireSeatMapVersion` | `SeatMap` | `SeatMapVersionRetired` | `seatMapId + seatMapVersion + retireReason + sourceEventId` |
| `CreateAdjacencyGroup` | `AdjacencyGroup` | `AdjacencyGroupCreated` | `journeyOrderId + segmentRef + sortedTravelerRefs + preferenceVersion` |
| `RecordBerthPreference` | `BerthPreferenceRequest` | `BerthPreferenceRecorded` | `segmentBookingId + travelerRef + preferenceVersion` |
| `AllocateSeat` | `SeatAllocation` | `SeatAllocated` / `SeatAllocationFailed` | `segmentBookingId + travelerRef + capacityHoldId + allocationPurpose` |
| `AssignStanding` | `SeatAllocation` | `StandingAssigned` | `segmentBookingId + travelerRef + capacityHoldId + standingPolicyVersion` |
| `SolveAdjacentAllocation` | `AdjacencyGroup` | `AdjacentAllocationSolved` / `SeatAllocationFailed` | `adjacencyGroupId + seatMapVersion + capacityHoldSetHash + solveAttemptNo` |
| `ApplyBerthPreference` | `BerthPreferenceRequest`, `SeatAllocation` | `BerthPreferenceApplied` / `SeatAllocationFailed` | `berthPreferenceRequestId + allocationAttemptId` |
| `ConfirmSeatAllocation` | `SeatAllocation` | `SeatAllocationConfirmed` | `seatAllocationId + entitlementId + issueEventId` |
| `ReleaseSeatAllocation` | `SeatAllocation` | `SeatAllocationReleased` | `seatAllocationId + releaseReason + sourceEventId` |
| `ExpireSeatAllocation` | `SeatAllocation` | `SeatAllocationExpired` | `seatAllocationId + capacityHoldExpiredEventId` |
| `MarkSeatAllocationMissed` | `SeatAllocation` | `SeatAllocationMissed` | `seatAllocationId + missedReason + sourceEventId` |
| `ReassignSeat` | `SeatAllocation` | `SeatReassigned` | `seatAllocationId + oldSeatUnitRef + newSeatUnitRef + reassignCaseId` |
| `DetectSeatAllocationDiscrepancy` | `SeatAllocationLedger` | `SeatAllocationDiscrepancyDetected` | `seatMapVersion + detectionRunId` |

语义：`SeatAllocated` 不表示已支付或出票；`StandingAssigned` 不创建伪 SeatUnit；`SeatAllocationReleased` 不替代 `CapacityReleased`；`SeatAllocationFailed` 原因至少覆盖 `NO_COMPATIBLE_SEAT`、`OVERLAPPING_ALLOCATION`、`CAPACITY_REFERENCE_MISSING`、`PREFERENCE_UNSATISFIABLE`、`SEAT_MAP_VERSION_STALE`。

幂等材料折叠 Notes：命令先规范化材料（排序 travelerRefs、规范 interval、SeatUnitRef、枚举和 sourceEventId），仓库以 `idempotencyKey + materialHash` 裁决；同 key 同 material 返回原结果，同 key 不同 material 产生冲突失败事件。`allocationId`、`seatMapId`、`adjacencyGroupId`、`ledgerEventId` 由仓库生成 UUID-v7。

## 8. 策略和 Saga 参与点

- 出票时刻分配：搜索、报价和普通 Hold 阶段不承诺具体座位；Booking Orchestration 在容量 Hold/确认条件满足、调用 `IssueEntitlement` 前请求分配。成功后座位号进入出票展示输入；失败且不接受 standing 时由 Saga 重试、降级或补偿。
- Saga 输入：`CapacityHeld` 校验容量引用并分配；`EntitlementIssued` 确认分配；`EntitlementIssueFailed`、`SegmentReservationFailed`、`SegmentBookingCancelled` 释放未确认分配；`EntitlementVoided` 释放已确认分配。
- 连座策略：硬约束为容量引用有效、SeatMapVersion 可用、席别匹配、站段不重叠；软偏好按同车厢、同排相邻、靠窗/过道、上下铺组合降级。每次降级记录 `degradationReason`。
- 卧铺策略：按 `LOWER`/`MIDDLE`/`UPPER`、同隔间、相邻铺、照护关系过滤。偏好不是保证；无匹配时按策略降级并保留解释。
- Standing 策略：容量允许但无 SeatUnit 或业务明确销售无座时创建 `StandingAllocation`；不占座、不参与连座、不展示为“座位待定”。
- 改签/退票：新段先分配新座，旧 Entitlement 作废后释放旧座；若新票失败，旧 confirmed 分配不得自动释放。Capacity 释放仍由 `ReleaseHold`/`CapacityReleased` 链路完成。
- Service Plan 变化：`ServicePlanPublished` 和 `ServicePlanChanged` 触发 SeatMap 构建/重建；影响已分配座位时只发布差异或重分配事实，不直接取消订单或票证。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `SeatMapView` | `SeatMapBuilt`, `SeatMapVersionPublished`, `SeatUnitUnavailableMarked`, `SeatUnitReopened` | 运营、客服、分配策略、未来选座 UI。 |
| `SeatOccupancyIntervalView` | `SeatAllocated`, `StandingAssigned`, `SeatAllocationConfirmed`, `SeatAllocationReleased`, `SeatAllocationExpired`, `SeatReassigned` | 冲突检查、排障、上座区间分析。 |
| `SeatAssignmentBySegmentBookingView` | SeatAllocation lifecycle events + `EntitlementIssued` / `EntitlementVoided` refs | Entitlement 展示、Booking Saga、客服。 |
| `AdjacencyGroupResultView` | `AdjacencyGroupCreated`, `AdjacentAllocationSolved`, `AdjacencyDegradationAccepted`, `SeatAllocationFailed` | 同行订单、客服解释、通知变量。 |
| `BerthPreferenceOutcomeView` | `BerthPreferenceRecorded`, `BerthPreferenceApplied`, `SeatAllocationFailed` | 卧铺偏好展示、客服、运营统计。 |
| `StandingAllocationView` | `StandingAssigned`, `SeatAllocationConfirmed`, `SeatAllocationReleased` | 凭证展示、通知、无座占比。 |
| `SeatAllocationTimeline` | all Seat Assignment events plus consumed event refs | Customer Service、Admin & Audit、故障回放。 |
| `SeatAllocationDiscrepancyView` | ledger and discrepancy events | 运营排障、Reporting、人工纠错。 |

读模型不可反向推进聚合；客服换座或释放必须发命令；Reporting 只消费脱敏维度。

## 10. 外部系统和防腐层

Seat Assignment 第一阶段无真实外部依赖。所有外部方一律模拟，遵守 ADR-0003 “SIMULATED behind ACLs, no real integrations” 裁定，并对照 Provider Integration：外部语言不进入核心聚合，raw 模拟输入只保留在 ACL 审计日志，核心模型只接收平台语言。

### Seat Composition SIM Gateway

| Capability | Deterministic Behavior | Protection Rule |
|---|---|---|
| 导入编组座位图 | 给定 `scheduledServiceRef + serviceDate + compositionSeed`，生成稳定车厢序列、SeatUnit、席别和铺位。 | 无真实网络、无真实铁路接口。 |
| 模拟编组变更 | 同 seed 加 `changeScenario` 确定性产生加挂、减挂、车厢替换、座位禁用。 | 先映射为 SeatMap draft，再由聚合校验发布。 |
| 模拟不可分配座位 | 基于 seed 选择固定 SeatUnit 标为维护、保留或故障。 | 必须走 `MarkSeatUnitUnavailable` 和审计。 |
| 模拟外部席位号 | 产生可重复 externalSeatCode，并经 ACL 映射到 SeatUnitRef。 | 外部编码不可直接成为核心 ID。 |

SIM 必须可种子化；相同 seed、输入版本和 mapping version 产生相同 SeatMap；错误场景也由 seed 决定。禁止真实凭证、真实供应商账号、真实网络和不可复现随机熵。

ACL：Service Plan ACL 转为 `VehicleCompositionSeed`、`StopSequenceSnapshot`；Capacity ACL 转为 `CapacityReservationRef`，不复制库存数量逻辑；Entitlement ACL 将 `EntitlementIssued`/`EntitlementVoided`/`EntitlementIssueFailed` 转为确认或释放触发；Notification ACL 只提供变量，发送和回执由 Notification 处理。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-seat-service` | 从“余票 + 随机座位 + 订单反推”拆分：余票/Hold 归 Capacity；座位图、分配和释放归 Seat Assignment。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 订票成功不能随机写座位；Booking 在出票前请求分配，再进入 `IssueEntitlement`。 |
| `ts-order-service`, `ts-order-other-service` | 订单只保存 seatAllocationId/entitlementId 投影，不保存座位权威状态。 |
| `ts-cancel-service` | 取消/退票由 Entitlement/Post Sales 事实驱动释放座位；退款和容量释放归原上下文。 |
| `ts-rebook-service` | 新段先分配新座；旧 Entitlement 作废后释放旧座。 |
| `ts-travel-service`, `ts-travel2-service` | 编组和站序输入通过 Service Plan/SIM ACL，不由查询服务拼装座位图。 |
| `ts-admin-travel-service`, `ts-admin-basic-info-service` | 编组、座位禁用、车厢调整必须走 SeatMap 版本和审批命令。 |
| `ts-admin-order-service` | 人工换座、释放、纠错必须调用命令并写 Ledger。 |
| `ts-notification-service` | 消费座位分配/变更事实或投影，发送座位号、无座、降级通知。 |
| `ts-execute-service` | 检票/进站/使用事实不释放座位。 |

迁移切片：先旁路投影旧数据并检测差异；接入 SIM 编组生成 SeatMapVersion；新订单在 Capacity Hold 后接入 `AllocateSeat`；接入 Entitlement 和 Capacity 释放闭环；开启连座/卧铺偏好；最后移除旧写路径。

## 12. 验收标准

- 聚合所有权明确：`SeatMap`、`SeatAllocation`、`AdjacencyGroup`、`BerthPreferenceRequest`、`SeatAllocationLedger` 归本域。
- 严格区分 Capacity 管“有多少张”、本域管“具体哪个座/铺”；分配必须绑定段级 Hold/occupancy。
- SeatMap 版本化且历史可解释；发布后不原地覆盖。
- 同一 SeatUnit 在不重叠站段可复用，重叠站段不可重复分配。
- `StandingAllocation` 表达无座/超员语义，不伪造 SeatUnit。
- 连座和卧铺偏好有降级解释且不越过容量、席别、区间硬约束。
- 状态机列明终态可查询安息；`Failed`、`Missed`、`Cancelled` 不可逆。
- 命令含幂等键，材料折叠和 UUID-v7 仓库裁决已写明。
- 上下游表只引用既有契约；新命令/事件只在第 7 节。
- 外部方一律 SIM：可种子化、确定性、无真实网络、无真实凭证。
- 只新增设计文档，不新增契约文档、代码或服务目录；`make check` 应通过。
