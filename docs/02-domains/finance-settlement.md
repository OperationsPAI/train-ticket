# Finance Settlement Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Finance Settlement |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-finance-settlement |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/context-map.md`, `docs/02-domains/payment.md`, `docs/02-domains/fare-pricing.md`, `docs/02-domains/post-sales.md` |

## 1. 领域目标

Finance Settlement 负责把交易、资金、履约、供应商和税务事实转换为可审计的财务事实。它回答“平台应该确认多少收入、应向哪个供应商结算多少、渠道资金是否到账、退款如何入账、差异由谁处理、发票和税务需要哪些输入”的问题。

本领域独立存在的原因：

1. `Payment` 是资金渠道事实来源，只说明扣款、授权释放、退款、渠道回调和 late payment；Finance Settlement 负责财务账、清分、对账、收入确认和差异处理。
2. `Fare & Pricing` 是价格、税费、手续费和退改规则计算来源；Finance Settlement 消费其冻结明细，不重新定价。
3. `Journey Order` 和 `Post Sales` 提供购买、取消、退改、补偿等业务原因；Finance Settlement 不直接改变订单、支付或票证状态。
4. 多供应商、多币种、组合支付、附加服务、部分退款、补偿、渠道对账和供应商结算需要统一 `Ledger` 与 `SettlementBatch`，否则交易事实与财务事实会散落在订单、支付和供应商适配层。
5. 财务差异常常需要人工证据、审批和审计链，必须与交易核心状态推进解耦，避免对账结果直接回写订单或支付。

Finance Settlement 的目标不是成为通用会计总账系统，而是在出行交易平台内部建立财务子账、清结算和对账边界，并向 Reporting、Admin & Audit、税务/发票系统和外部财务系统发布稳定输入。

## 2. 边界

### In Scope

- `Ledger`：记录平台交易子账，包括应收、实收、退款、收入、税费、供应商应付、渠道手续费、补偿成本、人工调整。
- `SettlementBatch`：按供应商、渠道、币种、周期和合同规则生成结算批次，跟踪应付、应收、扣减、付款和关闭。
- `Reconciliation`：对 Payment channel、供应商账单、银行/清算文件、平台交易事件进行对账和差异归类。
- `RevenueRecognition`：基于出票、履约、取消、退改、附加服务履约和规则快照确认或冲减收入。
- `RefundAccounting`：把 Payment `RefundSettled`、Post Sales 决策和费用保留映射为退款入账、收入冲减、税费返还或补偿成本。
- `Invoice` / 税务输入：生成发票候选、税费明细、红冲或更正输入，支持用户、企业和供应商发票场景。
- `Payout`：对供应商、承运商、渠道服务方或人工赔付对象的付款指令准备、审批、执行回执和失败处理。
- 多供应商、多币种、组合支付、附加服务、优惠/补偿、部分退款和人工差异的财务归集。
- 面向客服、运营、财务和审计的读模型：账务时间线、结算批次、差异队列、发票输入和供应商余额。

### Out of Scope

