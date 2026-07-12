# Payment Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Payment |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-payment |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/domain-glossary.md`, `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/aggregate-model.md`, `docs/01-ddd-high-level/state-machines.md`, `docs/01-ddd-high-level/event-storming.md`, `docs/01-ddd-high-level/consistency-and-saga.md`, `docs/01-ddd-high-level/order-inventory-payment-model.md` |

## 1. 领域目标

Payment bounded context 负责火车票业务中的资金生命周期与支付渠道交互，提供稳定的内部 Open Host Service 契约，使 Journey Order、Booking Orchestration、Post Sales、Disruption Recovery 等上游可以用明确的命令发起付款、授权、扣款、退款和异常资金处理。

Payment 的核心目标是：

1. 以 `PaymentIntent` 表达一次业务付款请求，并管理其从创建、待支付、授权、扣款、失败、取消到过期的状态变化。
2. 以 `Authorization` 和 `Capture` 表达授权占款与实际扣款，支持预授权、后续扣款、释放授权等资金动作。
3. 以 `Refund` 表达退款执行生命周期；退款原因、资格、金额来源于 Post Sales 或 Disruption Recovery 等上游决策，Payment 只校验资金与幂等约束并执行渠道退款。
4. 管理支付渠道回调的签名校验、幂等、顺序、审计和冲突处理，避免重复扣款、重复退款或乱序状态覆盖。
5. 管理 late payment cases：当订单已取消、PaymentIntent 已过期或上游 Saga 已走补偿后，渠道仍返回成功支付时，Payment 记录并隔离异常资金，不自行确认订单。
6. 提供 payment/refund read models，支撑 Customer Service、Finance Settlement、Notification、运营后台和排障。
7. 通过 `Outbox` 发布资金事实事件，通过 `Inbox` 消费上游命令/事件，保证跨域 Saga 的可追踪和可恢复。

Payment 明确不负责：车票退款资格判断、库存释放、乘车权益有效性、订单确认、订单取消、出票或改签规则。`PaymentCaptured` 只能表示资金事实成立，不等于 `EntitlementIssued`，也不等于订单已完成。

## 2. 边界

### 2.1 In Scope

- `PaymentIntent` 生命周期管理：创建、提交支付、等待用户动作、授权、扣款、失败、取消、过期。
- `Authorization` 生命周期管理：授权成功、授权失败、授权过期、授权释放、授权转 `Capture`。
- `Capture` 资金事实记录：扣款成功、扣款失败、重复扣款识别、渠道交易号绑定。
- `Refund` 生命周期管理：接收上游退款执行请求、渠道退款提交、退款成功、退款失败、重试、人工审核。
- 支付渠道回调处理：验签、幂等、重复回调去重、乱序回调停放、回调审计。
- late payment cases：过期/取消后成功付款、重复成功、渠道主动查询发现成功但本地未确认等异常资金场景。
- payment/refund read models：支付状态、退款状态、资金流水、渠道回调审计、客服时间线、对账候选项。
- Payment `Outbox`/`Inbox`：发布资金事件、消费上游订单/售后/灾备决策事件，保证幂等。
- Payment channel adapters 或与 Provider Integration 协作的支付渠道防腐层契约。
- Customer Service 手工处理入口：异常支付查询、补发事件、标记人工审核、late payment case 处理建议。

### 2.2 Out of Scope

- Journey Order 的订单确认、订单取消、订单完成、订单状态机最终解释权。
- Booking Orchestration 的订票 Saga 编排、库存保留与释放、出票推进。
- Ticketing/Entitlement 的乘车权益签发、失效、换票、验票可用性。
- Post Sales 的退票/改签资格、规则、手续费、退款金额计算。
- Disruption Recovery 的补偿资格、扰动原因归类、批量客运恢复策略。
- Inventory/Seat 的席位锁定、释放、占用和座位图一致性。
- Notification 的消息模板、发送渠道、触达策略。
- Finance Settlement 的清结算、会计分录、发票、商户结算周期；Payment 只提供资金事实与对账候选。
- 价格、促销、保险、餐食、托运等商品域的费用构成计算。

### 2.3 边界原则

- Payment 只接受上游已经决策好的业务意图：例如“为 order X 创建支付意图”“对 captured payment Y 执行金额 Z 的退款”。
- Payment 不通过读取订单内部状态来推导业务动作；它依赖命令携带的业务引用、金额、币种、过期时间、幂等键和原因码。
- Payment 可以拒绝违反资金不变量的命令，例如退款金额超过可退款余额、重复 `Capture`、非法状态迁移；但不得替代 Post Sales 判断是否应退款。
- 渠道状态必须先转换为 Payment 领域事实，再通过事件通知其他域；渠道返回成功不得直接修改订单或权益。

## 3. 统一语言补充

