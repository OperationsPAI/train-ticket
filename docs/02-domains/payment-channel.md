# Payment Channel Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Payment Channel |
| Status | proposed-ddd-baseline |
| Owner Agent | agentm-payment-channel |
| Last Updated | 2026-07-10 |
| High-Level Inputs | `docs/adr/0003-commercial-realism-scope.md`, `docs/02-domains/payment.md`, `docs/02-domains/finance-settlement.md`, `docs/02-domains/provider-integration.md`, `docs/08-contracts/events/payment.md`, `docs/08-contracts/api/payment.md`, `docs/08-contracts/events/finance-settlement-events.md`, `docs/08-contracts/api/finance-settlement.md` |

## 1. 领域目标

Payment Channel 负责平台与模拟支付渠道之间的渠道侧资金交互事实，不替代 Payment 的平台资金语义，也不替代 Finance Settlement 的清结算与会计判断。本域在 ADR-0003 Wave A 中引入，用于把 `ALIPAY_SIM`、`WECHAT_SIM`、`UNIONPAY_SIM` 的差异封装在本域防腐层内，形成可审计、可对账、可故障注入的渠道订单、原路退款和渠道账单模型。

核心目标：

1. 以 `ChannelOrder` 表达一次提交给 SIM 渠道的付款/授权/扣款请求，记录渠道受理、成功、失败、掉单和查询恢复事实。
2. 以 `ChannelRefund` 表达一次原路退回请求，支持同一 `ChannelOrder` 下多次部分退款，保证累计退款不超过可退渠道成功金额。
3. 以 `ChannelStatement` 表达 SIM 渠道按日、按渠道、按币种生成的确定性账单，供 Finance Settlement 做渠道对账输入。
4. 以 `ReconciliationDiscrepancy` 表达本域发现或由下游反馈的渠道账单差异，保留差异种子、证据摘要和处理状态。
5. 在防腐层内定义可种子化、无真实网络、无真实凭据的 SIM 网关行为，支持掉单、金额差异等对账场景种子。
6. 为 Payment 的未来 channel handoff 增量提供领域设计基线；不修改 Payment 现有契约文档，不把新命令伪装成既有契约。

本域明确不解释订单是否已支付、售后是否完成、收入是否确认。`ChannelOrder` 成功只表示渠道侧资金结果成立；平台资金状态仍由 Payment 聚合解释并发布其既有资金事件。

## 2. 边界 In-Out Scope

### In Scope

- `ChannelOrder` 生命周期：创建、提交 SIM 渠道、受理、成功、失败、掉单标记、主动查询恢复、终态查询。
- 渠道类型：`ALIPAY_SIM`、`WECHAT_SIM`、`UNIONPAY_SIM`，枚举使用 SCREAMING_SNAKE。
- `ChannelRefund` 原路退回：引用原 `ChannelOrder` 和渠道交易号，支持部分退款、多次退款、退款成功/失败/掉单查询。
- `ChannelStatement` 确定性日账单：按 `channel` + statement date + currency + seed version 生成或导入，行项目覆盖支付、退款、手续费、状态和差异注入标记。
- `ReconciliationDiscrepancy`：对账缺失、金额差、状态差、重复行、退款滞后等差异记录与解决状态。
- SIM 网关防腐层：请求映射、响应映射、状态映射、错误归类、幂等、防重、查询、账单生成和故障种子。
- Outbox/Inbox：本域命令幂等、事件发布、对 Payment/Finance 事实的重复消费防护。
- 面向运维、客服、财务的只读时间线和差异队列。

### Out of Scope

- 不创建或取消 `PaymentIntent`，不拥有 `Refund` 的平台资金生命周期；这些属于 Payment。
- 不决定订单、Booking Saga、出票、售后案例或权益状态。
- 不计算退票资格、手续费、票价、税费、优惠或补偿金额。
- 不做会计入账、收入确认、发票、供应商结算或 Payout；这些属于 Finance Settlement / Invoicing / Supplier 相关上下文。
- 不接入真实支付宝、微信、银联或银行网络；所有外部方均为 SIM，禁止真实凭据、真实网络调用和真实签名密钥。
- 不修改 `docs/08-contracts/` 下的 Payment、Finance Settlement 或 Provider Integration 契约；本文件只描述领域设计基线。

