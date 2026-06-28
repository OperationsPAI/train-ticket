# Entitlement & Ticketing Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Entitlement & Ticketing |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-entitlement-ticketing |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/domain-glossary.md`, `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/aggregate-model.md`, `docs/01-ddd-high-level/state-machines.md`, `docs/01-ddd-high-level/event-storming.md`, `docs/01-ddd-high-level/consistency-and-saga.md`, `docs/01-ddd-high-level/order-inventory-payment-model.md`, `docs/01-ddd-high-level/acl-provider-contracts.md` |

## 1. 领域目标

Entitlement & Ticketing 负责表达“用户是否拥有可用于履约核验的出行权益”。它把商业订单、供应侧预订、支付状态和实际履约事实拆开，提供稳定的 Entitlement 生命周期、Credential 身份、票证展示数据和票务读模型。

本上下文独立存在的原因：

1. `JourneyOrder` 表示用户买了什么和商业状态，但不等于用户已经拿到可使用凭证。
2. `SegmentBooking` 表示供应侧确认，不等于凭证已经签发、可展示或可核验。
3. `PaymentCaptured` 不等于 `EntitlementIssued`；支付成功后出票仍可能失败、重试或进入人工。
4. `Fulfillment` 记录检票、登乘、到达、完成等实际事实；Entitlement 只维护凭证是否允许被核验和使用。
5. General Travel 需要统一火车票、纸质票、登机牌、船票、大巴电子票、网约车乘车码等多种 Credential，不应让每种交通方式污染订单模型。

第一阶段 Train Ticket 范围：

- 电子火车票：签发、展示、作废、检票核验、进站/登乘标记、使用完成。
- 纸质票或取票凭证：记录 Credential 类型、取票/打印展示信息、取票后状态推进。
- 从当前 `PAID -> COLLECTED -> USED` 的订单状态中拆出 `EntitlementIssued -> CheckedIn -> Boarded -> Used`。

未来 General Travel 范围：

- 航空 Boarding Pass、电子客票号。
- Ferry ticket、登船牌、车辆登船凭证。
- Coach e-ticket、上车二维码。
- Ride credential、司机/车辆核验码、一次性上车码。
- 附加服务凭证可复用本上下文的 Credential 模式，但主运输 Entitlement 和 Ancillary Entitlement 的规则需要显式区分。

## 2. 边界

### In Scope

- Entitlement 聚合身份、生命周期和状态转换。
- Credential number/code/二维码/条码/票号/取票号/登机牌号等可核验标识的生成、接收、映射、唯一性和展示元数据。
- Entitlement 与 `SegmentBookingId`、`TravelerRef`、`SegmentRef`、`ProviderReference` 的引用关系。
- 出票、作废、冻结、恢复、CheckIn、Boarded、Used 等票证生命周期命令。
- 票证展示所需的脱敏旅客信息、车次/班次/航班摘要、座席/席别/舱位、出发到达节点、有效期、使用说明、二维码/条码显示策略。
- Ticketing read models：TicketView、CredentialDisplayView、EntitlementStatusView、TicketAuditTimeline、FulfillmentEligibilityView。
- 票证状态事件的 Outbox 发布，以及消费外部事件后的 Inbox 幂等处理。
- 供应商返回票号、电子票码、登机牌、取票码、作废结果的防腐映射。
- 当前火车票取票、进站、使用状态从 `ts-execute-service` 和订单服务中迁移出来。

### Out of Scope

