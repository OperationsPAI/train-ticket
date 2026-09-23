# Capacity & Availability Domain Design

更新日期：2026 年 9 月 24 日。
实现位置：[services/capacity-availability](../../services/capacity-availability/README.md)，使用 Rust、PostgreSQL 和 Redis Streams。

## 领域职责与当前状态

Capacity 管理库存池、临时 Hold、确认占用、释放和可用性查询。
订单、支付、票证、具体座位分配和候补队列分别由其他领域管理。
可用性查询提供查询时刻的供给证据，成功创建 Hold 才表示容量预留成立。

当前联合 supply 计算读取 Booking 接受的预订信息、Service Plan 的日期与区间，以及 Seat Assignment 的已发布编组和有效分配。
这部分实现仍是工作区中未提交、未部署的改动。
Booking coordinated 行为尚未启用，不能把联合计算描述为生产预订已经采用的规则。
Seat Assignment 的 standing 10% 政策及预订前 SeatMap 准备已发布，其状态与 Capacity 联合计算分别记录。
剩余工作统一见 [docs/TODO.md](../TODO.md)。

## 领域对象与所有权

| 对象 | 当前含义与约束 |
|---|---|
| `InventoryPool` | 保存服务、日期、席别、容量类型、站序版本、容量引用和 Hold 集合。 |
| `CapacityHold` | 保存 `holdId`、区间、容量引用、旅客、预订、订单、幂等键、状态和到期时间。 |
| Confirmed occupancy | 由 `Confirmed` Hold 表达，普通到期处理不能释放。 |
| Physical supply | 来自 Seat Assignment 已发布 SeatMap 中可分配的座位或铺位。 |
| Standing quota | 来自独立的站票政策，不增加具体座位或铺位数量。 |
| `AvailabilitySnapshot` | 查询结果及版本、时间和数量信息，不是交易承诺。 |
| `OverbookingPolicy` | 领域层保留可配置类型；联合供给路径将具体座位和铺位的超售设为零。 |

`QuotaAllocation`、独立库存审计账本、渠道配额管理和人工调拨没有对应的完整生产 HTTP 工作流。
这些概念不能作为当前已经提供的接口能力。

## 供给来源与预订身份

| 来源 | 当前读取事实 |
|---|---|
| Booking Orchestration | `segmentBookingId`、`journeyOrderId`、`travelerRef`、接受的 `segmentRef`、`classRef`、`scheduledServiceRef` 和 `serviceDate`。 |
| Service Plan | 内部 segment supply 接口返回有序站点、服务日期、区间、到发时间和 `stopSequenceVersion`。 |
| Seat Assignment | 内部 capacity facts 接口返回已发布 SeatMap、可分配单元、standing 政策及有效分配。 |
| Journey Order | 初次购买的历史来源通过 `purchase-source` 读取创建事实中的 Offer；改签通过 change case 的目标 Offer 解析。 |

联合路径要求一次旅客预订的 `quantity=1`，请求的 segment、旅客和席别必须与接受的预订一致。
`journeyOrderId` 与 `segmentBookingId` 分别保存，不能互相代替。
同一预订通过不同 HTTP 幂等键或事件重复到达时，在预订锁内读取同一个 Hold。
已经释放的 Hold 保留身份，重放不会为该预订重新取得容量。

## 区间和联合准入

区间使用半开区间 `[fromSeq,toSeq)`。
重叠条件为 `existingFromSeq < requestToSeq && requestFromSeq < existingToSeq`。
相邻区间允许复用，同一具体单元的重叠有效占用必须拒绝。

Service Plan 当前创建的 scheduled service 只有起点和终点两个 stop。
内部 supply 接口明确返回从零开始的 `[0,1)`，并根据服务引用和有序站点生成摘要。
Capacity 的联合路径校验预订日期和历史 Hold 区间，创建 Hold 时保存该站序摘要。
领域层的任意区间算法不代表多站车次创建和沿途区间销售已经接通。

联合准入在 `scheduledServiceRef + serviceDate + classRef` 范围取得 PostgreSQL 事务 advisory lock，并保留库存池事务锁。
它读取相关库存池中的有效 Hold，以及 Seat Assignment 当前有效分配，按 `capacityHoldId` 去重。
相同 Hold 的订单、预订、旅客和区间必须一致。
具体座位的可用数量为已发布容量扣除重叠分配占用的不同单元数量，再扣除尚未分配座位的重叠 Hold 数量，最低为零。
没有余量返回容量不足业务结果；上游不可用、引用缺失或事实冲突返回错误。

Capacity 选择的容量引用与 Seat Assignment 最终选择的具体座位仍是两个操作。
当前数量检查不构成两个服务对同一 SeatUnit 的一致分配保证。
联合可用性查询与库存池事件快照也不能视为已完成统一的跨池占用投影。
接受的购买席别与 standing substitution 实际占用类别尚未形成原子转换，不能将站票配额算法视为转换流程已经完整。