## 3. 统一语言

| Term | Definition | Owner/Notes |
|---|---|---|
| `PaymentChannel` | 支付渠道枚举：`ALIPAY_SIM`、`WECHAT_SIM`、`UNIONPAY_SIM`。 | Payment Channel |
| `ChannelOrder` | 一次发送给 SIM 渠道的付款/授权/扣款外部意图，包含 Payment 引用、金额、渠道、幂等材料、渠道流水和状态。 | Aggregate root |
| `ChannelOrderAttempt` | 对同一 `ChannelOrder` 的一次提交或查询尝试；用于记录 SIM 响应、超时、掉单和恢复。 | `ChannelOrder` 内部实体 |
| `ChannelTransactionId` | SIM 渠道返回的交易号。它不是 `paymentIntentId`，也不是订单号。 | ACL value object |
| `ChannelRefund` | 对成功 `ChannelOrder` 的原路退回请求，引用原渠道交易号，可为部分退款。 | Aggregate root |
| `ChannelRefundAttempt` | 对同一 `ChannelRefund` 的一次提交、重试或查询尝试。 | `ChannelRefund` 内部实体 |
| `Original Route Refund` | 退款必须回到原 `ChannelOrder` 的 `PaymentChannel` 和原渠道交易，除非上游 Payment 未来显式引入非原路补偿流程。 | Domain rule |
| `ChannelStatement` | SIM 渠道按日生成的确定性账单快照；同一 channel/date/currency/seedVersion 内容稳定。 | Aggregate root |
| `StatementLine` | 渠道账单行，表示支付、退款、手续费、状态或种子化差异行。 | `ChannelStatement` entity |
| `ReconciliationDiscrepancy` | 渠道账单、本域订单/退款和 Finance 对账结果之间的差异记录。 | Aggregate root |
| `Missed` | 本域已提交外部意图，但 SIM 按故障种子模拟“掉单/未出现在回调或账单中”的状态。 | 不可逆业务标记，后续只能通过查询/账单产生恢复或差异，不回到未提交。 |
| `FaultSeed` | 决定性故障注入参数，控制掉单、金额差异、状态差、退款滞后等模拟行为。 | ACL/testing value object |

## 4. 上下游契约

本节只引用已经存在于 `docs/08-contracts/` 的域、事件或端点名称。Payment Channel 自己新增的命令和事件只在第 7 节列出；未来 Payment channel handoff 需要单独修改契约文档，本设计不提前杜撰跨域契约。

### Upstream

| Upstream Context | Existing Contract Verified In `docs/08-contracts/` | How Payment Channel Uses It |
|---|---|---|
| Payment | Events: `PaymentIntentCreated`, `PaymentAuthorized`, `PaymentCaptured`, `PaymentFailed`, `PaymentIntentCancelled`, `PaymentIntentExpired`, `RefundSettled`, `RefundFailed` in `docs/08-contracts/events/payment.md`. HTTP endpoints: `POST /api/v1/payment-intents`, `POST /api/v1/payment-intents/{paymentIntentId}/capture`, `POST /api/v1/refunds`, `GET /api/v1/payment-intents/{paymentIntentId}`, `GET /api/v1/refunds/{refundId}` in `docs/08-contracts/api/payment.md`. | Payment remains the platform funds owner. Existing Payment facts are correlation/audit inputs for channel handoff design and read-side cross-checks. Actual channel order/refund commands are Payment Channel domain commands in section 7 until a future Payment contract increment is approved. |
| Finance Settlement | Bus-only commands `OpenReconciliationCase`, `ResolveReconciliationCase`, `RebuildSettlementView` and query endpoints `GET /api/v1/reconciliation-cases/{reconciliationCaseId}`, `GET /api/v1/reconciliation-cases?...` in `docs/08-contracts/api/finance-settlement.md`; events `ReconciliationCaseOpened`, `ReconciliationCaseResolved`, `ReconciliationCompleted` in `docs/08-contracts/events/finance-settlement-events.md`. | Finance may open or resolve reconciliation cases based on channel statements. Payment Channel consumes only case references/feedback as external facts; it does not let Finance mutate `ChannelOrder` or `ChannelRefund`. |
| Admin & Audit | HTTP endpoints `POST /api/v1/admin/manual-actions`, `POST /api/v1/admin/manual-actions/{manualActionId}/approve`, `POST /api/v1/admin/manual-actions/{manualActionId}/reject`, `GET /api/v1/admin/audit-trail?businessRef={ref}&limit=20&offset=0` in `docs/08-contracts/api/admin-audit.md`; events `ManualActionRequested`, `ManualActionApproved`, `ManualActionRejected`, `ManualActionExecuted`, `AuditEntryRecorded` in `docs/08-contracts/events/admin-audit.md`. | Manual discrepancy resolution and replay actions must carry operator, reason and evidence references through Admin & Audit. Payment Channel stores references only, not PII or raw documents. |