- `JourneyOrder` 商业订单状态、金额、订单项、整单确认条件。
- 支付、预授权、扣款、退款和差价执行。
- 库存 Hold、正式占用、释放、候补排序和库存审计。
- Provider booking internals，如 PNR 创建、供应商预订状态机、司机派单细节。
- 退改规则判定、手续费计算、退款金额计算、保护性改签方案选择。
- 物理履约事实的权威记录，例如实际到达、完成、No-show、行李交付；本上下文只消费或发布与票证核验相关的事件。
- 通知模板、发送渠道、重试策略。
- 报表口径和财务收入确认。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| Entitlement | 用户对一个 Segment 或受控服务拥有的可履约权益凭证。 | 本上下文的聚合根；Ticket 是其中一种表现。 |
| Ticket | 固定班次交通方式的票证表现。 | 第一阶段主要是 Train Ticket；通用模型中不作为聚合根。 |
| Credential | 可核验的凭证标识或载体，如票号、二维码、条码、取票号、登机牌号、乘车码。 | Credential 可来自平台生成，也可来自 Provider Integration。 |
| CredentialNo | 人类可读或供应商可识别的凭证号。 | 需要在 provider/type 范围内唯一。 |
| CredentialCode | 用于扫码、验票或一次性核验的编码内容。 | 可加密、可轮换，不一定直接展示原文。 |
| Display Metadata | 面向用户、客服、检票口展示的票证摘要。 | 是读模型或 Entitlement 快照，不是订单事实来源。 |
| Issue Purpose | 出票目的，如 initial、replacement、manualRecovery、providerRebuild。 | 用于幂等键和审计。 |
| Void Reason | 作废原因，如 refund、change、disruption、risk、manualCorrection。 | 作废不等于退款成功。 |
| CheckIn | 值机、签到、取票或检票前确认资格。 | 火车纸质票取票可映射为 CheckedIn。 |
| Boarded | 已通过检票、登机、登船或上车。 | 可由 Fulfillment 或 Provider Integration 事件触发。 |
| Used | 对应 Segment 已完成或凭证已完成履约。 | Used 后不能普通 Voided。 |
| Suspended | 凭证因风控、争议、供应商冲突或人工处理被冻结。 | Suspended 期间不可用于登乘核验。 |
| Ticketing Read Model | 面向用户、客服、履约核验和运营查询的票务投影。 | 只读，不反向推进聚合。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Booking Orchestration | `IssueEntitlement` command、`SegmentReservationConfirmed`、`SegmentTicketingRequested`、`ReplacementBookingConfirmed` | 只有编排确认 SegmentBooking 和支付/库存条件满足后，Entitlement 才能出票。 |
| Journey Order | `JourneyOrderId`、订单确认汇总所需引用、`JourneyOrderCancelled` | 票证需要归属订单用于展示和客服查询，但不拥有订单商业状态。 |
| Payment | `PaymentCaptured`、`PaymentExpired`、`PaymentLateSuccessDetected` | 作为出票 Saga 的条件输入；Payment 不直接改 Entitlement。 |
| Capacity & Availability | `CapacityHoldConfirmed`、`CapacityReleased` | 出票前确认资源已正式占用；退票作废后可触发释放。 |
| Post Sales | `VoidEntitlement` command、`SuspendEntitlement` command、`ResumeEntitlement` command、`PostSalesApproved` | 退票、改签、争议和人工售后需要作废或冻结凭证。 |
| Disruption Recovery | `RecoveryOptionAccepted`、`DisruptionAffectsEntitlement`、`SuspendEntitlement` command | 停运、取消、保护性改乘和异常恢复可能冻结或作废旧凭证并签发新凭证。 |
| Provider Integration | `ProviderIssueResult`、`ProviderVoidResult`、`ProviderCredentialStatusChanged`、`ProviderBoardingEvent` | 外部供应商出票、作废、状态变化和检票事件需要映射成平台事件。 |
| Fulfillment | `SegmentCompleted`、`BoardingVerified`、`CheckInSucceeded` | Fulfillment 事实推进 Entitlement 到 Boarded 或 Used。 |
| Customer Service | `ManualIssueEntitlement`、`ManualVoidEntitlement`、`ManualCorrectCredentialMetadata` | 人工恢复或纠错必须通过受控命令和审计事件。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Journey Order | `EntitlementIssued`、`EntitlementIssueFailed`、`EntitlementVoided`、`EntitlementSuspended`、`EntitlementUsed` | JourneyOrder 汇总确认、部分成功、取消和完成状态。 |
| Booking Orchestration | `EntitlementIssued`、`EntitlementIssueFailed`、`EntitlementVoided` | 编排 Saga 继续确认订单、重试出票、补偿或取消 SegmentBooking。 |
| Fulfillment | `EntitlementIssued`、`EntitlementVoided`、`EntitlementSuspended`、`CredentialDisplayUpdated`、`FulfillmentEligibilityChanged` | 履约核验需要知道凭证是否可用、是否冻结、展示码是否更新。 |
| Post Sales | `EntitlementVoided`、`EntitlementVoidFailed`、`EntitlementCheckedIn`、`EntitlementBoarded`、`EntitlementUsed` | 售后规则需要票证当前状态判断是否可退、可改或需人工。 |
| Capacity & Availability | `EntitlementVoided`、`EntitlementUsed` | 作废可释放库存；使用完成可用于库存审计。 |
| Payment | `EntitlementIssueFailedAfterPayment`、`EntitlementVoidedForRefund` | 触发退款请求或异常人工 Case，但 Payment 不从票证事件自行决定金额。 |
| Notification | `EntitlementIssued`、`EntitlementVoided`、`CredentialDisplayUpdated`、`EntitlementSuspended` | 发送出票、退票、改签、冻结和凭证更新通知。 |
| Customer Service | `TicketAuditTimeline` projection、`EntitlementStatusChanged` | 客服查看全链路并通过受控命令处理异常。 |
| Reporting | Ticketing read model events | 报表统计出票成功率、作废率、检票率和供应商票证差异。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| Entitlement | 必须绑定一个 `SegmentBookingId` 和 `TravelerRef`；同一 `SegmentBookingId + TravelerId + issuePurpose` 幂等；Issued 后必须有唯一 Credential；Voided/Suspended 不能 Boarded；Used 不能普通 Voided；每次状态变化必须有审计原因。 | `PrepareEntitlementIssue`、`IssueEntitlement`、`FailEntitlementIssue`、`VoidEntitlement`、`SuspendEntitlement`、`ResumeEntitlement`、`CheckInEntitlement`、`MarkBoarded`、`MarkUsed`、`UpdateCredentialDisplayMetadata`、`RebuildCredentialFromProvider` | `EntitlementIssuePrepared`、`EntitlementIssued`、`EntitlementIssueFailed`、`EntitlementVoided`、`EntitlementVoidFailed`、`EntitlementSuspended`、`EntitlementResumed`、`EntitlementCheckedIn`、`EntitlementBoarded`、`EntitlementUsed`、`CredentialDisplayUpdated`、`CredentialRebuilt` |
| CredentialRegistry | 同一 provider/type/scope 下 CredentialNo 或外部 ticketNo 不能重复绑定不同 Entitlement；CredentialCode 轮换必须保留旧码失效审计；敏感码不得明文泄露到普通读模型。 | `RegisterCredential`、`RotateCredentialCode`、`InvalidateCredentialCode`、`BindProviderCredential`、`MaskCredentialForDisplay` | `CredentialRegistered`、`CredentialCodeRotated`、`CredentialCodeInvalidated`、`ProviderCredentialBound`、`CredentialMaskedForDisplay` |
| TicketDisplayProfile | 一个 Entitlement 在同一展示渠道和语言下只有一个当前展示版本；展示字段只能来自 Entitlement 快照、Segment 摘要或 Provider 凭证结果；更新展示不改变业务状态。 | `CreateTicketDisplayProfile`、`UpdateTicketDisplayMetadata`、`ExpireTicketDisplayProfile` | `TicketDisplayProfileCreated`、`TicketDisplayMetadataUpdated`、`TicketDisplayProfileExpired` |

