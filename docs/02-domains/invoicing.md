# Invoicing Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Invoicing |
| Status | proposed-ddd-baseline |
| Owner Agent | agentm-invoicing |
| Last Updated | 2026-07-10 |
| High-Level Inputs | `docs/adr/0003-commercial-realism-scope.md`, `docs/02-domains/journey-order.md`, `docs/02-domains/post-sales.md`, `docs/02-domains/finance-settlement.md` |

## 1. 领域目标

Invoicing 负责面向乘客和企业客户的发票抬头、电子发票、退款红冲、行程单和报销导出。它回答“谁可以用哪个抬头开具哪张订单的合法票据、电子发票是否已被税局网关接受、已开票订单退款前是否完成红冲、行程单与报销包能否导出”的问题。

本领域独立存在的原因：

1. `Journey Order` 是商业订单事实来源，但不拥有税务票据生命周期。
2. `Post Sales` 负责退款、退改和补偿编排；已开票订单退款必须先红冲的不变量属于发票域，Post Sales 只消费可退款/已红冲结果。
3. `Finance Settlement` 提供收入确认、金额组件和税费口径；Invoicing 不重新定价、不重算税费，只按财务口径生成票据。
4. 税局电子发票网关、票据号码、红字发票、行程单和报销包有独立合规审计、失败处理和查询需求，不能散落在订单或财务服务中。
5. ADR-0003 明确外部税局网关为模拟方；本域通过防腐层提供确定性、可种子化、无真实网络的 SIM 行为。

## 2. 边界

### In Scope

- `InvoiceTitle` 抬头管理：个人抬头、企业抬头、纳税人识别号、企业地址电话、开户行账号、默认抬头和有效期。
- `InvoiceRequest` 开票请求：订单、旅客/账户、抬头、金额口径、可开票收入项、电子邮箱或报销接收信息。
- `EInvoice` 电子发票：蓝票签发、SIM 税局网关提交、受理、拒绝、失败、重试、下载引用和作废/红冲关联。
- `RedFlush` 退款红冲：必须关联原电子发票；已开票订单的退款放行前必须完成红冲或进入人工可审计的阻断状态。
- `ItineraryReceipt` 行程单：基于已确认订单和出票/履约事实生成乘车行程证明，不替代电子发票金额凭证。
- `ReimbursementExport` 报销导出：聚合电子发票、行程单、订单摘要和财务金额明细，生成可下载导出包。
- 发票、红冲、行程单和报销包的读模型、审计时间线、幂等处理和 Outbox 发布。
- SIM 税局网关的请求、响应、错误码和状态查询防腐层。

### Out of Scope

- 不创建、确认、取消或调整 `JourneyOrder`；订单状态和订单事实归 Journey Order。
- 不决定退款规则、退款金额、退改手续费或补偿；这些归 Post Sales、Fare & Pricing 和 Finance Settlement。
- 不执行支付退款或原路退回；资金状态归 Payment / Payment Channel。
- 不进行收入确认、对账、结算或会计分录；金额和税费口径归 Finance Settlement。
- 不做真实税局、ERP、邮箱或企业报销 SaaS 网络集成；ADR-0003 阶段外部方全部模拟。
- 不保存未脱敏证件号、企业敏感资质原件或真实税控凭证密钥；只保存必要摘要、引用和脱敏展示字段。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| InvoiceTitle | 用户可选择的发票抬头。 | 分为 `PERSONAL` 与 `ENTERPRISE`；企业抬头必须有 tax identity 字段。 |
| PersonalTitle | 个人抬头。 | 绑定 accountId，可用真实姓名或“个人”展示名；不得暴露未脱敏证件号。 |
| EnterpriseTitle | 企业抬头。 | 包含企业名称、纳税人识别号、地址电话、开户行账号等版本化资料。 |
| InvoiceRequest | 一次开票意图。 | 绑定 orderId、titleId、开票范围、金额组件快照和幂等键。 |
| EInvoice | 电子发票聚合根。 | 蓝票或红字发票的内部权威生命周期；SIM 网关号码只是外部引用。 |
| Blue Invoice | 正向电子发票。 | 对应已确认收入或允许预开票的订单金额。 |
| RedFlush | 红冲请求和结果。 | 必须引用 originalInvoiceId；完成后才允许已开票订单退款继续执行。 |
| ItineraryReceipt | 行程单证明。 | 用于出行证明和报销辅助，不等于税务发票。 |
| ReimbursementExport | 报销导出包。 | 包含电子发票、行程单、订单摘要、金额组件和校验摘要。 |
| Tax Bureau Gateway SIM | 税局电子发票模拟网关。 | 确定性、可种子化、无真实网络；所有响应可回放。 |
| Invoice Amount Basis | 开票金额口径。 | 来自 Finance Settlement 的 `RevenueRecognized` / `RevenueRecognitionReversed` 或 `InvoiceGenerated` 事实。 |
| Refund Blocker | 退款阻断事实。 | 表示已开票订单红冲未完成，Post Sales 不应继续资金退款。 |