### Downstream

| Downstream Context | Existing Contract Verified In `docs/08-contracts/` | What Payment Channel Provides Without Changing That Contract |
|---|---|---|
| Payment | Same Payment events/endpoints listed above, especially `PaymentAuthorized`, `PaymentCaptured`, `PaymentFailed`, `RefundSettled`, `RefundFailed`. | Payment Channel will publish its own channel facts in section 7 for a future Payment contract increment. Payment, not this domain, decides whether those facts become `PaymentCaptured` or `RefundSettled`. |
| Finance Settlement | `OpenReconciliationCase`, `ResolveReconciliationCase`, `RebuildSettlementView`; events `ReconciliationCaseOpened`, `ReconciliationCaseResolved`, `ReconciliationCompleted`. | `ChannelStatement` and `ReconciliationDiscrepancy` are the channel-side inputs Finance consumes to reconcile, open cases and complete matches. Finance remains owner of accounting and settlement outcomes. |
| Admin & Audit | `AuditEntryRecorded` plus `GET /api/v1/admin/audit-trail?businessRef={ref}&limit=20&offset=0`. | Channel order/refund attempts, statement generation and discrepancy resolution append audit facts through existing audit surfaces; no direct mutation of Payment or Finance state is allowed. |

## 5. 聚合设计

| Aggregate Root | Responsibilities | Invariants |
|---|---|---|
| `ChannelOrder` | 维护一次渠道付款/授权/扣款意图、SIM 提交尝试、渠道流水、状态、金额和审计证据。 | 同一 `paymentIntentId` + `captureRef`/purpose + `channel` + command material 在幂等窗口内只能有一个语义等价订单；提交后金额和币种不可改；`SUCCEEDED`、`FAILED`、`MISSED` 为本域结局态，普通提交命令不可回退；`MISSED` 只能通过查询/账单发现生成恢复事件或差异，不允许静默改成未提交；渠道成功金额必须等于请求金额，除非故障种子显式生成对账差异而非改变本地请求金额。 |
| `ChannelRefund` | 维护一次原路退款请求、部分退款金额、原渠道交易引用、提交/查询尝试和退款状态。 | 必须引用已成功或可查询成功的 `ChannelOrder`；退款渠道必须等于原订单渠道；同一 `refundId`/business refund ref + original `channelTransactionId` + amount 幂等；同一原订单累计成功退款金额不得超过可退成功金额；终态 `SUCCEEDED`、`FAILED`、`MISSED` 不可由普通重试逆转。 |
| `ChannelStatement` | 生成和冻结 SIM 日账单，记录账单行、生成种子、文件摘要和匹配状态。 | 同一 `channel` + `statementDate` + `currency` + `seedVersion` 唯一；账单冻结后行项目不可改写，只能追加匹配/差异状态；生成必须确定性，同一输入事件流和种子得到同一摘要；账单日期使用 UTC 自然日边界。 |
| `ReconciliationDiscrepancy` | 表达渠道订单/退款/账单与 Finance case 之间的差异，跟踪调查、解决和审计引用。 | 每个差异必须绑定 statement line、channel order/refund 或 finance case 中至少一个可追踪引用；差异类型不可在无新证据时改写；人工解决必须有 operatorRef、reasonCode、evidenceRef；`RESOLVED`、`REJECTED` 为终态，可查询安息。 |

