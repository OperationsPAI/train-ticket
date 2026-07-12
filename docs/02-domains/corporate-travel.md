# Corporate Travel Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Corporate Travel |
| Status | proposed-ddd-baseline |
| Owner Agent | agentm-corporate-travel |
| Last Updated | 2026-07-10 |
| High-Level Inputs | `docs/adr/0003-commercial-realism-scope.md`, `docs/02-domains/account.md`, `docs/02-domains/fare-pricing.md`, `docs/02-domains/journey-order.md`, `docs/02-domains/payment.md`, `docs/02-domains/finance-settlement.md` |

## 1. 领域目标

Corporate Travel 负责企业差旅的三件套：企业协议 `CorporateAgreement`、员工授权 `EmployeeAuthorization`、月结账单 `MonthlyStatement`。它回答“企业在有效协议下可使用哪些协议价引用和账期规则、哪个员工账户可代表企业下单、一个账期内哪些订单和支付事实被纳入月结并推给 Finance Settlement”的问题。

独立建模的原因：Account 负责账户和企业成员关系但不拥有差旅协议、月结额度和账期；Fare & Pricing 负责协议价规则解释和报价，本域只保存引用；Journey Order 与 Payment 是订单和资金事实来源，本域只聚合事实；Finance Settlement 拥有收入确认、对账、发票和会计口径，本域只形成企业月结业务账单。ADR-0003 将 corporate-travel 划入 Wave D P1 B2B，上限是协议-授权-账期三件套，不建设审批流 SaaS、HR SaaS 或 ERP 集成。

## 2. 边界

### In Scope

- `CorporateAgreement`：企业协议主体、有效期、协议价引用、适用产品/渠道/线路范围、月结额度、账期日历、企业联系人摘要和版本化条款。
- `EmployeeAuthorization`：把 platform `accountId` 绑定到企业协议下的员工授权，定义成本中心、项目、预算上限、可下单范围、是否可代订和授权有效期。
- `MonthlyStatement`：按 corporateId + agreementId + billingPeriod 聚合 Journey Order、Payment、售后调整和授权快照，生成月结账单、差异队列和提交结果。
- 企业协议启停、额度冻结/释放、授权冻结/撤销、账期关闭、失败重试、审计时间线和 Outbox/Inbox。
- 企业差旅读模型：员工可用协议视图、企业账期待办、额度占用视图、账单明细、账单异常和客服查询。
- SIM 企业对方/账单回执网关防腐层：用于企业名册、协议回执、月结回执等确定性模拟行为；无真实网络。

### Out of Scope

- 不做企业审批流 SaaS、预算审批流、OA/HR/ERP 实时集成或真实企业 SSO；外部企业系统统一模拟。
- 不创建、确认、取消或调整 `JourneyOrder`；订单生命周期归 Journey Order。
- 不计算票价、折扣、税费、退改费或协议价；协议价引用交给 Fare & Pricing 规则和报价链路解释。
- 不执行支付、预授权、扣款、退款或渠道原路退回；资金语义归 Payment / Payment Channel。
- 不做收入确认、应收账款、发票、对账结算或会计分录；这些归 Finance Settlement / Invoicing。
- 不保存未脱敏证件号、员工工号原文、企业银行账号原文或真实合同扫描件；只保存必要引用、摘要和脱敏展示字段。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| CorporateAgreement | 企业差旅协议聚合根。 | 绑定 corporateId、agreementVersion、有效期、协议价引用和月结额度。 |
| Agreement Price Reference | 指向 Fare & Pricing 规则集、规则快照或 fareRuleRefs 的引用。 | 本域不解释价格，只校验引用形制、有效期和适用范围。 |
| Monthly Credit Limit | 企业协议的账期额度上限。 | Money 使用 `{currency, minorUnits}`；额度占用按账期和授权快照聚合。 |
| Billing Calendar | 企业账期日历。 | 定义 billingPeriod、cutoffAt、dueAt、timezonePolicy；边界输出均为 RFC3339 UTC。 |
| EmployeeAuthorization | 员工差旅授权聚合根。 | 绑定 `accountId`、corporateId、agreementId、成本中心/项目和可用范围。 |
| Authorization Snapshot | 下单或账单聚合时固化的员工授权版本。 | 避免后续授权变更影响历史订单归属。 |
| Cost Center | 企业内部成本归集维度。 | 只保存企业提供的代码和脱敏 displayName；不调用真实 ERP。 |
| MonthlyStatement | 企业月结账单聚合根。 | 以企业账期聚合订单、支付和调整事实，形成可提交 Finance Settlement 的业务账单。 |
| Statement Line | 月结账单行。 | 引用 orderId、paymentIntentId/refundId、amount、authorizationSnapshotRef、fareRuleRef。 |
| Statement Submission | 月结账单提交记录。 | 表示账单事实已被推送或等待 Finance Settlement 接收；不是会计入账结果。 |
| Corporate Counterparty SIM | 企业外部方模拟网关。 | 确定性、可种子化、无真实网络；用于企业名册/账单回执演示，不代表真实 SaaS。 |

