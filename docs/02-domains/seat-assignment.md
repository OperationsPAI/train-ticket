# Seat Assignment Domain Design

更新日期：2026 年 9 月 24 日。
实现位置：[services/seat-assignment](../../services/seat-assignment/README.md)，使用 Go、PostgreSQL 和 Redis Streams。

## 领域职责

Seat Assignment 管理已发布编组中的具体座位、铺位和站票分配事实。
Capacity 管理 Hold 和容量准入，Service Plan 管理班次、日期与站序，Entitlement 管理票证。
Seat Assignment 保存这些领域的引用，为出票提供 `seatRef`，并处理确认、释放和过期消息。
剩余工作统一见 [docs/TODO.md](../TODO.md)。

当前服务包含 `ContractSeatMap`、`ContractSeatAllocation` 和按 segment/date 保存的 `SeatInventory`、`SeatAssignment` 两组模型。
内部出票分配接口使用前一组模型及其已发布编组。
`/seat-assignments` 使用后一组模型，不能将该入口的评分算法、分组请求和本地 Hold 语义直接套用于内部出票接口。

## SeatMap 与分配身份

| 对象 | 当前设计 |
|---|---|
| `ContractSeatMap` | 绑定 `scheduledServiceRef`、`serviceDate`、`compositionVersion`、`compositionSeed`、`mappingVersion` 和版本。 |
| `Coach` / `SeatUnit` | 表达席别、车厢、座位或铺位、位置、相邻分组及可分配状态。 |
| `ContractSeatAllocation` | 绑定订单、预订、旅客、segment、服务、日期、Capacity Hold、容量引用和半开区间。 |
| `SeatRef` | 保存分配类型、SeatMap 引用、具体座位展示或 `STANDING` 展示。 |
| `SeatPreferences` | 保存接受 standing、相邻分组、位置等请求信息和版本。 |

编组由确定性的 SIM seed 生成，支持 `CRH380A` 座席和 `SLEEPER` 铺位等输入。
它不是实际铁路车辆接口提供的编组。
SeatMap 标识和单元标识由编组材料确定，同材料重放保留身份。
已发布 SeatMap 的拓扑不可原地修改；改变供给需要发布新的编组。
旧分配保留其原 SeatMap 和单元引用。

内部新分配要求已有适用的 `PUBLISHED` SeatMap。
选择最多读取一百份 published maps，数量超过该限制会返回冲突，避免在不完整集合上选择。
同一服务和日期的分配在 PostgreSQL 事务 advisory lock 内读取有效占用并保存结果。
具体座位的冲突判定使用 `[fromSeq,toSeq)`，相邻区间允许复用。

## Standing 政策

[Standing capacity policy](../08-contracts/standing-capacity-policy.md) 中的 `standing-second-class-10pct-v1` 政策与 Seat Assignment 准入已发布。
限额为已发布编组中可分配二等座数量的 10%，向下取整。
已售二等座仍计入基数，商务座、一等座和铺位不计入。
缺少二等座编组时拒绝站票分配。

直接购买站票和接受 standing substitution 均占用同一限额。
分配记录包含政策版本、基数、比例、限额和原编组引用。
站票的 `allocationType` 与 `displayLabel` 均为 `STANDING`，没有 `seatUnitRef`、`coachNo` 或 `seatNo`。
站票是成功分配结果，不能显示为座位待定。

准入检查统计区间内最大同时 standing 占用，并包括缺少政策元数据的历史有效站票。
释放或过期后可重新使用名额。
编组限额减少到已有占用以下时拒绝新增分配，已有票证保持其记录。
当前 Seat Assignment 检查自身有效分配；合并尚未分配座位的 Capacity Hold 属于提交 `caafa668` 中的 Capacity 联合 supply 实现，该实现尚未部署。
Booking coordinated 行为尚未启用。

## 内部分配与重放

