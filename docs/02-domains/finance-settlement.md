# Finance Settlement 当前设计

更新日期：2026年9月24日。
运行基线为 Helm revision 279，服务使用 Java、PostgreSQL 和 Redis 事件处理。
统一待办见 [TODO](../TODO.md)。

## 领域边界

Finance Settlement 保存收入确认及冲减、费用计提、对账 Case、日对账批次、供应商结算结果、内部 Invoice、权益成本和附加服务财务事实。
主要模型为 `RevenueRecognition`、`FeeAccrual`、`ReconciliationCase`、`ReconciliationBatch`、`SupplierSettlement`、`Invoice` 和 `SettlementView`。
资金渠道执行属于 Payment，商业订单属于 Journey Order，退改与补偿决策属于 Post Sales。
Finance 通过事实和受控查询形成财务记录，不直接修改订单、票证或 Payment 状态。
本域内部 Invoice 基于已确认收入生成，不能据此认定外部税控或银行付款已经完成。

## 收款与售后

普通 `PaymentCaptured` 记录订单与 PaymentIntent 的关联、捕获金额及源事件，并按 `payment-capture-v1` 生成 `fare` 收入确认。
当前确认时点使用支付捕获事实。
附加服务支付先保存资金事实，附加服务履约事件再形成对应收入记录。

改签支付通过 Post Sales Case 找到原订单，使用真实捕获事件生成 `change-payment` 收入。
改签完成核对差价已捕获、Case 与订单一致、退款属于对应支付且全部 `SETTLED`，退款总额必须等于批准金额。
每笔冲减引用实际退款标识，改签费用从已保存的决策读取。
普通售后完成使用已批准退款金额冲减可匹配收入，缺少前置金额时等待事件重试，无法匹配时创建对账差异。
存在改签资金历史的退款会核对 Payment 的捕获和退款事实。
`PostSalesApplied` 与资金结算状态分别保存和核验。

## 补偿成本

`PostSalesApproved` 的 `decisionKind=COMPENSATION` 跳过退款金额投影。
`PostSalesApplied` 的 `caseType=COMPENSATION` 跳过收入冲减和退款处理。
钱包补偿保留原票收入、费用和捕获支付引用。
补偿发放只由 Wallet 的 `BenefitIssued` 记录成本，金额使用 `issuedAmount`，保留 `accountId`、`caseId`、`benefitId` 和 `issuanceSource=POST_SALES_COMP`。
审批和完成事件不重复生成补偿成本。

`benefit-costs` 是权益生命周期增量列表。
通用处理器还接受核销、核销冲正、撤销和过期事件，其当前计算口径见 [财务计算与对账](../10-domain-enrichment/finance-settlement-enrichment.md)。
该列表不等同于只包含首次发放的净成本汇总。

## 对账与结算

Payment Channel 的 statement、冻结、匹配行和差异事件进入本地投影。
渠道差异类型映射为 Finance 的差异原因，形成可查询的 `ReconciliationCase`。
供应商确认与预订取消事件也可触发财务事实匹配。

日对账和供应商结算已有应用服务方法、持久化模型和查询 API。
当前生产代码未接入日对账定时器或供应商结算自动调用流程，查询只返回已保存结果。
日对账区分 `MATCHED`、`PLATFORM_ONLY`、`CHANNEL_ONLY` 和 `AMOUNT_MISMATCH`。
供应商结算按指定期间、佣金比例和代扣比例生成结果。
费用与税额使用服务当前公式，详细输入、时间范围和限制见增补文档。

## API 与事件

| 入口 | 用途 |
|---|---|
| `GET /api/v1/revenue-recognitions/{revenueRecognitionId}` | 查询收入确认。 |
| `GET /api/v1/order-revenue-bases/{orderId}` | 查询订单收入、冲减和净额依据。 |
| `GET /api/v1/reconciliation-cases` | 分页查询差异，可过滤订单。 |
| `GET /api/v1/reconciliation-cases/{reconciliationCaseId}` | 查询单个差异。 |
| `GET /api/v1/channel-statements` | 按渠道和日期查询 statement。 |
| `GET /api/v1/channel-statements/{channelStatementId}` | 查询单个 statement。 |
| `GET /api/v1/settlements/daily/{date}` | 查询已保存日对账摘要。 |
| `GET /api/v1/settlements/reconciliation/{batchId}` | 查询对账条目。 |
| `GET /api/v1/settlements/suppliers/{supplierId}/period` | 按 `startDate`、`endDate` 查询供应商结算。 |
| `GET /api/v1/benefit-costs` | 查询权益成本增量，可过滤账户。 |
| `GET /api/v1/ancillary-financial-facts` | 查询附加服务财务事实。 |
| `POST /api/v1/invoices` | 根据订单净收入生成或返回内部 Invoice。 |
| `GET /api/v1/invoices/{invoiceId}` | 查询内部 Invoice。 |
| `POST /api/v1/internal/change-financial-reconciliations/{caseId}` | 核验并应用改签资金事实。 |

消费事件按 `eventId` 去重，财务记录与 Outbox 在本地事务中保存。
事件包括 `RevenueRecognized`、`RevenueRecognitionReversed`、`ReconciliationCompleted`、`SupplierSettlementCalculated`、`FeeAccrued` 和 `InvoiceGenerated`。
Reporting 自身的 gross captured 指标直接统计 Payment 事件，其金额口径与 Finance 的净收入及冲减记录分别定义。

实现入口见 [服务说明](../../services/finance-settlement/README.md)、[事件处理器](../../services/finance-settlement/src/main/java/com/trainticket/financesettlement/application/FinanceSettlementEventHandler.java)、[应用服务](../../services/finance-settlement/src/main/java/com/trainticket/financesettlement/application/FinanceSettlementApplicationService.java) 和 [HTTP Controller](../../services/finance-settlement/src/main/java/com/trainticket/financesettlement/adapters/http/FinanceSettlementController.java)。
