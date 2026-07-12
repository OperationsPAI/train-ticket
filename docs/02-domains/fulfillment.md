# Fulfillment Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Fulfillment |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-fulfillment |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/domain-glossary.md`, `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/general-travel-ddd.md`, `docs/02-domains/entitlement-ticketing.md`, `docs/02-domains/place-network.md` |

## 1. 领域目标

Fulfillment 负责记录出行服务被实际使用、核验、开始、进行和完成的履约事实。它回答的问题不是“用户买了什么”，也不是“用户是否持有票证”，而是“某个 Traveler 在某个 Segment 上发生了哪些真实履约事件”。

本上下文独立存在的原因：

1. `Entitlement` 代表可核验权益和 Credential 生命周期；Fulfillment 代表 CheckIn、Boarding、Boarded、NoShow、InTravel、Arrival、Completed 等实际事实。
2. `Journey Order` 代表用户商业订单和售后入口；Fulfillment 只发布履约事实供订单汇总，不拥有订单商业状态。
3. 固定班次和按需出行的履约节奏不同：火车检票、航空值机/登机、轮船登船、网约车接驾/上车/下车都需要统一 UsageEvent 语言，但内部规则不能被订单状态字段吞并。
4. 多 Segment、部分使用、离线核验、重复核验、状态回补和供应商晚到事件都需要可审计的事实流，不能依赖前端展示或供应商原始状态码。
5. Transfer Management、Post Sales、Disruption Recovery 和 Finance Settlement 都需要稳定的实际使用证据。

第一阶段 Train Ticket 范围聚焦：取票或进站 CheckIn、检票 Boarding、已上车 Boarded、到站 Arrival、Segment 完成、NoShow 识别、离线闸机核验回传、重复检票幂等和旧 `COLLECTED/USED` 状态迁移。

未来 General Travel 范围扩展到：航空值机/安检/登机/到达，轮船港口安检/登船/靠港/下船，大巴检票/上车/到站，网约车司机接驾/乘客上车/InTravel/下车/行程完成，以及主运输相关的核验记录。

## 2. 边界

### In Scope

- FulfillmentRecord、UsageSession、VerificationAttempt、UsageEvent 等履约聚合和事实流。
- CheckIn、Boarding、Boarded、NoShow、InTravel、Arrival、Completed 等本域状态和状态转换。
- 闸机、检票口、登机口、登船口、司机端、车载设备、供应商回调和人工后台产生的核验记录。
- 固定班次与按需出行的 Segment 使用事实：火车/飞机/大巴/轮船/网约车均以 Segment 为履约单位。
- 多 Segment Journey 的分段履约、部分使用、多人同行中单人未使用、分段 NoShow 和分段完成。
- 离线核验包发放后的回传、冲突合并、重复核验幂等、晚到状态回补和人工纠错审计。
- 履约事实对 Post Sales、Transfer Management、Disruption Recovery、Journey Order、Finance Settlement 和 Reporting 的事件发布。
- Fulfillment Timeline、Gate Verification View、Ride Execution View、NoShow Candidate View 等读模型。

### Out of Scope