- 不拥有 `PaymentIntent`、`Refund`、渠道回调、扣款或退款执行；这些属于 Payment。
- 不计算票价、税费、手续费、优惠、退改费或差价；这些属于 Fare & Pricing，Finance 只消费冻结明细。
- 不决定订单是否创建、支付、取消、完成、改签成功或售后完成；这些属于 Journey Order、Booking Orchestration、Post Sales。
- 不签发、作废或恢复票证，不判断履约是否完成；这些属于 Entitlement & Ticketing 和 Fulfillment。
- 不直接调用供应商预订、取消或改签接口；供应商原始账单通过 Provider Integration / Supplier ACL 进入。
- 不发送通知，不作为 Reporting 的离线分析事实源反向修正交易。
- 不替代企业 ERP、法定总账或税控系统；本域提供可追溯的交易财务输入和内部子账。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| FinanceSettlement | 财务清结算上下文，负责交易财务事实、对账、结算、收入确认和发票输入。 | 通用域，但模型必须严谨。 |
| Ledger | 平台交易子账，由不可变 `LedgerEntry` 组成。 | 不是外部 ERP 总账。 |
| LedgerEntry | 一条借贷方向、金额、币种、会计科目、业务引用和来源事件的账务记录。 | 追加式，不原地修改。 |
| SettlementBatch | 某一供应商、渠道、币种和周期下的结算批次。 | 聚合应付、应收、扣减和差异。 |
| Reconciliation | 平台事实、渠道文件、供应商账单和银行清算结果的匹配过程。 | 差异进入人工队列或受控调整。 |
| RevenueRecognition | 收入确认策略和结果。 | 依据履约、出票、取消、退改和附加服务完成事实。 |
| RefundAccounting | 退款入账模型，区分本金退回、手续费保留、税费返还、补偿成本和渠道手续费。 | 资金执行仍属 Payment。 |
| Invoice | 发票或税务输入候选。 | 可包括蓝票、红冲、更正和供应商票据。 |
| Payout | 对供应商或赔付对象的付款准备和执行记录。 | 付款通道可由外部财务/银行系统执行。 |
| SettlementDifference | 对账或结算中的差异项。 | 包含金额差、币种差、状态差、重复项、缺失项。 |
| ManualAdjustment | 经审批的人工账务调整。 | 必须有 operatorId、reason、evidenceRef、approvalRef。 |
| FinancialComponent | 交易金额拆分组件。 | fare、Tax、service fee、Discount、supplier penalty、ancillary、compensation。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Payment | `PaymentCaptured`、`RefundSettled`、`AuthorizationReleased`、`LatePaymentDetected`、`PaymentLedgerEntry`、channel reconciliation candidate | 资金事实、渠道交易号、退款状态和 late payment 是账务与渠道对账输入。 |
| Fare & Pricing | FareBreakdown、Tax、ServiceFee、Discount、RefundFee、ChangeFee、RuleSnapshot、TaxAndFeeAuditView | 财务拆账、税费、服务费保留和收入冲减需要冻结价格明细。 |
| Journey Order | Order snapshot、Order item、purchase commitment、commercial summary events | 识别交易主体、用户、商品、供应商、订单项和购买承诺。 |
| Post Sales | RefundDecision、fee retained、supplier penalty、compensation reason、`PostSalesApplied` | 售后业务原因、费用保留和补偿归集来源。 |
| Fulfillment | `SegmentCompleted`、`EntitlementBoarded`、service consumed facts | 收入确认和供应商应付常依赖履约事实。 |
| Entitlement & Ticketing | `EntitlementIssued`、`EntitlementVoided`、ticket references | 出票和作废是收入递延、供应商应付和退款冲减的重要事实。 |
| Provider Integration | supplier statement、provider settlement file、provider fee/penalty result、external transaction refs | 供应商账单、票号、外部订单号和供应商罚金需要经 ACL 归一化。 |
| Ancillary Service | ancillary purchase、fulfillment、refund policy result | 附加服务收入、供应商分成和退款入账输入。 |
| Admin & Audit / Customer Service | approved manual adjustment、evidence、operator and approval refs | 人工差异、赔付、核销和调整必须受控并可审计。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Reporting | finance facts、revenue summary、settlement metrics、difference metrics | 报表读取财务结果，不反向修改交易。 |
| Admin & Audit | SettlementBatchView、ReconciliationDifferenceQueue、ManualAdjustmentAudit | 财务运营、审批和审计。 |
| Payment | `SettlementMismatchDetected`、channel dispute feedback、confirmed duplicate/short-settled facts | Payment 可据此打开 dispute 或 late payment investigation，但不由 Finance 直接改 Payment 状态。 |
| Customer Service | FinanceTimeline、invoice status、refund accounting explanation、difference status | 客服解释账务、发票和退款入账。 |
| External Finance / ERP | Ledger export、settlement voucher、payout request、tax report input | 对接总账、付款和税务系统。 |
| Provider Integration / Supplier Portal | supplier settlement statement、difference notice、payout status | 供应商确认结算、处理差异和查看付款。 |
| Invoice / Tax platform | invoice candidate、tax line items、red invoice request | 法票或税控系统输入。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| Ledger | `LedgerEntry` 只能追加；同一来源事件和 posting purpose 幂等；借贷金额在同一 posting batch 内平衡；币种不可隐式混算；人工调整必须有审批和证据。 | PostFinancialEvent、ReverseLedgerEntry、PostManualAdjustment、CloseLedgerPeriod | LedgerEntryPosted、LedgerEntryReversed、ManualAdjustmentPosted、LedgerPeriodClosed |
| SettlementBatch | 批次必须绑定 supplier/channel、currency、period、contract version；Submitted 后金额不可静默改写；差异未解决不得关闭；Payout 金额必须等于已批准净额。 | CreateSettlementBatch、AddSettlementItem、SubmitSettlementBatch、ApproveSettlementBatch、RecordPayoutResult、CloseSettlementBatch | SettlementBatchCreated、SettlementItemAdded、SettlementBatchSubmitted、SettlementBatchApproved、PayoutRecorded、SettlementBatchClosed |
| Reconciliation | 同一外部文件和平台周期只能生成一个 active run；匹配规则版本固定；差异必须分类、分配 owner 并进入解决状态；人工解决不可绕过审计。 | StartReconciliationRun、MatchReconciliationItem、OpenSettlementDifference、ResolveSettlementDifference、EscalateDifference | ReconciliationRunStarted、ReconciliationItemMatched、SettlementDifferenceOpened、SettlementDifferenceResolved、SettlementDifferenceEscalated |
| Invoice | 发票候选必须来自已确认交易或合规允许的预开规则；税额与 Tax 组件一致；红冲必须引用原发票；重复开票需拦截。 | CreateInvoiceCandidate、IssueInvoiceInput、VoidInvoiceInput、CreateRedInvoiceInput、MarkInvoiceAccepted | InvoiceCandidateCreated、InvoiceInputIssued、InvoiceInputVoided、RedInvoiceInputCreated、InvoiceAccepted |
| RevenueRecognition | 收入确认必须引用履约/出票/售后事实和价格快照；已确认收入只能通过冲减或调整反向处理；确认时点策略版本固定。 | RecognizeRevenue、DeferRevenue、ReverseRecognizedRevenue、ApplyRevenueAdjustment | RevenueRecognized、RevenueDeferred、RevenueReversed、RevenueAdjustmentApplied |
| Payout | Payout 必须引用已批准 SettlementBatch 或人工赔付决策；同一 payee + batch + purpose 幂等；失败可重试但不得重复付款。 | PreparePayout、ApprovePayout、SubmitPayout、RecordPayoutFailure、MarkPayoutSettled | PayoutPrepared、PayoutApproved、PayoutSubmitted、PayoutFailed、PayoutSettled |