| Term | Definition | Owner/Notes |
| --- | --- | --- |
| `PaymentIntent` | 一次由业务域发起的付款意图，包含业务引用、金额、币种、过期时间、可用支付方式、幂等键和当前状态。 | Payment aggregate root |
| `PaymentAttempt` | 用户或系统对同一个 `PaymentIntent` 的一次具体支付尝试，可绑定渠道、收银台会话、二维码、跳转链接或银行卡授权。 | Payment 内部实体 |
| `Authorization` | 渠道确认资金已授权但尚未扣款的事实，常用于候补、预授权或后扣款流程。 | Payment aggregate root 或 `PaymentIntent` 下的受控聚合 |
| `Capture` | 对授权或直接支付资金的实际扣款事实；成功后发布 `PaymentCaptured`。 | Payment 事实记录 |
| `ChannelTransaction` | 支付渠道侧交易标识、状态、金额、时间和渠道错误码的归一化表示。 | 防腐层值对象 |
| `ChannelCallback` | 渠道推送的异步通知原文及其验签、幂等、解析结果。 | Payment owns idempotency |
| `ChannelCallbackRecord` | 回调审计与处理状态记录，确保同一渠道回调只被应用一次。 | Payment aggregate root |
| `Refund` | 一次退款执行请求及其渠道执行状态；金额和原因由上游提供。 | Payment aggregate root |
| `RefundAttempt` | 对某个 `Refund` 的一次渠道退款提交或重试。 | Payment 内部实体 |
| `LatePaymentCase` | PaymentIntent 已取消/过期或相关业务 Saga 已补偿后出现的成功支付/扣款异常案例。 | Payment aggregate root |
| `PaymentLedgerEntry` | Payment 侧不可变资金事实流水，记录授权、扣款、退款、释放、异常调整等。 | Payment read/audit model source |
| `PaymentDispute` | 渠道状态、本地状态、上游业务状态不一致时的争议或待人工确认事项。 | Payment 与 Customer Service 协作 |
| `Outbox` | Payment 提交本地状态变化时原子写入的待发布领域事件表。 | 跨域可靠发布 |
| `Inbox` | Payment 对上游命令/事件的幂等消费记录。 | 跨域可靠消费 |

## 4. 上下游契约

### 4.1 Upstream：Payment 接收的请求与事实

#### Journey Order

- 发起 `CreatePaymentIntent`：订单需要收款时提供 order reference、amount、currency、expireAt、payer reference、business idempotency key。
- 发起 `CancelPaymentIntent`：订单取消、超时未支付或用户放弃付款时请求取消未完成付款。
- 消费 Payment 事件后推进订单状态，但 Payment 不直接写订单。
- 对 Payment 的要求：同一 order/payment purpose 在幂等窗口内不得创建多个可扣款意图，除非 Journey Order 明确提供新的 payment purpose 或补差价引用。

#### Booking Orchestration

- 在订票 Saga 中编排库存保留、订单创建、付款、出票推进。
- 可基于 Saga 决策请求 Payment 创建付款、取消付款、查询付款状态。
- Payment 发布 `PaymentCaptured` 后只表示 Saga 可继续，不表示 Booking Orchestration 必须出票成功。
- Saga 补偿时可请求取消未完成 `PaymentIntent`，或对已扣款资金发起退款请求；是否退款由 Saga/售后规则决定。

#### Post Sales

- 发起 `RequestRefund`：退票、改签、部分退款、手续费退还等场景，必须提供 refund amount、currency、reason code、source case id、refund idempotency key。
- 发起 `CancelRefund` 或 `RetryRefund`：在允许的状态下取消或重试退款执行。
- Payment 不判断退票/改签资格，不重新计算手续费；只校验金额不超过 captured-and-not-refunded balance、币种一致、幂等键一致。
- Payment 发布 `RefundSettled` 后，Post Sales 再决定售后案例是否完成。

#### Disruption Recovery

- 在列车取消、晚点、运力调整等扰动场景中批量发起退款/补偿执行请求。
- Payment 只执行资金退回或标记人工审核；补偿资格、优先级、批次策略属于 Disruption Recovery。
- 大批量退款需要 Payment 暴露批次状态读模型与限流/重试策略。

#### Customer Service

- 查询 payment/refund timeline、渠道回调、late payment case、对账状态。
- 发起受控的人工命令：补发 Payment 事件、将 Refund 移入 manual review、标记 dispute 处理结果、请求渠道主动查询。
- 人工命令必须带 operator id、reason、evidence reference，并进入审计。

#### Provider Integration / payment channel adapters

- Payment 通过防腐层请求 create payment、authorize、capture、release authorization、refund、query transaction、verify callback。
- 如果渠道 adapter 部署在 Provider Integration，Payment 仍拥有平台级 `PaymentIntent`、`Refund`、回调幂等和资金事实解释权。
- 防腐层必须返回归一化错误码、渠道交易号、可重试性、最终性和原始报文摘要。

#### Risk & Compliance / Security

- 可在创建或提交支付前提供风险结果、支付方式限制、用户校验结果。
- Payment 可根据明确的风险拦截结果拒绝提交渠道，但不承担风控规则归属。

### 4.2 Downstream：Payment 发布的事实与读模型

#### Journey Order

- 消费 `PaymentIntentCreated`、`PaymentAuthorized`、`PaymentCaptured`、`PaymentFailed`、`PaymentCancelled`、`PaymentExpired`、`LatePaymentDetected`。
- Journey Order 根据自身状态机判断订单是否进入 paid/confirmed/cancelled/exception；不得把 Payment 事件当作直接写状态命令。

#### Booking Orchestration

- 消费 Payment 事件推进或补偿 Saga。
- 对 late payment，Booking Orchestration 只能进入异常分支，不能默认恢复已取消库存或出票。