## 4. 上下游契约

本节只列已在 `docs/08-contracts/` 存在的跨上下文契约；Corporate Travel 新增命令和事件只在第 7 节出现，后续单独契约化。凡既有契约没有企业月结专用入口的地方，本节明确记录当前只能引用或等待后续契约化，不杜撰端点。

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Account | 事件 `AccountCreated`、`AccountFrozen`、`AccountUnfrozen`、`AccountClosureStarted`、`AccountClosed`；端点 `GET /api/v1/accounts/{accountId}` | 员工授权必须绑定真实 platform account；账户冻结/注销会阻断新授权和新企业下单，但不改写历史账单。 |
| Fare & Pricing | 事件 `FareRuleSetPublished`、`FareRuleSetSuperseded`；端点 `GET /api/v1/fare-quotes/{quoteId}`、`POST /api/v1/fare-quotes` | 企业协议只持有 fare rule / quote 规则引用；报价、规则解释和 Money breakdown 仍由 Fare & Pricing 决定。 |
| Journey Order | 事件 `JourneyOrderCreated`、`JourneyOrderPendingPayment`、`JourneyOrderPaymentRecorded`、`JourneyOrderConfirmed`、`JourneyOrderCancelled`、`JourneyOrderPostSalesAdjusted`；端点 `GET /api/v1/journey-orders/{orderId}`、`GET /api/v1/journey-orders?accountId={accountId}&limit=20&offset=0&status=CONFIRMED` | 月结账单从订单事实获取 orderId、accountId、旅客/段引用、订单状态和金额摘要；本域不改变订单。 |
| Payment | 事件 `PaymentIntentCreated`、`PaymentAuthorized`、`PaymentCaptured`、`PaymentFailed`、`PaymentIntentCancelled`、`PaymentIntentExpired`、`RefundSettled`、`RefundFailed`；端点 `GET /api/v1/payment-intents/{paymentIntentId}`、`GET /api/v1/refunds/{refundId}` | 额度占用、账单行收款/退款归集和异常识别依赖资金事实；资金状态仍由 Payment 决定。 |
| Finance Settlement | 事件 `RevenueRecognized`、`RevenueRecognitionReversed`、`InvoiceGenerated`、`ReconciliationCaseOpened`、`ReconciliationCaseResolved`、`ReconciliationCompleted`；端点 `GET /api/v1/revenue-recognitions/{revenueRecognitionId}`、`GET /api/v1/invoices/{invoiceId}`、`GET /api/v1/reconciliation-cases/{reconciliationCaseId}` | 月结账单需要引用收入确认、冲减、发票和对账结果作为财务反馈；本域不生成会计结果。 |
| Admin & Audit | 事件 `ManualActionApproved`、`ManualActionRejected`、`ManualActionExecuted` | 企业协议启停、额度人工调整、账单失败关闭等高风险操作必须消费审批/执行结果。 |

### Downstream