### Entitlement 关键字段

| 字段 | 说明 |
|---|---|
| entitlementId | 平台票证权益唯一标识。 |
| journeyOrderId | 所属 JourneyOrder 引用，只用于追踪和展示。 |
| segmentBookingId | 所属 SegmentBooking 引用，是出票的主要业务来源。 |
| travelerRef | 旅客引用，含脱敏证件和资格快照。 |
| segmentRef | Segment 摘要引用，含 mode、serviceDate、from/to。 |
| credentialRef | CredentialRegistry 引用，包含 CredentialNo、CredentialType、providerCredentialRef。 |
| status | PendingIssue、Issued、CheckedIn、Boarded、Used、Voided、Suspended。 |
| issuePurpose | initial、replacement、manualRecovery、providerRebuild。 |
| validityWindow | 凭证可核验时间窗口。 |
| displaySnapshotVersion | 展示快照版本。 |
| auditTrail | 状态变化原因、操作者、correlationId、eventId。 |

### 值对象

| Value Object | 字段 | 说明 |
|---|---|---|
| CredentialRef | credentialId、credentialType、credentialNo、providerCredentialRef、maskedCode | Entitlement 对 Credential 的稳定引用。 |
| CredentialType | E_TICKET、PAPER_TICKET、PICKUP_CODE、BOARDING_PASS、FERRY_TICKET、COACH_E_TICKET、RIDE_CODE | 支持第一阶段和未来扩展。 |
| TicketDisplayMetadata | title、subtitle、routeText、seatText、timeText、travelerMaskedName、instructions、barcodeFormat | 展示层可缓存的票证摘要。 |
| VerificationPolicySnapshot | allowCheckIn、allowBoarding、requiresPhysicalPickup、offlineVerificationAllowed、version | 核验规则快照，不代替 Fulfillment 事实。 |
| ProviderCredentialRef | providerId、providerType、confirmationNo、ticketNo、rawCredentialStatus、mappedStatus | 供应商凭证引用和状态映射。 |
| EntitlementLifecycleAudit | action、reason、actorType、actorId、occurredAt、correlationId | 审计不可缺失。 |

