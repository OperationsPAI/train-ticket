# Group Booking Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Group Booking |
| Status | proposed-ddd-baseline |
| Owner Agent | agentm-group-booking |
| Last Updated | 2026-07-10 |
| High-Level Inputs | `docs/adr/0003-commercial-realism-scope.md`, `docs/02-domains/capacity-availability.md`, `docs/02-domains/journey-order.md`, `docs/02-domains/booking-orchestration.md`, `docs/02-domains/entitlement-ticketing.md`, `docs/02-domains/provider-integration.md`, `docs/08-contracts/events/capacity-availability.md`, `docs/08-contracts/api/capacity-availability.md`, `docs/08-contracts/events/journey-order.md`, `docs/08-contracts/api/journey-order.md`, `docs/08-contracts/events/booking-orchestration.md`, `docs/08-contracts/api/booking-orchestration.md`, `docs/08-contracts/events/entitlement-ticketing.md`, `docs/08-contracts/api/entitlement-ticketing.md` |

## 1. 领域目标

Group Booking 负责团体订票的申请、运营审批、批量占座、分批实名资料补全、分批出票、超时衰减释放和部分成团决策。它回答“一个 >=10 人的团体出行需求是否被平台接受、保留了多少座位、哪些旅客已补全并可出票、未补全名额何时释放、低于目标人数时是否仍然成团”的问题。

本领域独立存在的原因：团体订单不是普通 `JourneyOrder` 的简单大数组，它有运营审批、批量名额池、分批实名资料、分批出票、部分成团和超时衰减释放等长生命周期规则。`Capacity & Availability` 保护库存不变量；Group Booking 只表达“批量保留意图”和衰减策略，实际不超卖、不重叠 Hold、释放和确认仍由 Capacity 聚合裁决。`Journey Order` 负责用户可见商业订单；Group Booking 负责团体申请与名额治理，不把审批和实名补全状态塞进普通订单状态机。

核心裁决建议：出票继续走既有单客链路的批量编排，不新建独立“团体出票链”。Group Booking 将每个已补全旅客名额转换为可追踪的 `segmentBookingId` / `travelerRef` / `holdId` 输入，交给 Booking Orchestration 和 Entitlement & Ticketing 的既有命令/事件处理；本域只增加批次治理、部分成团和异常补偿视图。

## 2. 边界 In-Out Scope

### In Scope

- `GroupOrder`：团体订票申请、目标人数、最小成团人数、旅客名单补全要求、支付/担保引用、审批状态和部分成团决策。
- `BulkHold`：对 Capacity & Availability 的预留扩展语义，表达一组同路线、同席别或相邻座位策略的批量占座意图、已获 Hold 数量、衰减释放计划和分配到旅客的名额。
- 运营审批流：申请 → 运营审批 → 批量占座 → 分批实名补全 → 分批出票 → 完成/部分成团/失败/取消。
- 分批实名补全：对每个 `GroupParticipant` 保存 `TravelerRef`、脱敏证件引用、核验摘要和补全截止时间；不保存未脱敏证件号。
- 分批出票：按已补全且已分配 Hold 的旅客批次触发既有 Booking/Entitlement 链路，并记录批次进度。
- 超时衰减释放：按审批、资料补全、支付/担保、出票窗口等 deadline 自动释放未使用名额或降档为部分成团。
- 部分成团规则：目标人数、最小成团人数、已补全人数、已出票人数、可接受席别/车次替代和运营审批共同决定。
- SIM 网关防腐层：运营审批、团体客户资质、名单导入校验等外部方一律模拟、可种子化、无真实网络。

### Out of Scope

- 不拥有物理库存、区间重叠、不超卖和具体座位冲突；这些属于 Capacity & Availability。
- 不拥有普通商业订单状态、订单金额汇总、支付状态或订单取消生命周期；这些属于 Journey Order / Payment。
- 不拥有实名核验的证件登记、自然人归并、优惠证书和限购事实。ADR-0003 的 Identity Verification 是上游，但本基线分支没有 `docs/08-contracts/events/identity-verification.md` 或 `docs/08-contracts/api/identity-verification.md`，因此本文件不杜撰跨域契约。
- 不计算票价、团体折扣、手续费、退款费或优惠规则；这些属于 Fare & Pricing / Offer Management / Post Sales。
- 不签发、作废、冻结或核验票证；这些属于 Entitlement & Ticketing / Fulfillment。
- 不直接调用铁路、学校、企业客户或政府真实接口；外部方一律通过本域 SIM ACL 模拟。
- 不替代 Corporate Travel 的企业协议、员工授权或月结账单；团体订票可引用企业/渠道快照，但不拥有企业账户。

## 3. 统一语言

| Term | Definition | Notes |
|---|---|---|
| `GroupOrder` | 团体订票聚合根，表达 >=10 人的团体出行申请、审批、名额、补全和成团结果。 | 不是 JourneyOrder；可在确认后产生一个或多个 JourneyOrder 引用。 |
| `GroupParticipant` | 团体内的一个旅客名额。 | 可先为占位名额，后续补全 TravelerRef 和核验摘要。 |
| `BulkHold` | 一次批量占座意图和结果，聚合多个 Capacity Hold 引用、目标数量、衰减策略和分配规则。 | 不拥有库存；Capacity 仍是强一致裁决者。 |
| `HoldSlot` | BulkHold 内的单个可分配名额，绑定 holdId、segmentRef、classRef、interval 和状态。 | 可为 `UNASSIGNED` 或绑定 GroupParticipant。 |
| `OperationalApproval` | 运营对团体申请的审批结论。 | 高风险或超额申请必须有 Admin & Audit 引用。 |
| `RosterCompletion` | 团体名单分批补全过程。 | 只保存 TravelerRef、maskedDocumentNo、verificationSummaryRef，不保存原始证件。 |
| `TicketingBatch` | 一批已补全旅客进入既有 Booking/Entitlement 链路的批次。 | 批次失败不自动失败整个 GroupOrder。 |
| `DecayRelease` | 超时或人数不足时按策略释放部分 BulkHold 名额。 | 释放未分配或低优先级名额，保留可成团所需名额。 |
| `PartialFormation` | 部分成团。 | 达到最小成团人数但低于目标人数时，经规则或运营审批继续出票。 |
| `GroupGuaranteeRef` | 团体担保、预授权或信用额度引用。 | 资金 owner 仍是 Payment/Corporate/Finance，Group Booking 只保存引用。 |
| `MissedDeadline` | 审批、补全或出票 deadline 错过。 | 不可逆事实；后续只能按补偿/人工规则追加新事实。 |
| `SimApprovalGateway` | 运营审批/团体客户资质模拟网关。 | 确定性、可种子化、无真实网络。 |