| Downstream Context | Published / Invoked Existing Contract | Reason |
|---|---|---|
| Fare & Pricing | 端点 `POST /api/v1/fare-quotes`、`GET /api/v1/fare-quotes/{quoteId}` | 企业下单入口携带协议价引用请求报价；本域只提供引用和授权快照，不提供价格。 |
| Notification | 命令 `ScheduleNotification` | 协议即将到期、授权冻结/撤销、月结账单可确认、账单失败或额度不足需通知企业联系人或员工。 |
| Customer Service | 命令 `AppendTimelineEntry`；端点 `GET /api/v1/support-cases/{caseId}` | 企业协议变更、授权异常、账单差异和人工处理进入客服可见时间线。 |
| Admin & Audit | 命令 `RecordAuditEntry`、`RequestManualAction` | 协议启停、额度变更、账单关闭、失败重试上限调整和 SIM 回执异常必须审计。 |
| Finance Settlement | 既有命令 `GenerateInvoice`；端点 `GET /api/v1/invoices/{invoiceId}`、`GET /api/v1/reconciliation-cases?orderId={orderId}&limit=20&offset=0` | 当前既有契约只能用于生成/查询财务发票和对账案例；企业月结业务账单事实将在本域事件表中定义，后续由 Finance Settlement 契约化消费。 |
| Reporting | 端点 `GET /api/v1/metrics?category=operational&limit=20&offset=0`、`GET /api/v1/metrics/{metricId}`、`GET /api/v1/dashboards/{dashboardId}` 依赖的事件消费机制 | Reporting 从本域事件流构建企业差旅使用率、额度占用、账单准时率和失败原因指标。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| CorporateAgreement | 同一 corporateId + agreementCode + effectiveWindow 不能有重叠 active 版本；协议价引用必须指向已知 Fare & Pricing rule/quote ref；月结额度必须为非负 Money 且币种单一；生效后条款不可原地改写，只能版本化修订；终止后不能创建新授权。 | CreateCorporateAgreement、ActivateCorporateAgreement、ReviseCorporateAgreement、SuspendCorporateAgreement、TerminateCorporateAgreement、AdjustMonthlyCreditLimit | CorporateAgreementCreated、CorporateAgreementActivated、CorporateAgreementRevised、CorporateAgreementSuspended、CorporateAgreementTerminated、MonthlyCreditLimitAdjusted |
| EmployeeAuthorization | 必须绑定有效 accountId、corporateId 和 active agreementId；同一 accountId + agreementId + scope 在有效期内只能有一个 active 授权；账户冻结、协议暂停或额度阻断不能创建新企业下单授权；撤销/过期后不可复活，只能新建。 | GrantEmployeeAuthorization、UpdateEmployeeAuthorizationScope、SuspendEmployeeAuthorization、RevokeEmployeeAuthorization、ExpireEmployeeAuthorization、ValidateEmployeeAuthorizationForOrder | EmployeeAuthorizationGranted、EmployeeAuthorizationScopeUpdated、EmployeeAuthorizationSuspended、EmployeeAuthorizationRevoked、EmployeeAuthorizationExpired、EmployeeAuthorizationValidated |
| MonthlyStatement | 同一 corporateId + agreementId + billingPeriod 只能有一个 open statement；账单行必须引用可追溯订单/支付/退款事实和授权快照；关闭后的账单内容不可改写，只能追加调整账单；FAILED/MISSED 不能自动变为 CLOSED；提交 Finance 前必须冻结 statementHash。 | OpenMonthlyStatement、AttachStatementLine、ApplyStatementAdjustment、FreezeMonthlyStatement、SubmitMonthlyStatement、RecordStatementAccepted、RecordStatementRejected、MarkStatementFailed、MarkStatementMissed、CloseMonthlyStatement | MonthlyStatementOpened、StatementLineAttached、StatementAdjustmentApplied、MonthlyStatementFrozen、MonthlyStatementSubmitted、MonthlyStatementAccepted、MonthlyStatementRejected、MonthlyStatementFailed、MonthlyStatementMissed、MonthlyStatementClosed |
| CorporateCreditLedger | 额度账本按 agreementId + billingPeriod + currency 分区；占用、释放、账单冻结和调整必须可追溯到订单或人工审批；不得让 availableCredit minorUnits 低于零，除非协议显式允许 overdraftPolicy；额度结转只通过账期策略。 | ReserveCorporateCredit、ReleaseCorporateCredit、ConsumeCorporateCredit、ReverseCorporateCredit、CarryForwardCreditBalance | CorporateCreditReserved、CorporateCreditReleased、CorporateCreditConsumed、CorporateCreditReversed、CorporateCreditBalanceCarriedForward |

## 6. 状态机

### CorporateAgreement 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| DRAFT | 协议资料已创建，尚未生效。 | ACTIVE、REJECTED、TERMINATED |
| ACTIVE | 协议在有效期内，可创建员工授权并用于企业下单。 | SUSPENDED、REVISING、EXPIRED、TERMINATED |
| REVISING | 正在创建新版本或调整额度，旧 active 版本仍按锁定规则服务历史订单。 | ACTIVE、SUSPENDED、FAILED |
| SUSPENDED | 因额度、风控、人工或企业原因暂停新授权和新企业下单。 | ACTIVE、TERMINATED、EXPIRED |
| EXPIRED | 协议有效期结束。 | 终态 |
| REJECTED | 协议资料或审批材料被拒绝。 | 终态 |
| FAILED | 修订或激活失败并人工关闭。 | 终态 |
| TERMINATED | 企业或平台终止协议。 | 终态 |