`POST /api/v1/internal/seat-allocations` 接受一个旅客的预订、订单、服务、日期、席别、区间、Hold、容量引用、用途、到期时间及 preferences。
用途支持 `INITIAL`、`REPLACEMENT`、`MANUAL_RECOVERY`、`PROVIDER_REBUILD` 和 `DISRUPTION_REPLACEMENT`。
同一有效 `segmentBookingId` 重放时，引用、区间、原请求席别、用途、preferences 和到期时间必须一致。
接受 standing substitution 的结果保留 `requestedClassRef` 与 `issuePurpose`，重放不会重复占用站票名额。

内部选择器按可分配单元过滤占用和 `avoidSeatUnitRefs`，并尝试满足已有同组分配的车厢、排或相邻分组要求。
无法满足相邻要求时保留 `NO_ADJACENT_BLOCK` 解释。
它按单个旅客逐次分配，没有一次原子分配整个同行组的保证。
位置和铺位 preferences 会被校验并记录，但当前内部选择器没有使用全部位置字段进行评分。
`AdjacencyGroupCreated` 等消息由该次分配生成，不代表独立持久化的完整分组求解工作流。

请求中的 `capacityUnitRef` 是关联信息，具体 `seatRef.seatUnitRef` 由本服务选择。
当前入口检查引用字段和本地重放一致性，没有同步向 Capacity 验证 Hold 的当前状态或建立跨服务原子分配。
该入口要求 `expiresAt` 非空，但没有以当前时间检查其是否已经过期。
数量准入与最终座位身份的一致保证不能从字段存在推导。

## 生命周期与消息

| 输入或操作 | 当前结果 |
|---|---|
| 建立具体座位或铺位分配 | `ALLOCATED`，发布 `SeatAllocated`。 |
| 建立站票分配 | `STANDING`，发布 `StandingAssigned`。 |
| `EntitlementIssued` / `TicketIssued` | 确认匹配分配，发布确认事实。 |
| `EntitlementVoided` / `EntitlementIssueFailed` / `PostSalesApplied` | 处理匹配分配的释放。 |
| `CapacityReleased` | 按 Hold 身份释放匹配分配。 |
| `CapacityHoldExpired` | 将可过期分配转为 `EXPIRED`，已确认分配不由普通到期操作释放。 |

内部 allocation 状态为 `ALLOCATED`、`STANDING`、`CONFIRMED`、`RELEASED` 和 `EXPIRED`。
失败请求通过 HTTP 错误返回，不能假定每次失败都创建 `FAILED` 或 `MISSED` 分配记录。
释放座位与释放 Capacity Hold 分别记录，座位事件不代替库存释放事实。
当前订阅流为 Booking、Entitlement、Post Sales 和 Capacity；Service Plan 发布本身没有自动构建 SeatMap 的订阅处理。

## HTTP 与持久化

| 接口集合 | 用途 |
|---|---|
| `/api/v1/seat-maps` | 创建、分页查询和读取 SeatMap。 |
| `/api/v1/seat-maps/{seatMapId}/publish`、`/retire` | 带预期版本的发布和停用。 |
| SeatMap 下的 `/seat-units/{seatUnitRef}/mark-unavailable`、`/reopen` | 修改未发布编组中的单元可分配状态。 |
| `/api/v1/internal/seat-allocations` | 内部出票前分配。 |
| `/api/v1/seat-allocations` | 按预订查询或按分配标识读取结果。 |
| `/api/v1/internal/capacity-facts` | 读取服务、日期和席别对应的已发布供给及有效占用。 |
| `/api/v1/seat-assignments` | segment/date 座位请求、可用性、确认和释放。 |

`capacity-facts` 对 `STANDING` 返回空 `units`、政策及有效站票，对具体席别返回可分配单元和相应历史分配。
SeatMap、allocation、inventory、幂等记录、处理过的事件和 outbox 使用 PostgreSQL 保存。
接口字段见 [Seat Assignment HTTP API](../08-contracts/api/seat-assignment.md)。
源码与已有验证范围见 [seat-assignment-impl.md](../10-domain-enrichment/seat-assignment-impl.md)。