## 4. 上下游契约

本节只列已在 `docs/08-contracts/` 存在的跨上下文契约；Invoicing 新增命令和事件只在第 7 节出现，后续单独契约化。

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Journey Order | 事件 `JourneyOrderConfirmed`、`JourneyOrderPostSalesAdjusted`；查询端点 `GET /api/v1/journey-orders/{orderId}` | 确认订单、订单账户、旅客引用、订单金额摘要和售后调整后的订单事实，是开票与行程单的订单来源。 |
| Post Sales | 事件 `PostSalesApproved`、`PostSalesApplied`、`PostSalesFailed`；端点 `GET /api/v1/post-sales-cases/{caseId}` | 退款/退改审批和执行结果触发红冲检查；失败事件用于解除或关闭红冲等待队列。 |
| Finance Settlement | 事件 `RevenueRecognized`、`RevenueRecognitionReversed`、`InvoiceGenerated`；端点 `GET /api/v1/revenue-recognitions/{revenueRecognitionId}`、`GET /api/v1/invoices/{invoiceId}` | 提供可开票收入、已冲减收入、税费/金额口径和历史发票候选，不由本域重算金额。 |
| Admin & Audit | 事件 `ManualActionApproved`、`ManualActionRejected`、`ManualActionExecuted` | 发票异常处理、人工作废和敏感抬头资料修改必须经过审批和审计；本域消费审批/执行结果并记录审计输入。 |

### Downstream