#### Post Sales

- 消费 `RefundAccepted`、`RefundSubmitted`、`RefundSettled`、`RefundFailed`、`RefundManualReviewRequired`。
- `RefundSettled` 表示资金已退，不表示售后单所有业务副作用已完成。

#### Disruption Recovery

- 消费批量退款进度、失败原因、人工审核事项。
- 使用 Payment read model 识别待重试、待客服处理、待财务核对的案例。

#### Finance Settlement

- 消费 `PaymentCaptured`、`RefundSettled`、`AuthorizationReleased`、`LatePaymentDetected`、`PaymentDisputeOpened` 与 `PaymentLedgerEntry`。
- 使用 `PaymentReconciliationCandidateView` 对账；清结算差异处理结果可反馈 Payment 形成 dispute/adjustment。

#### Notification

- 消费 Payment 事件或由其他业务域转发的通知指令。
- Payment 不负责消息文案和发送；只提供足够的 payment/refund status facts。

#### Customer Service

- 使用 `PaymentOperationTimeline`、`RefundView`、`ChannelCallbackAuditView`、`LatePaymentCaseView`。
- 对异常案例通过受控命令回写处理动作。

#### Reporting / Admin

- 读取汇总 read models：支付成功率、渠道失败率、退款时效、late payment 数量、人工审核积压。

## 5. 聚合设计

### 5.1 `PaymentIntent` Aggregate

**职责**

- 表达一次付款意图及其支付尝试。
- 维护金额、币种、业务引用、过期时间、payer reference、payment purpose、当前状态。
- 控制 `PaymentAttempt`、直接 `Capture` 或关联 `Authorization` 的合法迁移。
- 生成资金事实与 Outbox 事件。

**关键字段**

- `paymentIntentId`
- `businessRef`：order id、booking saga id、change case id 等业务引用。
- `purpose`：initial booking payment、fare difference、post-sales charge 等。
- `amount`、`currency`
- `payerRef`
- `status`
- `expiresAt`
- `idempotencyKey`
- `attempts[]`
- `capturedAmount`
- `refundedAmount`（可由 ledger/read side 计算，写侧可保留快照）
- `channelTransactionRefs[]`
- `version`

**不变量**

- 同一业务引用、purpose、idempotency key 在幂等窗口内只能有一个语义等价的 `PaymentIntent`。
- `Capture` 总金额不得超过 `PaymentIntent.amount` 或已授权可扣款金额。
- 终态 `Captured`、`Cancelled`、`Expired`、`Failed` 不能被普通支付命令回退；渠道 late success 必须进入 `LatePaymentCase` 或显式恢复流程。
- `PaymentIntent` 过期后不得提交新的用户支付尝试。
- Payment 不因 `Captured` 直接修改订单状态，只发布事件。

**主要命令**

- `CreatePaymentIntent`
- `SubmitPayment`
- `AuthorizePayment`
- `CapturePayment`
- `CancelPaymentIntent`
- `ExpirePaymentIntent`
- `FailPayment`
- `ApplyChannelCallbackToPayment`

**主要事件**

- `PaymentIntentCreated`
- `PaymentActionRequired`
- `PaymentAuthorized`
- `PaymentCaptured`
- `PaymentFailed`
- `PaymentIntentCancelled`
- `PaymentIntentExpired`
- `LatePaymentDetected`

### 5.2 `Authorization` Aggregate

**职责**

- 管理渠道授权占款事实。
- 支持后续 `Capture` 或 release authorization。
- 支撑候补、预授权和先锁资金后确认票务资源的流程。

**关键字段**

- `authorizationId`
- `paymentIntentId`
- `authorizedAmount`、`currency`
- `capturedAmount`
- `releasedAmount`
- `status`
- `channelAuthorizationRef`
- `expiresAt`

**状态**

- `Requested`
- `Authorized`
- `PartiallyCaptured`
- `Captured`
- `Released`
- `Expired`
- `Failed`

**不变量**

- captured amount + released amount 不得超过 authorized amount。
- 已释放、过期或失败的 `Authorization` 不得再 `Capture`。
- `Authorization` 的币种必须与关联 `PaymentIntent` 一致。

### 5.3 `Refund` Aggregate

**职责**

- 表达一次上游批准后的退款执行请求。
- 管理渠道退款提交、重试、成功、失败和人工审核。
- 控制退款金额不超过 Payment 可退款余额。

**关键字段**

- `refundId`
- `sourceCaseRef`：Post Sales case、Disruption batch、Customer Service case。
- `paymentIntentId` 或 `captureId`
- `amount`、`currency`
- `reasonCode`
- `requestedByDomain`
- `idempotencyKey`
- `status`
- `attempts[]`
- `channelRefundRef`
- `failureReason`

**不变量**

- 同一 source case 和 idempotency key 只能创建一个语义等价的 `Refund`。
- 同一 `Capture` 的 settled refunds 总额不得超过 captured amount minus prior settled/refund-in-flight amount。
- Payment 不改变 refund amount 或 reason；如金额不合法，拒绝命令并发布/返回资金约束错误。
- `RefundSettled` 后不得再次提交渠道退款。

**主要命令**

- `RequestRefund`
- `AcceptRefund`
- `SubmitRefundToChannel`
- `SettleRefund`
- `FailRefund`
- `RetryRefund`
- `CancelRefund`
- `MoveRefundToManualReview`