## 4. 上下游契约

本节只引用已经存在于 `docs/08-contracts/` 的域、事件或端点名称。Group Booking 拟新增的命令和领域事件只在第 7 节列出；未来契约化必须单独修改 `docs/08-contracts/`。Identity Verification 是 ADR-0003 上游之一，但当前 base 分支没有对应 08-contracts 文档，本设计只记录集成缺口，不编造事件或端点。

### Upstream

| Upstream Context | Existing Contract Verified In `docs/08-contracts/` | How Group Booking Uses It |
|---|---|---|
| Offer Management | Events `OfferQuoted`, `OfferExpired`; endpoint `POST /api/v1/offers`; endpoint `GET /api/v1/offers/{offerId}`. | 引用 itinerary、priceSnapshot、ruleSnapshot 和 availabilitySnapshotRefs；Offer 不锁库存，BulkHold 仍需 Capacity 裁决。 |
| Fare & Pricing | Event `FareQuoteComputed`; endpoint `POST /api/v1/fare-quotes`; endpoint `GET /api/v1/fare-quotes/{quoteId}`. | 读取价格/规则快照和失败原因；本域不重算 Money，跨边界 Money 使用 `{currency, minorUnits}`。 |
| Traveler Profile | Events `TravelerSnapshotUpdated`, `EligibilityDetermined`, `EligibilityExpired`; endpoints `GET /api/v1/travelers/{travelerId}`, `PATCH /api/v1/travelers/{travelerId}`. | 补全名单时读取 TravelerRef、travelerType、maskedDocumentRef 和资格摘要；完整实名证件不进入本域普通事件。 |
| Risk & Compliance | Events `RiskAssessmentResult`, `ChallengeResolved`, `RiskBlockApplied`, `RiskBlockLifted`; endpoint `POST /api/v1/risk-assessments`. | 大团、异常补全速度、重复证件摘要或审批例外需要风险评估；风险阻断只暂停本域推进。 |
| Payment | Events `PaymentIntentCreated`, `PaymentAuthorized`, `PaymentCaptured`, `PaymentFailed`, `PaymentIntentCancelled`, `PaymentIntentExpired`; endpoints `POST /api/v1/payment-intents`, `POST /api/v1/payment-intents/{paymentIntentId}/capture`, `GET /api/v1/payment-intents/{paymentIntentId}`. | 团体担保、预授权或支付条件作为占座/出票前置事实引用；Payment 是资金状态 owner。 |
| Admin & Audit | Events `ManualActionRequested`, `ManualActionApproved`, `ManualActionRejected`, `ManualActionExecuted`, `AuditEntryRecorded`; endpoints `POST /api/v1/admin/manual-actions`, `POST /api/v1/admin/manual-actions/{manualActionId}/approve`, `POST /api/v1/admin/manual-actions/{manualActionId}/reject`, `GET /api/v1/admin/audit-trail?businessRef={ref}&limit=20&offset=0`. | 运营审批、超额保留、手工部分成团、名单异常和释放例外必须带人工审批与审计引用。 |
| Capacity & Availability | Events `CapacityHeld`, `CapacityHoldFailed`, `CapacityHoldConfirmed`, `CapacityReleased`, `CapacityHoldExpired`; endpoint `POST /api/v1/capacity-holds`; endpoint `POST /api/v1/capacity-holds/{holdId}/release`; endpoint `GET /api/v1/capacity-holds/{holdId}`. | BulkHold 的实际单元由现有 Hold/Release 结果组成；本域消费这些事实更新 HoldSlot，不解释库存冲突。 |
| Booking Orchestration | Events `BookingSagaStarted`, `SegmentReservationRequested`, `SegmentCapacityHolding`, `SegmentReservationConfirmed`, `SegmentReservationFailed`, `SegmentTicketed`, `BookingSagaCompleted`, `BookingSagaFailed`; endpoint `POST /api/v1/internal/booking-sagas`; endpoint `POST /api/v1/internal/booking-sagas/{sagaId}/request-reservation`; endpoint `GET /api/v1/internal/booking-sagas/{sagaId}`. | 已补全旅客批次交给既有 Booking Saga；本域跟踪批次结果和部分失败补偿。 |
| Entitlement & Ticketing | Events `EntitlementIssued`, `EntitlementIssueFailed`, `EntitlementVoided`, `EntitlementSuspended`; endpoint `POST /api/v1/entitlements`; endpoint `GET /api/v1/entitlements/{entitlementId}`. | 票证签发结果更新 TicketingBatch 和 GroupParticipant 进度；票号和 Credential 生命周期仍由 Entitlement 拥有。 |
| Journey Order | Events `JourneyOrderCreated`, `JourneyOrderPendingPayment`, `JourneyOrderPaymentRecorded`, `JourneyOrderConfirmed`, `JourneyOrderCancelled`; endpoints `POST /api/v1/journey-orders`, `GET /api/v1/journey-orders/{orderId}`, `POST /api/v1/journey-orders/{orderId}/cancel`. | 团体确认后可生成一个团体商业订单或按批次生成多个 JourneyOrder 引用；订单状态仍由 Journey Order 决定。 |

### Downstream