- 不签发、作废、冻结或重建票证；这些属于 Entitlement & Ticketing。
- 不管理 Journey Order 的商业状态、金额、售后入口或整单确认条件。
- 不决定异常恢复方案、保护性改乘、赔偿或免费退改；这些属于 Disruption Recovery 与 Post Sales。
- 不拥有 Place、Station、Gate、Platform、GeoFence 等地点主数据；这些属于 Place & Network。
- 不拥有 Service Plan 的计划时刻、运行日历、停运配置和 Gate/Platform 计划分配。
- 不拥有 Capacity、座席、舱位、司机供给或库存释放规则。
- 不计算票价、手续费、取消费、等待费或退款金额。
- 不把供应商原始状态码直接暴露给核心域；供应商语言必须经 Provider Integration ACL 映射。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| FulfillmentRecord | 某个 Traveler 在某个 Segment 上的履约事实聚合。 | 本域核心聚合根，通常一人一段一条。 |
| UsageEvent | 一次不可变的履约事实，如 CheckInSucceeded、BoardingVerified、ArrivalRecorded。 | 事件可来自设备、供应商或人工。 |
| VerificationAttempt | 一次核验尝试，不论成功、失败或离线待确认。 | 重复扫码、无效 Credential、离线核验都记录。 |
| CheckIn | 值机、取票、签到或进入履约前资格确认。 | 不等于已登乘。 |
| Boarding | 正在进行登乘核验的阶段。 | 可短暂存在于闸机或司机端交互。 |
| Boarded | 已通过检票、登机、登船或上车。 | 表示用户进入运输服务。 |
| InTravel | Segment 正在履约中。 | 对网约车很关键；固定班次可由发车或 Boarded 推导。 |
| Arrival | 到达目的节点、靠港、降落、到站或车辆到达下车点的事实。 | 不等于整单完成。 |
| Completed | 本 Segment 的履约闭环完成。 | 可触发收入确认和售后规则变化。 |
| NoShow | 到核验窗口结束仍未 Boarded 或未上车。 | 需要按 Segment 和 Traveler 判断。 |
| Offline Verification | 设备离线时依据本地核验包做出的临时核验事实。 | 回传后需要冲突合并。 |
| Replay/Backfill | 供应商或设备晚到事件对历史状态的回补。 | 必须保留原 occurredAt 和 receivedAt。 |
| Fulfillment Source | 产生履约事实的来源。 | Gate、Provider、DriverApp、ConductorApp、Admin、System。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Entitlement & Ticketing | `EntitlementIssued`、`EntitlementVoided`、`EntitlementSuspended`、`FulfillmentEligibilityChanged`、Credential 摘要 | 核验前必须知道凭证是否可用、是否冻结、是否可离线核验。 |
| Journey Order | JourneyOrderId、Segment 与 Traveler 引用、订单取消汇总事件 | 用于关联时间线和判断取消后晚到核验是否需要人工。 |
| Booking Orchestration | `SegmentBookingConfirmed`、`SegmentBookingCancelled`、SegmentBookingRef | FulfillmentRecord 绑定确认后的分段预订。 |
| Service Plan | `ServiceRunSnapshot`、计划出发/到达时间、boardingWindow | 固定班次的 CheckIn、Boarding、NoShow 窗口需要计划输入。 |
| Place & Network | `BoardingLocationSnapshot`、`GeoFencePublished`、PlaceGraphVersion | 核验地点、上车点、Gate、Platform、GeoFence 只引用地点快照。 |
| Provider Integration | `ProviderFulfillmentEvent`、`ProviderGateScanEvent`、`ProviderRideStatusEvent` | 外部闸机、航司、船司、网约车平台的履约事实输入。 |
| Dispatch / Ride Provider | driverArrived、pickupConfirmed、rideStarted、rideEnded | 按需出行没有固定检票口，依赖司机和车辆执行事件。 |
| Admin & Audit / Customer Service | `CorrectFulfillmentFact`、`RecordManualNoShow`、`ResolveVerificationConflict` | 人工纠错必须受控并可审计。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Entitlement & Ticketing | `CheckInSucceeded`、`BoardingVerified`、`SegmentCompleted` | 推进 Entitlement 到 CheckedIn、Boarded 或 Used。 |
| Journey Order | `SegmentFulfillmentStarted`、`SegmentBoarded`、`SegmentArrived`、`SegmentCompleted`、`SegmentNoShowRecorded` | 订单时间线和 InTravel/Completed 汇总需要履约事实。 |
| Transfer Management | `SegmentArrived`、`SegmentDelayedInFulfillment`、`SegmentNoShowRecorded` | 中转风险和错过接续依赖实际到达或未登乘。 |
| Post Sales | `CheckInSucceeded`、`SegmentBoarded`、`SegmentCompleted`、`NoShowRecorded` | 售后可退改性、误乘处理、No-show 规则需要使用事实。 |
| Disruption Recovery | `BoardingBlockedByProvider`、`SegmentDelayedInFulfillment`、`ArrivalBackfilled` | 异常恢复需要知道实际履约和晚到事实。 |
| Finance Settlement | `SegmentCompleted`、`NoShowRecorded`、`UsageEvidenceRecorded` | 收入确认、供应商结算和争议证据依赖履约证明。 |
| Notification | `CheckInWindowOpened`、`BoardingStarted`、`DriverArrived`、`NoShowRiskDetected`、`SegmentCompleted` | 发送出行提醒和履约状态通知。 |
| Reporting | Fulfillment read model events | 统计检票率、NoShow 率、准点率、完成率和设备质量。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| FulfillmentRecord | 必须绑定 SegmentBookingId、TravelerRef、EntitlementId 或可核验替代引用；同一 Traveler 同一 Segment 只能有一个当前履约记录；Completed/NoShow 之后不能普通 Boarded；所有状态推进必须保留 sourceEventId、occurredAt、receivedAt。 | `OpenFulfillmentRecord`、`RecordCheckIn`、`RecordBoardingAttempt`、`ConfirmBoarded`、`StartInTravel`、`RecordArrival`、`CompleteSegment`、`RecordNoShow`、`BackfillFulfillmentState`、`CorrectFulfillmentFact` | `FulfillmentRecordOpened`、`CheckInSucceeded`、`BoardingAttemptRecorded`、`BoardingVerified`、`SegmentInTravelStarted`、`SegmentArrived`、`SegmentCompleted`、`NoShowRecorded`、`FulfillmentStateBackfilled`、`FulfillmentFactCorrected` |
| VerificationSession | 一次核验窗口内，同一 Credential/Traveler/Segment 的重复尝试必须幂等合并；失败尝试不得推进履约状态；离线成功必须标记 pending reconciliation。 | `StartVerificationSession`、`VerifyCredential`、`RejectCredential`、`AcceptOfflineVerification`、`ReconcileOfflineVerification`、`CloseVerificationSession` | `VerificationSessionStarted`、`CredentialVerificationSucceeded`、`CredentialVerificationRejected`、`OfflineVerificationAccepted`、`OfflineVerificationReconciled`、`VerificationSessionClosed` |
| UsageTimeline | UsageEvent 只能追加；同一 sourceEventId 不得重复入账；回补事件按 occurredAt 排序展示但按 receivedAt 审计。 | `AppendUsageEvent`、`MergeDuplicateUsageEvent`、`AnnotateUsageEvent`、`SealUsageTimeline` | `UsageEventAppended`、`DuplicateUsageEventMerged`、`UsageEventAnnotated`、`UsageTimelineSealed` |
| NoShowAssessment | NoShow 判断必须基于 boardingWindow、Entitlement 状态、实际核验事件和交通方式策略；可先生成候选，再确认事实。 | `EvaluateNoShowCandidates`、`ConfirmNoShow`、`WithdrawNoShowCandidate` | `NoShowCandidateDetected`、`NoShowRecorded`、`NoShowCandidateWithdrawn` |