## 6. 状态机

### SettlementBatch 状态机

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Open | 批次已创建，仍可加入结算项。 | Calculated、Cancelled |
| Calculated | 已按规则计算应收应付和扣减。 | UnderReview、Open、Cancelled |
| UnderReview | 财务或供应商审核中。 | Approved、DifferencePending、Rejected |
| DifferencePending | 存在未解决差异。 | UnderReview、Approved、Rejected |
| Approved | 净额已批准，可生成 Payout。 | PayoutPending、Closed |
| PayoutPending | 已生成或提交付款，等待回执。 | Paid、PayoutFailed |
| PayoutFailed | 付款失败或退回。 | PayoutPending、DifferencePending |
| Paid | 付款已结清。 | Closed |
| Closed | 批次关闭，后续变化走新调整批次。 | 终态 |
| Rejected | 批次被拒绝，需要重建或拆分。 | 终态 |
| Cancelled | 批次作废且未对外结算。 | 终态 |

### Reconciliation Difference 状态机

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Opened | 差异已识别。 | Investigating、AutoResolved、ManualReview |
| Investigating | 等待渠道、供应商、Payment 或交易事实补充。 | Resolved、ManualReview、Escalated |
| ManualReview | 需要人工判断、证据或审批。 | Resolved、Escalated、Rejected |
| Escalated | 超阈值或跨域冲突升级。 | Resolved、Rejected |
| AutoResolved | 系统按规则确认无须人工。 | Resolved |
| Resolved | 已归类并产生调整、反馈或关闭理由。 | 终态 |
| Rejected | 差异项无效或来源文件错误。 | 终态 |