### 5.1 `ChannelOrder` 关键字段

- `channelOrderId`：UUID v7。
- `paymentIntentId`：Payment 侧引用，按 `docs/08-contracts/shared-primitives.md` 的跨上下文 ID 约定。
- `businessRef`：Payment 传入的订单或业务引用快照。
- `channel`：`ALIPAY_SIM`、`WECHAT_SIM`、`UNIONPAY_SIM`。
- `amount`：Money；跨币种操作拒绝。
- `idempotencyKey`、`requestFingerprint`、`sourceCommandId`。
- `status`：`CREATED`、`SUBMITTED`、`ACCEPTED`、`SUCCEEDED`、`FAILED`、`MISSED`。
- `channelTransactionId`、`acceptedAt`、`completedAt`。
- `attempts[]`、`faultSeedRef`、`version`。

### 5.2 `ChannelRefund` 关键字段

- `channelRefundId`：UUID v7。
- `refundId`：Payment 侧退款引用。
- `channelOrderId`、`originalChannelTransactionId`。
- `amount`、`currency`、`refundReasonCode`。
- `status`：`CREATED`、`SUBMITTED`、`ACCEPTED`、`SUCCEEDED`、`FAILED`、`MISSED`。
- `channelRefundTransactionId`、`attempts[]`、`faultSeedRef`、`version`。

### 5.3 `ChannelStatement` 关键字段

- `channelStatementId`：UUID v7。
- `channel`、`statementDate`、`currency`、`seedVersion`。
- `generatedAt`、`periodStartAt`、`periodEndAt`（RFC3339 UTC）。
- `lineCount`、`grossPaymentAmount`、`grossRefundAmount`、`feeAmount`。
- `statementHash`、`status`：`GENERATED`、`FROZEN`、`MATCHING`、`MATCHED`、`DISCREPANCY_FOUND`、`CLOSED`。
- `lines[]`：支付、退款、手续费和种子化差异行。

### 5.4 `ReconciliationDiscrepancy` 关键字段

- `discrepancyId`：UUID v7。
- `channelStatementId`、`statementLineId`、`channelOrderId`/`channelRefundId`。
- `financeReconciliationCaseId`：可选，对应 Finance `ReconciliationCaseOpened`。
- `differenceType`：`MISSING_IN_CHANNEL`、`MISSING_IN_PLATFORM`、`AMOUNT_MISMATCH`、`CURRENCY_MISMATCH`、`STATUS_MISMATCH`、`DUPLICATE`、`REFUND_LAG`、`LATE_PAYMENT`。
- `expectedAmount`、`actualAmount`、`evidenceRef`、`resolutionRef`。
- `status`：`OPENED`、`INVESTIGATING`、`MANUAL_REVIEW`、`RESOLVED`、`REJECTED`。

## 6. 状态机

### 6.1 `ChannelOrder` 状态机

| State | Meaning | Allowed Next |
|---|---|---|
| `CREATED` | 本域已记录渠道订单意图，尚未提交 SIM。 | `SUBMITTED`, `FAILED` |
| `SUBMITTED` | 请求已交给 SIM ACL，等待同步或异步结果。 | `ACCEPTED`, `SUCCEEDED`, `FAILED`, `MISSED` |
| `ACCEPTED` | SIM 明确受理，最终成功/失败可通过确定性查询或账单得到。 | `SUCCEEDED`, `FAILED`, `MISSED` |
| `SUCCEEDED` | SIM 确认渠道支付/扣款成功。 | 终态，可查询安息 |
| `FAILED` | SIM 明确拒绝或不可重试失败。 | 终态，可查询安息 |
| `MISSED` | 故障种子导致掉单或超过查询窗口仍无确定结果。 | 结局态；不可普通重试，只能查询安息、打开差异或由后续账单证据生成恢复/差异记录 |

规则：