| Downstream Context | Published / Invoked Existing Contract | Reason |
|---|---|---|
| Notification | 命令 `ScheduleNotification` | 发票开具成功、红冲完成、开票失败需通知用户或企业联系人；通知发送不属于本域。 |
| Customer Service | 命令 `AppendTimelineEntry` | 发票、红冲、行程单和报销导出的关键节点进入客服可见时间线。 |
| Reporting | 查询端点 `GET /api/v1/metrics`、`GET /api/v1/dashboards/{dashboardId}` 依赖的事件消费机制 | 后续 Reporting 从 Invoicing 事件流构建开票成功率、红冲时效、失败原因等指标；Reporting 不反向驱动票据状态。 |
| Admin & Audit | 命令 `RecordAuditEntry` | 发票异常处理、SIM 网关异常和敏感抬头资料修改需追加审计记录；治理事件由本域消费，不由本域发布。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| InvoiceTitle | 同一 accountId 下 titleId 唯一；`ENTERPRISE` 抬头必须包含企业名称与纳税人识别号；抬头资料版本化，已用于开票的版本不可原地改写；删除只做停用。 | CreateInvoiceTitle、UpdateInvoiceTitle、SetDefaultInvoiceTitle、DeactivateInvoiceTitle | InvoiceTitleCreated、InvoiceTitleUpdated、DefaultInvoiceTitleSet、InvoiceTitleDeactivated |
| InvoiceRequest | 同一 orderId + titleVersion + invoiceScope + amountBasisHash 只能有一个 active request；金额必须来自 Finance Settlement；请求取消后不可重新激活，只能新建请求。 | RequestEInvoice、AttachAmountBasis、CancelInvoiceRequest、RejectInvoiceRequest | InvoiceRequested、InvoiceAmountBasisAttached、InvoiceRequestCancelled、InvoiceRequestRejected |
| EInvoice | 必须由 accepted InvoiceRequest 创建；蓝票成功后 invoiceNumber 不可变；同一 request 只能生成一张有效蓝票；Rejected/Failed 不能静默改为 Issued，必须重试或新请求；终态可查询不可修改。 | SubmitEInvoice、RecordGatewayAccepted、RecordGatewayRejected、RecordGatewayFailed、MarkEInvoiceIssued、ExpireEInvoiceRequest | EInvoiceSubmitted、EInvoiceAccepted、EInvoiceRejected、EInvoiceFailed、EInvoiceIssued、EInvoiceExpired |
| RedFlush | 必须引用 originalInvoiceId 和 postSalesCaseId；原票未 Issued 不允许红冲；同一 originalInvoiceId + postSalesCaseId 只能有一个 active red flush；红冲完成前不得发布退款放行事实；Failed 不可自动变成功。 | RequestRedFlush、SubmitRedFlush、RecordRedFlushAccepted、RecordRedFlushRejected、RecordRedFlushFailed、CompleteRedFlush | RedFlushRequested、RedFlushSubmitted、RedFlushAccepted、RedFlushRejected、RedFlushFailed、RedFlushCompleted |
| ItineraryReceipt | 只能基于已确认订单和可证明的行程/票证事实生成；同一 orderId + travelerRefs + segmentRefs + receiptVersion 幂等；撤销后不可复用原 receiptNo。 | GenerateItineraryReceipt、ReissueItineraryReceipt、RevokeItineraryReceipt | ItineraryReceiptGenerated、ItineraryReceiptReissued、ItineraryReceiptRevoked |
| ReimbursementExport | 导出包必须引用不可变的发票、行程单、订单摘要和金额口径版本；生成失败可重试但 packageDigest 不可伪造；下载链接过期不改变导出内容。 | CreateReimbursementExport、AttachExportArtifact、ExpireReimbursementExport、RegenerateReimbursementExport | ReimbursementExportCreated、ReimbursementArtifactAttached、ReimbursementExportExpired、ReimbursementExportRegenerated |

## 6. 状态机

### InvoiceRequest / EInvoice 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| REQUESTED | 已收到开票请求，尚未绑定完整金额口径。 | AMOUNT_READY、CANCELLED、REJECTED |
| AMOUNT_READY | 已绑定 Finance Settlement 金额与税费口径。 | SUBMITTED、CANCELLED、REJECTED |
| SUBMITTED | 已向 SIM 税局网关提交。 | ACCEPTED、REJECTED、FAILED |
| ACCEPTED | 网关已受理，等待票号和版式结果。 | ISSUED、FAILED |
| ISSUED | 蓝票已开具，invoiceNumber 和 downloadRef 固化。 | RED_FLUSH_PENDING |
| RED_FLUSH_PENDING | 原票因售后退款进入红冲等待。 | RED_FLUSHED |
| RED_FLUSHED | 原票已被红字发票冲销。 | 终态 |
| CANCELLED | 用户或业务在提交前取消。 | 终态 |
| REJECTED | 规则、抬头、金额或 SIM 业务拒绝。 | 终态 |
| FAILED | 非可恢复技术失败或人工关闭。 | 终态 |
| EXPIRED | 请求超过有效期未提交或未完成。 | 终态 |

终态 `RED_FLUSHED`、`CANCELLED`、`REJECTED`、`FAILED`、`EXPIRED` 必须可查询安息：读模型和审计时间线长期保留，但聚合不再接受改变业务结果的命令。`REJECTED` 与 `FAILED` 不可逆；如需再次开票必须创建新的 `InvoiceRequest`，并引用原失败请求作为 causationId。

