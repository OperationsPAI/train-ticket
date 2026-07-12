# Travel Insurance Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Travel Insurance |
| Status | proposed-ddd-baseline |
| Phase | ADR-0003 wave C |
| Last Updated | 2026-07-10 |
| High-Level Inputs | `docs/adr/0003-commercial-realism-scope.md`, `docs/02-domains/ancillary-service.md`, `docs/02-domains/fulfillment.md`, `docs/02-domains/disruption-recovery.md`, `docs/02-domains/payment-channel.md`, `docs/02-domains/wallet-promotion.md` |

## 1. 领域目标

Travel Insurance 负责 ADR-0003 Wave C 的出行保险商业语义：静态保险产品、随订单附加购买后的保单出单、基于出行事实的自动理赔、客服人工理赔，以及赔付出口建议。当前只覆盖 `DELAY_INSURANCE`（延误险）和 `ACCIDENT_INSURANCE`（意外险）；产品价格为静价，金额使用 Money `{currency, minorUnits}`。

本域独立存在的原因是保险有产品条款、承保确认、保障期间、退保、理赔证据、赔付裁决和模拟承保网关等不变量。Ancillary Service 仍拥有 `INSURANCE` 附加服务的报价、选择和附加订单项生命周期；Travel Insurance 拥有保单、理赔和赔付建议。Payment、Wallet / Promotion 和 Payment Channel 只执行各自资金或权益语义，本域不执行资金或钱包账本。

核心目标：

1. 管理 `InsuranceProduct` 的静态产品、保障范围、价格、销售窗口、退保规则和理赔规则版本。
2. 以 `Policy` 表达随 Journey Order / Ancillary Order Item 附加出单的保单，通过确定性 SIM 承保网关生成 `policyNumber`。
3. 以 `Claim` 表达自动理赔和人工理赔；延误事实自动理赔消费 Fulfillment 段级事件与 Disruption Recovery 事实，人工理赔进入 Customer Service 支撑态。
4. 以 `PayoutAdvice` 给出赔付出口裁决建议：Wallet 权益补偿或 Payment / Payment Channel 原路退付建议；本域只建议，不执行。
5. 明确已购保单在订单退票/取消时的退保规则，避免退保与理赔重复赔付。

## 2. 边界 In-Out Scope

### In Scope

- `InsuranceProduct`：延误险、意外险，静态价格、保障条款、销售窗口、交通方式、保障开始/结束规则、退保规则和理赔规则版本。
- `Policy`：保单附加到订单、旅客、Segment 或 Ancillary order item；出单、承保确认、承保失败、退保、失效和终态查询。
- SIM 承保网关：确定性出单、查询、作废/退保结果和故障注入；无真实保险公司网络、无真实凭据。
- `Claim`：延误事实自动理赔；人工理赔登记、证据引用、客服态协作、审核、批准、拒绝、失败和关闭。
- 延误事实核实：消费既有 Fulfillment 事件 `SegmentDelayed`、`SegmentArrived`、`SegmentCancelled`，以及 Disruption Recovery 事件 `DisruptionReported`、`IncidentOpened`、`RecoveryCaseOpened`、`RecoveryOptionsGenerated`、`RecoveryOptionSelected`、`RecoveryCompleted`、`RecoveryFailed`。
- 赔付裁决建议：生成 Wallet / Promotion `POST /api/v1/benefits` 或 Payment `POST /api/v1/refunds` 的建议材料、金额、原因和幂等材料；不直接执行。
- 订单退票/取消联动：根据保单状态、保障是否开始、是否已有理赔、供应商规则和 Post Sales 原因给出退保、保留或人工审核建议。
- 面向客服、运营、财务和报表的保单、理赔、赔付建议只读视图。

### Out of Scope

- 不拥有附加服务目录、附加订单项选择、附加服务退款建议端点；这些归 Ancillary Service。
- 不创建、取消或调整 Journey Order；不拥有主订单状态。
- 不执行 Payment 退款、Payment Channel 原路退回、Wallet benefit 发放、钱包冻结或核销；本域只给出裁决建议和原因。
- 不计算主票票价、退票费、改签费、保险动态价格、税费或营销折扣。
- 不打开真实保险公司、经纪平台、银行、支付通道或政府接口；所有保险外部方为 SIM。
- 不保存未脱敏证件、完整联系方式、医疗材料原文或事故文书；只保存 evidenceRef、摘要、hash 和访问级别。
- 不替代 Customer Service 的工单、人工证据和操作权限；人工理赔由客服态承载，本域消费或引用其结果。
- 不新增或修改 `docs/08-contracts/` 契约文档；本文是领域设计基线，新命令和事件只在第 7 节出现。

## 3. 统一语言

| Term | Definition | Notes |
|---|---|---|
| `InsuranceProduct` | 平台销售的保险产品定义。 | 当前仅 `DELAY_INSURANCE`、`ACCIDENT_INSURANCE`，静价。 |
| `CoverageRule` | 保障责任、保障窗口、免赔条件、赔付额度和证据要求的版本化规则。 | 产品发布后不可原地修改，只能发布新版本。 |
| `Premium` | 保险保费。 | Money `{currency, minorUnits}`，禁止浮点数。 |
| `Policy` | 已购保险合同在平台内的聚合根。 | 绑定订单、旅客、Segment/行程和承保网关引用。 |
| `PolicyNumber` | SIM 承保网关返回的保单号。 | 不是 `journeyOrderId`、`ancillaryOrderItemId` 或 Payment 引用。 |
| `Underwriting` | 出单/承保动作。 | 本域只接 SIM 网关，确定性、可查询、无真实网络。 |
| `Surrender` | 保单退保/作废。 | 订单退票、主票取消或人工操作可触发；不等于 Payment 退款已完成。 |
| `Claim` | 对某张保单的一次理赔请求或自动理赔案件。 | 同一保单同一触发事实按规则防重。 |
| `DelayFact` | 由 Fulfillment 和 Disruption Recovery 归一化得到的延误/取消事实。 | 事件名必须来自第 4 节核实过的真实契约。 |
| `ManualClaim` | 需要客服证据、人工审核或事故证明的理赔。 | 通过 Customer Service `OpenSupportCase` / `RequestManualAction` 协作。 |
| `PayoutAdvice` | 本域对赔付出口、金额、原因和执行目标的裁决建议。 | 可建议 Wallet 权益或 Payment 退款；资金/权益执行不在本域。 |
| `Terminal Rest` | 终态可查询安息。 | `PAYOUT_RECOMMENDED`、`REJECTED`、`FAILED`、`CLOSED` 等不可由普通命令反转。 |