**主要事件**

- `RefundRequested`
- `RefundAccepted`
- `RefundSubmitted`
- `RefundSettled`
- `RefundFailed`
- `RefundCancelled`
- `RefundManualReviewRequired`

### 5.4 `ChannelCallbackRecord` Aggregate

**职责**

- 接收并审计渠道回调。
- 负责验签、重复识别、乱序停放、应用结果记录。
- 防止渠道重复推送造成重复状态迁移。

**关键字段**

- `callbackRecordId`
- `channel`
- `channelCallbackId` 或由原文摘要生成的幂等键
- `receivedAt`
- `signatureStatus`
- `normalizedEventType`
- `channelTransactionRef`
- `relatedPaymentIntentId` / `refundId`
- `status`
- `rawPayloadDigest`
- `applyResult`

**不变量**

- 同一 channel + channel callback id/transaction event id 只能被应用一次。
- 验签失败的回调不得影响 `PaymentIntent` 或 `Refund`。
- 无法匹配本地对象或违反状态顺序的回调必须 `Parked`，等待主动查询或人工处理。

### 5.5 `LatePaymentCase` Aggregate

**职责**

- 隔离“业务上已放弃/取消，但资金后到”的异常事实。
- 记录关联订单、PaymentIntent、渠道交易、当前处理建议和处理结果。
- 与 Journey Order、Booking Orchestration、Post Sales、Customer Service、Finance Settlement 协作解决。

**触发场景**

- PaymentIntent `Cancelled` 或 `Expired` 后收到成功支付回调。
- Booking Saga 已补偿释放库存后，渠道主动查询显示支付成功。
- 本地已失败但渠道最终成功。
- 重复成功扣款或渠道状态与本地状态冲突。

**不变量**

- `LatePaymentCase` 创建不改变订单为已支付，不触发出票，不恢复库存。
- 解决动作必须来自明确命令：例如上游决定退款、客服决定人工核销、财务确认差异调整。
- 每个 late channel transaction 至多打开一个 active case。

## 6. 状态机

### 6.1 `PaymentIntent` 状态机

| State | Meaning | Allowed Transitions |
| --- | --- | --- |
| `Created` | 付款意图已创建，尚未生成用户动作或渠道交易。 | `PendingAction`, `Authorized`, `Captured`, `Cancelled`, `Expired`, `Failed` |
| `PendingAction` | 等待用户在收银台、扫码、跳转页或银行页面完成动作。 | `Authorized`, `Captured`, `Failed`, `Cancelled`, `Expired` |
| `Authorized` | 渠道已授权占款，尚未全部扣款。 | `Captured`, `Cancelled`（通过 release）, `Expired`, `Failed` |
| `Captured` | 资金已成功扣款。 | 仅允许关联 `Refund` 或 dispute；不回退 |
| `Failed` | 支付失败，且当前尝试不可继续。 | 可在业务允许时新建新的 `PaymentIntent`；本实例不回退 |
| `Cancelled` | 上游取消未完成付款。 | late success -> `LatePaymentCase`；不回到 active |
| `Expired` | 超过支付截止时间。 | late success -> `LatePaymentCase`；不回到 active |

**迁移规则**

- `Created/PendingAction -> Captured`：渠道同步返回成功或回调成功，金额匹配且未过期。
- `Created/PendingAction -> Authorized`：预授权成功。
- `Authorized -> Captured`：上游命令或 Saga 到达扣款条件，渠道扣款成功。
- `Created/PendingAction/Authorized -> Cancelled`：上游取消；若已授权需先 release 或记录 release pending。
- active 状态超时后进入 `Expired`。
- terminal 后渠道成功不得迁移为 `Captured`，必须打开 `LatePaymentCase`。

### 6.2 `Refund` 状态机

| State | Meaning | Allowed Transitions |
| --- | --- | --- |
| `Requested` | 上游提交退款执行请求，尚未完成 Payment 资金校验。 | `Accepted`, `Cancelled`, `ManualReview`, `Failed` |
| `Accepted` | Payment 已接受，等待提交渠道。 | `Processing`, `Cancelled`, `ManualReview` |
| `Processing` | 已提交渠道或等待渠道异步结果。 | `Settled`, `Failed`, `ManualReview` |
| `Settled` | 渠道确认退款成功或对账确认已退。 | 终态 |
| `Failed` | 渠道拒绝或不可恢复失败。 | `Processing`（显式 retry）, `ManualReview`, `Cancelled` |
| `Cancelled` | 上游或客服在允许窗口内取消退款执行。 | 终态 |
| `ManualReview` | 需要客服/财务/运营人工确认。 | `Processing`, `Settled`, `Failed`, `Cancelled` |

**迁移规则**

- `Requested -> Accepted`：幂等与可退款余额校验通过。
- `Accepted -> Processing`：已调用渠道退款接口。
- `Processing -> Settled`：渠道回调/主动查询/对账确认退款成功。
- `Processing -> Failed`：渠道返回最终失败。
- 任意非终态 -> `ManualReview`：状态冲突、渠道超时超过阈值、对账差异或人工标记。

### 6.3 `ChannelCallbackRecord` 状态机