### RedFlush 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| REQUESTED | 已识别已开票订单存在退款/售后红冲需求。 | BLOCKING_REFUND、CANCELLED |
| BLOCKING_REFUND | 红冲进行中，退款资金动作必须等待。 | SUBMITTED、FAILED |
| SUBMITTED | 已向 SIM 税局网关提交红字发票请求。 | ACCEPTED、REJECTED、FAILED |
| ACCEPTED | 网关已受理红冲。 | COMPLETED、FAILED |
| COMPLETED | 红字发票已开具并关联原票。 | 终态 |
| REJECTED | 业务拒绝，例如原票不存在、金额口径不一致。 | 终态 |
| FAILED | 技术失败达到上限或人工关闭。 | 终态 |
| CANCELLED | 对应售后失败或未实际退款，红冲请求取消。 | 终态 |

`REJECTED`、`FAILED`、`CANCELLED` 不可自动重开；必须通过新 `RequestRedFlush` 携带新的 evidenceRef 或 postSalesCaseId 重新进入。只要存在 active RedFlush 未到 `COMPLETED`，本域对该 orderId 输出的退款前置检查为阻断。

### ItineraryReceipt 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| GENERATED | 行程单已生成。 | REISSUED、REVOKED、EXPIRED |
| REISSUED | 因格式、收件信息或合规模板变化重出。 | REVOKED、EXPIRED |
| REVOKED | 行程证明撤销。 | 终态 |
| EXPIRED | 下载有效期或展示有效期结束。 | 终态 |

### ReimbursementExport 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| REQUESTED | 报销包导出请求已创建。 | BUILDING、CANCELLED |
| BUILDING | 正在汇总发票、行程单、订单摘要和金额明细。 | AVAILABLE、FAILED |
| AVAILABLE | 导出包可下载。 | EXPIRED、REGENERATED |
| REGENERATED | 按相同材料重新生成了新 artifact。 | AVAILABLE、EXPIRED |
| FAILED | 导出失败且达到重试上限。 | 终态 |
| CANCELLED | 用户取消或材料已失效。 | 终态 |
| EXPIRED | 下载链接过期，内容摘要仍可查询。 | 终态 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| CreateInvoiceTitle | InvoiceTitle | InvoiceTitleCreated | accountId + titleType + normalizedTitleName + taxIdentityHash |
| UpdateInvoiceTitle | InvoiceTitle | InvoiceTitleUpdated | titleId + expectedVersion + materialHash |
| SetDefaultInvoiceTitle | InvoiceTitle | DefaultInvoiceTitleSet | accountId + titleId + commandPurpose |
| DeactivateInvoiceTitle | InvoiceTitle | InvoiceTitleDeactivated | titleId + deactivationReason |
| RequestEInvoice | InvoiceRequest | InvoiceRequested | orderId + titleVersion + invoiceScope + clientRequestId |
| AttachAmountBasis | InvoiceRequest | InvoiceAmountBasisAttached | invoiceRequestId + revenueRecognitionIdsHash + amountBasisHash |
| SubmitEInvoice | EInvoice | EInvoiceSubmitted | invoiceRequestId + gatewayProfile + submitAttempt |
| RecordGatewayAccepted | EInvoice | EInvoiceAccepted | gatewayRequestId + gatewayAcceptedAt |
| RecordGatewayRejected | EInvoice | EInvoiceRejected | gatewayRequestId + rejectionCode |
| RecordGatewayFailed | EInvoice | EInvoiceFailed | gatewayRequestId + failureClass + attemptNo |
| MarkEInvoiceIssued | EInvoice | EInvoiceIssued | gatewayInvoiceNumber + originalRequestId |
| ExpireEInvoiceRequest | EInvoice / InvoiceRequest | EInvoiceExpired | invoiceRequestId + expiryPolicyVersion |
| RequestRedFlush | RedFlush | RedFlushRequested | originalInvoiceId + postSalesCaseId + refundScopeHash |
| SubmitRedFlush | RedFlush | RedFlushSubmitted | redFlushId + gatewayProfile + submitAttempt |
| RecordRedFlushAccepted | RedFlush | RedFlushAccepted | gatewayRequestId + gatewayAcceptedAt |
| RecordRedFlushRejected | RedFlush | RedFlushRejected | gatewayRequestId + rejectionCode |
| RecordRedFlushFailed | RedFlush | RedFlushFailed | gatewayRequestId + failureClass + attemptNo |
| CompleteRedFlush | RedFlush | RedFlushCompleted | redInvoiceNumber + originalInvoiceId |
| GenerateItineraryReceipt | ItineraryReceipt | ItineraryReceiptGenerated | orderId + travelerRefsHash + segmentRefsHash + receiptVersion |
| ReissueItineraryReceipt | ItineraryReceipt | ItineraryReceiptReissued | itineraryReceiptId + reissueReason + templateVersion |
| RevokeItineraryReceipt | ItineraryReceipt | ItineraryReceiptRevoked | itineraryReceiptId + revokeReason |
| CreateReimbursementExport | ReimbursementExport | ReimbursementExportCreated | accountId + orderIdsHash + artifactPurpose + clientRequestId |
| AttachExportArtifact | ReimbursementExport | ReimbursementArtifactAttached | exportId + packageDigest |
| ExpireReimbursementExport | ReimbursementExport | ReimbursementExportExpired | exportId + expiryPolicyVersion |
| RegenerateReimbursementExport | ReimbursementExport | ReimbursementExportRegenerated | exportId + sourceMaterialHash + regenerateReason |