## 4. 上下游契约

本节只引用 `docs/08-contracts/` 已存在的域、事件、命令或端点名称。Travel Insurance 自己的新命令和新领域事件只在第 7 节出现；跨上下文契约需要后续单独修改 `docs/08-contracts/`，本文不提前杜撰。

### Upstream

| Upstream Context | Consumed Contract Verified In `docs/08-contracts/` | How Travel Insurance Uses It |
|---|---|---|
| Ancillary Service | Events `AncillaryCatalogItemPublished`, `AncillaryOfferQuoted`, `AncillaryOrderItemSelected`, `AncillaryOrderItemConfirmed`, `AncillaryOrderItemFulfillmentReady`, `AncillaryOrderItemFulfilled`, `AncillaryOrderItemFailed`, `AncillaryOrderItemCancelled`, `AncillaryOrderItemRefundPending`, `AncillaryOrderItemRefunded`, `AncillaryFulfillmentFactRecorded`; endpoints `POST /api/v1/ancillary-offers`, `POST /api/v1/ancillary-offers/{ancillaryOfferId}/quote`, `POST /api/v1/ancillary-offers/{ancillaryOfferId}/select`, `POST /api/v1/ancillary-order-items/{ancillaryOrderItemId}/confirm`, `POST /api/v1/ancillary-order-items/{ancillaryOrderItemId}/cancel`, `POST /api/v1/ancillary-order-items/{ancillaryOrderItemId}/refund-suggestions`, `POST /api/v1/ancillary-order-items/{ancillaryOrderItemId}/refunded`, `POST /api/v1/ancillary-order-items/{ancillaryOrderItemId}/fulfillment-facts`. | 保险作为附加服务被选择、确认和取消；本域将保险订单项映射成保单出单意图，并把退保/理赔事实回填为保险履约或退款建议。 |
| Journey Order | Events `JourneyOrderCreated`, `JourneyOrderPendingPayment`, `JourneyOrderPaymentRecorded`, `JourneyOrderConfirmed`, `JourneyOrderCancelled`, `JourneyOrderPostSalesAdjusted`; endpoints `POST /api/v1/journey-orders`, `GET /api/v1/journey-orders/{orderId}`, `POST /api/v1/journey-orders/{orderId}/cancel`. | 订单、旅客、Segment、支付满足和取消事实是保单绑定、承保生效和退保评估输入；本域不修改订单。 |
| Payment | Events `PaymentIntentCreated`, `PaymentAuthorized`, `PaymentCaptured`, `PaymentFailed`, `PaymentIntentCancelled`, `PaymentIntentExpired`, `RefundSettled`, `RefundFailed`; endpoints `POST /api/v1/payment-intents`, `POST /api/v1/payment-intents/{paymentIntentId}/capture`, `POST /api/v1/refunds`, `GET /api/v1/payment-intents/{paymentIntentId}`, `GET /api/v1/refunds/{refundId}`. | 保费支付满足和退款结果是保单生效、退保完成和赔付建议调和输入；Payment 仍拥有资金状态。 |
| Fulfillment | Events `BoardingVerified`, `NoShowRecorded`, `FulfillmentCompleted`, `EvidenceDisputeOpened`, `EvidenceDisputeResolved`, `SegmentDelayed`, `SegmentArrived`, `SegmentCancelled`; endpoints `POST /api/v1/fulfillment-records/boarding`, `POST /api/v1/fulfillment-records/no-show`, `POST /api/v1/fulfillment-records/completions`, `GET /api/v1/fulfillment-records/{fulfillmentRecordId}`, `POST /api/v1/segment-status`. | 延误险自动理赔只消费已核实的段级运营事件：`SegmentDelayed`、`SegmentArrived`、`SegmentCancelled`；上车、完成和 no-show 事实用于判断旅客是否在保障范围内。 |
| Disruption Recovery | Events `DisruptionReported`, `IncidentOpened`, `RecoveryCaseOpened`, `RecoveryOptionsGenerated`, `RecoveryOptionSelected`, `RecoveryExecutionStarted`, `RecoveryCompleted`, `RecoveryFailed`, `RecoveryCaseClosed`, `ServiceAlertPublished`; endpoints `POST /api/v1/disruptions`, `GET /api/v1/incidents/{incidentId}`, `GET /api/v1/recovery-cases/{caseId}`, `GET /api/v1/incidents/{incidentId}/recovery-cases`, `POST /api/v1/recovery-cases/{caseId}/select-option`, `POST /api/v1/recovery-cases/{caseId}/close`. | 平台确认的异常事实、恢复案例和用户选择用于理赔证据、去重和避免与已执行恢复补偿重复赔付。 |
| Post Sales | Events `PostSalesCaseOpened`, `PostSalesRequested`, `PostSalesEligibilityEvaluated`, `PostSalesDecisionQuoted`, `PostSalesApproved`, `PostSalesExecutionStarted`, `PostSalesApplied`, `PostSalesFailed`, `ChangeApplied`; endpoint `POST /api/v1/post-sales-cases`, `GET /api/v1/post-sales-cases/{caseId}`. | 退票/改签/退款案例触发保单退保评估；已应用的售后结果是退保、保留或人工审核依据。 |
| Customer Service | Events `SupportCaseOpened`, `EvidenceAttached`, `ManualActionRequested`, `ManualActionResultRecorded`, `SupportCaseResolved`, `SupportCaseClosed`; endpoints `POST /api/v1/support-cases`, `POST /api/v1/support-cases/{caseId}/evidence`, `POST /api/v1/support-cases/{caseId}/manual-action-requests`, `POST /api/v1/support-cases/{caseId}/resolve`, `POST /api/v1/support-cases/{caseId}/close`. | 人工理赔和意外险事故材料走客服态；本域只保存 case/evidence/action 引用和审核结果摘要。 |