| Downstream Context | Existing Contract Verified In `docs/08-contracts/` | What Group Booking Provides Without Changing That Contract |
|---|---|---|
| Capacity & Availability | Commands/endpoints `HoldCapacity` / `POST /api/v1/capacity-holds`, `ReleaseHold` / `POST /api/v1/capacity-holds/{holdId}/release`; events `CapacityHeld`, `CapacityHoldFailed`, `CapacityReleased`, `CapacityHoldExpired`. | BulkHold 在契约落地前适配为多个现有 `HoldCapacity` 请求；衰减释放适配为多个 `ReleaseHold` 请求。 |
| Journey Order | Command/endpoint `CreateJourneyOrder` / `POST /api/v1/journey-orders`; events `JourneyOrderCreated`, `JourneyOrderConfirmed`, `JourneyOrderCancelled`. | GroupOrder 成团后提交订单创建材料或保存已创建订单引用；不直接写 JourneyOrder 状态。 |
| Booking Orchestration | Command/endpoint `StartBookingSaga` / `POST /api/v1/internal/booking-sagas`; bus-only `ConfirmSegmentReservation`; events `SegmentReservationConfirmed`, `SegmentReservationFailed`, `SegmentTicketed`. | 对每个 TicketingBatch 发送普通 segment booking 计划，复用既有单客链；批量只存在于本域编排层。 |
| Entitlement & Ticketing | Command/endpoint `IssueEntitlement` / `POST /api/v1/entitlements`; events `EntitlementIssued`, `EntitlementIssueFailed`. | 已补全且满足支付/担保/Capacity 条件的旅客由 Booking Orchestration 触发出票；本域消费结果。 |
| Payment | Endpoint `POST /api/v1/payment-intents`, endpoint `POST /api/v1/payment-intents/{paymentIntentId}/capture`, events `PaymentCaptured`, `PaymentFailed`. | 团体定金、担保或尾款由 Payment 处理；本域仅保存 paymentIntentId、授权金额和状态摘要。 |
| Notification | Command `ScheduleNotification`; events `NotificationScheduled`, `NotificationDispatched`, `NotificationDelivered`, `NotificationFailed`, `NotificationCancelled`. | 审批结果、补全提醒、部分成团确认、释放和出票批次结果可触发通知；通知失败不回滚 GroupOrder。 |
| Customer Service | Endpoint `POST /api/v1/support-cases`; events `SupportCaseOpened`, `EvidenceAttached`, `SupportCaseEscalated`, `SupportCaseResolved`; event `ManualActionRequested`. | 大团审批、名单异常、出票部分失败和超时释放进入客服时间线或工单；客服不直接修改聚合。 |
| Reporting | Endpoint `GET /api/v1/metrics?category=operational&limit=20&offset=0`; events consumption pattern in Reporting contracts. | 后续从本域事件流构建团体申请转化率、占座使用率、补全超时率和部分成团率；Reporting 不反向驱动状态。 |
| Admin & Audit | Command `RecordAuditEntry`; events `AuditEntryRecorded`, `ManualActionExecuted`. | 所有运营审批、手工释放、覆盖成团规则和异常查看都追加审计事实。 |

### Contract gap for ADR-0003 Identity Verification

当前 base 分支没有 `docs/08-contracts/events/identity-verification.md` 或 `docs/08-contracts/api/identity-verification.md`，因此本设计禁止引用不存在的实名核验事件/端点。Wave D 实施前应由 Identity Verification 契约提供“按 travelerRefs 批量返回 verification summary / purchase-limit recommendation”的查询或事件；在契约落地前，Group Booking 只能使用 Traveler Profile 的脱敏旅客资料和 Risk & Compliance 的既有风险结果作为临时输入。

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `GroupOrder` | 目标人数 `targetParticipantCount` 必须 >=10；`minimumFormationCount` 必须 >=10 且 <= target；未审批不得创建 BulkHold；`FORMED`、`PARTIALLY_FORMED`、`FAILED`、`CANCELLED`、`EXPIRED` 为结局态，可查询安息；`FAILED`/`EXPIRED` 不可普通恢复为活跃。 | `ApplyGroupOrder`, `SubmitGroupOrderForApproval`, `ApproveGroupOrder`, `RejectGroupOrder`, `AttachGroupGuarantee`, `OpenRosterCompletion`, `AcceptPartialFormation`, `CancelGroupOrder`, `ExpireGroupOrder`, `MarkGroupOrderFailed` | `GroupOrderApplied`, `GroupOrderSubmittedForApproval`, `GroupOrderApproved`, `GroupOrderRejected`, `GroupGuaranteeAttached`, `RosterCompletionOpened`, `PartialFormationAccepted`, `GroupOrderCancelled`, `GroupOrderExpired`, `GroupOrderFailed` |
| `BulkHold` | 必须绑定已审批 GroupOrder；`requestedQuantity` 不得超过审批数量；每个 HoldSlot 至多绑定一个 Capacity `holdId` 和一个 participant；有效 HoldSlot 数不得超过 Capacity 已确认 held 数；释放和衰减必须幂等；`FAILED`、`RELEASED`、`EXPIRED` 为安息态。 | `CreateBulkHold`, `RecordCapacityHoldResult`, `AssignHoldSlot`, `ReleaseHoldSlot`, `ApplyDecayRelease`, `ConfirmBulkHoldUsage`, `ExpireBulkHold`, `MarkBulkHoldFailed` | `BulkHoldCreated`, `BulkHoldCapacityRecorded`, `HoldSlotAssigned`, `HoldSlotReleased`, `BulkHoldDecayed`, `BulkHoldUsageConfirmed`, `BulkHoldExpired`, `BulkHoldFailed` |
| `GroupParticipantRoster` | 同一 GroupOrder 内 participantSeq 唯一；同一 active roster 内同一 TravelerRef/credential hash 不得重复；补全后证件摘要不可覆盖，只能版本化更正；未核验或被风险阻断的 participant 不可进入出票批次。 | `AddParticipantPlaceholders`, `CompleteParticipantIdentity`, `RecordParticipantVerificationSummary`, `RejectParticipant`, `WithdrawParticipant`, `LockParticipantForTicketing`, `ExpireIncompleteParticipants` | `ParticipantPlaceholdersAdded`, `ParticipantIdentityCompleted`, `ParticipantVerificationSummaryRecorded`, `ParticipantRejected`, `ParticipantWithdrawn`, `ParticipantLockedForTicketing`, `IncompleteParticipantsExpired` |
| `TicketingBatch` | 只包含已补全、已分配 HoldSlot、风险允许且未进入其他 active batch 的 participants；同一 participant 只能有一个成功 entitlement；批次失败不自动失败 GroupOrder；`ISSUED`、`PARTIALLY_ISSUED`、`FAILED`、`CANCELLED` 为安息态。 | `CreateTicketingBatch`, `SubmitBatchToBooking`, `RecordSegmentReservationResult`, `RecordEntitlementResult`, `RetryTicketingBatch`, `CancelTicketingBatch`, `CloseTicketingBatch` | `TicketingBatchCreated`, `TicketingBatchSubmittedToBooking`, `BatchSegmentReservationRecorded`, `BatchEntitlementRecorded`, `TicketingBatchRetried`, `TicketingBatchCancelled`, `TicketingBatchClosed` |
| `FormationPolicy` | 同一 groupOrderId + policyVersion 唯一；规则必须明确 target、minimum、deadline、decay schedule、partial acceptance required；策略变更只能影响未锁定批次；人工覆盖必须有 approvalRef。 | `DefineFormationPolicy`, `EvaluateFormation`, `ApplyFormationOverride`, `CloseFormationPolicy` | `FormationPolicyDefined`, `FormationEvaluated`, `FormationOverrideApplied`, `FormationPolicyClosed` |