所有命令必须携带 `sourceCommandId`、`correlationId`、`causationId` 和 RFC3339 UTC 时间戳；领域事件进入 Outbox 后采用统一 envelope。跨上下文 JSON 字段使用 camelCase；枚举在边界使用 SCREAMING_SNAKE；Money 使用 `{currency, minorUnits}`，不使用浮点或 decimal 字符串。

幂等键采用“材料折叠”：先将业务材料规范化（去空格、统一大小写、排序数组、金额按 `currency + minorUnits`、时间按 RFC3339 UTC），再计算 materialHash；命令携带的 UUID-v7 只作为 command identity，不替代业务幂等材料。Notes：仓库现有契约已裁决跨上下文 ID 采用 UUID-v7 形制，本域沿用该形制，且由仓储层按幂等键裁决重复命令返回既有结果或拒绝 `IDEMPOTENCY_KEY_REUSED`。

## 8. 策略和 Saga 参与点

### 开票策略

- 只有 `JourneyOrderConfirmed` 且 Finance Settlement 已提供可开票金额口径后，才允许自动或用户触发 `RequestEInvoice`。
- 金额以 Finance Settlement 的收入确认和税费事实为准；Invoicing 可以校验合计、币种和 tax line 完整性，但不得重新计算价格、税额或退改费。
- 企业抬头资料按 titleVersion 固化到 `InvoiceRequest`；后续修改抬头不影响已提交或已开具发票。
- 同一 orderId 的重复开票必须按 invoiceScope、金额组件和已开票记录校验，避免超过可开票余额。

### 红冲和退款阻断策略

- 当消费到 `PostSalesApproved` 且 approvedActions 表示 REFUND，若 orderId 存在已 `ISSUED` 蓝票，本域创建 `RedFlush` 并将退款前置检查置为阻断。
- `RedFlushCompleted` 后，本域可发布后续契约化的红冲完成事实供 Post Sales 继续退款；在契约落地前，Post Sales 通过本域查询读模型或编排约定等待。
- 若 `PostSalesFailed` 到达且没有资金退款事实，本域可取消未提交的 RedFlush；已提交到 SIM 网关的红冲必须等待明确结果或人工处理。
- 已开票订单退款必须先红冲是不变量，不依赖读模型缓存；命令处理时必须回到 `EInvoice` / `RedFlush` 聚合判断。

### 行程单和报销导出策略

- 行程单生成依赖订单、旅客引用和段引用，不包含未脱敏证件号；如需旅客展示名只使用脱敏或用户确认的展示字段。
- 报销导出包以不可变材料生成 packageDigest，重试不改变内容；如果材料发生变化，必须生成新版本并保留旧版本审计。
- 导出包下载链接过期只影响访问授权，不改变 artifact 的审计摘要。

### Saga 参与点