终态 `EXPIRED`、`REJECTED`、`FAILED`、`TERMINATED` 必须可查询安息：保留读模型、版本、审计和账单引用，但不再接受改变业务结果的命令。`REJECTED` 与 `FAILED` 不可逆；需要重新签约时创建新的 `CorporateAgreement` 或新版本并引用原失败协议作为 causationId。

### EmployeeAuthorization 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| GRANTED | 员工授权已创建但可能尚未被订单使用。 | ACTIVE、SUSPENDED、REVOKED、EXPIRED |
| ACTIVE | 授权在有效期和范围内，可为企业订单生成授权快照。 | SUSPENDED、REVOKED、EXPIRED |
| SUSPENDED | 因账户冻结、协议暂停、额度不足或企业策略暂停。 | ACTIVE、REVOKED、EXPIRED |
| REVOKED | 企业管理员、员工或人工处理撤销授权。 | 终态 |
| EXPIRED | 授权有效期结束。 | 终态 |
| FAILED | 授权创建或范围变更失败并关闭。 | 终态 |

`REVOKED`、`EXPIRED`、`FAILED` 不可复活；历史订单只保留当时 `Authorization Snapshot`。Missed/Failed 类授权校验（例如账期 cutoff 已过导致 `AUTHORIZATION_WINDOW_MISSED`）不可在同一命令上重放成功，必须以新的业务材料重新请求。

### MonthlyStatement 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| OPEN | 账期已打开，可接收订单、支付和退款事实。 | FROZEN、MISSED、FAILED |
| FROZEN | cutoff 已到，账单行和 statementHash 已冻结，等待提交。 | SUBMITTED、REOPENED_FOR_ADJUSTMENT、FAILED、MISSED |
| SUBMITTED | 已提交 Finance Settlement 或等待其后续契约消费。 | ACCEPTED、REJECTED、FAILED |
| ACCEPTED | Finance Settlement 已接受或账单回执 SIM 确认。 | CLOSED |
| REJECTED | Finance 或 SIM 业务拒绝，例如金额/币种/材料不一致。 | FAILED、REOPENED_FOR_ADJUSTMENT |
| REOPENED_FOR_ADJUSTMENT | 仅允许追加调整账单或新版本，不改写原冻结材料。 | FROZEN、FAILED |
| CLOSED | 账单完成，statementHash、行项目和提交引用固化。 | 终态 |
| MISSED | 账期 cutoff/dueAt 已错过且无可提交账单，需下期调整或人工处理。 | 终态 |
| FAILED | 技术失败达到上限或人工关闭。 | 终态 |

`MISSED` 与 `FAILED` 不可逆，不能在原聚合上直接改为 `CLOSED`；必须创建调整账单或下一期补账，并保留原 statementId 为 causationId。终态 `CLOSED`、`MISSED`、`FAILED` 可查询安息，读模型长期保留但聚合不再接受改变结果的命令。