| State | Meaning | Allowed Transitions |
| --- | --- | --- |
| `Received` | 回调已接收，尚未验签或解析。 | `Verified`, `Rejected`, `Duplicate` |
| `Verified` | 验签通过，已归一化。 | `Applied`, `Parked`, `Duplicate` |
| `Rejected` | 验签失败或格式非法。 | 终态 |
| `Duplicate` | 已处理过的重复回调。 | 终态 |
| `Applied` | 回调已成功影响 Payment/Refund 状态或确认 no-op。 | 终态 |
| `Parked` | 无法匹配、乱序或与本地状态冲突，等待查询/人工处理。 | `Applied`, `Rejected` |

### 6.4 `LatePaymentCase` 状态机

| State | Meaning | Allowed Transitions |
| --- | --- | --- |
| `Opened` | late payment 已识别并隔离。 | `Investigating`, `RefundRequested`, `Resolved`, `ManualReview` |
| `Investigating` | 等待订单、Saga、渠道或财务事实确认。 | `RefundRequested`, `Resolved`, `ManualReview` |
| `RefundRequested` | 上游已决定退款，Payment 已或将创建 `Refund`。 | `Resolved`, `ManualReview` |
| `ManualReview` | 需要客服/财务人工处理。 | `RefundRequested`, `Resolved` |
| `Resolved` | 已退款、核销或与业务域达成一致处理。 | 终态 |

## 7. 命令和领域事件

### 7.1 Commands

| Command | Issuer | Idempotency Key | Payment Responsibility |
| --- | --- | --- | --- |
| `CreatePaymentIntent` | Journey Order / Booking Orchestration | businessRef + purpose + caller key | 创建付款意图，校验金额/币种/重复请求。 |
| `SubmitPayment` | Journey Order / client-facing API / Booking Orchestration | paymentIntentId + attempt key | 创建 `PaymentAttempt`，调用渠道收银台或支付接口。 |
| `AuthorizePayment` | Booking Orchestration / channel callback | channel transaction key | 记录授权成功/失败。 |
| `CapturePayment` | Channel callback / synchronous channel result | channel transaction key | 记录直接扣款事实。 |
| `CaptureAuthorizedPayment` | Booking Orchestration | authorizationId + capture request key | 对已授权资金执行扣款。 |
| `FailPayment` | Channel callback / timeout policy | attempt key | 记录失败，判断是否终态。 |
| `CancelPaymentIntent` | Journey Order / Booking Orchestration | paymentIntentId + cancel reason + caller key | 取消未完成付款，必要时释放授权。 |
| `ExpirePaymentIntent` | Payment scheduler | paymentIntentId + expiry timestamp | 将超时未完成意图置为过期。 |
| `RecordChannelCallback` | Channel adapter | channel + callback id/digest | 保存回调、验签、去重。 |
| `ApplyChannelCallbackToPayment` | Payment callback processor | callbackRecordId | 将合法回调应用到 Payment/Refund。 |
| `DetectLatePaymentSuccess` | Callback processor / reconciliation | channel transaction key | 打开或更新 `LatePaymentCase`。 |
| `RequestRefund` | Post Sales / Disruption Recovery / Booking Saga / Customer Service | sourceCaseRef + refund purpose + caller key | 创建退款执行请求，不判断业务资格。 |
| `AcceptRefund` | Payment | refundId + version | 资金校验通过，准备提交渠道。 |
| `SubmitRefundToChannel` | Payment | refundId + attempt no | 调用渠道退款接口。 |
| `SettleRefund` | Channel callback / query / reconciliation | channel refund transaction key | 记录退款成功。 |
| `FailRefund` | Channel callback / query | channel refund transaction key | 记录退款失败及可重试性。 |
| `RetryRefund` | Post Sales / Customer Service / Payment retry policy | refundId + retry no | 在允许时再次提交。 |
| `CancelRefund` | Post Sales / Customer Service | refundId + cancel key | 在未提交或渠道允许时取消退款。 |
| `MoveRefundToManualReview` | Payment policy / Customer Service / Finance | refundId + reason | 转人工审核。 |
| `ResolveLatePaymentCase` | Customer Service / Post Sales / Finance / Booking Orchestration | caseId + resolution key | 记录 late payment 处理结果。 |

### 7.2 Domain Events Published by Payment