- `FAILED` 不可逆；相同幂等键重放只能返回原失败结果，不得创建新外部意图。
- `MISSED` 不可逆为“未提交”；它表示曾经提交过有副作用的外部意图。后续发现成功时，不改写历史状态为未发生，而是追加查询恢复事实和/或打开 `ReconciliationDiscrepancy`。
- `SUCCEEDED` 后不得再次提交同一渠道订单；重复命令返回已成功结果。

### 6.2 `ChannelRefund` 状态机

| State | Meaning | Allowed Next |
|---|---|---|
| `CREATED` | 原路退款意图已记录，金额校验通过。 | `SUBMITTED`, `FAILED` |
| `SUBMITTED` | 已提交 SIM 退款。 | `ACCEPTED`, `SUCCEEDED`, `FAILED`, `MISSED` |
| `ACCEPTED` | SIM 受理退款，等待最终结果或日账单。 | `SUCCEEDED`, `FAILED`, `MISSED` |
| `SUCCEEDED` | 退款原路退回成功。 | 终态，可查询安息 |
| `FAILED` | SIM 明确拒绝或不可重试失败。 | 终态，可查询安息 |
| `MISSED` | 退款掉单或最终性未知。 | 结局态；不可普通重试，只能查询安息、打开差异或等待账单证据 |

部分退款规则：每次 `ChannelRefund` 只处理一个确定金额；多个部分退款必须各自有独立幂等材料；累计 `SUCCEEDED` 加上已受理但未终结的退款金额不得超过原成功支付金额，避免并发超退。

### 6.3 `ChannelStatement` 状态机

| State | Meaning | Allowed Next |
|---|---|---|
| `GENERATED` | 按 SIM 种子和日切规则生成账单草稿。 | `FROZEN`, `DISCREPANCY_FOUND` |
| `FROZEN` | 账单摘要已冻结，行项目不可改写。 | `MATCHING` |
| `MATCHING` | Finance 或本域匹配任务正在处理。 | `MATCHED`, `DISCREPANCY_FOUND` |
| `MATCHED` | 所有账单行与平台事实匹配。 | `CLOSED` |
| `DISCREPANCY_FOUND` | 至少一个差异已打开。 | `MATCHING`, `CLOSED` |
| `CLOSED` | 对账周期关闭。 | 终态，可查询安息 |

## 7. 命令和领域事件（含幂等键）

本节是 Payment Channel 自己的命令和事件清单。它们不是现有 Payment 或 Finance Settlement 契约；跨上下文发布前必须另行进入 `docs/08-contracts/` 契约流程。

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `CreateChannelOrder` | `ChannelOrder` | `ChannelOrderCreated` | `paymentIntentId + businessRef + purpose + channel + amount + currency + sourceCommandId` 的规范化材料折叠 |
| `SubmitChannelOrder` | `ChannelOrder` | `ChannelOrderSubmitted` | `channelOrderId + submitAttemptNo + requestFingerprint` |
| `ApplyChannelOrderAccepted` | `ChannelOrder` | `ChannelOrderAccepted` | `channelOrderId + channel + channelAcceptRef` |
| `ApplyChannelOrderSucceeded` | `ChannelOrder` | `ChannelOrderSucceeded` | `channelOrderId + channelTransactionId + succeededAmount` |
| `ApplyChannelOrderFailed` | `ChannelOrder` | `ChannelOrderFailed` | `channelOrderId + providerErrorCode + terminalReason` |
| `MarkChannelOrderMissed` | `ChannelOrder` | `ChannelOrderMissed` | `channelOrderId + faultSeedRef + missWindow` |
| `QueryChannelOrder` | `ChannelOrder` | `ChannelOrderQueryRecorded` 或 `ChannelOrderRecoveryDetected` | `channelOrderId + queryAttemptNo` |
| `CreateChannelRefund` | `ChannelRefund` | `ChannelRefundCreated` | `refundId + channelOrderId + originalChannelTransactionId + amount + currency` 的规范化材料折叠 |
| `SubmitChannelRefund` | `ChannelRefund` | `ChannelRefundSubmitted` | `channelRefundId + submitAttemptNo + requestFingerprint` |
| `ApplyChannelRefundSucceeded` | `ChannelRefund` | `ChannelRefundSucceeded` | `channelRefundId + channelRefundTransactionId + succeededAmount` |
| `ApplyChannelRefundFailed` | `ChannelRefund` | `ChannelRefundFailed` | `channelRefundId + providerErrorCode + terminalReason` |
| `MarkChannelRefundMissed` | `ChannelRefund` | `ChannelRefundMissed` | `channelRefundId + faultSeedRef + missWindow` |
| `GenerateChannelStatement` | `ChannelStatement` | `ChannelStatementGenerated` | `channel + statementDate + currency + seedVersion` |
| `FreezeChannelStatement` | `ChannelStatement` | `ChannelStatementFrozen` | `channelStatementId + statementHash` |
| `MatchStatementLine` | `ChannelStatement` | `ChannelStatementLineMatched` | `channelStatementId + statementLineId + matchRuleVersion` |
| `OpenReconciliationDiscrepancy` | `ReconciliationDiscrepancy` | `ReconciliationDiscrepancyOpened` | `channelStatementId + statementLineId + differenceType + evidenceHash` |
| `AttachFinanceCase` | `ReconciliationDiscrepancy` | `ReconciliationDiscrepancyLinkedToFinanceCase` | `discrepancyId + financeReconciliationCaseId` |
| `ResolveReconciliationDiscrepancy` | `ReconciliationDiscrepancy` | `ReconciliationDiscrepancyResolved` | `discrepancyId + resolutionVersion + evidenceRef` |