- Invoicing 不拥有长事务 Saga；它参与 Journey Order → Finance Settlement → Invoicing 的开票链路，以及 Post Sales → Invoicing RedFlush → Post Sales refund continuation 的售后链路。
- 对 SIM 网关超时或 Ambiguous 结果，先状态查询再人工升级，不盲目重复提交副作用请求。
- Notification、Customer Service 和 Reporting 只消费事实或命令，不反向改变发票聚合。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| InvoiceTitleBook | InvoiceTitleCreated、InvoiceTitleUpdated、DefaultInvoiceTitleSet、InvoiceTitleDeactivated | 用户发票抬头管理、客服查看。 |
| OrderInvoiceEligibilityView | JourneyOrderConfirmed、RevenueRecognized、RevenueRecognitionReversed、InvoiceRequested、EInvoiceIssued、RedFlushCompleted | 开票入口、客服、Post Sales 红冲检查。 |
| EInvoiceTimelineView | InvoiceRequested、EInvoiceSubmitted、EInvoiceAccepted、EInvoiceIssued、EInvoiceRejected、EInvoiceFailed、EInvoiceExpired | 用户查询、客服、审计。 |
| RedFlushQueueView | PostSalesApproved、RedFlushRequested、RedFlushSubmitted、RedFlushCompleted、RedFlushRejected、RedFlushFailed、PostSalesFailed | Post Sales 编排等待、客服异常处理、财务运营。 |
| ItineraryReceiptView | ItineraryReceiptGenerated、ItineraryReceiptReissued、ItineraryReceiptRevoked | 用户行程单查询、报销导出。 |
| ReimbursementExportView | ReimbursementExportCreated、ReimbursementArtifactAttached、ReimbursementExportRegenerated、ReimbursementExportExpired | 用户下载、企业客户、客服。 |
| GatewayInteractionAuditView | EInvoiceSubmitted、EInvoiceAccepted、EInvoiceRejected、EInvoiceFailed、RedFlushSubmitted、RedFlushAccepted、RedFlushRejected、RedFlushFailed | Invoicing 运维、Admin & Audit、故障回放。 |
| InvoiceMetricsProjection | EInvoiceIssued、EInvoiceRejected、EInvoiceFailed、RedFlushCompleted、RedFlushFailed | Reporting 指标和仪表盘。 |

读模型可以冗余 orderId、accountId、title display name、invoiceNumber、redInvoiceNumber、金额 `{currency, minorUnits}` 和脱敏旅客展示；不能保存未脱敏证件号、税局原始敏感报文或凭证密钥。

## 10. 外部系统和防腐层

### Tax Bureau Gateway SIM ACL

ADR-0003 要求税局电子发票网关为模拟外部方。本域内置 `TaxBureauGatewaySimAdapter`，不得进行真实网络调用、不得读取真实税控证书、不得提交真实企业税号到外部系统。

确定性行为：

1. 可种子化：adapter 接受 `gatewaySeed`、`scenarioCode`、`requestFingerprint`，相同输入必定产生相同 gatewayRequestId、invoiceNumber/redInvoiceNumber、状态序列和错误码。
2. 无真实网络：所有 submit、statusQuery、downloadRef 生成均在进程内或测试 fixture 内完成；延迟、超时和 Ambiguous 结果由 seed 与场景表模拟。
3. 状态可回放：`SUBMITTED -> ACCEPTED -> ISSUED`、`SUBMITTED -> REJECTED`、`SUBMITTED -> FAILED`、`SUBMITTED -> AMBIGUOUS -> ACCEPTED/FAILED` 等路径由 deterministic script 决定。
4. 错误码归一：SIM 私有错误映射为 `BUSINESS_REJECTED`、`RETRYABLE_TECHNICAL_ERROR`、`NON_RETRYABLE_TECHNICAL_ERROR`、`AMBIGUOUS_RESULT`、`DUPLICATE_REQUEST`。
5. 副作用保护：蓝票和红字发票 submit 使用 gateway idempotency key；超时后优先 `QueryGatewayStatus`，禁止直接重复提交新外部意图。
6. Raw archive：仅保存脱敏 raw request/response、hash、mappingVersion 和 scenarioCode；不记录未脱敏企业证照、个人证件或真实联系方式。
7. Capability descriptor：声明 `ISSUE_E_INVOICE`、`RED_FLUSH`、`QUERY_STATUS`、`DOWNLOAD_ARTIFACT` 四类能力，以及是否支持同步完成、最大重试次数和超时策略。