## Standing 政策

已发布政策为 [`standing-second-class-10pct-v1`](../08-contracts/standing-capacity-policy.md)。
每个服务和日期使用已发布二等座编组中的可分配 `SECOND_CLASS` 单元作为基数，站票限额为 `floor(basisUnits / 10)`。
已经售出的二等座仍计入基数，商务座、一等座、铺位和不可分配单元不计入。
缺少适用编组时不提供站票；基数小于十时限额为零。

直接购买 `STANDING` 和旅客接受的 standing substitution 使用同一站票限额。
站票没有 `SeatUnit`、车厢号或座位号。
Capacity 的 `quota:<policyVersion>:<index>` 只是准入名额引用。
联合路径按区间内最大同时站票占用计算余量，将分配与待分配 Hold 按 Hold 身份合并。
standing snapshot 的 `physicalCapacity=0`，`totalCapacity` 来自政策限额。

## Hold 生命周期与消息

| 状态变化 | 当前规则 |
|---|---|
| `Requested` → `Held` | 校验准入、区间和幂等信息后建立临时占用。 |
| `Requested` → `Failed` | 领域拒绝请求，不产生有效占用。 |
| `Held` → `Confirmed` | 接受预订确认，保留原 Hold 身份。 |
| `Held` → `Released` | 取消或释放命令收回临时占用。 |
| `Held` → `Expired` | 到期处理结束临时占用。 |
| `Confirmed` → `Released` | 接受取消或售后释放事实。 |

领域状态枚举为 `Requested`、`Held`、`Confirmed`、`Released`、`Expired` 和 `Failed`。
当前没有独立的 `Replacing` 状态或公开的延长 Hold 接口。
HTTP 创建路径使用五分钟 Hold 到期时间。
当前确认方法检查 Hold 状态，未独立检查到期时间；状态仍为 `Held` 的晚到确认不能被描述为已有完整到期隔离。
确认或释放先于创建事件到达时，存储层保存 pending booking facts，并在创建时应用。

生产存储适配器处理 `SegmentReservationRequested`、`SegmentReservationConfirmed`、`SegmentBookingCancelled`、`SegmentTicketed`、`PostSalesApplied` 和 `EntitlementVoided`。
发布事实包括 `CapacityHeld`、`CapacityHoldConfirmed`、`CapacityReleased`、`CapacityHoldExpired`、`CapacityHoldFailed` 和 `CapacitySnapshotUpdated`。
领域层还包含 `WaitlistActivated`、`WaitlistCapacityFreed` 和 `OverbookingThresholdReached`。
这些事件类型的存在不代表候补自动兑现或超售运营入口已经完成。
当前普通 `CapacityReleased` 没有 Waitlist 解析所需的 `departureDate`，候补消费者会跳过缺少日期的释放事实。

## HTTP 与存储

| 接口 | 用途 |
|---|---|
| `GET /api/v1/availability-snapshots` | 查询指定 segment 的可用性。 |
| `POST /api/v1/capacity-holds` | 创建或重放 Hold。 |
| `GET /api/v1/capacity-holds/{holdId}` | 读取 Hold、引用、区间和状态。 |
| `POST /api/v1/capacity-holds/{holdId}/confirm` | 确认 Hold。 |
| `POST /api/v1/capacity-holds/{holdId}/release` | 释放 Hold。 |

生产 router 也接受 `POST /api/v1/capacity/holds`。
接口字段见 [Capacity HTTP API](../08-contracts/api/capacity-availability.md)。
库存池、Hold、幂等响应和 outbox 使用 PostgreSQL 持久化。

未提交的 `009_reservation_supply.sql` 增加 `reservation_supply_facts` 和 `capacity_supply_observations`。
前者保存不可变的接受来源，后者保存准入使用的 SeatMap 和容量证据。
`InventoryPool.physical_units` 区分当前可分配供给与历史容量引用。
历史预订来源在库存事务之前解析，每个服务实例最多并发处理八个上游预订查询。
每份验证通过的来源独立提交，因此容量不足、重复查询和服务重建仍可复用来源事实。
联合查询会保存来源与供给观察记录，不能被描述为完全不写数据库的查询。

## 证据范围

已有测试分别覆盖 Hold 生命周期、释放顺序、事务并发、记录供给与 standing 准入。
已有记录中的 PostgreSQL 回放和在线 Seat Assignment 购买结果，只支持各自执行范围内的结论。
当前文档核对没有重新执行测试，也没有验证新的生产部署。
联合供给的来源、历史样本和验证边界见 [capacity-overbooking.md](../10-domain-enrichment/capacity-overbooking.md)。