幂等材料折叠规则：

- 命令先把业务引用、金额、币种、渠道、purpose、原渠道流水、尝试号等语义字段按稳定顺序规范化，再计算 `requestFingerprint`；重复命令的材料完全一致时返回原结果，材料不同但复用幂等键时拒绝。
- 仓库裁决 Notes：本仓库 `docs/08-contracts/shared-primitives.md` 对跨上下文 ID 采用 UUID v7；Payment Channel 的 `channelOrderId`、`channelRefundId`、`channelStatementId`、`discrepancyId` 和事件 envelope ID 也按 UUID v7 分配。幂等键可以是业务材料折叠值或上游传入键，但落库裁决以幂等记录 + UUID-v7 聚合 ID 的唯一索引为准。
- 所有事件通过 Outbox 发布，所有外部或跨域事实通过 Inbox 去重；事件 envelope 使用 RFC3339 UTC 时间和标准 correlation/causation/sourceCommand 字段。

## 8. 策略和 Saga 参与点

### 8.1 Payment channel handoff 策略

- Payment 是平台资金语义 owner；Payment Channel 是渠道交互 owner。
- 未来 Payment 增量可以把支付意图/捕获/退款路由给 Payment Channel，但本设计不修改 Payment 契约文档，也不要求现有 `POST /api/v1/payment-intents/{paymentIntentId}/capture` 直接调用本域。
- Payment Channel 返回渠道事实后，Payment 自己决定是否发布既有 `PaymentAuthorized`、`PaymentCaptured`、`PaymentFailed`、`RefundSettled` 或 `RefundFailed`。
- Late success、掉单恢复和金额差异不得直接改 Payment 聚合；必须以差异/恢复事实进入 Payment 或 Finance 的受控流程。

### 8.2 退款和部分退款策略

- 原路退回是默认且唯一的 Wave A 策略：`ChannelRefund.channel` 必须等于原 `ChannelOrder.channel`。
- 每次部分退款金额必须为正且币种一致；并发退款按原订单维度加锁或使用乐观并发，确保累计成功与在途退款不超过原成功金额。
- 退款掉单后不得盲目重放创建新外部退款；优先 `QueryChannelRefund`，耗尽查询后进入 `MISSED` 和差异流程。

### 8.3 对账策略

- SIM 日账单按 UTC 日切生成；同一 channel/date/currency/seedVersion 内容确定，便于测试和复现。
- 对账匹配优先级：`channelTransactionId`/`channelRefundTransactionId` 精确匹配，其次幂等材料和金额/时间窗口匹配，最后进入人工差异。
- 差异分类采用 SCREAMING_SNAKE 内部枚举；向 Finance 既有 `ReconciliationCaseOpened.differenceType` 投影时需要映射为其既有字符串分类，不在本设计中新增 Finance 枚举。
- 金额差异、掉单和退款滞后是允许的模拟输入，不得被自动修正为本地资金事实。