### Downstream

| Downstream Context | Published or Called Existing Contract | How Travel Insurance Uses It Without Changing Contract |
|---|---|---|
| Ancillary Service | `POST /api/v1/ancillary-order-items/{ancillaryOrderItemId}/fulfillment-facts`, `POST /api/v1/ancillary-order-items/{ancillaryOrderItemId}/refund-suggestions`, `POST /api/v1/ancillary-order-items/{ancillaryOrderItemId}/refunded`; events `AncillaryFulfillmentFactRecorded`, `AncillaryOrderItemRefundPending`, `AncillaryOrderItemRefunded`. | 保单出单成功可建议记录 `factType=INSURANCE_ACTIVATED`；退保成功可建议记录 `factType=INSURANCE_VOIDED` 或退款状态。实际附加订单项状态仍由 Ancillary Service 维护。 |
| Wallet / Promotion | Endpoint `POST /api/v1/benefits`; events `BenefitIssued`, `BenefitReserved`, `BenefitRedeemed`, `BenefitReservationReleased`, `BenefitExpired`, `BenefitRevoked`, `BenefitRedemptionReversed`. | 小额延误赔付可建议发放权益/补偿金。真实 `issuanceSource` 仅有 `MANUAL_OPS`、`POST_SALES_COMP`、`DISRUPTION_COMP`；保险来源枚举未存在前，在线执行需阻断或走客服人工路径，不能伪装来源。 |
| Payment | Endpoint `POST /api/v1/refunds`; events `RefundSettled`, `RefundFailed`. | 退保保费退回或现金赔付建议可指向 Payment refund。Payment 决定退款 intent、状态和失败处理；本域只记录建议与结果引用。 |
| Payment Channel | Domain design `docs/02-domains/payment-channel.md` defines original-route `ChannelRefund` via `ChannelOrder` / `ChannelRefund` SIM ACL. No `docs/08-contracts/` contract exists yet. | 当赔付建议要求原路退付时，实际执行仍应通过 Payment 未来 channel handoff；本设计不直接调用 Payment Channel，也不新增契约。 |
| Customer Service | `POST /api/v1/support-cases`, `POST /api/v1/support-cases/{caseId}/evidence`, `POST /api/v1/support-cases/{caseId}/manual-action-requests`; events `SupportCaseOpened`, `EvidenceAttached`, `ManualActionRequested`, `ManualActionResultRecorded`. | 人工理赔、异常赔付、合同争议和来源枚举阻断时打开或推进客服工单。 |
| Notification | Bus-only command `ScheduleNotification`; events `NotificationScheduled`, `NotificationDispatched`, `NotificationDelivered`, `NotificationFailed`, `NotificationCancelled`. | 出单成功、承保失败、理赔受理、理赔拒绝和赔付建议完成只发送触达意图；通知失败不回滚保单或理赔。 |
| Reporting | Endpoints `GET /api/v1/metrics`, `GET /api/v1/metrics/{metricId}`; events `MetricDefined`, `MetricVersionPublished`, `ReadModelRebuilt`. | 保险转化率、承保成功率、理赔率、自动理赔时效、赔付出口分布进入 Reporting；Reporting 不回写保险聚合。 |

### 延误事实事件名核实 Notes

- Fulfillment 当前真实段级事件是 `SegmentDelayed`、`SegmentArrived`、`SegmentCancelled`，触发来源为 `POST /api/v1/segment-status`，不是 `TrainDelayed`、`FlightDelayed` 或 `SegmentDelayConfirmed`。
- Disruption Recovery 当前真实异常事件是 `DisruptionReported`、`IncidentOpened`、`RecoveryCaseOpened`、`RecoveryOptionsGenerated`、`RecoveryOptionSelected`、`RecoveryExecutionStarted`、`RecoveryCompleted`、`RecoveryFailed`、`RecoveryCaseClosed`、`ServiceAlertPublished`。
- 自动理赔只使用上述既有事件和本域内部归一化 `DelayFact`，不得在上下游表中引用未存在的新跨域事件。

## 5. 聚合设计

| Aggregate Root | Responsibilities | Invariants |
|---|---|---|
| `InsuranceProduct` | 管理保险产品目录、静态保费、保障规则、销售窗口、退保规则和理赔规则版本。 | `productCode` + `version` 唯一；只允许 `DELAY_INSURANCE`、`ACCIDENT_INSURANCE`；Published 版本价格、条款、保障额度和退保规则不可原地修改；Money `minorUnits` 必须为正；销售窗口和保障窗口使用 RFC3339 UTC 且不能倒置；终态 `RETIRED`、`SUPERSEDED` 可查询安息。 |
| `Policy` | 管理保单附加购买、承保请求、SIM 出单结果、生效、退保、失效和与 Ancillary item 的关联。 | 每个 `ancillaryOrderItemId` + `travelerRef` + `segmentScopeHash` + `productVersion` 只能有一个非终态保单；保费支付满足前不得生效；承保成功后 `policyNumber` 不可改；`ACTIVE` 后保障范围不可扩张；已有已批准或赔付建议中的理赔时不得自动退保；`SURRENDERED`、`EXPIRED`、`UNDERWRITING_FAILED`、`CLOSED` 为终态，可查询安息。 |
| `Claim` | 管理自动/人工理赔的证据、审核、去重、批准、拒绝、失败、赔付建议和关闭。 | 同一 `policyId` + `claimType` + `triggerFactKey` 只能有一个 active claim；自动延误理赔必须有 Fulfillment 段级事实和/或 Disruption Recovery 事实；人工理赔必须有 Customer Service case/evidence 引用；赔付金额不得超过产品规则额度；`REJECTED`、`FAILED`、`CLOSED` 不可由普通命令反转。 |
| `PayoutAdvice` | 表达赔付出口建议、金额、执行目标、幂等材料、执行引用和调和结果。 | 必须绑定已批准 `Claim` 或合规退保评估；`amount.minorUnits` 为正且币种与产品/保费规则一致；出口只能为 `WALLET_BENEFIT`、`PAYMENT_REFUND`、`MANUAL_REVIEW`；同一 claim 只能有一个 accepted advice，重算必须生成新版本并保留旧版本；`EXECUTED_RECORDED`、`REJECTED`、`FAILED`、`CLOSED` 为终态。 |
| `UnderwritingRequestLog` | 记录 SIM 承保、查询、作废/退保请求和确定性响应摘要。 | 同一 `policyId` + operation + idempotencyKey 唯一；创建/作废类副作用请求超时后不得盲目重放新外部意图，优先查询；raw archive 只保留摘要、hash、seedRef 和映射版本。 |