### 关键字段

| 字段 | 说明 |
|---|---|
| fulfillmentRecordId | 一人一段履约记录唯一标识。 |
| journeyOrderId | 所属 Journey Order 引用，只用于追踪和读模型。 |
| segmentBookingId | 分段预订引用，是履约记录的主要业务来源。 |
| entitlementId | 可核验 Entitlement 引用，网约车可引用 Ride credential。 |
| travelerRef | 旅客引用和脱敏快照。 |
| segmentRef | mode、serviceDate、from/to、plannedDeparture/Arrival。 |
| fulfillmentStatus | NotOpened、Ready、CheckedIn、Boarding、Boarded、InTravel、Arrived、Completed、NoShow、Cancelled。 |
| source | Gate、Provider、DriverApp、ConductorApp、Admin、System。 |
| locationSnapshot | PlaceId、nodeId、GeoFenceVersion、gate/platform 显示快照。 |
| auditTrail | commandId、sourceEventId、actor、reason、occurredAt、receivedAt。 |

## 6. 状态机

Fulfillment 只拥有履约事实状态机，不定义 Entitlement、Journey Order、Post Sales、Transfer 或 Disruption 的内部状态。

### Segment Fulfillment 状态

主路径：NotOpened -> Ready -> CheckedIn/Boarding -> Boarded -> InTravel -> Arrived -> Completed。
可选路径：Ready/CheckedIn -> NoShow；NotOpened/Ready -> Cancelled；Ready 可在直接核验成功时跳过 CheckedIn 进入 Boarded。
回补路径不覆盖历史，只追加 Backfill 或 Correction 事件来解释晚到的 Boarded、Arrival 或 Completed。

### 状态规则