### 8.4 Saga 参与点

- 本域不编排订票 Saga、售后 Saga 或财务月结，只提供渠道命令结果和账单输入。
- Payment Saga 可在未来把渠道成功作为捕获成功的证据；Booking Orchestration 不直接消费 Payment Channel 事件。
- Finance Settlement 可用账单和差异记录启动 `OpenReconciliationCase`，但差异解决不直接反向修改 `ChannelOrder` 或 `ChannelRefund` 终态。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `ChannelOrderTimelineView` | `ChannelOrderCreated`, `ChannelOrderSubmitted`, `ChannelOrderAccepted`, `ChannelOrderSucceeded`, `ChannelOrderFailed`, `ChannelOrderMissed`, `ChannelOrderQueryRecorded`, `ChannelOrderRecoveryDetected` | Payment operations, Customer Service, Admin & Audit |
| `ChannelRefundTimelineView` | `ChannelRefundCreated`, `ChannelRefundSubmitted`, `ChannelRefundSucceeded`, `ChannelRefundFailed`, `ChannelRefundMissed` | Payment operations, Post-sales support, Customer Service |
| `ChannelStatementView` | `ChannelStatementGenerated`, `ChannelStatementFrozen`, `ChannelStatementLineMatched` | Finance Settlement, Reporting, channel operations |
| `PaymentChannelReconciliationQueue` | `ReconciliationDiscrepancyOpened`, `ReconciliationDiscrepancyLinkedToFinanceCase`, `ReconciliationDiscrepancyResolved` | Finance Settlement, Admin & Audit, Customer Service |
| `SimGatewayFaultReplayView` | order/refund attempts, statement generation events and `faultSeedRef` references | QA, deterministic load scenarios, orchestrator validation |
| `ChannelSlaSnapshot` | attempts, terminal outcomes, missed counts and query latencies | Reporting, channel operations |

读模型可以冗余订单号、Payment 引用、渠道号、金额、币种、脱敏后的业务说明和证据引用；不得保存真实支付凭据、完整证件、银行卡号或未脱敏 raw 文档。所有写侧判断必须回到聚合命令，不能通过读模型直接改状态。

## 10. 外部系统和防腐层

Payment Channel 的外部方一律模拟，SIM 网关实现位于本域 ACL 内，遵循 Provider Integration 的防腐层思想但不经过真实网络。

### 10.1 SIM 网关组件

1. Request Mapper：把 `ChannelOrder` / `ChannelRefund` 命令转为 SIM DTO，隐藏平台聚合结构。
2. Response Mapper：把 SIM 响应映射为 `ACCEPTED`、`SUCCEEDED`、`FAILED`、`MISSED` 和归一化错误码。
3. Status Mapper：把 SIM 查询状态 `ACCEPTED`、`SUCCESS`、`FAILED`、`REFUNDED`、`REFUND_FAILED`、`NOT_FOUND` 映射为本域订单/退款状态或差异输入。
4. Statement Generator：按 channel/date/currency/seedVersion 和本域事件流生成确定性 `ChannelStatement`。
5. Fault Injector：按 `FaultSeed` 注入掉单、金额差异、状态差、重复行、退款滞后。
6. Raw Archive Lite：只保存请求/响应摘要、签名摘要占位、seedRef、hash 和映射版本；禁止保存真实凭据或 PII。
7. Resilience Policy：模拟 Timeout、Ambiguous/Missed、查询次数和重试窗口；副作用命令超时后禁止盲目重放。

### 10.2 渠道能力矩阵

| SIM Channel | Payment/Capture | Original Route Refund | Partial Refund | Status Query | Daily Statement | Fault Injection |
|---|---|---|---|---|---|---|
| `ALIPAY_SIM` | yes | yes | yes | yes | yes | missed order, amount mismatch, refund lag |
| `WECHAT_SIM` | yes | yes | yes | yes | yes | missed order, status mismatch, duplicate line |
| `UNIONPAY_SIM` | yes | yes | yes | yes | yes | amount mismatch, refund failed, statement delay |