### 5.1 关键字段

- `InsuranceProduct`：`insuranceProductId`、`productCode`、`version`、`premium`、`coverageLimit`、`coverageRuleVersion`、`claimRuleVersion`、`surrenderRuleVersion`、`salesWindow`、`status`。
- `Policy`：`policyId`、`policyNumber`、`productCode`、`productVersion`、`premium`、`coverageLimit`、`journeyOrderId`、`ancillaryOrderItemId`、`accountId`、`travelerRef`、`segmentRefs[]`、`paymentIntentId?`、`coverageStartAt`、`coverageEndAt`、`status`、`underwritingSeedRef`、`aggregateVersion`。
- `Claim`：`claimId`、`policyId`、`claimType` (`DELAY_AUTO`, `ACCIDENT_MANUAL`, `SERVICE_FAILURE_MANUAL`)、`triggerFactKey`、`delayFact`、`supportCaseId?`、`evidenceRefs[]`、`claimedAmount`、`approvedAmount`、`payoutAdviceId?`、`status`。
- `PayoutAdvice`：`payoutAdviceId`、`claimId`、`policyId`、`payoutTarget` (`WALLET_BENEFIT`, `PAYMENT_REFUND`, `MANUAL_REVIEW`)、`amount`、`reasonCode`、`idempotencyKey`、`decisionVersion`、`walletBenefitRequest?`、`paymentRefundRequest?`、`executionRef?`、`status`。

## 6. 状态机

### 6.1 `InsuranceProduct` 状态机

| State | Meaning | Allowed Next |
|---|---|---|
| `DRAFT` | 产品、条款和价格正在配置。 | `PUBLISHED`, `RETIRED` |
| `PUBLISHED` | 可被 Ancillary Service/报价流程引用销售。 | `SUSPENDED`, `SUPERSEDED`, `RETIRED` |
| `SUSPENDED` | 暂停新销售；存量保单继续按原条款解释。 | `PUBLISHED`, `SUPERSEDED`, `RETIRED` |
| `SUPERSEDED` | 被新版本替代。 | `RETIRED` |
| `RETIRED` | 不再销售。 | 终态，可查询安息 |

`PUBLISHED` 后价格、责任、免赔和理赔阈值不可原地修改；必须新版本 `SUPERSEDED`。

### 6.2 `Policy` 状态机

| State | Meaning | Allowed Next |
|---|---|---|
| `PURCHASE_SELECTED` | 保险作为附加服务被选择，但保费和订单条件未满足。 | `AWAITING_PREMIUM_CAPTURE`, `UNDERWRITING_FAILED`, `CLOSED` |
| `AWAITING_PREMIUM_CAPTURE` | 等待 Payment `PaymentCaptured` 或订单支付满足事实。 | `UNDERWRITING_REQUESTED`, `SURRENDER_REQUESTED`, `UNDERWRITING_FAILED`, `CLOSED` |
| `UNDERWRITING_REQUESTED` | 已向 SIM 承保网关提交出单。 | `UNDERWRITTEN`, `UNDERWRITING_FAILED`, `SURRENDER_REQUESTED` |
| `UNDERWRITTEN` | SIM 已返回保单号，等待保障开始条件。 | `ACTIVE`, `SURRENDER_REQUESTED`, `EXPIRED` |
| `ACTIVE` | 保单保障中。 | `SURRENDER_REQUESTED`, `EXPIRED`, `CLOSED` |
| `SURRENDER_REQUESTED` | 已按退票/取消/人工原因请求退保或作废。 | `SURRENDERED`, `UNDERWRITING_FAILED`, `CLOSED` |
| `SURRENDERED` | 保单退保/作废完成。 | 终态，可查询安息 |
| `UNDERWRITING_FAILED` | SIM 明确拒保或不可恢复失败。 | 终态，可查询安息 |
| `EXPIRED` | 保障期结束且无未结理赔。 | `CLOSED` |
| `CLOSED` | 历史保单归档。 | 终态，可查询安息 |

规则：`UNDERWRITING_FAILED` 不可逆；相同幂等键重放只返回原失败。出单请求若超时或疑似掉单，不创建新保单意图，必须 `QueryUnderwritingStatus`。`SURRENDERED` 不回到 `ACTIVE`；错误退保只能开人工 Case 和新保单/补偿流程。

### 6.3 `Claim` 状态机