## 6. 状态机

本上下文拥有 Entitlement 状态机。其他上下文的 JourneyOrder、PaymentIntent、CapacityHold、SegmentBooking、PostSalesCase、FulfillmentRecord 状态只作为事件输入或读模型汇总，不在本上下文定义。

### 状态定义

| 状态 | 含义 | 可展示 | 可核验 |
|---|---|---|---|
| PendingIssue | 已准备出票或等待供应商/平台生成 Credential。 | 可展示“出票中”。 | 否。 |
| Issued | 凭证已签发，可展示或核验。 | 是。 | 是，受 validityWindow 和 VerificationPolicy 限制。 |
| CheckedIn | 已值机、签到、取票或完成检票前确认。 | 是。 | 是。 |
| Boarded | 已通过检票、登机、登船或上车。 | 是。 | 通常不允许再次登乘核验。 |
| Used | 对应 Segment 已完成或凭证使用闭环。 | 可展示历史。 | 否。 |
| Voided | 凭证已作废。 | 可展示作废原因。 | 否。 |
| Suspended | 凭证被冻结，等待风控、供应商冲突、售后或人工处理。 | 可展示冻结提示。 | 否。 |

### 允许转换

| 当前状态 | 触发 | 目标状态 | 说明 |
|---|---|---|---|
| PendingIssue | `EntitlementIssued` | Issued | 必须同时注册 Credential。 |
| PendingIssue | `EntitlementIssueFailed` | PendingIssue | 可保留重试；失败事实进入审计和 Saga。 |
| Issued | `CheckInSucceeded` | CheckedIn | 火车纸质票取票、航空值机、大巴签到都可映射。 |
| Issued | `BoardingVerified` | Boarded | 不强制经过 CheckedIn。 |
| CheckedIn | `BoardingVerified` | Boarded | 登乘核验成功。 |
| Boarded | `SegmentCompleted` | Used | 由 Fulfillment 完成事实触发。 |
| Issued | `VoidEntitlement` | Voided | 退票、改签、异常取消。 |
| CheckedIn | `VoidEntitlementWithRule` | Voided | 必须带售后或异常规则依据。 |
| Issued | `SuspendEntitlement` | Suspended | 风控、供应商冲突、人工冻结。 |
| CheckedIn | `SuspendEntitlement` | Suspended | 取票后仍可冻结但需要原因。 |
| Suspended | `ResumeEntitlement` | Issued | 恢复后回到 Issued，必要时更新 Credential。 |
| Suspended | `VoidEntitlement` | Voided | 冻结后确认需作废。 |