| Event | Meaning | Consumers |
| --- | --- | --- |
| `PaymentIntentCreated` | 付款意图已创建。 | Journey Order, Booking Orchestration, Customer Service |
| `PaymentActionRequired` | 需要用户跳转、扫码或完成认证。 | Journey Order, Notification, Customer Service |
| `PaymentAuthorized` | 渠道授权占款成功。 | Booking Orchestration, Journey Order, Finance Settlement |
| `PaymentCaptured` | 渠道确认扣款成功。 | Journey Order, Booking Orchestration, Finance Settlement, Notification, Customer Service |
| `PaymentFailed` | 支付失败或尝试失败。 | Journey Order, Booking Orchestration, Notification, Customer Service |
| `PaymentIntentCancelled` | PaymentIntent 已取消。 | Journey Order, Booking Orchestration, Customer Service |
| `PaymentIntentExpired` | PaymentIntent 已过期。 | Journey Order, Booking Orchestration, Customer Service |
| `AuthorizationReleased` | 授权资金已释放或释放请求已确认。 | Booking Orchestration, Finance Settlement |
| `RefundRequested` | Payment 收到上游退款执行请求。 | Post Sales, Disruption Recovery, Customer Service |
| `RefundAccepted` | Payment 接受退款并准备执行。 | Post Sales, Disruption Recovery, Customer Service |
| `RefundSubmitted` | 退款已提交渠道。 | Post Sales, Disruption Recovery, Customer Service, Finance Settlement |
| `RefundSettled` | 渠道/对账确认退款成功。 | Post Sales, Disruption Recovery, Finance Settlement, Notification, Customer Service |
| `RefundFailed` | 退款失败。 | Post Sales, Disruption Recovery, Customer Service |
| `RefundManualReviewRequired` | 退款需要人工审核。 | Customer Service, Finance Settlement, Post Sales |
| `ChannelCallbackReceived` | 渠道回调已接收并入审计。 | Customer Service, Operations |
| `ChannelCallbackRejected` | 回调验签失败或非法。 | Security, Operations |
| `ChannelCallbackParked` | 回调停放等待处理。 | Customer Service, Operations |
| `LatePaymentDetected` | 发现 late payment。 | Journey Order, Booking Orchestration, Post Sales, Finance Settlement, Customer Service |
| `LatePaymentCaseResolved` | late payment case 已解决。 | Journey Order, Booking Orchestration, Finance Settlement, Customer Service |
| `PaymentDisputeOpened` | 支付/退款/对账状态冲突已打开争议。 | Finance Settlement, Customer Service, Operations |

### 7.3 Events Consumed by Payment

| Event | Producer | Payment Reaction |
| --- | --- | --- |
| `OrderPaymentRequested` | Journey Order | 创建或返回已有 `PaymentIntent`。 |
| `OrderPaymentCancelled` | Journey Order | 取消未完成 `PaymentIntent`；已扣款则等待上游退款命令。 |
| `BookingSagaCompensating` | Booking Orchestration | 取消 pending payment 或准备异常资金处理。 |
| `PostSalesRefundApproved` | Post Sales | 创建 `Refund`。 |
| `PostSalesRefundCancelled` | Post Sales | 在允许状态取消 `Refund`。 |
| `DisruptionRefundApproved` | Disruption Recovery | 批量创建 `Refund`。 |
| `CustomerServicePaymentAdjustmentRequested` | Customer Service | 进入人工受控处理流程。 |
| `SettlementMismatchDetected` | Finance Settlement | 打开 dispute 或 late payment investigation。 |

所有 consumed events 必须通过 `Inbox` 按 producer event id 幂等处理；所有 published events 必须通过 `Outbox` 原子写入并异步发布。

## 8. 策略和 Saga 参与点

### 8.1 订票付款 Saga

参与方：Journey Order、Booking Orchestration、Inventory/Seat、Payment、Ticketing/Entitlement、Notification。

Payment 参与点：

1. Booking Orchestration 请求创建 `PaymentIntent`。
2. 用户完成支付或授权后，Payment 发布 `PaymentCaptured` / `PaymentAuthorized`。
3. Booking Orchestration 根据 Payment 事件推进出票；出票失败时由 Booking Orchestration 或 Post Sales 决定是否发起退款。
4. Payment 不直接释放库存、不签发权益、不确认订单。

### 8.2 未支付订单取消 Saga

参与方：Journey Order、Booking Orchestration、Payment、Inventory/Seat、Notification。

Payment 参与点：

1. Journey Order 或 Booking Orchestration 在支付截止时间到达时发起 `CancelPaymentIntent` 或 Payment 自身 `ExpirePaymentIntent`。
2. 若 PaymentIntent 未扣款，Payment 进入 `Cancelled`/`Expired` 并发布事件。
3. 若取消后收到渠道成功，Payment 打开 `LatePaymentCase`，由 Booking Orchestration/Journey Order/Post Sales 决定退款或异常恢复。

### 8.3 退票/改签退款 Saga

参与方：Post Sales、Journey Order、Ticketing/Entitlement、Payment、Finance Settlement、Notification、Customer Service。

Payment 参与点：

1. Post Sales 完成资格、规则、手续费和金额决策后发起 `RequestRefund`。
2. Payment 校验 captured balance 与幂等性，接受或拒绝资金执行。
3. Payment 调用渠道退款，发布 `RefundSubmitted`、`RefundSettled` 或 `RefundFailed`。
4. Post Sales 消费结果后推进售后单；Payment 不宣布售后单完成。

### 8.4 改签补差价 Saga

参与方：Post Sales 或 Rebook context、Journey Order、Booking Orchestration、Payment、Ticketing/Entitlement。

Payment 参与点：

- 对更贵改签，Payment 创建新的 `PaymentIntent` 收取 fare difference。
- 对更便宜改签，Payment 执行由 Post Sales/Rebook 决策后的 `Refund`。
- 新票权益生效顺序由 Booking/Post Sales/Ticketing 决定，不由 Payment 决定。

### 8.5 候补/预授权 Saga

参与方：Waitlist/Booking Orchestration、Payment、Inventory/Seat、Journey Order。

Payment 参与点：

- 支持 `Authorization` 先占款，席位确认后 `CaptureAuthorizedPayment`。
- 候补失败或过期时释放授权。
- 授权过期、扣款失败或释放失败均发布事件，由 Saga 决定下一步。