| 状态 | 含义 | 关键规则 |
|---|---|---|
| NotOpened | 履约窗口尚未开放。 | 不接受普通 Boarding，只可接收预检或回补。 |
| Ready | 可 CheckIn 或可 Boarding。 | 需要 Entitlement 可核验或按需出行已匹配资源。 |
| CheckedIn | 已完成值机、取票、签到或预检。 | 仍可能 NoShow。 |
| Boarding | 正在核验登乘。 | 通常是短状态，可被 Boarded 或 Rejected 结束。 |
| Boarded | 已进入运输服务。 | 后续普通退改受限。 |
| InTravel | Segment 正在执行。 | 网约车必须显式记录，固定班次可由发车或 Boarded 推进。 |
| Arrived | 已到达目的节点或下车点。 | 可能还需完成行李、下船、出站等闭环。 |
| Completed | Segment 履约完成。 | 不可再记录 NoShow；纠错走 Backfill 或 Correction。 |
| NoShow | 未按窗口登乘或上车。 | 可被人工撤销或供应商晚到 Boarded 回补纠正。 |
| Cancelled | 履约前取消。 | 不表示退款完成。 |

禁止转换：Completed -> NoShow、NoShow -> Boarded 的普通命令、Cancelled -> Boarded 的普通命令、Boarded -> Ready、Arrived -> CheckIn。需要修正历史时必须使用 `BackfillFulfillmentState` 或 `CorrectFulfillmentFact`，保留原事实和纠错原因。

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `OpenFulfillmentRecord` | FulfillmentRecord | `FulfillmentRecordOpened` | segmentBookingId + travelerId |
| `OpenCheckInWindow` | FulfillmentRecord | `CheckInWindowOpened` | segmentId + serviceDate + windowType |
| `RecordCheckIn` | FulfillmentRecord, UsageTimeline | `CheckInSucceeded` | entitlementId + checkInSource + sourceEventId |
| `RecordBoardingAttempt` | VerificationSession | `BoardingAttemptRecorded` | credentialRef + deviceId + attemptTimeBucket |
| `VerifyCredential` | VerificationSession, FulfillmentRecord | `CredentialVerificationSucceeded`, `BoardingVerified` | entitlementId + sourceEventId |
| `RejectCredential` | VerificationSession | `CredentialVerificationRejected` | credentialRef + deviceId + rejectionSourceEventId |
| `AcceptOfflineVerification` | VerificationSession | `OfflineVerificationAccepted` | offlinePackageId + deviceId + localSequenceNo |
| `ReconcileOfflineVerification` | VerificationSession, FulfillmentRecord | `OfflineVerificationReconciled` | offlinePackageId + reconciliationBatchId |
| `ConfirmBoarded` | FulfillmentRecord | `SegmentBoarded` | fulfillmentRecordId + sourceEventId |
| `StartInTravel` | FulfillmentRecord | `SegmentInTravelStarted` | fulfillmentRecordId + sourceEventId |
| `RecordArrival` | FulfillmentRecord | `SegmentArrived` | fulfillmentRecordId + arrivalSourceEventId |
| `CompleteSegment` | FulfillmentRecord | `SegmentCompleted` | fulfillmentRecordId + completionSourceEventId |
| `EvaluateNoShowCandidates` | NoShowAssessment | `NoShowCandidateDetected` | segmentId + boardingWindowId + evaluationRunId |
| `ConfirmNoShow` | NoShowAssessment, FulfillmentRecord | `NoShowRecorded` | fulfillmentRecordId + noShowWindowId |
| `BackfillFulfillmentState` | FulfillmentRecord, UsageTimeline | `FulfillmentStateBackfilled` | fulfillmentRecordId + externalEventId + targetState |
| `CorrectFulfillmentFact` | FulfillmentRecord, UsageTimeline | `FulfillmentFactCorrected` | correctionCaseId + fulfillmentRecordId |

事件发布要求：所有履约事实事件必须包含 `occurredAt`、`receivedAt`、`source`、`sourceEventId`、`correlationId` 和地点快照；失败核验事件不携带未脱敏 CredentialCode；人工命令必须携带 caseId、operatorId、approvalRef 和 reason。

## 8. 策略和 Saga 参与点

### 出行前和检票策略

| 触发 | 本域策略 | 输出 |
|---|---|---|
| `EntitlementIssued` + Service Plan 窗口临近 | 创建或激活 FulfillmentRecord，准备可核验读模型。 | `FulfillmentRecordOpened`、`CheckInWindowOpened` |
| `EntitlementVoided/Suspended` | 从核验读模型移除或标记不可用；不删除历史记录。 | `FulfillmentEligibilityBlocked` |
| 设备扫码或人工核验 | 校验 Entitlement 可用、时间窗、地点快照、重复尝试和离线策略。 | `BoardingVerified` 或 `CredentialVerificationRejected` |
| 重复核验 | 合并为同一 VerificationSession，保留重复尝试证据，不重复推进状态。 | `DuplicateUsageEventMerged` |