### 禁止转换

| 禁止转换 | 原因 |
|---|---|
| Used -> Voided | 已完成履约不能普通退票作废；争议走 Compensation 或人工修正。 |
| Voided -> Issued | 作废不可逆；恢复应重新签发 replacement Entitlement。 |
| Boarded -> Issued | 已登乘不能回到未登乘状态。 |
| Suspended -> Boarded | 冻结期间不能通过登乘核验。 |
| PendingIssue -> Boarded | 未签发凭证不能登乘。 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `PrepareEntitlementIssue` | Entitlement | `EntitlementIssuePrepared` | segmentBookingId + travelerId + issuePurpose |
| `IssueEntitlement` | Entitlement, CredentialRegistry | `EntitlementIssued`, `CredentialRegistered` | segmentBookingId + travelerId + issuePurpose |
| `FailEntitlementIssue` | Entitlement | `EntitlementIssueFailed` | entitlementId + issueAttempt + failureCode |
| `BindProviderCredential` | CredentialRegistry | `ProviderCredentialBound` | providerId + providerCredentialNo + segmentBookingId |
| `RebuildCredentialFromProvider` | Entitlement, CredentialRegistry | `CredentialRebuilt` | entitlementId + providerStatusVersion |
| `UpdateCredentialDisplayMetadata` | TicketDisplayProfile | `CredentialDisplayUpdated` | entitlementId + displayVersion |
| `CheckInEntitlement` | Entitlement | `EntitlementCheckedIn` | entitlementId + checkInSource + sourceEventId |
| `MarkBoarded` | Entitlement | `EntitlementBoarded` | entitlementId + boardingSource + sourceEventId |
| `MarkUsed` | Entitlement | `EntitlementUsed` | entitlementId + segmentCompletedEventId |
| `VoidEntitlement` | Entitlement, CredentialRegistry | `EntitlementVoided`, `CredentialCodeInvalidated` | entitlementId + voidReason + postSalesCaseId/recoveryCaseId |
| `SuspendEntitlement` | Entitlement | `EntitlementSuspended` | entitlementId + suspendReason + businessCaseId |
| `ResumeEntitlement` | Entitlement | `EntitlementResumed` | entitlementId + resumeReason + businessCaseId |
| `RotateCredentialCode` | CredentialRegistry | `CredentialCodeRotated` | credentialId + rotationReason + requestedAtBucket |
| `ManualIssueEntitlement` | Entitlement, CredentialRegistry | `EntitlementIssued` | manualCaseId + segmentBookingId + travelerId |
| `ManualCorrectCredentialMetadata` | TicketDisplayProfile | `TicketDisplayMetadataUpdated` | manualCaseId + entitlementId + targetVersion |

事件发布要求：

1. 所有状态改变事件必须通过本上下文 Outbox 发布。
2. 消费 `PaymentCaptured`、`CapacityHoldConfirmed`、`ProviderIssueResult`、`BoardingVerified` 等外部事件时必须记录 Inbox。
3. 面向用户展示的读模型事件不得携带未脱敏证件号或原始 CredentialCode。
4. `EntitlementIssueFailedAfterPayment` 是票务失败事实，不直接决定退款金额；退款金额仍由 Post Sales 或 Disruption Recovery 决定。

## 8. 策略和 Saga 参与点

### 下单、占座、支付、出票 Saga

Entitlement & Ticketing 不是下单 Saga 的拥有者，而是其中的出票参与者。