### CorporateCreditLedger 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| AVAILABLE | 账期额度可用。 | RESERVED、BLOCKED、CLOSED |
| RESERVED | 已为待确认企业订单占用额度。 | CONSUMED、RELEASED、BLOCKED |
| CONSUMED | 已确认订单/支付事实消耗额度，等待账单冻结。 | REVERSED、CLOSED |
| RELEASED | 订单取消、支付失败或授权失效释放占用。 | AVAILABLE、CLOSED |
| BLOCKED | 额度不足、协议暂停或人工阻断。 | AVAILABLE、CLOSED |
| CLOSED | 账期额度已随 MonthlyStatement 关闭。 | 终态 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| CreateCorporateAgreement | CorporateAgreement | CorporateAgreementCreated | corporateId + agreementCode + normalizedLegalName + effectiveWindowHash |
| ActivateCorporateAgreement | CorporateAgreement | CorporateAgreementActivated | agreementId + agreementVersion + activationEvidenceHash |
| ReviseCorporateAgreement | CorporateAgreement | CorporateAgreementRevised | agreementId + previousVersion + revisedMaterialHash |
| SuspendCorporateAgreement | CorporateAgreement | CorporateAgreementSuspended | agreementId + suspensionReason + operatorOrCaseRef |
| TerminateCorporateAgreement | CorporateAgreement | CorporateAgreementTerminated | agreementId + terminationReason + effectiveAt |
| AdjustMonthlyCreditLimit | CorporateAgreement | MonthlyCreditLimitAdjusted | agreementId + billingPeriod + currency + newLimitMinorUnits + approvalRef |
| GrantEmployeeAuthorization | EmployeeAuthorization | EmployeeAuthorizationGranted | agreementId + accountId + costCenter + scopeHash + validWindow |
| UpdateEmployeeAuthorizationScope | EmployeeAuthorization | EmployeeAuthorizationScopeUpdated | authorizationId + expectedVersion + scopeHash |
| SuspendEmployeeAuthorization | EmployeeAuthorization | EmployeeAuthorizationSuspended | authorizationId + reason + sourceEventId |
| RevokeEmployeeAuthorization | EmployeeAuthorization | EmployeeAuthorizationRevoked | authorizationId + revocationReason + requesterRef |
| ExpireEmployeeAuthorization | EmployeeAuthorization | EmployeeAuthorizationExpired | authorizationId + expiresAt + expiryPolicyVersion |
| ValidateEmployeeAuthorizationForOrder | EmployeeAuthorization | EmployeeAuthorizationValidated | authorizationId + orderIntentRef + agreementVersion + scopeHash |
| ReserveCorporateCredit | CorporateCreditLedger | CorporateCreditReserved | agreementId + billingPeriod + orderIntentRef + amountHash |
| ReleaseCorporateCredit | CorporateCreditLedger | CorporateCreditReleased | agreementId + billingPeriod + orderIntentRef + releaseReason |
| ConsumeCorporateCredit | CorporateCreditLedger | CorporateCreditConsumed | agreementId + billingPeriod + orderId + paymentIntentId + amountHash |
| ReverseCorporateCredit | CorporateCreditLedger | CorporateCreditReversed | agreementId + billingPeriod + orderId + refundId + amountHash |
| CarryForwardCreditBalance | CorporateCreditLedger | CorporateCreditBalanceCarriedForward | agreementId + fromBillingPeriod + toBillingPeriod + policyVersion |
| OpenMonthlyStatement | MonthlyStatement | MonthlyStatementOpened | corporateId + agreementId + billingPeriod |
| AttachStatementLine | MonthlyStatement | StatementLineAttached | statementId + lineSourceType + sourceBusinessRef + amountHash |
| ApplyStatementAdjustment | MonthlyStatement | StatementAdjustmentApplied | statementId + adjustmentReason + sourceBusinessRef + adjustmentHash |
| FreezeMonthlyStatement | MonthlyStatement | MonthlyStatementFrozen | statementId + billingPeriod + statementHash |
| SubmitMonthlyStatement | MonthlyStatement | MonthlyStatementSubmitted | statementId + statementHash + submissionAttempt |
| RecordStatementAccepted | MonthlyStatement | MonthlyStatementAccepted | statementId + financeAcceptanceRef + acceptedAt |
| RecordStatementRejected | MonthlyStatement | MonthlyStatementRejected | statementId + rejectionCode + rejectionEvidenceHash |
| MarkStatementFailed | MonthlyStatement | MonthlyStatementFailed | statementId + failureClass + attemptNo |
| MarkStatementMissed | MonthlyStatement | MonthlyStatementMissed | statementId + cutoffAt + missReason |
| CloseMonthlyStatement | MonthlyStatement | MonthlyStatementClosed | statementId + acceptedRef + closePolicyVersion |

所有命令必须携带 `sourceCommandId`、`correlationId`、`causationId` 和 RFC3339 UTC 时间戳；领域事件进入 Outbox 后采用统一 envelope。跨上下文 JSON 字段使用 camelCase；枚举在边界使用 SCREAMING_SNAKE；Money 使用 `{currency, minorUnits}`，不使用浮点或 decimal 字符串。

幂等键采用“材料折叠”：先将业务材料规范化（去空格、统一大小写、排序数组、金额按 `currency + minorUnits`、时间按 RFC3339 UTC、成本中心和 scope 按稳定字段排序），再计算 materialHash。命令携带的 UUID-v7 只作为 command identity，不替代业务幂等材料。Notes：仓库现有契约已裁决跨上下文 ID 采用 UUID-v7 形制，本域沿用该形制，且由仓储层按幂等键与聚合版本裁决重复命令返回既有结果或拒绝 `IDEMPOTENCY_KEY_REUSED`。