### 10.3 确定性行为

- 无真实网络：SIM gateway 是本进程或测试夹具内的确定性适配器，不打开外部 HTTP/TCP 连接，不读取真实渠道凭据。
- 可种子化：`FaultSeed(channel, businessRef, statementDate, scenarioCode, seedVersion)` 决定同步响应、异步结果、查询结果和账单行差异。
- 可复现：相同事件流、相同 UTC 日切、相同 seedVersion 必须生成相同 `statementHash`。
- 掉单：SIM 对提交返回 accepted 或 timeout，但查询在故障窗口内返回 `NOT_FOUND`，日账单可缺失该行或迟到生成差异。
- 金额差异：SIM 日账单对指定行注入 `actualAmount != expectedAmount`，本域保持原 `ChannelOrder.amount` 不变并打开 `AMOUNT_MISMATCH` 差异。
- 退款状态：SIM 的 `REFUNDED` 只证明渠道退款完成；Payment 是否发布 `RefundSettled` 由 Payment 自己决定。

## 11. 当前服务迁移影响

| Current Service / Artifact | Migration Impact |
|---|---|
| Payment channel adapter logic currently implied inside Payment design | 抽出为 Payment Channel 的 `ChannelOrder`、`ChannelRefund` 和 SIM ACL；Payment 保留资金语义与现有契约。 |
| Existing Payment HTTP commands (`POST /api/v1/payment-intents`, capture, refunds) | Wave A 不改端点；未来 handoff 由 Payment 内部或契约增量调用本域命令。 |
| Finance channel reconciliation candidate flow | 新增 `ChannelStatementView` 和 `PaymentChannelReconciliationQueue` 作为对账输入；Finance 仍通过既有 reconciliation case 模型处理差异。 |
| Provider Integration payment adapter references | ADR-0003 后 SIM 支付渠道由 Payment Channel owning context 承担；Provider Integration 的通用 ACL 思想可复用，但不拥有支付渠道资金语义。 |
| Load/test scenarios | 增加确定性 seed：掉单、金额差异、状态差、重复账单行、退款滞后，支持对账和 late-payment 验证。 |
| Observability | 新增 channel order/refund attempt、statement generation、fault seed 和 discrepancy metrics；日志只记录引用和摘要，不记录 PII 或凭据。 |
| Skeleton / service directories | 本任务只写领域设计文档，不新增服务目录，不影响 skeleton-check。 |

## 12. 验收标准

- 文档位于 `docs/02-domains/payment-channel.md`，Metadata `Status` 为 `proposed-ddd-baseline`，High-Level Inputs 引用 `docs/adr/0003-commercial-realism-scope.md`。
- 12 节结构齐全：领域目标、边界、统一语言、上下游契约、聚合设计、状态机、命令和领域事件、策略和 Saga 参与点、读模型、外部系统和防腐层、当前服务迁移影响、验收标准。
- 上下游契约只引用 `docs/08-contracts/` 中真实存在的 Payment 和 Finance Settlement 事件/端点/命令；Payment Channel 新命令和事件仅出现在第 7 节。
- 聚合所有权清晰：`ChannelOrder`、`ChannelRefund`、`ChannelStatement`、`ReconciliationDiscrepancy` 归 Payment Channel；Payment 拥有平台资金语义；Finance Settlement 拥有对账案例、入账和清结算。
- 状态机包含受理、成功、失败、退款和掉单/差异路径；`FAILED`、`MISSED` 等结局态不可由普通命令逆转，终态可查询安息。
- 命令表包含幂等键和材料折叠规则，并记录 UUID-v7 仓库裁决 Notes。
- SIM 网关防腐层明确无真实网络、可种子化、确定性账单生成，并覆盖掉单与金额差异故障注入。
- 设计不越过 ADR-0003 Wave A 范围：只做 payment-channel 领域基线，不写契约文档、不写代码、不新增服务骨架。