### Invoice 状态机

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Candidate | 已形成发票候选。 | IssuedInput、Suppressed、Expired |
| IssuedInput | 已向税务/发票系统提交输入。 | Accepted、Rejected、RedInvoiceRequested |
| Accepted | 外部系统确认开票。 | RedInvoiceRequested、Corrected |
| Rejected | 外部系统拒绝。 | Candidate、Suppressed |
| RedInvoiceRequested | 已请求红冲或冲销。 | RedInvoiced、Rejected |
| RedInvoiced | 红冲已确认。 | Corrected、终态 |
| Corrected | 更正票据已完成。 | 终态 |
| Suppressed | 因规则或人工决定不开发票。 | 终态 |
| Expired | 超过候选有效期未提交。 | 终态 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| PostFinancialEvent | Ledger | LedgerEntryPosted | sourceEventId + accountingPurpose + componentCode |
| ReverseLedgerEntry | Ledger | LedgerEntryReversed | originalEntryId + reversalReason |
| PostManualAdjustment | Ledger | ManualAdjustmentPosted | adjustmentRequestId + approvalRef |
| RecognizeRevenue | RevenueRecognition | RevenueRecognized | orderItemId + componentCode + recognitionPolicyVersion |
| ReverseRecognizedRevenue | RevenueRecognition | RevenueReversed | revenueEntryId + sourceEventId |
| StartReconciliationRun | Reconciliation | ReconciliationRunStarted | sourceType + fileId + period |
| OpenSettlementDifference | Reconciliation | SettlementDifferenceOpened | reconciliationRunId + itemHash |
| ResolveSettlementDifference | Reconciliation | SettlementDifferenceResolved | differenceId + resolutionVersion |
| CreateSettlementBatch | SettlementBatch | SettlementBatchCreated | supplierOrChannelId + period + currency + batchPurpose |
| AddSettlementItem | SettlementBatch | SettlementItemAdded | batchId + sourceFinancialEventId |
| SubmitSettlementBatch | SettlementBatch | SettlementBatchSubmitted | batchId + calculationVersion |
| ApproveSettlementBatch | SettlementBatch | SettlementBatchApproved | batchId + approvalRef |
| PreparePayout | Payout | PayoutPrepared | batchId + payeeId + netAmount |
| RecordPayoutResult | Payout / SettlementBatch | PayoutSettled / PayoutFailed | payoutId + externalResultId |
| CreateInvoiceCandidate | Invoice | InvoiceCandidateCreated | orderItemId + taxComponentHash |
| IssueInvoiceInput | Invoice | InvoiceInputIssued | invoiceCandidateId + submitAttempt |
| CreateRedInvoiceInput | Invoice | RedInvoiceInputCreated | originalInvoiceId + correctionReason |
| CloseLedgerPeriod | Ledger | LedgerPeriodClosed | period + ledgerBookId |

所有外部事件消费必须通过 Inbox 幂等处理；所有 Finance Settlement 领域事件必须通过 Outbox 发布。金额事件必须携带 amount、currency、component、source event、business reference、posting time 和 accounting time。

## 8. 策略和 Saga 参与点

### 交易入账策略