## 8. 策略和 Saga 参与点

### 企业协议和协议价策略

- `CorporateAgreement` 生效前必须校验协议价引用形制、Fare & Pricing rule/quote ref 可查询性、有效期窗口、币种和产品/线路范围；失败进入 `REJECTED` 或 `FAILED`，不得降级为默认散客价。
- 企业协议保存 `fareRuleRefs`、`ruleSetId`、`ruleSetVersion`、`agreementPriceRefDigest` 等引用，不保存可变价格结果；实际报价必须通过 Fare & Pricing 的 `POST /api/v1/fare-quotes` 或订单链路完成。
- `FareRuleSetSuperseded` 到达后，协议不自动改价；只将关联协议标记为需要复核或触发版本化修订。
- 月结额度按 agreementId + billingPeriod + currency 分区；跨币种协议必须拆分为多个额度策略，不允许在一个额度账本内混算。

### 员工授权策略

- 授权必须绑定 Account 的 `accountId`，并在授予、验证、账单聚合时记录 account 状态版本；`AccountFrozen` / `AccountClosed` 阻断新验证，历史账单仍使用既有授权快照。
- 员工授权范围包括 costCenter、projectCode、travelerRelationPolicy、maxTripAmount、routeScope、bookingChannel 和 delegateBooking flag；命令处理时由聚合不变量校验，不依赖读模型缓存。
- 企业管理员撤销授权后不取消已确认订单；是否取消、退款或售后由 Journey Order / Post Sales / Payment 决定。

### 月结账单和额度策略

- `JourneyOrderConfirmed` 与 `PaymentCaptured` 均到达后才能把订单金额从 reserved credit 转为 consumed credit；`JourneyOrderCancelled`、`PaymentFailed`、`PaymentIntentExpired` 释放预占。
- `RefundSettled` 和 `JourneyOrderPostSalesAdjusted` 到达后，只能追加冲减或调整账单行，不能改写已冻结账单行。
- cutoff 后 `FreezeMonthlyStatement` 固化 line ordering、statementHash、amount totals 和 sourceEventIds；提交失败重试必须使用同一 statementHash。
- 若 cutoff/dueAt 由于缺失关键订单/支付事实而错过，进入 `MISSED` 并通过下一期调整账单或人工处理；禁止直接把 `MISSED` 改成 `CLOSED`。

### Saga 参与点

- 企业下单 Saga：下单入口先验证 `EmployeeAuthorization` 和额度，Journey Order 保存 corporate authorization snapshot，Fare & Pricing 解释协议价引用，Payment 根据订单链路处理资金；Corporate Travel 只负责授权和额度事实。
- 月结 Saga：Corporate Travel 从 Journey Order / Payment / Finance facts 构建 `MonthlyStatement`，冻结后发布本域账单事件；Finance Settlement 后续契约化消费并返回接受、拒绝、发票或对账结果。
- 异常处理 Saga：账单差异、SIM 回执 Ambiguous、Finance 拒绝或人工额度调整进入 Customer Service / Admin & Audit；本域只在审批结果到达后执行受控命令。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| CorporateAgreementCatalogView | CorporateAgreementCreated、CorporateAgreementActivated、CorporateAgreementRevised、CorporateAgreementSuspended、CorporateAgreementTerminated、MonthlyCreditLimitAdjusted、FareRuleSetSuperseded | 企业后台、下单入口、Fare & Pricing 调用适配、客服。 |
| EmployeeAuthorizationView | EmployeeAuthorizationGranted、EmployeeAuthorizationScopeUpdated、EmployeeAuthorizationSuspended、EmployeeAuthorizationRevoked、EmployeeAuthorizationExpired、AccountFrozen、AccountClosed | 企业员工下单入口、企业管理员、Customer Service、Risk & Compliance。 |
| CorporateCreditAvailabilityView | CorporateCreditReserved、CorporateCreditReleased、CorporateCreditConsumed、CorporateCreditReversed、MonthlyCreditLimitAdjusted、MonthlyStatementClosed | 下单前额度校验、企业后台、客服。 |
| MonthlyStatementWorkbench | MonthlyStatementOpened、StatementLineAttached、StatementAdjustmentApplied、MonthlyStatementFrozen、MonthlyStatementRejected、MonthlyStatementFailed、MonthlyStatementMissed | 财务运营、企业后台、Customer Service。 |
| MonthlyStatementDetailView | StatementLineAttached、StatementAdjustmentApplied、MonthlyStatementFrozen、MonthlyStatementAccepted、MonthlyStatementClosed、RevenueRecognized、RevenueRecognitionReversed、InvoiceGenerated | 企业月结下载、Finance Settlement 对账、客服解释。 |
| StatementSubmissionTimeline | MonthlyStatementSubmitted、MonthlyStatementAccepted、MonthlyStatementRejected、MonthlyStatementFailed、ReconciliationCaseOpened、ReconciliationCaseResolved | 财务运营、Admin & Audit、Customer Service。 |
| CorporateTravelMetricsProjection | CorporateAgreementActivated、EmployeeAuthorizationValidated、CorporateCreditConsumed、MonthlyStatementClosed、MonthlyStatementMissed、MonthlyStatementFailed | Reporting 指标和仪表盘。 |
| SimGatewayAuditView | CorporateAgreementActivated、MonthlyStatementSubmitted、MonthlyStatementAccepted、MonthlyStatementRejected、MonthlyStatementFailed | 本域运维、Admin & Audit、故障回放。 |