| 触发事件或命令 | 本上下文策略 | 输出 |
|---|---|---|
| `IssueEntitlement` command from Booking Orchestration | 校验 SegmentBooking 引用、Payment 条件、Capacity 条件、Traveler 快照和幂等键；生成或绑定 Credential。 | `EntitlementIssued` 或 `EntitlementIssueFailed` |
| `ProviderIssueResult(Success)` | 绑定供应商票号/电子码，签发 Entitlement。 | `ProviderCredentialBound`, `EntitlementIssued` |
| `ProviderIssueResult(Timeout/Unknown)` | 保持 PendingIssue，要求 Booking Orchestration 或 Provider Integration 查询最终状态，禁止重复创建供应商票。 | `EntitlementIssuePendingProviderStatus` |
| `EntitlementIssueFailed` after payment | 标记可重试或不可恢复原因，发布失败事件给 Booking Orchestration。 | 重试、人工、退款或释放由 Saga 决定 |

### 取消未支付 Saga

正常情况下未支付取消不应存在已签发 Entitlement。本上下文仅处理异常晚到出票或供应商状态冲突：

| 触发 | 策略 | 输出 |
|---|---|---|
| `JourneyOrderCancelled` 且无 Entitlement | 不创建票证，不发布作废事件。 | 无业务状态变化 |
| `EntitlementIssued` 晚于订单取消 | 先 `SuspendEntitlement`，等待 Post Sales 或 Provider conflict 恢复流程。 | `EntitlementSuspended` |

### 退票和退款 Saga

Entitlement & Ticketing 负责先作废票证，再让后续上下文释放库存和退款。

| Step | 本上下文责任 | 下游影响 |
|---|---|---|
| `PostSalesApproved` | 接收 `VoidEntitlement` command。 | 校验当前状态是否可作废。 |
| `EntitlementVoided` | 发布票证作废事实。 | Booking 取消 SegmentBooking，Capacity 释放，Payment 接收退款请求的前置信号。 |
| `EntitlementVoidFailed` | 发布不可作废原因。 | PostSalesCase 进入 CompensationPending 或人工。 |

关键规则：票证已 Voided 后，Refund 失败不能自动恢复票证；需要人工重新签发 replacement Entitlement 并留下审计。

### 改签 Saga

改签同时涉及旧 Entitlement 作废和新 Entitlement 签发。

| 阶段 | 本上下文策略 |
|---|---|
| 新 SegmentBooking 确认前 | 原 Entitlement 保持有效或按规则 Suspended；不提前 Voided。 |
| 差价处理完成后 | 接收 `VoidEntitlement` 作废旧票。 |
| ReplacementBookingConfirmed 后 | 接收 `IssueEntitlement` 签发新票，issuePurpose=replacement。 |
| 新票出票失败 | 发布 `EntitlementIssueFailed`，由改签 Saga 决定恢复旧票、重试或人工。 |

### Fulfillment 协作

| Fulfillment 事件 | 本上下文响应 | 说明 |
|---|---|---|
| `CheckInSucceeded` | `CheckInEntitlement` | 火车取票、航空值机、船票安检前签到都可映射。 |
| `BoardingVerified` | `MarkBoarded` | 本上下文记录票证已被使用进入履约，但实际登乘事实仍属于 Fulfillment。 |
| `SegmentCompleted` | `MarkUsed` | Segment 完成后凭证进入 Used。 |

### Disruption Recovery 协作

| 异常恢复动作 | 本上下文参与点 |
|---|---|
| 停运/取消后自动退票 | 批量接收 `VoidEntitlement`，按 entitlementId 幂等作废。 |
| 保护性改乘 | 旧 Entitlement Voided，新 Entitlement issuePurpose=disruptionReplacement。 |
| 供应商取消但平台仍显示有效 | 先 Suspended，等待恢复方案或售后作废。 |

### Customer Service 协作