- 收到 `PaymentCaptured` 后，Finance Settlement 记录应收/实收、渠道手续费候选和未确认收入；不把订单改成已支付。
- 收到 `EntitlementIssued` 或供应商确认事实后，记录供应商应付候选；是否立即确认收入取决于 RevenueRecognition 策略。
- 收到 `SegmentCompleted`、附加服务完成或履约完成事实后，确认或释放递延收入。
- 对组合支付，按 Payment 提供的资金组件和 Fare & Pricing 的 FinancialComponent 拆分到同一订单项或多个供应商维度。

### 退款和售后入账策略

- Post Sales 决定退款原因、手续费保留、供应商罚金和补偿；Payment 执行 `Refund`；Finance Settlement 在 `RefundSettled` 后入账。
- 普通退款冲减应收、实收或递延收入；不可退服务费可转为收入；税费按 Tax 可退标识冲回。
- 补偿型退款若超出原支付金额或不对应原路退回，计入补偿成本或人工赔付，不改写原交易金额。
- `RefundSettled` 不等于 `PostSalesApplied`，Finance 需要同时追踪资金事实和售后业务结果。

### 对账和差异策略

- 渠道对账以 Payment `PaymentReconciliationCandidateView`、渠道文件和银行清算结果匹配。
- 供应商对账以平台出票/履约/退票事实、Provider Integration 归一化账单和合同规则匹配。
- 差异分类包括 missing-in-channel、missing-in-platform、amount-mismatch、currency-mismatch、duplicate、late-payment、refund-lag、provider-penalty-conflict。
- Finance 可向 Payment 发布 `SettlementMismatchDetected` 或 dispute feedback，但不能直接修改 PaymentIntent、Refund 或订单状态。

### 供应商结算和 Payout 策略

- `SettlementBatch` 按供应商、合同、币种和结算周期汇总主票、附加服务、退票扣减、供应商罚金、平台佣金和人工调整。
- 批次提交后进入审核；差异未解决前不得关闭。
- Payout 只对 Approved 批次或经审批人工赔付生成；付款回执只影响 Payout/SettlementBatch，不反向确认供应商业务状态。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| FinanceTimelineView | Payment、Post Sales、Entitlement、Fulfillment、Ledger events | 客服、财务排障、审计。 |
| LedgerView | LedgerEntryPosted、LedgerEntryReversed、ManualAdjustmentPosted | 财务、External Finance、Audit。 |
| RevenueRecognitionView | RevenueRecognized、RevenueDeferred、RevenueReversed | Reporting、财务月结。 |
| RefundAccountingView | RefundSettled、RefundDecisionCreated、LedgerEntryPosted | 客服、财务、Post Sales 查询。 |
| ReconciliationDashboard | ReconciliationRunStarted、ReconciliationItemMatched、SettlementDifferenceOpened/Resolved | 财务运营、渠道运营、供应商运营。 |
| SettlementBatchView | SettlementBatch events、Payout events | 供应商结算、Admin & Audit、Supplier Portal。 |
| SupplierBalanceView | Ledger entries、SettlementBatch、Payout | 供应商余额、应付应收、扣减和未结项。 |
| InvoiceCandidateView | Invoice events、TaxAndFeeAuditView、Order snapshots | 发票系统、客服、企业客户运营。 |
| MultiCurrencyExposureView | Ledger entries、FX rates、SettlementBatch | 财务监控、汇率差异分析。 |
| ManualAdjustmentQueue | SettlementDifferenceOpened、ManualAdjustmentPosted、approval events | Admin & Audit、财务主管。 |

读模型可以冗余订单号、车次、供应商名、旅客脱敏信息和渠道交易号；规则判断和账务 posting 必须回到聚合命令，不能靠读模型直接改账。

## 10. 外部系统和防腐层

### Payment / Channel ACL

- 将渠道对账文件、清算文件、手续费、结算日期和外部交易号转换为平台 reconciliation item。
- 渠道私有状态不能覆盖 Payment 资金事实；差异先进入 Reconciliation，再反馈 Payment dispute。
- 组合支付、部分退款、渠道手续费和汇率必须保留原渠道金额与平台记账金额。

### Provider Settlement ACL