`BulkHold` 不直接保存物理座位。它保存一组 `HoldSlot`：`holdSlotId`、`holdId`、`segmentRef`、`classRef`、`interval`、`heldUntil`、`participantId?`、`slotStatus`。实际 `holdId` 来自 Capacity & Availability 的 `CapacityHeld`；失败原因来自 `CapacityHoldFailed.reason`，例如 `NO_AVAILABLE_CAPACITY` 或 `OVERLAPPING_HOLD`。衰减释放通过既有 `ReleaseHold` / `CapacityReleased` 事实闭环。

名单补全只存 `travelerId`、`travelerType`、`maskedDocumentNo` 或 `maskedDocumentRef`、`verificationSummaryRef`、`eligibilityRef`、`completionVersion`。未脱敏证件号、姓名全文、出生日期原文和 SIM raw response 不进入普通事件、日志或读模型。

## 6. 状态机

### `GroupOrder` 状态机

| State | Meaning | Allowed Next |
|---|---|---|
| `DRAFT` | 申请草稿或导入中，尚未提交审批。 | `SUBMITTED`, `CANCELLED` |
| `SUBMITTED` | 团体申请已提交，等待运营或自动审批。 | `APPROVED`, `REJECTED`, `CANCELLED`, `EXPIRED` |
| `APPROVED` | 运营批准目标数量、deadline 和部分成团规则。 | `BULK_HOLDING`, `CANCELLED`, `EXPIRED` |
| `BULK_HOLDING` | 正在向 Capacity 申请一组 Hold。 | `HOLD_ACTIVE`, `PARTIAL_HOLD_ACTIVE`, `FAILED`, `CANCELLED` |
| `HOLD_ACTIVE` | 批量占座达到批准数量。 | `ROSTER_COMPLETING`, `DECAYING`, `CANCELLED`, `EXPIRED` |
| `PARTIAL_HOLD_ACTIVE` | 只获得部分 Hold，等待运营/规则决定。 | `ROSTER_COMPLETING`, `FAILED`, `CANCELLED`, `DECAYING` |
| `ROSTER_COMPLETING` | 名单和实名资料分批补全中。 | `TICKETING_IN_PROGRESS`, `DECAYING`, `PARTIALLY_FORMED`, `FAILED`, `CANCELLED` |
| `TICKETING_IN_PROGRESS` | 已补全批次进入既有 Booking/Entitlement 链路。 | `FORMED`, `PARTIALLY_FORMED`, `DECAYING`, `FAILED`, `CANCELLED` |
| `DECAYING` | 超时或人数不足，正在释放部分名额。 | `ROSTER_COMPLETING`, `PARTIALLY_FORMED`, `FAILED`, `EXPIRED` |
| `FORMED` | 目标人数已满足并完成必要出票/订单确认。 | 终态，可查询安息 |
| `PARTIALLY_FORMED` | 达到最小成团人数但低于目标人数，已按规则/审批接受。 | 终态，可查询安息 |
| `REJECTED` | 审批拒绝。 | 终态，可查询安息 |
| `FAILED` | Hold、核验、支付/担保或出票无法满足，且无可接受补偿。 | 终态，可查询安息 |
| `CANCELLED` | 申请方或运营取消，并触发释放/补偿。 | 终态，可查询安息 |
| `EXPIRED` | 关键 deadline 错过且未形成有效部分成团。 | 终态，可查询安息 |

`REJECTED`、`FAILED`、`CANCELLED`、`EXPIRED` 不可逆；重新申请必须创建新的 `GroupOrder` 并引用原 `groupOrderId`。`MissedDeadline` 是不可逆事实；后续人工恢复只能追加 override 和新 deadline，不删除错过记录。`FORMED` / `PARTIALLY_FORMED` 后仍可查询 BulkHold、批次和参与者历史，但不得再增加新 participant；追加旅客应走新 GroupOrder 或普通订单。

### `BulkHold` 状态机

| State | Meaning | Allowed Next |
|---|---|---|
| `REQUESTED` | 已创建 BulkHold 意图，等待拆分或发送 Capacity Hold。 | `HOLDING`, `FAILED`, `CANCELLED` |
| `HOLDING` | 部分 Hold 请求进行中。 | `HELD`, `PARTIALLY_HELD`, `FAILED`, `DECAYING` |
| `HELD` | 已达到 requestedQuantity。 | `ALLOCATING`, `CONFIRMED`, `DECAYING`, `RELEASED`, `EXPIRED` |
| `PARTIALLY_HELD` | 获得的 Hold 少于 requestedQuantity。 | `ALLOCATING`, `DECAYING`, `FAILED`, `RELEASED` |
| `ALLOCATING` | HoldSlot 正在分配给已补全 participant。 | `CONFIRMED`, `DECAYING`, `RELEASED`, `EXPIRED` |
| `CONFIRMED` | 已被下游确认使用或转为正式占用。 | `RELEASED` |
| `DECAYING` | 按策略释放未使用或低优先级名额。 | `PARTIALLY_HELD`, `RELEASED`, `EXPIRED` |
| `RELEASED` | 所有可释放 HoldSlot 已释放。 | 终态，可查询安息 |
| `EXPIRED` | Hold TTL 到期或衰减窗口结束。 | 终态，可查询安息 |
| `FAILED` | Capacity 全部失败或幂等/策略冲突无法继续。 | 终态，可查询安息 |