### 8.6 Disruption Recovery 批量退款 Saga

参与方：Disruption Recovery、Payment、Finance Settlement、Customer Service、Notification。

Payment 参与点：

- 接收批量退款命令并按渠道限流执行。
- 暴露批次 read model：成功、失败、处理中、人工审核。
- 对大规模渠道失败进入重试/暂停策略，避免重复退款。

### 8.7 Customer Service 手工兜底

参与方：Customer Service、Payment、Finance Settlement、Journey Order、Post Sales。

Payment 参与点：

- 提供 timeline 和原始渠道摘要，帮助客服解释用户问题。
- 支持受控补发事件、主动查询渠道、移动人工审核、解决 late payment case。
- 所有人工动作必须审计，不得绕开聚合不变量。

## 9. 读模型

| Read Model | Primary Users | Content | Update Source |
| --- | --- | --- | --- |
| `PaymentStatusView` | Journey Order, Booking Orchestration, client API | PaymentIntent 状态、金额、币种、支付方式、过期时间、最后失败原因、下一步动作。 | Payment events |
| `RefundView` | Post Sales, Disruption Recovery, Customer Service | Refund 状态、金额、原因、渠道退款号、尝试次数、失败原因、预计到账信息。 | Refund events |
| `PaymentOperationTimeline` | Customer Service, Operations | 从创建 PaymentIntent 到支付/退款/回调/人工动作的时间线。 | Payment/Refund/Callback/Manual events |
| `PaymentLedgerView` | Finance Settlement, Audit | 授权、扣款、退款、释放、异常调整的不可变资金流水。 | Ledger entries + Payment events |
| `ChannelCallbackAuditView` | Operations, Security, Customer Service | 回调接收时间、验签结果、幂等结果、原文摘要、应用结果、停放原因。 | ChannelCallbackRecord events |
| `LatePaymentCaseView` | Customer Service, Finance Settlement, Booking Orchestration | late payment case 状态、关联订单/PaymentIntent/渠道交易、处理建议、负责人、解决结果。 | LatePaymentCase events |
| `PaymentReconciliationCandidateView` | Finance Settlement, Operations | 待对账、差异、渠道查询结果、可能重复扣款/退款候选项。 | Payment ledger + channel query + settlement feedback |
| `PaymentChannelHealthView` | Operations | 渠道成功率、失败率、超时率、回调延迟、退款耗时。 | Channel adapter telemetry |

读模型原则：

- 读模型可以冗余订单号、售后单号、乘客展示引用，但不得成为业务规则来源。
- 客服展示可以合并 Journey Order/Post Sales 状态，但 Payment 自身 read model 必须清楚区分资金事实与业务状态。
- 财务对账读模型必须保留 channel transaction id、platform transaction id、amount、currency、event time、posting time。

## 10. 外部系统和防腐层

### 10.1 Payment Channel ACL 职责

Payment channel 防腐层负责把渠道差异转换为平台统一语义：

- 创建支付：收银台、二维码、跳转支付、快捷支付等。
- 授权：pre-authorization、授权查询、授权过期。
- 扣款：direct capture、capture authorized payment。
- 释放授权：void/release authorization。
- 退款：全额退款、部分退款、退款查询、退款重试语义。
- 主动查询：按渠道交易号查询支付/退款最终状态。
- 回调验签：签名算法、证书轮换、时间戳校验、重放保护。
- 错误码映射：平台归一化 `retryable`、`finalFailure`、`pending`、`manualReviewRequired`。
- 报文审计：保存脱敏后的 raw request/response digest、必要字段和追踪 ID。
- 幂等映射：平台 idempotency key 与渠道 request id/out trade no 的稳定绑定。

### 10.2 防腐边界规则

- 渠道私有状态不得泄漏给 Journey Order、Post Sales 或 Booking Orchestration；只暴露 Payment 统一状态和原因码。
- 渠道成功只产生资金事实，不产生订单确认或权益发放。
- 渠道失败需要区分最终失败、可重试、处理中、需要人工确认。
- 渠道乱序回调不得覆盖本地更强事实；必须通过 callback record 和主动查询解决。
- 当渠道状态、本地状态、上游业务状态冲突时，进入 `PaymentDispute` 或 `LatePaymentCase`，不得静默修正订单。

### 10.3 与 Provider Integration 的关系

- 若 payment channel adapters 归属 Provider Integration，Provider Integration 是技术适配层，Payment 仍拥有支付语义、资金状态机、回调幂等、退款不变量。
- 若 adapters 归属 Payment，仍应以 ACL 接口隔离渠道 SDK 和报文，避免渠道概念污染核心聚合。
- 两种部署方式都必须保证：Payment 的 `Outbox` 事件是内部系统唯一可信的资金事实来源。

### 10.4 Legacy ACL

当前 `ts-inside-payment-service`、`ts-payment-service` 及相关调用方在迁移期应通过 Legacy ACL 接入：

- 把旧接口中的 order status update、ticket side effect、notification side effect 拆出为事件消费方行为。
- 把旧 payment success/fail callback 转换为 `RecordChannelCallback` + `ApplyChannelCallbackToPayment`。
- 把旧 refund amount/rule 计算从 Payment 调用链迁移到 Post Sales 或 Disruption Recovery。