- Provider Integration 提供供应商账单、票号、外部订单号、退票罚金、佣金、补贴和应收应付文件。
- ACL 将供应商语言映射为 supplier settlement item，不把 PNR、外部错误码或供应商私有状态写入核心聚合。
- 供应商账单与平台事实冲突时，打开 `SettlementDifference`，不直接改订单、票证或 Payment。

### Tax / Invoice / ERP ACL

- 税控、发票、ERP 和银行付款系统通过 ACL 接收 Finance Settlement 的稳定输入。
- 外部系统回执只更新 `Invoice`、`Payout` 或导出状态；交易域状态不受其直接驱动。
- 法币、税率、汇率、舍入和会计科目映射必须版本化并可审计。

### Legacy Finance ACL

- 迁移期将旧支付表、订单金额、退款记录、供应商结算脚本和人工 Excel 差异转换为 Ledger/Reconciliation/SettlementBatch 事件。
- 旧系统中直接改订单或退款状态的财务修正必须改为 ManualAdjustment 或差异反馈命令。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-inside-payment-service`, `ts-payment-service` | 提供资金事实和渠道对账候选；清结算、差异和会计分录从支付服务剥离。 |
| `ts-order-service`, `ts-order-other-service` | 订单金额和订单项快照作为财务输入；不再由财务对账直接改订单状态。 |
| `ts-cancel-service`, `ts-rebook-service` | 退款原因、手续费保留、差价和补偿通过 Post Sales / Fare & Pricing 输入 Finance；旧服务不再直接写财务结果。 |
| `ts-price-service` | 价格、税费、服务费、Discount 和退改费明细需要版本化输出，支撑 Ledger component。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 下单/出票阶段产生供应商应付候选和递延收入候选，不直接结算。 |
| `ts-execute-service` | 履约完成事实用于 RevenueRecognition 和供应商结算确认。 |
| `ts-ticket-office-service`, `ts-voucher-service` | 票据、取票点、凭证或券相关财务事实通过 ACL 纳入 Invoice、Payout 或补偿成本。 |
| `ts-assurance-service`, `ts-food-service`, `ts-consign-service` | 附加服务收入、供应商分成、退款和税费拆分接入 FinancialComponent。 |
| `ts-admin-order-service` | 后台财务操作迁移为差异处理、人工调整和审批命令，保留完整审计。 |
| batch scripts / Excel reconciliation | 迁移为 ReconciliationRun、SettlementDifference 和 SettlementBatch，逐步取消手工文件作为唯一事实源。 |

迁移顺序建议：先建立 `Ledger` 只追加子账和 Payment 资金事实投影；再接入 Fare & Pricing 金额组件和 Post Sales 退款原因；随后落地渠道 Reconciliation 与差异队列；最后迁移供应商 SettlementBatch、Payout、Invoice 和 ERP 导出。

## 12. 验收标准

- Finance Settlement 的聚合所有权明确：`Ledger`、`SettlementBatch`、`Reconciliation`、`Invoice`、`RevenueRecognition`、`RefundAccounting`、`Payout` 属于本上下文。
- 与 Payment 边界明确：Payment 提供资金渠道事实，Finance 做账务、对账和差异反馈，不改 Payment 状态。
- 与 Fare & Pricing 边界明确：价格、税费、手续费和退改费计算来自规则快照，Finance 不重新定价。
- 与 Journey Order / Post Sales 边界明确：订单和售后提供业务原因与范围，Finance 不直接改变订单、支付或票证状态。
- 多供应商、多币种、组合支付、退款、补偿、附加服务、渠道对账、供应商结算和人工差异均已覆盖。
- `LedgerEntry`、`SettlementBatch`、`Reconciliation Difference`、`Invoice`、`Payout` 的状态机和不变量不依赖其他域内部状态。
- 财务差异只能形成差异、争议、人工调整或反馈事件，不得静默修改交易核心事实。
- 当前服务迁移影响覆盖支付、订单、取消、改签、价格、出票履约、附加服务、后台和手工对账脚本。