`FAILED` 不可普通重试为 `HELD`；若需要重新占座，应创建新 `BulkHold`。`EXPIRED` 后晚到 `CapacityHeld` 必须进入冲突/人工队列，不得自动分配给 participant。

### `GroupParticipantRoster` 状态机

| State | Meaning | Allowed Next |
|---|---|---|
| `PLACEHOLDER` | 名额已创建，尚未补全实名资料。 | `COMPLETED`, `WITHDRAWN`, `EXPIRED` |
| `COMPLETED` | 已提供 TravelerRef 和脱敏证件摘要。 | `VERIFICATION_PENDING`, `REJECTED`, `WITHDRAWN` |
| `VERIFICATION_PENDING` | 等待核验摘要或风险结果。 | `VERIFIED`, `REJECTED`, `EXPIRED` |
| `VERIFIED` | 可进入 HoldSlot 分配和出票批次。 | `ASSIGNED_TO_HOLD`, `WITHDRAWN` |
| `ASSIGNED_TO_HOLD` | 已绑定 HoldSlot。 | `LOCKED_FOR_TICKETING`, `RELEASED` |
| `LOCKED_FOR_TICKETING` | 已进入 TicketingBatch，资料不可普通更改。 | `TICKETED`, `TICKETING_FAILED`, `RELEASED` |
| `TICKETED` | 已收到 EntitlementIssued 或等价批次完成事实。 | 终态，可查询安息 |
| `TICKETING_FAILED` | 该 participant 出票失败。 | 终态；可按新批次补偿但不改写本事实 |
| `REJECTED` | 资料、核验或风险不通过。 | 终态，可查询安息 |
| `WITHDRAWN` | 申请方移除该名额。 | 终态，可查询安息 |
| `EXPIRED` | 补全 deadline 错过。 | 终态，可查询安息 |
| `RELEASED` | 名额释放，HoldSlot 可释放或重分配。 | 终态，可查询安息 |

`REJECTED`、`TICKETING_FAILED`、`EXPIRED` 不可逆。重新提交同一旅客资料应产生新的 participant 版本或新 GroupOrder，并保留原失败链路。

### `TicketingBatch` 状态机

| State | Meaning | Allowed Next |
|---|---|---|
| `CREATED` | 批次已形成但未提交 Booking。 | `SUBMITTED`, `CANCELLED` |
| `SUBMITTED` | 已交给既有 Booking Saga。 | `RESERVING`, `FAILED`, `CANCELLED` |
| `RESERVING` | Booking/Capacity/Provider 预订确认中。 | `TICKETING`, `PARTIALLY_FAILED`, `FAILED` |
| `TICKETING` | Entitlement 签发中。 | `ISSUED`, `PARTIALLY_ISSUED`, `FAILED` |
| `PARTIALLY_FAILED` | 预订阶段部分失败，可拆分或重试。 | `TICKETING`, `PARTIALLY_ISSUED`, `FAILED`, `CANCELLED` |
| `ISSUED` | 批次全部出票成功。 | 终态，可查询安息 |
| `PARTIALLY_ISSUED` | 批次部分出票成功，失败项进入补偿。 | 终态，可查询安息 |
| `FAILED` | 批次无可保留成功项或达到重试上限。 | 终态，可查询安息 |
| `CANCELLED` | 批次取消。 | 终态，可查询安息 |

## 7. 命令和领域事件（含幂等键）