### Amount Basis ACL

- Finance Settlement 的 `RevenueRecognized`、`RevenueRecognitionReversed`、`InvoiceGenerated` 进入本域后转换为 `InvoiceAmountBasis`。
- Money 在边界必须是 `{currency, minorUnits}`；同一发票请求内不允许多币种混算。
- 财务组件缺失、金额为负或税费口径冲突时拒绝开票并进入可查询失败状态，不尝试自行修正。

### Legacy / Export ACL

- 旧发票、行程单和报销下载表迁移时只作为历史材料导入，不覆盖聚合事实。
- 报销导出 artifact 存储只接收 packageDigest、storageRef、expiresAt；访问控制和下载鉴权在边界层处理。
- 邮件、短信或企业报销系统不在本阶段真实接入，统一通过 Notification 或模拟导出引用表达。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-order-service`, `ts-order-other-service` | 订单确认、账户、旅客引用和订单摘要作为开票输入；不再在订单服务内保存发票状态机。 |
| `ts-ticket-office-service` | 票据/取票点相关的历史票据能力迁移为 `EInvoice`、`ItineraryReceipt` 和 `GatewayInteractionAuditView`。 |
| `ts-voucher-service` | 历史凭证、报销凭证和下载逻辑迁移为 `ReimbursementExport`，凭证不再代表税务发票权威状态。 |
| `ts-cancel-service`, `ts-rebook-service` | 退款和改签售后触发红冲检查；已开票订单必须等待 `RedFlush` 完成后才能继续退款资金动作。 |
| `ts-price-service` | 不再向发票直接提供临时价格计算；改由 Finance Settlement 输出金额和税费口径。 |
| `ts-payment-service`, `ts-inside-payment-service` | Payment 不再判断发票是否红冲；只在 Post Sales / Invoicing 阻断解除后执行资金退款。 |
| `ts-notification-service` | 接收 Invoicing 事实后通过 `ScheduleNotification` 发送开票成功、失败、红冲完成和导出可下载通知。 |
| batch export scripts / manual invoice Excel | 迁移为 `ReimbursementExport` 和 `GatewayInteractionAuditView`；人工处理必须经过 Customer Service / Admin & Audit 审批链。 |
| database columns carrying invoice title or invoice number | 迁移为 Invoicing 聚合和读模型；其他服务只保留引用，不复制未脱敏抬头资料。 |

迁移顺序建议：先建立抬头管理和订单开票读模型；再接入 Finance Settlement 金额口径并开具 SIM 蓝票；随后接入 Post Sales 红冲阻断；最后迁移行程单、报销导出和历史票据查询。

## 12. 验收标准

- Invoicing 的聚合所有权明确：`InvoiceTitle`、`InvoiceRequest`、`EInvoice`、`RedFlush`、`ItineraryReceipt`、`ReimbursementExport` 归本上下文。
- 与 Journey Order 边界明确：只消费订单事实和查询订单摘要，不改变订单状态。
- 与 Post Sales 边界明确：退款触发红冲，已开票订单退款必须先红冲；退款规则和资金执行不在本域。
- 与 Finance Settlement 边界明确：金额、税费和收入确认口径来自 Finance，本域不重新定价或做账。
- SIM 税局网关防腐层确定性、可种子化、无真实网络，且覆盖成功、拒绝、失败、超时和 Ambiguous 查询路径。
- 状态机覆盖蓝票、红冲、行程单和报销导出；终态可查询但不可修改；`REJECTED` / `FAILED` 类不可逆规则已写明。
- 命令和领域事件表包含幂等键；幂等键使用材料折叠，UUID-v7 仅作为命令/事件身份。
- 读模型支持用户查询、客服时间线、Post Sales 红冲等待、Reporting 指标和网关交互审计。
- 文档只新增领域设计，不新增契约文档、服务目录或代码；`make check` 不因本文件影响 contract-lint 或 skeleton-check。