读模型可以冗余 corporate display name、agreementCode、accountId、员工展示名掩码、costCenter、orderId、paymentIntentId、refundId、Money `{currency, minorUnits}`、statementHash 和 finance refs；不能保存未脱敏证件号、员工薪酬/工号原文、企业银行账号原文、真实合同扫描件或真实外部 SaaS raw payload。

## 10. 外部系统和防腐层

### Corporate Counterparty SIM ACL

ADR-0003 要求新增商业域的外部方均为模拟方。本域不接入真实企业 HR、OA、ERP、审批流 SaaS、银行授信或邮件附件网关；如演示企业对方能力，统一内置 `CorporateCounterpartySimAdapter`，不得进行真实网络调用、不得读取真实企业凭证、不得发送真实账单到外部系统。

确定性行为：

1. 可种子化：adapter 接受 `gatewaySeed`、`scenarioCode`、`corporateId`、`agreementId`、`billingPeriod`、`requestFingerprint`，相同输入必定产生相同 roster result、ack ref、statement receipt、错误码和状态序列。
2. 无真实网络：企业名册校验、协议回执、账单提交回执和状态查询均在进程内或测试 fixture 内完成；延迟、超时和 Ambiguous 结果由 seed 与场景表模拟。
3. 状态可回放：`SUBMITTED -> ACCEPTED`、`SUBMITTED -> REJECTED`、`SUBMITTED -> AMBIGUOUS -> ACCEPTED/FAILED`、`SUBMITTED -> TIMEOUT -> QUERY_STATUS` 等路径由 deterministic script 决定。
4. 错误码归一：SIM 私有错误映射为 `BUSINESS_REJECTED`、`RETRYABLE_TECHNICAL_ERROR`、`NON_RETRYABLE_TECHNICAL_ERROR`、`AMBIGUOUS_RESULT`、`DUPLICATE_REQUEST`、`ROSTER_MISMATCH`、`CREDIT_POLICY_BLOCKED`。
5. 副作用保护：协议确认和月结账单 submit 使用 sim idempotency key；超时后优先 `QuerySimSubmissionStatus`，禁止以新外部意图盲目重放。
6. Raw archive：仅保存脱敏 raw request/response、hash、mappingVersion、scenarioCode、seedVersion 和 simReceiptRef；不保存未脱敏员工证件、真实企业合同、银行账号或联系人敏感信息。
7. Capability descriptor：声明 `VALIDATE_ROSTER`、`ACK_AGREEMENT`、`RECEIVE_MONTHLY_STATEMENT`、`QUERY_SUBMISSION_STATUS` 四类能力，以及是否同步完成、最大重试次数、超时策略和是否支持 Ambiguous 回放。

### Fare & Pricing ACL

- 协议价引用转换为 Fare & Pricing 可理解的 `fareRuleRefs`、`productCode`、`channel`、`segmentRefs` 和 travelerRefs；本域不把 CorporateAgreement 内部条款泄露给定价聚合。
- Fare & Pricing 返回的 Money 必须遵守 `{currency, minorUnits}`；协议额度校验只使用定价结果的金额摘要和 rule snapshot ref。
- 规则缺失、引用过期、币种冲突或定价失败时，本域拒绝企业授权/额度占用，不自行计算替代价格。

### Finance Settlement ACL