客服不能直接改数据库或读模型。所有人工出票、作废、冻结、恢复、展示纠错必须通过命令进入 Entitlement 聚合，并产生审计事件。人工命令必须携带 caseId、reason、operatorId、approvalRef。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| TicketView | `EntitlementIssued`、`CredentialDisplayUpdated`、`EntitlementVoided`、`EntitlementSuspended`、`EntitlementUsed` | 用户订单详情、移动端票夹、客服订单页。 |
| CredentialDisplayView | `CredentialRegistered`、`CredentialCodeRotated`、`CredentialMaskedForDisplay`、`TicketDisplayMetadataUpdated` | 前端展示二维码/条码/取票号、离线核验包。 |
| EntitlementStatusView | 所有 Entitlement lifecycle events | Booking Orchestration、Journey Order、Post Sales、Fulfillment eligibility 查询。 |
| TicketAuditTimeline | Entitlement events + Provider credential events + manual command events | Customer Service、Admin & Audit、供应商差异排查。 |
| FulfillmentEligibilityView | `EntitlementIssued`、`EntitlementSuspended`、`EntitlementVoided`、`CredentialCodeInvalidated`、`EntitlementBoarded` | 闸机/检票/值机核验服务、Fulfillment。 |
| TicketingOperationDashboard | `EntitlementIssueFailed`、`EntitlementVoidFailed`、`ProviderCredentialBound`、`CredentialRebuilt` | 运营监控出票失败率、作废失败率、供应商延迟。 |
| TicketingReportFeed | Entitlement lifecycle events with masked dimensions | Reporting、Finance Settlement 辅助分析，不作为交易写入来源。 |

读模型规则：

1. TicketView 可以冗余 JourneyOrder、SegmentBooking、SegmentRef、座席、旅客脱敏信息，但只作为展示。
2. CredentialDisplayView 不保存未加密原始 CredentialCode；展示层按渠道和权限获取脱敏或短期签名版本。
3. FulfillmentEligibilityView 可以被高频查询，但任何核验成功仍应通过命令或事件回写 Entitlement/Fulfillment。
4. TicketAuditTimeline 是客服和人工兜底的事实线索，不允许人工直接编辑。

## 10. 外部系统和防腐层

Entitlement & Ticketing 需要两类防腐：供应商凭证防腐和遗留服务防腐。

### Provider Integration 防腐

| Provider Type | 外部凭证 | 平台映射 | 注意事项 |
|---|---|---|---|
| Rail | 票号、取票号、检票状态 | CredentialNo、PAPER_TICKET/E_TICKET、EntitlementBoarded | 区分电子票和纸质取票；检票事实可来自 Provider 或 Fulfillment。 |
| Air | 电子客票号、Boarding Pass、PNR 关联 | CredentialNo、BOARDING_PASS、ProviderCredentialRef | PNR 不是 Entitlement；登机牌可能在出票后另行生成。 |
| Coach | 电子票码、座位号、上车点核验码 | COACH_E_TICKET、TicketDisplayMetadata | 供应商可能不支持座位级 Credential。 |
| Ferry | 船票号、登船牌、车辆票 | FERRY_TICKET、CredentialRef | 人票和车辆票可能需要绑定作废。 |
| RideHailing | 上车码、司机/车辆核验信息 | RIDE_CODE、Display Metadata | 司机接单和上车码不等于固定班次出票。 |

防腐规则：

1. `ProviderIssueResult` 必须映射为 `EntitlementIssued` 或 `EntitlementIssueFailed/PendingProviderStatus`，不能把供应商原始状态直接暴露给 JourneyOrder。
2. `ProviderVoidResult` 必须映射为 `EntitlementVoided` 或 `EntitlementVoidFailed`。
3. 原始供应商状态只保存在 ProviderCredentialRef 审计字段和 Provider Integration 日志中。
4. 对账发现平台与供应商票证不一致时，先 `SuspendEntitlement`，再由受控恢复流程处理。

### 遗留服务防腐

当前 Train Ticket 中，票证状态混在订单和执行服务中：

- `ts-order-service`、`ts-order-other-service` 保存 `NOTPAID/PAID/COLLECTED/USED/CANCEL/CHANGE` 等状态。
- `ts-execute-service` 执行取票和进站。
- `ts-cancel-service`、`ts-rebook-service` 直接修改订单和退款。
- `ts-seat-service` 从订单反推已售座位。

迁移期需要 Anti-Corruption Layer 把遗留状态映射为 Entitlement 事件：