本节是 Group Booking 自己的命令和领域事件清单。它们不是现有 Capacity、Journey Order、Booking Orchestration 或 Entitlement 契约；跨上下文发布前必须进入 `docs/08-contracts/` 契约流程。

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `ApplyGroupOrder` | `GroupOrder` | `GroupOrderApplied` | `accountId + offerId + offerVersion + segmentRefsHash + targetParticipantCount + clientRequestId` 的规范化材料折叠 |
| `SubmitGroupOrderForApproval` | `GroupOrder` | `GroupOrderSubmittedForApproval` | `groupOrderId + applicationVersion` |
| `ApproveGroupOrder` | `GroupOrder` | `GroupOrderApproved` | `groupOrderId + approvalRef + approvedQuantity + policyVersion` |
| `RejectGroupOrder` | `GroupOrder` | `GroupOrderRejected` | `groupOrderId + approvalRef + rejectionCode` |
| `AttachGroupGuarantee` | `GroupOrder` | `GroupGuaranteeAttached` | `groupOrderId + paymentIntentId/groupGuaranteeRef + amount.currency + amount.minorUnits` |
| `OpenRosterCompletion` | `GroupOrder` | `RosterCompletionOpened` | `groupOrderId + rosterDeadlineAt + rosterVersion` |
| `AcceptPartialFormation` | `GroupOrder` | `PartialFormationAccepted` | `groupOrderId + acceptedCount + approvalRef/decisionRef` |
| `CancelGroupOrder` | `GroupOrder` | `GroupOrderCancelled` | `groupOrderId + cancelReason + requesterRef` |
| `ExpireGroupOrder` | `GroupOrder` | `GroupOrderExpired` | `groupOrderId + missedDeadlineType + deadlineAt` |
| `MarkGroupOrderFailed` | `GroupOrder` | `GroupOrderFailed` | `groupOrderId + failureCode + sourceEventId` |
| `CreateBulkHold` | `BulkHold` | `BulkHoldCreated` | `groupOrderId + approvedQuantity + segmentRefsHash + classRef + holdPolicyVersion` |
| `RecordCapacityHoldResult` | `BulkHold` | `BulkHoldCapacityRecorded` | `bulkHoldId + holdId/requestedHoldId + capacityEventId` |
| `AssignHoldSlot` | `BulkHold` | `HoldSlotAssigned` | `bulkHoldId + holdSlotId + participantId + rosterVersion` |
| `ReleaseHoldSlot` | `BulkHold` | `HoldSlotReleased` | `bulkHoldId + holdSlotId + releaseReason + sourceCommandId` |
| `ApplyDecayRelease` | `BulkHold` | `BulkHoldDecayed` | `bulkHoldId + decayWindow + decayStepNo + policyVersion` |
| `ConfirmBulkHoldUsage` | `BulkHold` | `BulkHoldUsageConfirmed` | `bulkHoldId + ticketingBatchId + confirmedHoldIdsHash` |
| `ExpireBulkHold` | `BulkHold` | `BulkHoldExpired` | `bulkHoldId + expiresAt + monitorRunId` |
| `MarkBulkHoldFailed` | `BulkHold` | `BulkHoldFailed` | `bulkHoldId + failureCode + capacityFailureHash` |
| `AddParticipantPlaceholders` | `GroupParticipantRoster` | `ParticipantPlaceholdersAdded` | `groupOrderId + placeholderCount + rosterVersion` |
| `CompleteParticipantIdentity` | `GroupParticipantRoster` | `ParticipantIdentityCompleted` | `groupOrderId + participantSeq + travelerId + identityMaterialFingerprint` |
| `RecordParticipantVerificationSummary` | `GroupParticipantRoster` | `ParticipantVerificationSummaryRecorded` | `participantId + verificationSummaryRef + verificationVersion` |
| `RejectParticipant` | `GroupParticipantRoster` | `ParticipantRejected` | `participantId + rejectionCode + evidenceRef` |
| `WithdrawParticipant` | `GroupParticipantRoster` | `ParticipantWithdrawn` | `participantId + withdrawReason + requesterRef` |
| `LockParticipantForTicketing` | `GroupParticipantRoster` | `ParticipantLockedForTicketing` | `participantId + holdSlotId + ticketingBatchId` |
| `ExpireIncompleteParticipants` | `GroupParticipantRoster` | `IncompleteParticipantsExpired` | `groupOrderId + rosterDeadlineAt + monitorRunId` |
| `CreateTicketingBatch` | `TicketingBatch` | `TicketingBatchCreated` | `groupOrderId + participantIdsHash + batchPurpose + batchNo` |
| `SubmitBatchToBooking` | `TicketingBatch` | `TicketingBatchSubmittedToBooking` | `ticketingBatchId + bookingSagaAttemptNo + participantIdsHash` |
| `RecordSegmentReservationResult` | `TicketingBatch` | `BatchSegmentReservationRecorded` | `ticketingBatchId + segmentBookingId + bookingEventId` |
| `RecordEntitlementResult` | `TicketingBatch` | `BatchEntitlementRecorded` | `ticketingBatchId + participantId + entitlementEventId` |
| `RetryTicketingBatch` | `TicketingBatch` | `TicketingBatchRetried` | `ticketingBatchId + retryAttemptNo + retryReason` |
| `CancelTicketingBatch` | `TicketingBatch` | `TicketingBatchCancelled` | `ticketingBatchId + cancelReason + sourceCommandId` |
| `CloseTicketingBatch` | `TicketingBatch` | `TicketingBatchClosed` | `ticketingBatchId + terminalState + closeReason` |
| `DefineFormationPolicy` | `FormationPolicy` | `FormationPolicyDefined` | `groupOrderId + policyVersion + target/minimum/deadlines hash` |
| `EvaluateFormation` | `FormationPolicy` | `FormationEvaluated` | `groupOrderId + policyVersion + evaluationAt + rosterCount + ticketedCount` |
| `ApplyFormationOverride` | `FormationPolicy` | `FormationOverrideApplied` | `groupOrderId + approvalRef + overrideVersion` |
| `CloseFormationPolicy` | `FormationPolicy` | `FormationPolicyClosed` | `groupOrderId + policyVersion + closeReason` |

幂等材料折叠规则：命令先把业务材料按稳定顺序规范化：数组排序、时间转 RFC3339 UTC、Money 使用 `currency + minorUnits`、枚举使用 SCREAMING_SNAKE、证件材料只使用 hash/masked/ref，再计算 materialHash。重复命令材料完全一致时返回既有结果；复用幂等键但材料不同必须拒绝为 `IDEMPOTENCY_KEY_REUSED` 或等价领域错误。Notes：本仓库 `docs/08-contracts/shared-primitives.md` 已采用 UUID-v7 形制；Group Booking 的 `groupOrderId`、`bulkHoldId`、`participantId`、`ticketingBatchId`、`formationPolicyId`、commandId、eventId、correlationId 均沿用 UUID-v7。仓储裁决以“业务幂等材料唯一索引 + UUID-v7 聚合 ID”为准，而不是信任客户端随机 UUID。

## 8. 策略和 Saga 参与点

### 团体申请与审批策略

- `targetParticipantCount` 必须 >=10；`minimumFormationCount` 默认也必须 >=10。小于 10 人应走普通 Journey Order/Booking 链路。
- 自动审批只允许低风险、低数量、在销售窗口内且有足够可用性证据的申请；高数量、临近发车、跨席别、重复证件摘要或风险 `HOLD` 必须进入运营审批。
- 审批结果只决定本域是否可创建 BulkHold，不承诺 Capacity 一定成功。Capacity 仍可能返回 `CapacityHoldFailed`。
- 手工审批、拒绝、超额保留、partial override 必须引用 Admin & Audit 的 `ManualActionApproved` 或 `AuditEntryRecorded` 事实。

### BulkHold 与超时衰减策略

- Wave D 基线不修改 Capacity 契约。BulkHold 命令在适配层拆分为多个现有 `HoldCapacity` / `POST /api/v1/capacity-holds` 调用，并消费 `CapacityHeld`、`CapacityHoldFailed`、`CapacityReleased`、`CapacityHoldExpired`。
- Hold TTL 分层：审批后初始 Hold 较短；名单补全达到阈值后可请求受控延长；出票批次锁定后未使用名额不再延长。
- DecayRelease 优先释放：未分配 HoldSlot、资料未补全 participant、风险阻断 participant、低优先级替补名额；不得释放已提交 TicketingBatch 的 HoldSlot，除非批次取消或失败。
- `MissedDeadline` 不可逆。错过 deadline 后即使后续资料补齐，也必须走人工 override 或新批次，不得抹掉超时记录。