- `MonthlyStatement` 冻结后转换为稳定的 statement package：statementId、statementHash、billingPeriod、corporateId、agreementId、line source refs、Money totals、sourceEventIds 和 authorization snapshot refs。
- 既有 Finance Settlement 仅提供收入确认、发票和对账契约；企业月结专用消费命令需后续契约化。本域在设计阶段通过 Outbox 事件与提交记录表达，不直接写 Finance 数据库。
- Finance 的 `RevenueRecognized`、`RevenueRecognitionReversed`、`InvoiceGenerated` 和 `ReconciliationCase*` 事件进入本域后只作为账单反馈和读模型材料，不反向修改已关闭账单。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| 企业/代理后台模块 | 企业协议、员工授权、额度和账期迁移为 CorporateAgreement / EmployeeAuthorization / CorporateCreditLedger；后台只通过命令修改，不直接写企业订单标记。 |
| `ts-user-service`, `ts-auth-service` | 继续归 Account；Corporate Travel 只引用 accountId 和账户状态事件，不复制登录身份或联系方式全量。 |
| `ts-order-service`, `ts-preserve-service`, `ts-order-other-service` | 企业订单创建时保存 corporate authorization snapshot 和 agreement price refs；订单生命周期仍在 Journey Order。 |
| `ts-price-service` | 协议价计算迁移为 Fare & Pricing 规则引用；Corporate Travel 不维护价格算法、折扣表或票价缓存。 |
| `ts-payment-service`, `ts-inside-payment-service` | 企业月结额度占用和账单聚合消费 Payment 事实；支付、退款和渠道状态仍归 Payment。 |
| 财务/月结 Excel 或人工对账脚本 | 迁移为 MonthlyStatement、StatementSubmissionTimeline 和 MonthlyStatementDetailView；人工更正必须经 Admin & Audit。 |
| `ts-notification-service` | 消费企业协议、授权和账单事实后通过 `ScheduleNotification` 发送企业联系人/员工通知。 |
| `ts-admin-order-service` | 企业协议启停、额度调整、账单关闭等人工动作转为受控命令并调用 `RecordAuditEntry`。 |
| 数据库中散落的 corporateId、companyName、monthlyPayFlag 字段 | 迁移为 Corporate Travel 聚合和读模型；其他服务只保留购买时 snapshot/ref，不复制企业敏感资料。 |

迁移顺序建议：先建立企业协议和员工授权读模型；再在下单链路接入授权快照和 Fare & Pricing 协议价引用；随后接入 Payment/Journey Order 事实构建额度账本；最后冻结月结账单并与 Finance Settlement 契约化对接。

## 12. 验收标准

- Corporate Travel 的聚合所有权明确：`CorporateAgreement`、`EmployeeAuthorization`、`MonthlyStatement`、`CorporateCreditLedger` 归本上下文。
- 边界不越 ADR-0003：只做协议、授权、账期/月结账单，不做审批流 SaaS、HR/ERP 集成、真实企业 SSO 或资金结算。
- 与 Account 边界明确：员工授权绑定 accountId 和账户状态事实，不复制账户内部资料、不拥有 CorporateAccount 登录关系。
- 与 Fare & Pricing 边界明确：协议价只保存引用和适用范围，价格计算、规则解释和 Money breakdown 不在本域。
- 与 Journey Order / Payment 边界明确：月结账单聚合订单和资金事实，不创建/取消订单，不执行支付/退款。
- 与 Finance Settlement 边界明确：MonthlyStatement 是企业月结业务账单；收入确认、发票、对账和会计结果归 Finance Settlement，企业月结消费入口后续单独契约化。
- 上下游契约表只引用 `docs/08-contracts/` 已存在的事件、命令和端点；本域新增事件/命令仅出现在第 7 节。
- SIM 企业对方防腐层确定性、可种子化、无真实网络，覆盖成功、拒绝、失败、超时和 Ambiguous 查询路径。
- 状态机覆盖协议、授权、账单和额度；终态可查询安息；`MISSED` / `FAILED` / `REJECTED` 类不可逆规则已写明。
- 命令和领域事件表包含幂等键；幂等键使用材料折叠，UUID-v7 仅作为命令/事件身份并由仓储层裁决重复。
- 读模型支持企业后台、员工下单、客服时间线、财务运营、Reporting 指标和 SIM 网关审计。
- 文档只新增领域设计，不新增契约文档、服务目录或代码；`make check` 不因本文件影响 contract-lint 或 skeleton-check。