### 固定班次履约策略

- 火车、大巴、轮船可从 CheckIn 直接进入 Boarded，也可先记录 Boarding 尝试。
- 航空值机 CheckIn 不代表登机；Boarded 必须来自登机口、航司或人工确认。
- Arrival 可来自供应商到达事件、运行计划实际到达、站内设备或人工确认；Completed 可在 Arrival 后按策略自动或人工闭环。
- NoShow 在 boardingWindow 结束后生成候选，若供应商晚到 Boarded 事件可信，则通过 Backfill 撤销或纠正 NoShow。

### 按需出行履约策略

| 事件 | Fulfillment 映射 | 说明 |
|---|---|---|
| driverAssigned | FulfillmentRecord Ready | 司机分配不是 Boarded。 |
| driverArrived | DriverArrived / CheckIn 可选 | 用户未上车仍可能 NoShow 或取消。 |
| pickupConfirmed | SegmentBoarded | 乘客上车事实。 |
| rideStarted | SegmentInTravelStarted | 可能与 pickupConfirmed 合并。 |
| rideEnded | SegmentArrived + SegmentCompleted | 下车和完成通常同源。 |

### 对 Post Sales 和 Disruption 的影响

- `CheckInSucceeded` 后的退改可能需要更高规则门槛，但本域不计算手续费。
- `SegmentBoarded` 后普通退票通常被禁止或进入争议补偿；规则由 Post Sales 决定。
- `NoShowRecorded` 会触发 No-show 售后路径、误乘处理或司机等待费争议，但金额由 Post Sales/Payment 决定。
- `SegmentDelayedInFulfillment`、`ArrivalBackfilled` 和 `SegmentArrived` 会影响 Transfer Management 的连接风险和 Disruption Recovery 的恢复时机。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| FulfillmentTimeline | 所有 UsageEvent、Correction、Backfill 事件 | 用户订单详情、客服、Admin & Audit |
| GateVerificationView | `EntitlementIssued`、`FulfillmentEligibilityChanged`、`CredentialVerificationSucceeded/Rejected` | 闸机、检票口、登机口、登船口 |
| SegmentOccupancyView | `SegmentBoarded`、`NoShowRecorded`、`SegmentCompleted` | 运营、Reporting、Capacity 审计 |
| TravelerUsageStatusView | `CheckInSucceeded`、`SegmentBoarded`、`SegmentArrived`、`SegmentCompleted` | Journey Order、Post Sales、Customer Service |
| TransferFulfillmentFeed | `SegmentArrived`、`SegmentDelayedInFulfillment`、`NoShowRecorded` | Transfer Management、Disruption Recovery |
| RideExecutionView | driverArrived、pickupConfirmed、rideStarted、rideEnded | 用户端、客服、网约车运营 |
| OfflineVerificationReconciliationView | `OfflineVerificationAccepted`、`OfflineVerificationReconciled`、冲突事件 | 设备运营、风控、客服 |
| NoShowCandidateView | `NoShowCandidateDetected`、`NoShowRecorded`、`NoShowCandidateWithdrawn` | Post Sales、客服、运营 |
| FulfillmentEvidenceFeed | `BoardingVerified`、`SegmentCompleted`、`FulfillmentFactCorrected` | Finance Settlement、争议处理、Reporting |

读模型规则：高频核验读模型允许缓存 Entitlement eligibility，但核验成功必须回写命令；FulfillmentTimeline 按 occurredAt 展示、按 receivedAt 审计；任何读模型都不能直接修改履约事实。

## 10. 外部系统和防腐层

Fulfillment 需要对设备、供应商和遗留执行服务做防腐，避免外部状态污染核心模型。

| 外部来源 | 原始语言 | 平台映射 | 注意事项 |
|---|---|---|---|
| Rail gate / station device | 进站、出站、验票结果、设备流水 | VerificationAttempt、BoardingVerified、ArrivalRecorded | 设备离线时必须保留本地序号和回传批次。 |
| Air provider / GDS / DCS | check-in、boarding、offload、arrival | CheckInSucceeded、BoardingVerified、SegmentArrived | PNR 和 boarding pass 状态不直接成为本域状态。 |
| Coach / Ferry provider | ticket scan、boarding、departure、arrival | BoardingVerified、SegmentInTravelStarted、SegmentArrived | 车辆票和人票可能需要绑定核验。 |
| Ride provider / driver app | driver arrived、pickup、start、dropoff、complete | DriverArrived、SegmentBoarded、InTravel、Completed | 司机取消属于 Disruption 或 Dispatch，不由本域决定恢复。 |
| Legacy execute service | collect、enter station、use ticket | CheckIn、Boarded、Completed 候选事件 | 迁移期必须保留旧状态原文和映射置信度。 |
| Manual admin | 人工补登、撤销 NoShow、修正到达 | Correction/Backfill events | 必须通过 Admin & Audit 审批。 |