| State | Meaning | Allowed Next |
|---|---|---|
| `OPENED` | 理赔已创建，事实或人工材料初步存在。 | `EVIDENCE_COLLECTING`, `AUTO_VERIFYING`, `MANUAL_REVIEW`, `REJECTED` |
| `EVIDENCE_COLLECTING` | 等待 Fulfillment / Disruption / Customer Service 证据。 | `AUTO_VERIFYING`, `MANUAL_REVIEW`, `FAILED`, `REJECTED` |
| `AUTO_VERIFYING` | 自动核实延误、取消、履约和重复赔付规则。 | `APPROVED`, `REJECTED`, `MANUAL_REVIEW`, `FAILED` |
| `MANUAL_REVIEW` | 客服或审核员处理人工理赔/争议。 | `APPROVED`, `REJECTED`, `FAILED` |
| `APPROVED` | 理赔责任和金额已批准。 | `PAYOUT_RECOMMENDED`, `CLOSED` |
| `PAYOUT_RECOMMENDED` | 已生成赔付出口建议，等待执行或记录结果。 | `CLOSED`, `FAILED` |
| `REJECTED` | 明确不赔。 | `CLOSED` |
| `FAILED` | 系统、合同阻断或证据冲突导致不能自动收敛。 | `CLOSED` |
| `CLOSED` | 案件安息。 | 终态，可查询安息 |

`REJECTED`、`FAILED`、`CLOSED` 不可由普通命令改回审核中；如有新证据，必须创建新 claim revision 或 Customer Service 重开人工 Case，不能改写历史裁决。错过理赔窗口的自动案件进入 `FAILED` 或 `REJECTED` 后不可反写成功。

### 6.4 `PayoutAdvice` 状态机

| State | Meaning | Allowed Next |
|---|---|---|
| `DRAFTED` | 已计算候选出口和金额。 | `RECOMMENDED`, `BLOCKED_BY_CONTRACT`, `REJECTED` |
| `RECOMMENDED` | 裁决建议已冻结，可由下游执行。 | `EXECUTION_REQUESTED`, `EXECUTED_RECORDED`, `FAILED`, `CLOSED` |
| `BLOCKED_BY_CONTRACT` | 现有 Wallet/Payment 契约不支持直接执行该建议。 | `EXECUTION_REQUESTED`, `CLOSED` |
| `EXECUTION_REQUESTED` | 已把建议交给受控执行方或客服动作。 | `EXECUTED_RECORDED`, `FAILED` |
| `EXECUTED_RECORDED` | 已记录 Wallet benefit 或 Payment refund 结果引用。 | `CLOSED` |
| `REJECTED` | 裁决建议被审核拒绝。 | `CLOSED` |
| `FAILED` | 执行或调和失败。 | `CLOSED` |
| `CLOSED` | 建议归档。 | 终态，可查询安息 |

`FAILED`/`BLOCKED_BY_CONTRACT` 不能被静默当作已赔付；只能通过客服、Payment 或 Wallet 的真实结果引用推进。

## 7. 命令和领域事件（含幂等键）

本节是 Travel Insurance 自己的命令和事件清单。它们不是现有 Ancillary、Payment、Wallet、Fulfillment、Disruption Recovery 或 Customer Service 契约；跨上下文发布前必须另行进入 `docs/08-contracts/` 契约流程。

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `DraftInsuranceProduct` | `InsuranceProduct` | `InsuranceProductDrafted` | productCode + draftVersion |
| `PublishInsuranceProduct` | `InsuranceProduct` | `InsuranceProductPublished` | insuranceProductId + version + approvalRef |
| `SuspendInsuranceProduct` | `InsuranceProduct` | `InsuranceProductSuspended` | insuranceProductId + reasonCode + operatorRef |
| `SupersedeInsuranceProduct` | `InsuranceProduct` | `InsuranceProductSuperseded` | insuranceProductId + replacementProductId + approvalRef |
| `SelectPolicyForOrder` | `Policy` | `PolicySelectionRecorded` | journeyOrderId + ancillaryOrderItemId + travelerRef + productVersion + clientRequestId 的规范化材料折叠 |
| `RecordPremiumCaptured` | `Policy` | `PolicyPremiumCaptured` | policyId + paymentIntentId + payment event id |
| `RequestUnderwriting` | `Policy` / `UnderwritingRequestLog` | `PolicyUnderwritingRequested` | policyId + productVersion + premium fingerprint + underwritingAttemptNo |
| `ApplyUnderwritingSucceeded` | `Policy` | `PolicyIssued` | policyId + policyNumber + SIM response hash |
| `ApplyUnderwritingFailed` | `Policy` | `PolicyUnderwritingFailed` | policyId + providerErrorCode + terminalReason |
| `QueryUnderwritingStatus` | `UnderwritingRequestLog` | `UnderwritingStatusQueried` 或 `PolicyIssueRecovered` | policyId + queryAttemptNo |
| `ActivatePolicy` | `Policy` | `PolicyActivated` | policyId + coverageStartAt + activationRuleVersion |
| `RequestPolicySurrender` | `Policy` | `PolicySurrenderRequested` | policyId + surrenderReason + sourceCaseOrEventId |
| `ApplyPolicySurrendered` | `Policy` | `PolicySurrendered` | policyId + surrenderRef + refundable premium fingerprint |
| `ExpirePolicy` | `Policy` | `PolicyExpired` | policyId + coverageEndAt bucket |
| `OpenAutoDelayClaim` | `Claim` | `ClaimOpened` | policyId + DELAY_AUTO + triggerFactKey |
| `AttachDelayFact` | `Claim` | `ClaimEvidenceAttached` | claimId + sourceEventType + sourceEventId |
| `VerifyDelayClaim` | `Claim` | `ClaimAutoVerified` 或 `ClaimRejected` | claimId + delayRuleVersion + evidenceHash |
| `OpenManualClaim` | `Claim` | `ClaimOpened` | policyId + claimType + supportCaseId + evidenceHash |
| `AttachCustomerServiceEvidence` | `Claim` | `ClaimEvidenceAttached` | claimId + supportCaseId + evidenceId |
| `ReviewManualClaim` | `Claim` | `ClaimApproved` 或 `ClaimRejected` | claimId + reviewerRef + reviewVersion |
| `GeneratePayoutAdvice` | `PayoutAdvice` | `PayoutAdviceRecommended` 或 `PayoutAdviceBlocked` | UUID-v7 from folded payout material; see Notes |
| `RecordPayoutExecutionResult` | `PayoutAdvice` | `PayoutExecutionRecorded` 或 `PayoutExecutionFailed` | payoutAdviceId + executionRef + downstream event id |
| `CloseClaim` | `Claim` | `ClaimClosed` | claimId + closeReason + finalStatus |

