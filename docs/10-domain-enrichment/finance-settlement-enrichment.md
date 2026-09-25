# Finance Settlement 计算与对账

更新日期：2026年9月25日。
本文描述 Helm revision 279 文档核对时的 Java 实现。
领域边界见 [Finance Settlement](../02-domains/finance-settlement.md)，统一待办见 [TODO](../TODO.md)。

## 日对账

`runDailyReconciliation` 按指定日期读取平台 capture 和渠道 statement line。
未指定日期时使用服务时钟的前一天，批次 `cutoffAt` 记录该日下一天的 UTC 零点。
平台与渠道按 `orderId` 和 `paymentIntentId` 匹配，并保留源事件引用。
渠道明细为空时，当前方法使用 statement 的付款总额减退款总额构造渠道输入。
完成结果保存为 `ReconciliationBatch`，包含条目、匹配率、异常列表、总差额和状态数量。
同一日期已有批次时返回已有结果。

| 状态 | 含义 |
|---|---|
| `MATCHED` | 两侧存在且金额一致。 |
| `PLATFORM_ONLY` | 仅平台存在。 |
| `CHANNEL_ONLY` | 仅渠道存在。 |
| `AMOUNT_MISMATCH` | 两侧存在且金额不同。 |

内置调度器默认每分钟检查一次，自动处理前一个已闭合 UTC 日期。
`settlement_schedule_runs` 持久化逻辑周期、租约 owner、尝试次数、状态、失败原因和生成的 batch ID；成功后不重复计算，失败或租约过期可由另一副本接管。
`GET /api/v1/settlements/daily/{date}` 和 `GET /api/v1/settlements/reconciliation/{batchId}` 读取已保存结果，不触发计算。
批次采用单币种总差额，跨币种自动换算未实现。

## 供应商结算

`calculateSupplierSettlement` 接收供应商、起止日期、结算频率、佣金比例和代扣比例。
期间包含起止日期，频率支持 `DAILY`、`WEEKLY` 和 `MONTHLY`。
应用服务读取供应商期间内的收入记录，以各记录的 `netAmount` 作为 allocation 的 `grossRevenue` 输入。
计算公式为 `supplierPayable = grossRevenue - platformCommission - taxesWithheld + adjustments`。
其中佣金与代扣均按输入金额乘指定比例计算，adjustments 来自该订单费用记录中的税额冲回。
金额按币种小数位使用 `HALF_UP` 舍入。

同一供应商与期间已有结果时复用保存的 `SupplierSettlement`。
内置调度器自动计算最新已闭合的 daily、weekly 或 monthly 周期，频率和佣金/代扣比例来自服务配置。
自动发现只包含普通 `fare` 收入中已归一化且不等于订单号的 supplier reference；缺少 supplier 归属的数据不进入自动付款口径。
每个周期的供应商 settlement IDs 与运行状态一并持久化，可在重启后查询和恢复。
查询入口为 `GET /api/v1/settlements/suppliers/{supplierId}/period?startDate=...&endDate=...`。
该结果表示内部结算计算，不表示供应商款项已支付。

## 费用与税额

普通支付捕获的当前计提公式为平台服务费2%、供应商服务费1%。
`TaxCalculation` 按服务费计算6% VAT，按票款计算0.05% stamp duty，供应商代扣使用传入比例。
支付捕获路径当前传入的代扣比例为零。
退款税额冲回按退款票款的0.05%记负值。

通用 `accrueFeesForRefund` 按退款金额计提10%保留费用。
已核验改签路径从 `changeAssessment.changeFee` 记录费用，包含改签资金历史的售后退款路径从 `refundAssessment.penaltyAmount` 记录费用。
这些路径使用各自当前输入，不能将某一比例解释为所有售后的统一规则。
内部 Invoice 汇总收入记录的净额，同一订单复用已有 Invoice。
上述公式是代码行为，不代表外部税控计算或通用税务规则。

## 钱包补偿与权益增量

钱包补偿审批和完成事件保留原票收入，不形成退款投影或收入冲减。
补偿发放成本仅来自 `BenefitIssued.issuedAmount`，通过源事件去重并保存账户、Case、权益和来源。
`PostSalesApproved` 与 `PostSalesApplied` 不增加该发放成本。

通用权益处理器当前还保存以下生命周期增量。
核销事件的正值与首次发放成本同时存在，查询返回事件行，不能直接将全部行相加解释为一次补偿发放成本。

| 输入事件 | 当前成本增量 |
|---|---|
| `BenefitIssued` | `issuedAmount`。 |
| `BenefitRedeemed` | `redeemedAmount`。 |
| `BenefitRedemptionReversed` | `reversedAmount` 的负值，查询事件名为 `BenefitReversed`。 |
| `BenefitRevoked` | `revokedAmount` 的负值。 |
| `BenefitExpired` | `expiredAmount` 的负值。 |

生命周期事件缺少来源或 Case 时，从已有权益成本记录继承，找不到来源时使用 `UNKNOWN`。
`GET /api/v1/benefit-costs` 提供分页结果及可选账户过滤。

实现依据为 [应用服务](../../services/finance-settlement/src/main/java/com/trainticket/financesettlement/application/FinanceSettlementApplicationService.java)、[事件处理器](../../services/finance-settlement/src/main/java/com/trainticket/financesettlement/application/FinanceSettlementEventHandler.java)、[税额计算](../../services/finance-settlement/src/main/java/com/trainticket/financesettlement/domain/TaxCalculation.java) 和 [分配公式](../../services/finance-settlement/src/main/java/com/trainticket/financesettlement/domain/RevenueAllocation.java)。