### 名单补全与实名核验策略

- Group Booking 只保存补全结果和核验摘要引用，不拥有实名核验。当前 base 分支没有 Identity Verification 契约；上线前必须补齐对应 08-contracts 或以 Traveler Profile + Risk 的既有契约临时兜底。
- 补全批次允许多次提交，但同一 active roster 内同一 `travelerId` 或同一 credential hash 不得重复占用两个名额。
- 被 Risk `DENY` 或 `RiskBlockApplied` 覆盖的 participant 不能进入 TicketingBatch；`CHALLENGE` / `HOLD` 只暂停，不释放，直到 deadline 或挑战结果到达。
- 日志、事件、读模型中禁止未脱敏证件号、姓名全文、出生日期和 raw SIM response。

### 部分成团策略

- 达到 `minimumFormationCount` 且运营规则允许时，可在低于目标人数时形成 `PARTIALLY_FORMED`。
- 若已补全人数 < `minimumFormationCount`，不得自动部分成团；只能取消、失败、延长 deadline 或重新申请。
- 价格或团体折扣受人数影响时，必须重新获取 Fare & Pricing / Offer Management 快照；Group Booking 不重算差价。
- 接受部分成团前必须明确释放未使用 HoldSlot、通知申请方、冻结可出票 participant 集合，并记录 decisionRef/approvalRef。

### 出票链路裁决

- 裁决建议：不建设独立团体出票链。Group Booking 创建 `TicketingBatch` 后，按 participant 拆成既有 `StartBookingSaga` / `RequestSegmentReservation` / `IssueEntitlement` 能识别的普通 segment booking 输入。
- 原因：现有 Booking Orchestration 已拥有 Capacity、Provider、Payment 和 Entitlement 的 Saga 参与点；复制一套批量出票链会制造双写、双补偿和状态漂移。
- 本域新增的只是批次视图和幂等分组：同一 participant 的 `segmentBookingId`、`holdId`、`travelerRef`、`journeyOrderId` 可追踪回 `ticketingBatchId`。
- 批次部分失败时，成功 participant 保留票证，失败 participant 进入补偿、重试或释放；不得回滚已成功出票，除非 Post Sales/Entitlement 另行作废。

### Saga 参与点

- Group Booking 不拥有跨域长事务。它以本地域事件驱动：审批 → BulkHold → Roster → TicketingBatch；跨域动作通过适配器调用既有端点/命令并用 Inbox 消费结果。
- 与 Capacity：BulkHold 创建/释放是最关键的 Saga 参与点；释放失败进入重试和人工队列。
- 与 Journey Order：成团或部分成团后创建/关联 JourneyOrder；订单取消事件会触发本域取消或释放剩余名额。
- 与 Payment：担保或定金 captured/failed 只作为本域推进条件；资金补偿仍由 Payment/Post Sales 决定。
- 与 Entitlement：出票成功/失败更新批次；票证作废或悬挂只影响读模型和后续售后，不改写原批次事实。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `GroupOrderDashboardView` | `GroupOrderApplied`, `GroupOrderSubmittedForApproval`, `GroupOrderApproved`, `GroupOrderRejected`, `GroupOrderCancelled`, `GroupOrderExpired`, `GroupOrderFailed`, `PartialFormationAccepted` | 运营、客服、申请方门户 |
| `BulkHoldUtilizationView` | `BulkHoldCreated`, `BulkHoldCapacityRecorded`, `HoldSlotAssigned`, `HoldSlotReleased`, `BulkHoldDecayed`, `BulkHoldExpired`, Capacity `CapacityReleased`/`CapacityHoldExpired` 投影 | Capacity operations、运营、Reporting |
| `RosterCompletionView` | `ParticipantPlaceholdersAdded`, `ParticipantIdentityCompleted`, `ParticipantVerificationSummaryRecorded`, `ParticipantRejected`, `ParticipantWithdrawn`, `IncompleteParticipantsExpired` | 申请方、客服、运营、Risk |
| `TicketingBatchProgressView` | `TicketingBatchCreated`, `TicketingBatchSubmittedToBooking`, `BatchSegmentReservationRecorded`, `BatchEntitlementRecorded`, `TicketingBatchClosed`, Booking `SegmentReservationConfirmed`/`SegmentReservationFailed`, Entitlement `EntitlementIssued`/`EntitlementIssueFailed` 投影 | 申请方、客服、Booking operations |
| `PartialFormationDecisionView` | `FormationPolicyDefined`, `FormationEvaluated`, `FormationOverrideApplied`, `PartialFormationAccepted`, `BulkHoldDecayed` | 运营审批、客服、Reporting |
| `GroupBookingTimelineView` | 全部 Group Booking 事件 + 关联 Journey Order、Payment、Capacity、Booking、Entitlement 摘要 | Customer Service、Admin & Audit |
| `GroupBookingSlaSnapshot` | 审批时长、Hold 成功率、补全超时率、出票批次成功率、释放失败数 | Reporting、运营管理 |
| `DecayReleaseQueue` | `BulkHoldDecayed`, `HoldSlotReleased`, `BulkHoldExpired`, release retry results | Capacity operations、Admin & Audit |

读模型可冗余 groupOrderId、journeyOrderId、paymentIntentId、holdId、segmentBookingId、entitlementId、数量、状态、deadline、脱敏旅客展示名和 evidenceRef；不得保存未脱敏证件、原始名单文件、真实审批附件或完整支付凭据。写侧判断必须回到聚合命令，不能用读模型直接推进状态。

## 10. 外部系统和防腐层

Group Booking 的外部方一律模拟。防腐层遵循 Provider Integration 的设计原则：隔离外部语言、保存必要 raw 摘要、用稳定结果映射为领域事实，但不接真实网络、不需要真实凭据。

### SIM 网关组件