Notes:

- `SelectPolicyForOrder` 材料折叠：`journeyOrderId | ancillaryOrderItemId | accountId | travelerRef | sorted segmentRefs | productCode | productVersion | premium.currency | premium.minorUnits | clientRequestId`。
- `OpenAutoDelayClaim` 的 `triggerFactKey` 折叠：`policyId | segmentRef | serviceDate | scheduledServiceRef | sourceEventType | sourceEventId | claimRuleVersion`，其中 `sourceEventType` 必须是第 4 节核实的真实事件名。
- `GeneratePayoutAdvice` 材料折叠：`claimId | policyId | approvedAmount.currency | approvedAmount.minorUnits | payoutTarget | reasonCode | decisionVersion | downstreamReferenceHash`。仓库裁决 Notes：导线形制使用 UUID-v7；UUID 的随机/序列部分可由规范化材料 hash 折叠生成或在仓储层以唯一索引绑定，外部导线只暴露 UUID-v7，不暴露原始 PII 材料。
- 所有事件通过 Outbox 发布，所有上游事实通过 Inbox 幂等消费；事件载荷遵守 camelCase、SCREAMING_SNAKE_CASE enum、RFC3339 UTC、Money `minorUnits` 和标准 envelope 字段。

## 8. 策略和 Saga 参与点

### 8.1 购买与出单 Saga

- Ancillary Service 负责 `INSURANCE` 服务的报价、选择和附加订单项状态；Travel Insurance 在 `AncillaryOrderItemSelected` / `AncillaryOrderItemConfirmed` 和订单支付满足后创建或推进 `Policy`。
- `PaymentCaptured` 或 Journey Order 支付满足事实是保单可出单前置条件；未捕获保费不得进入 `ACTIVE`。
- `RequestUnderwriting` 只调用本域 SIM 承保网关。同步成功进入 `UNDERWRITTEN`，满足保障开始规则后 `ACTIVE`；失败进入 `UNDERWRITING_FAILED` 并建议 Ancillary 取消或退款。
- 出单超时或 Ambiguous 不重建保单、不重复提交副作用意图；先 `QueryUnderwritingStatus`，耗尽后进入客服/差异队列。

### 8.2 自动延误理赔策略

- 自动理赔只针对 `DELAY_INSURANCE` 且保单在受影响 segment 的保障窗口内为 `ACTIVE`。
- 事实输入优先级：Fulfillment `SegmentArrived` 给出实际到达时间时计算 `delayMinutes`；`SegmentDelayed` 作为预警和证据收集，不单独触发最终赔付；`SegmentCancelled` 可按产品条款映射为延误等价或人工审核；Disruption Recovery `DisruptionReported` / `IncidentOpened` / `RecoveryCaseOpened` 作为平台异常确认和影响范围证据。
- 若 Disruption Recovery 已经通过 `RecoveryCompleted` 给出 `COMPENSATION` 或 `REFUND`，本域必须按条款检查是否可叠加；不可叠加时理赔进入 `REJECTED` 或只建议差额赔付。
- 同一 `policyId + segmentRef + serviceDate + claimRuleVersion` 只允许一个自动理赔案件；重复事件只补充证据。
- Fulfillment 与 Disruption Recovery 事实冲突时进入 `MANUAL_REVIEW`，不得自动赔付。

### 8.3 人工理赔策略

- `ACCIDENT_INSURANCE` 默认人工理赔：用户或客服通过 Customer Service `OpenSupportCase`、`AttachEvidence`、`RequestManualAction` 提供证据和人工动作。
- 本域只保存 `supportCaseId`、`evidenceId`、access level、摘要和 hash；不保存未脱敏医疗、证件、联系人或事故原文。
- 审核员通过 `ReviewManualClaim` 输出批准/拒绝和原因；需四眼审批的高额赔付由 Customer Service / Admin & Audit 承担权限流，本域只记录审批引用。

### 8.4 赔付出口裁决建议

- 小额固定延误赔付优先建议 Wallet / Promotion 权益：`POST /api/v1/benefits`，`benefitType=COMPENSATION_CREDIT` 或按产品配置，`balanceType=PROMOTION_CREDIT` 或 `STORED_VALUE`，金额使用 Money `minorUnits`。
- 当前 Wallet / Promotion `issuanceSource` 枚举没有 `INSURANCE_CLAIM`。若产品要求严格保险来源，`PayoutAdvice` 必须进入 `BLOCKED_BY_CONTRACT` 或客服人工路径；不得伪装为 `DISRUPTION_COMP`、`POST_SALES_COMP` 或 `MANUAL_OPS`。
- 退保保费返还或现金赔付建议 Payment `POST /api/v1/refunds`，带 `paymentIntentId`、`amount`、`reason` 和 `businessCaseRef=claimId/policyId`。Payment 决定是否路由到 Payment Channel 原路退回。
- Payment Channel 是渠道原路退回 owner，但当前无 `docs/08-contracts/` 契约；本域不直接调用 `ChannelRefund`，只在建议中标注 `preferredRoute=ORIGINAL_PAYMENT_CHANNEL` 供 Payment 未来 handoff 使用。
- 赔付建议一经 `RECOMMENDED` 冻结，金额和目标不可原地修改；若人工复核改变裁决，生成新 `decisionVersion` 并保留旧建议。

### 8.5 已购保单在订单退票/取消时的退保规则