## 11. 当前服务迁移影响

| Current Service | Current Concern | Target Impact |
| --- | --- | --- |
| `ts-inside-payment-service` | 内部支付逻辑、可能耦合订单状态修改、支付查询和回调处理。 | 收敛为 Payment core/ACL 的一部分；拆出订单写入副作用；引入 `PaymentIntent`、`Refund`、`ChannelCallbackRecord`、`Outbox`。 |
| `ts-payment-service` | 对外或客户端支付入口、渠道交互。 | 迁移为 `SubmitPayment` API 和 channel ACL；返回统一 next action，不直接暴露渠道私有状态。 |
| `ts-cancel-service` | 取消订单时可能同时判断退款或触发支付状态变更。 | 取消资格和库存释放留在 Journey Order/Booking/Post Sales；Payment 只接收 `CancelPaymentIntent` 或 `RequestRefund`。 |
| `ts-rebook-service` | 改签补差价/退款与票务流程耦合。 | 补差价创建新的 `PaymentIntent`；差额退款通过 Post Sales/Rebook 决策后调用 `RequestRefund`。 |
| `ts-order-service` | 订单状态与支付状态可能同步写。 | 改为消费 Payment events 推进订单状态；不直接写 Payment 表。 |
| `ts-order-other-service` | 其他订单类型支付/退款逻辑。 | 通过 businessRef/purpose 接入统一 Payment；保留订单类型差异在上游。 |
| `ts-preserve-service` | 下单/保留流程中触发支付。 | 通过 Booking Orchestration 请求 `CreatePaymentIntent`；不直接调用渠道。 |
| `ts-preserve-other-service` | 其他保留/下单流程。 | 同上，使用 Payment Open Host Service。 |
| `ts-wait-order-service` | 候补订单可能需要预授权或后扣款。 | 使用 `Authorization`，席位确认后 capture，候补失败释放授权。 |
| `ts-seat-service` | 座位锁定/释放可能受支付结果影响。 | 只消费 Booking Orchestration 决策，不直接依赖 Payment channel callback。 |
| `ts-notification-service` | 支付/退款通知。 | 消费 Payment 或上游转发事件发送消息；Payment 不内嵌通知发送。 |
| `ts-admin-order-service` | 后台订单和支付查询/人工处理。 | 改为读取 Payment read models，并通过 Customer Service/Admin 受控命令处理异常。 |
| `ts-security-service` | 安全、认证、风险校验。 | 提供风险/认证结果给 Payment；Payment 不拥有安全规则。 |
| `ts-assurance-service` | 保险商品费用和退款。 | 商品资格和金额由 Assurance/Post Sales 决定；Payment 执行收退款。 |
| `ts-food-service` | 餐食商品费用和退款。 | 商品域产生 payment/refund request；Payment 统一资金执行。 |
| `ts-consign-service` | 托运商品费用和退款。 | 同上，Payment 不理解托运业务规则。 |
| payment channel SDK/config modules | 渠道参数、证书、错误码。 | 纳入 channel ACL 管理，统一签名、幂等、查询、错误码映射和审计。 |

迁移顺序建议：

1. 先引入 `PaymentIntent` 与 callback idempotency，不改变外部用户支付体验。
2. 再把支付成功直接写订单改为发布 `PaymentCaptured` 事件，由 Journey Order/Booking Orchestration 消费。
3. 拆分 Refund：Post Sales 决策退款资格与金额，Payment 只执行资金。
4. 引入 late payment case 和 channel callback audit read model，提升异常可见性。
5. 最后迁移 Finance Settlement 对账视图和批量退款能力。

## 12. 验收标准

- [x] Payment 聚合 ownership 清晰：`PaymentIntent`、`Authorization`、`Capture`、`Refund`、`ChannelCallbackRecord`、`LatePaymentCase` 均由 Payment 负责。
- [x] 明确 Payment 不决定 ticket refund eligibility、inventory release、entitlement validity、order confirmation。
- [x] `PaymentIntent`、`Refund`、`ChannelCallbackRecord`、`LatePaymentCase` 状态机独立于其他域内部状态。
- [x] 命令、领域事件、幂等键、`Outbox`/`Inbox` 责任明确。
- [x] 支付渠道回调的签名校验、幂等、乱序、停放和审计模型明确。
- [x] late payment 不会自动确认订单、恢复库存或签发权益，只会打开异常案例并通知协作域。
- [x] Refund 的原因、资格和金额来自 Post Sales、Disruption Recovery 或其他上游决策；Payment 只执行资金与资金不变量校验。
- [x] `PaymentCaptured != EntitlementIssued`，`RefundSettled != PostSalesCase Applied` 的边界明确。
- [x] 与 Journey Order、Booking Orchestration、Post Sales、Disruption Recovery、Finance Settlement、Notification、Customer Service、Provider Integration/payment channel adapters 的协作契约明确。
- [x] payment/refund read models 覆盖状态查询、客服时间线、渠道回调审计、late payment、财务对账。
- [x] 当前服务迁移影响覆盖主要 payment/order/cancel/rebook/preserve/wait/seat/notification/admin/security/商品服务。
- [x] 跨域冲突和开放问题仅记录在第 12 节。