1. `SimApprovalGateway`：模拟运营审批或团体客户资质检查。输入为 groupOrder 摘要、数量、渠道、deadline、risk summary、seed；输出 `APPROVED`、`REJECTED`、`MANUAL_REVIEW_REQUIRED`、`LIMIT_REDUCED`。
2. `SimRosterImportGateway`：模拟名单文件解析和格式校验。输入为 seed、行数、列映射摘要和 masked/hash 材料；输出确定性的有效行、重复行、格式错误和敏感字段拒绝结果。
3. `SimGroupCustomerGateway`：模拟学校、旅行社、企业客户资质或团体协议检查。输出资质有效、过期、需人工复核或拒绝；不做真实网络调用。
4. Request Mapper：把本域命令转换成 SIM DTO，绝不把聚合内部状态或未脱敏证件泄露给 SIM 日志。
5. Response Mapper：把 SIM 响应映射为本域审批、名单校验或资质摘要；错误归类为 `BUSINESS_REJECTED`、`MANUAL_REVIEW_REQUIRED`、`RETRYABLE_TECHNICAL_ERROR`、`NON_RETRYABLE_TECHNICAL_ERROR`、`AMBIGUOUS_RESULT`。
6. Fault Injector：按 `faultSeedRef` 注入审批延迟、名单重复、格式错误、人工复核、掉单、查询恢复等场景。
7. Raw Archive：仅保存 seed、requestFingerprint、responseFingerprint、错误分类、版本和审计引用；禁止保存真实原始证件号、完整名单文件或外部凭据。

### 确定性行为

- 同一 `gatewayProfile + seedVersion + requestFingerprint` 必须返回相同结果，便于回放、测试和审计。
- SIM 不访问真实 HTTP、SDK、数据库或第三方系统；实现可为本地纯函数或本域进程内 adapter。
- 超时和 Ambiguous 结果由 seed 控制；副作用类动作不盲目重放，只能使用相同 idempotency key 查询或进入人工。
- SIM 输出枚举使用 SCREAMING_SNAKE；时间均为 RFC3339 UTC；金额若出现必须使用 `{currency, minorUnits}`。
- ACL 层不得记录 PII 明文；错误消息面向用户展示前必须经 Customer Service/Notification 模板处理。

铁路供应商、PNR、票号、供应侧确认和外部凭证仍由 Provider Integration / Booking Orchestration / Entitlement 处理。Group Booking 的 SIM 网关只模拟团体审批、客户资质和名单导入，不接入供应商预订或出票接口。

## 11. 当前服务迁移影响

| Current Capability / Service Area | Migration Impact |
|---|---|
| 普通订单批量下单脚本 | 收敛为 `ApplyGroupOrder` + `CreateBulkHold` + `TicketingBatch`；禁止直接循环创建普通订单绕过审批和 BulkHold。 |
| 运营人工团体审批表 | 迁移为 `OperationalApproval` 和 Admin & Audit `ManualActionApproved` 引用；审批原因、数量、deadline 和 override 必须可审计。 |
| 线下名单 Excel/CSV 导入 | 进入 `SimRosterImportGateway` 和 `GroupParticipantRoster`；只保存脱敏/哈希材料和导入摘要，原文件进入受控 evidence store。 |
| 现有 Capacity 单人 Hold 调用 | BulkHold adapter 先拆成多个既有 `POST /api/v1/capacity-holds` 请求；未来如引入容量批量契约，再替换 adapter，不改 GroupOrder 不变量。 |
| 普通 Booking Saga | 保持为出票执行链路；新增 `ticketingBatchId` correlation 和批次读模型，不复制 Saga。 |
| Journey Order 创建 | 成团后可创建一个团体 JourneyOrder 或按批次创建多个 JourneyOrder，需保留 groupOrderId 关联；Journey Order 状态仍由其自身事件推进。 |
| 支付定金/担保 | 通过 Payment 既有 PaymentIntent/Capture/Failure 事实引用；Group Booking 不保存支付渠道详情。 |
| 客服查询 | 新增 `GroupBookingTimelineView`，聚合审批、Hold、名单、出票批次、释放和关联订单摘要。 |
| 报表统计 | Reporting 从本域事件流构建团体转化率、占座浪费率、补全超时率、部分成团率和出票批次 SLA。 |
| 日志与审计 | 所有跨域调用带 correlationId、causationId、idempotencyKey；名单和证件日志必须脱敏，人工操作进入 Admin & Audit。 |

## 12. 验收标准

- 文档位于 `docs/02-domains/group-booking.md`，Metadata `Status` 为 `proposed-ddd-baseline`，High-Level Inputs 引用 `docs/adr/0003-commercial-realism-scope.md`。
- 12 节结构齐全：Metadata、领域目标、边界 In-Out Scope、统一语言、上下游契约、聚合设计、状态机、命令和领域事件、策略和 Saga 参与点、读模型、外部系统和防腐层、当前服务迁移影响、验收标准。
- 上下游契约只引用 `docs/08-contracts/` 中真实存在的事件、命令或端点；Identity Verification 契约缺口被明确记录，未杜撰事件或端点。
- GroupOrder 明确约束 `targetParticipantCount >= 10` 和 `minimumFormationCount >= 10`；普通小团体走既有单客订单链路。
- BulkHold 被设计为 Capacity Hold 的预留扩展语义；库存强一致和区间冲突仍由 Capacity & Availability 拥有。
- 审批流覆盖申请 → 运营审批 → 占座 → 分批实名补全 → 分批出票 → 完成/部分成团/失败/取消，且 Missed/Failed 类不可逆、结局态可查询安息。
- 命令表包含幂等键和材料折叠规则；Notes 记录 UUID-v7 形制和仓储裁决方式。
- 部分成团规则明确目标人数、最小人数、deadline、价格重算和运营 override。
- 出票裁决建议明确：复用既有 Booking Orchestration + Entitlement 的单客链路，以 TicketingBatch 做批量治理，不新建独立批量出票链。
- 外部方一律 SIM：审批、客户资质、名单导入均确定性、可种子化、无真实网络、无真实凭据，对照 Provider Integration 的防腐层思想。
- 只新增设计文档，不新增契约文档、代码或服务目录；`make check` 应不受 contract-lint 与 skeleton-check 影响并全绿。