- 未出单或 `AWAITING_PREMIUM_CAPTURE`：订单取消或 Post Sales 退票后关闭保单选择；若保费未捕获，建议 `NO_REFUND_REQUIRED`；若已捕获但未承保，建议全额保费退回。
- `UNDERWRITING_REQUESTED`：先查询 SIM 状态。若未承保成功，申请作废并建议全额退保；若已承保成功，按 `UNDERWRITTEN`/`ACTIVE` 规则判断。
- `UNDERWRITTEN` 但保障未开始：退票/订单取消通常全额退保，生成 `PolicySurrenderRequested` 和 Payment refund 建议；SIM 作废成功后保单 `SURRENDERED`。
- `ACTIVE` 且保障已开始但对应 segment 未出发、无理赔、无 Disruption compensation：按产品 `surrenderRuleVersion` 可全额或部分退保；若条款规定保障开始后不可退，给出 `NO_REFUND` 或 `MANUAL_REVIEW`。
- `ACTIVE` 且已发生保障事件、已有 `APPROVED` / `PAYOUT_RECOMMENDED` claim，或 Fulfillment 已有 `BoardingVerified` / `FulfillmentCompleted` 表明服务已使用：默认不可退保，只可人工争议。
- `DELAY_INSURANCE` 在 segment 已取消且触发自动理赔时，不同时给全额退保和延误赔付；按产品条款选择较高责任或差额建议，避免重复赔付。
- `ACCIDENT_INSURANCE` 在保障期开始后退票是否退保取决于条款；如风险期间已覆盖过旅客，默认 `MANUAL_REVIEW`。
- Journey Order `JourneyOrderCancelled` 和 Post Sales `PostSalesApplied` 只触发退保评估，不直接改 `Policy` 终态；必须通过 `RequestPolicySurrender` 和 SIM 结果推进。

### 8.6 Outbox/Inbox 与失败不可逆策略

- 消费上游事件使用 Inbox，以 source event id、aggregate id 和事件类型去重。
- `UNDERWRITING_FAILED`、`REJECTED`、`FAILED`、`SURRENDERED`、`CLOSED` 等失败或结局态不可由普通命令逆转；晚到事实只能追加调和、人工 Case 或新版本案件。
- 终态聚合可查询安息：读模型必须保留保单号、理赔裁决、赔付建议和证据引用，便于客服解释和审计。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `InsuranceProductCatalogView` | `InsuranceProductDrafted`, `InsuranceProductPublished`, `InsuranceProductSuspended`, `InsuranceProductSuperseded` | Ancillary Service 加购页、运营后台、客服解释。 |
| `PolicyTimelineView` | `PolicySelectionRecorded`, `PolicyPremiumCaptured`, `PolicyUnderwritingRequested`, `PolicyIssued`, `PolicyActivated`, `PolicySurrenderRequested`, `PolicySurrendered`, `PolicyExpired`, `PolicyUnderwritingFailed` | Journey Order 详情、Ancillary Service、Customer Service、运营。 |
| `PolicyCoverageLookupView` | `PolicyIssued`, `PolicyActivated`, `PolicyExpired`, `PolicySurrendered`, Fulfillment segment events | 自动理赔扫描、客服查询、Post Sales 退保评估。 |
| `DelayClaimCandidateView` | Fulfillment `SegmentDelayed`, `SegmentArrived`, `SegmentCancelled`, Disruption Recovery `DisruptionReported`, `IncidentOpened`, `RecoveryCaseOpened`, `RecoveryCompleted` | 自动理赔任务、异常恢复去重、运营监控。 |
| `ClaimWorklistView` | `ClaimOpened`, `ClaimEvidenceAttached`, `ClaimAutoVerified`, `ClaimApproved`, `ClaimRejected`, `PayoutAdviceRecommended`, `ClaimClosed` | Customer Service、理赔审核、保险运营。 |
| `PayoutAdviceQueue` | `PayoutAdviceRecommended`, `PayoutAdviceBlocked`, `PayoutExecutionRecorded`, `PayoutExecutionFailed` | Payment operations、Wallet operations、客服、Finance/Reporting。 |
| `PolicySurrenderDecisionView` | `PolicySurrenderRequested`, `PolicySurrendered`, `PayoutAdviceRecommended`, Post Sales events, Ancillary refund events | Post Sales、Ancillary Service、Payment operations、客服。 |
| `InsuranceSimGatewayReplayView` | `PolicyUnderwritingRequested`, `PolicyIssued`, `PolicyUnderwritingFailed`, underwriting query events with `seedRef` | QA、SRE、orchestrator validation。 |
| `InsuranceKpiProjection` | Product, Policy, Claim, Payout events plus Payment/Wallet result events | Reporting、保险产品运营、财务分析。 |

读模型可以保存订单、旅客、Segment、保单号、保费、赔付金额、状态、规则版本、证据引用和脱敏摘要；不得保存未脱敏证件、完整联系方式、医疗原文、银行卡或真实保险凭据。所有写侧判断必须回到聚合命令。

## 10. 外部系统和防腐层

Travel Insurance 的外部保险方一律模拟。SIM 承保网关位于本域 ACL 内，借鉴 Provider Integration 的防腐层：隔离外部语言、请求/响应映射、错误归类、幂等、查询、raw 摘要和可观测性，但绝不发起真实网络调用。

### 10.1 SIM 承保网关组件

1. Request Mapper：把 `Policy` 出单、查询、作废/退保命令映射为 SIM DTO；不泄露内部聚合结构。
2. Response Mapper：把 SIM 同步响应映射为 `UNDERWRITTEN`、`UNDERWRITING_FAILED`、`SURRENDERED`、`AMBIGUOUS` 和归一错误码。
3. Status Mapper：把 SIM 查询状态 `ISSUED`、`REJECTED`、`VOIDED`、`NOT_FOUND`、`PENDING` 映射为本域保单或请求日志事实。
4. Fault Injector：按 `InsuranceFaultSeed` 注入拒保、超时、掉单、迟到成功、退保失败和状态不一致。
5. Policy Number Generator：按 `seedVersion + policyId + productCode + travelerRef hash` 生成稳定 `policyNumber`；同一材料同一输出。
6. Raw Archive Lite：只保存请求/响应摘要、hash、seedRef、mappingVersion、correlationId；禁止保存真实证件、联系方式、医疗材料和真实保险凭据。
7. Resilience Policy：副作用命令超时后进入 `AMBIGUOUS` / 查询路径；创建和作废请求不得盲目重放。