防腐规则：

1. Provider Integration 负责协议、重试、签名、原始码解析和错误码映射；Fulfillment 只消费平台化事件。
2. 外部事件必须携带 sourceEventId；没有稳定 ID 时由 providerId、deviceId、eventTime、credentialRef、sequenceNo 生成幂等键。
3. 离线核验先进入 Pending Reconciliation，不立即覆盖可信在线事实。
4. 晚到事件不能删除原事实，只能追加 Backfill 或 Correction。
5. 地点字段只使用 PlaceId/nodeId/GeoFenceVersion 或地点快照，不保存供应商地点字符串作为事实依据。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-execute-service` | 取票、进站、用票逻辑迁移为 Fulfillment 命令和 UsageEvent；不再直接修改订单状态。 |
| `ts-order-service`, `ts-order-other-service` | `COLLECTED/USED` 不再作为订单主状态推进；订单消费 Fulfillment 事件生成时间线和汇总。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 预订成功后只触发 Entitlement 和 Fulfillment 准备，不表示已履约。 |
| `ts-ticket-office-service` | 若承担线下取票或检票设备接入，改为 Fulfillment Source 或 Provider Integration 适配器。 |
| `ts-cancel-service` | 退票前查询 TravelerUsageStatusView 判断是否 CheckIn/Boarded/Completed，不直接读取订单状态。 |
| `ts-rebook-service` | 改签前消费 Fulfillment 状态判断旧 Segment 是否已使用；已 Boarded/Completed 的改签走异常或人工。 |
| `ts-seat-service` | 不能通过订单 USED 推断上座；改为消费 SegmentBoarded、NoShowRecorded、SegmentCompleted 做占用审计。 |
| `ts-notification-service` | 消费 CheckInWindowOpened、BoardingStarted、SegmentCompleted、NoShowRiskDetected 发送提醒。 |
| `ts-admin-order-service` | 人工补检票、撤销 NoShow、状态回补必须进入 Fulfillment 命令并记录审计。 |
| 旧报表脚本 | 履约率、检票率、NoShow 率改由 Fulfillment read model 输出，保留旧字段兼容期。 |

迁移切片：

1. 先从旧 `COLLECTED/USED` 生成 FulfillmentTimeline 只读投影，不改变旧写路径。
2. 新核验入口先双写 UsageEvent 和旧状态，验证幂等与客服时间线。
3. 将退票、改签、客服查询改为读取 TravelerUsageStatusView。
4. 关闭订单服务对履约状态的直接写入，只保留兼容字段或投影。
5. 最后把离线核验、NoShow、Arrival、Completed 扩展到多 Segment 和多交通方式。

## 12. 验收标准

- 本 domain 的聚合所有权明确：FulfillmentRecord、VerificationSession、UsageTimeline、NoShowAssessment 均由 Fulfillment 拥有。
- 本 domain 明确不签发 Entitlement、不管理 Journey Order 商业状态、不决定 Disruption Recovery 方案、不拥有 Place & Network 主数据。
- 固定班次和按需出行均有履约映射：CheckIn、Boarding、Boarded、InTravel、Arrival、Completed、NoShow 语义一致。
- 多 Segment、部分使用、多人同行单人未使用、离线核验、重复核验、状态回补和人工纠错均有建模位置。
- 命令和事件均包含幂等键，事件使用过去式业务事实，并保留 occurredAt、receivedAt、source 和 sourceEventId。
- 读模型覆盖用户/客服时间线、闸机核验、网约车执行、NoShow 候选、离线核验 reconciliation、结算证据和报表。
- 与 Entitlement & Ticketing、Journey Order、Post Sales、Transfer Management、Disruption Recovery、Place & Network 的上下游契约清晰。
- 当前服务迁移影响覆盖 `ts-execute-service`、订单服务、取消改签、座席审计、通知、后台和报表。