| Legacy State/Action | Entitlement Mapping | Notes |
|---|---|---|
| `PAID` after successful booking | `IssueEntitlement` candidate | 仍需确认 SegmentBooking、Payment、Capacity 条件。 |
| `COLLECTED` | `EntitlementCheckedIn` | 对火车纸质票可表示取票完成。 |
| `USED` | `EntitlementBoarded` 或 `EntitlementUsed` | 需要由 Fulfillment 进一步区分进站、登乘和完成。 |
| `CANCEL` | `EntitlementVoided` if ticket exists | 未出票取消不创建 Voided Entitlement。 |
| `CHANGE` | old `EntitlementVoided` + replacement `EntitlementIssued` | 必须保留新旧票关联。 |

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-execute-service` | 取票、进站逻辑迁移到 Entitlement 命令和 Fulfillment 事件协作；禁止直接修改订单状态。 |
| `ts-order-service`, `ts-order-other-service` | 票证状态从订单记录中拆出；订单只保存 EntitlementId 引用和汇总投影。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 订票成功后不直接视为可使用票；改为由 Booking Orchestration 调用 `IssueEntitlement`。 |
| `ts-cancel-service` | 取消/退票时先通过 Post Sales 触发 `VoidEntitlement`，再释放库存和退款。 |
| `ts-rebook-service` | 改签拆成旧 Entitlement 作废和新 Entitlement 签发，保留失败补偿。 |
| `ts-seat-service` | 不再通过订单 `USED/CANCEL/CHANGE` 推断票证和库存；消费 Entitlement/Booking/Capacity 事件做审计投影。 |
| `ts-inside-payment-service`, `ts-payment-service` | 支付成功只作为出票条件事件，不直接造成 `EntitlementIssued`；出票失败后由 Saga 决定退款或人工。 |
| `ts-admin-order-service` | 后台票证操作改为受控命令：人工出票、作废、冻结、恢复、展示纠错，并进入审计。 |
| `ts-notification-service` | 消费 `EntitlementIssued/Voided/Suspended/CredentialDisplayUpdated` 发送通知，不参与票证状态决策。 |
| `ts-voucher-service` | 需要确认其历史职责；若是票据/凭证展示，应并入或适配 Ticketing read model；若是财务凭证，则归 Finance Settlement。 |
| `ts-ticket-office-service` | 若表示线下取票点，作为 Fulfillment/Provider Integration 的外部核验来源；不拥有 Entitlement 状态。 |

迁移建议切片：

1. 建立 Entitlement 状态和 TicketView，不改变旧订单写入，只从旧状态投影票证读模型。
2. 将 `PAID -> COLLECTED -> USED` 映射为 `Issued -> CheckedIn -> Boarded/Used`，让客服时间线能同时显示旧状态和新事件。
3. 新下单链路由 Booking Orchestration 调用 `IssueEntitlement`，旧订单服务只保存引用。
4. 退票改签链路改为先作废旧 Entitlement，再释放库存和退款。
5. 最后移除订单表中承担票证语义的状态字段或降级为只读兼容字段。

## 12. 验收标准

- 本 domain 的聚合所有权明确：Entitlement、CredentialRegistry、TicketDisplayProfile 只保护票证和 Credential 不变量。
- 本 domain 发布和消费的事件明确：出票、作废、冻结、CheckIn、Boarded、Used、Credential 展示更新都通过 Outbox/Inbox。
- 不变量和状态机没有依赖其他 domain 内部状态：支付、库存、订单、供应商预订和履约事实只通过契约事件或命令输入。
- 第一阶段 Train Ticket 电子票和纸质票范围已说明，且能从 `PAID/COLLECTED/USED/CANCEL/CHANGE` 迁移到 Entitlement 生命周期。
- 未来 General Travel 的 Boarding Pass、Ferry ticket、Coach e-ticket、Ride credential 已预留 CredentialType 和 Provider 防腐映射。
- 下游 Journey Order、Booking Orchestration、Fulfillment、Post Sales、Notification、Customer Service、Reporting 的协作契约可追踪。