### 10.2 产品能力矩阵

| SIM Product | Static Premium | Issue Policy | Void/Surrender | Status Query | Auto Claim | Manual Claim | Fault Injection |
|---|---|---|---|---|---|---|---|
| `DELAY_INSURANCE` | yes | yes | yes | yes | yes, from Fulfillment + Disruption facts | yes, dispute fallback | delayed issue, missed issue, rule conflict, payout contract block |
| `ACCIDENT_INSURANCE` | yes | yes | yes | yes | no in this wave | yes, via Customer Service case | underwriting reject, evidence conflict, surrender manual review |

### 10.3 确定性行为

- 无真实网络：SIM gateway 是本进程或测试 fixture 内的确定性适配器，不打开外部 HTTP/TCP 连接，不读取真实保险公司凭据。
- 可种子化：`InsuranceFaultSeed(productCode, policyId, segmentRef, serviceDate, scenarioCode, seedVersion)` 决定出单、查询、退保和故障结果。
- 可复现：相同 policy material、相同 seedVersion 必须得到相同 `policyNumber`、响应分类和故障路径。
- 掉单/迟到成功：出单请求可返回 timeout 或 accepted；查询在故障窗口内 `PENDING`/`NOT_FOUND`，窗口后可能恢复为 `ISSUED`。本域追加恢复事实，不创建第二张保单。
- 拒保：SIM 返回确定性 `REJECTED`，`Policy` 进入 `UNDERWRITING_FAILED`，不可普通重试。
- 退保失败：SIM 可按 seed 返回 `VOID_REJECTED`，本域进入客服/人工审核，不直接退款。
- 防腐红线：SIM 保单号不是订单号；SIM 出单成功不是 Ancillary item fulfilled 的唯一事实；Wallet benefit issued 不是现金退款；Payment refund settled 不是理赔责任重新裁决。

## 11. 当前服务迁移影响

| Current Service / Artifact | Migration Impact |
|---|---|
| Ancillary Service 中泛化的 `INSURANCE` 服务项 | 保留销售/附加订单项生命周期；保单、承保、理赔和赔付建议迁入 Travel Insurance。Ancillary 只记录 `INSURANCE_ACTIVATED` / `INSURANCE_VOIDED` fulfillment fact 或退款建议结果。 |
| 旧保险供应商调用脚本或直连接口 | 替换为本域 SIM 承保网关 ACL；禁止真实网络、真实凭据和未脱敏 raw 文档。 |
| Journey Order 订单详情保险展示 | 改读 `PolicyTimelineView` 和 Ancillary order item 汇总；订单域不解释保单状态。 |
| 退票/售后流程 | Post Sales 退票/取消触发 `RequestPolicySurrender` 评估；退保资金仍经 Payment，附加订单项退款状态仍由 Ancillary 记录。 |
| 延误补偿批处理 | 迁移为 `DelayClaimCandidateView` + `OpenAutoDelayClaim`；只消费核实过的 Fulfillment 与 Disruption Recovery 事件名。 |
| 客服人工理赔 | 使用 Customer Service support case、evidence 和 manual action 引用；理赔域记录裁决，不保存 PII 原文。 |
| 赔付执行 | 迁移为 `PayoutAdviceQueue`；Wallet / Payment 执行由对应域完成。当前 Wallet 无保险来源枚举时，建议进入 `BLOCKED_BY_CONTRACT` 或人工路径。 |
| 报表和财务分析 | Reporting 消费保险产品、保单、理赔、赔付建议和执行结果；Finance/Payment 事实仍是资金口径来源。 |
| Skeleton / service directories | 本任务只写领域设计文档，不新增服务目录，不影响 skeleton-check。 |

## 12. 验收标准

- [x] 文档位于 `docs/02-domains/travel-insurance.md`，Metadata `Status` 为 `proposed-ddd-baseline`，High-Level Inputs 引用 `docs/adr/0003-commercial-realism-scope.md`。
- [x] 严格沿用既有领域文档 12 节结构：Metadata、领域目标、边界 In-Out Scope、统一语言、上下游契约、聚合设计、状态机、命令和领域事件（含幂等键）、策略和 Saga 参与点、读模型、外部系统和防腐层、当前服务迁移影响、验收标准。
- [x] 上下游契约只引用 `docs/08-contracts/` 中真实存在的事件、命令或端点；Travel Insurance 新命令和事件只在第 7 节出现。
- [x] 已核实延误事实事件名：Fulfillment 使用 `SegmentDelayed`、`SegmentArrived`、`SegmentCancelled`；Disruption Recovery 使用 `DisruptionReported`、`IncidentOpened`、`RecoveryCaseOpened` 等真实事件。
- [x] 聚合所有权明确：`InsuranceProduct`、`Policy`、`Claim`、`PayoutAdvice`、`UnderwritingRequestLog` 属于本域；Ancillary、Payment、Wallet、Customer Service、Post Sales 保持各自所有权。
- [x] 状态机包含终态可查询安息；`UNDERWRITING_FAILED`、`REJECTED`、`FAILED`、`SURRENDERED`、`CLOSED` 等不可逆规则已写明。
- [x] 命令表包含幂等键和材料折叠；UUID-v7 导线形制仓库裁决已写入 Notes。
- [x] 外部方一律模拟：SIM 承保网关确定性、可种子化、无真实网络，对照 Provider Integration 防腐层写法。
- [x] 已购保单在订单退票/取消时的退保规则写明，避免退保与延误/异常赔付重复赔付。
- [x] 只写设计文档，不写契约文档、不写代码、不新增服务骨架；`make check` 应不受 contract-lint 和 skeleton-check 新目录影响。
