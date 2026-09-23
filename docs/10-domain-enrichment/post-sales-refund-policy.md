# Post Sales 退改政策

更新日期：2026年9月24日。
本文描述 Helm revision 279 文档核对时的 Java 政策实现。
领域边界见 [Post Sales](../02-domains/post-sales.md)，统一待办见 [TODO](../TODO.md)。

## 输入与保存

`PostSalesApplicationService` 从 adjustment quote 返回的已接受订单读取出发时间、旅客类型、订单人数和金额组成，成功改签次数来自已应用的改签 Case。
同步读取获得政策输入时，评估不依赖异步订单投影的到达顺序。
生产适配器要求订单、Offer 和 Fare Pricing 报价引用有效，读取失败或字段不完整会使评估失败。
报价读取在数据库事务外执行，保存时检查 Case 范围和目标一致，并冻结决策、金额明细和规则引用。
当前决策有效期为生成后900秒。

## 退款手续费

`RefundPolicyEngine` 按下表顺序选择第一条符合的规则。
时间间隔使用申请时刻至出发时刻的精确 Duration。

| 顺序 | 条件 | 罚金比例与规则 |
|---|---|---|
| 1 | 非自愿退款分类 | 0%，跳过时间阶梯。 |
| 2 | 已到出发时刻或已经出发 | 100%。 |
| 3 | 出发前，订单人数至少10人 | 5%。 |
| 4 | 学生票，距离出发严格超过2天 | 0%。 |
| 5 | 距离出发不足48小时 | 100%。 |
| 6 | 至少48小时且不足8天 | 20%。 |
| 7 | 至少8天且不超过15天 | 10%。 |
| 8 | 超过15天 | 5%，非零罚金最低为当前币种2.00，且不超过罚金基数。 |

学生判定使用旅客类型中是否包含 `STUDENT`。
Case 范围涉及多个旅客时，当前上下文选取范围内首个可用旅客类型，人数来自订单上下文。
罚金按币种小数位使用 `HALF_UP` 舍入。
100% 规则作用于罚金基数，最终退款金额还取决于组成项，不能由规则名称直接推断总退款为零。

`reasonCode` 转为大写后按关键词顺序分类。
`CARRIER` 或 `TRAIN_CANCEL` 对应 `INVOLUNTARY_CARRIER`，`DELAY` 对应 `INVOLUNTARY_DELAY`，`FORCE` 或 `MAJEURE` 对应 `INVOLUNTARY_FORCE_MAJEURE`，`PLATFORM` 或 `ERROR` 对应 `INVOLUNTARY_PLATFORM`，其他原因使用 `VOLUNTARY`。
当前分类器没有延误时长判定。

## 退款组成项

`RefundWaterfall` 保存各组成项的原金额、可退金额、保留金额和原因。
未使用附加服务可退，已使用附加服务保留，平台服务费保留，供应商服务费和税费全退。
基本票价扣除罚金，优惠金额从可退总额中全额扣回，结果最低为零。
缺少独立组成项时，以政策上下文中的票价构造简单票价退款。
非自愿分类只免除罚金，平台服务费和附加服务使用规则仍参与计算。
这些是服务当前公式，不代表所有供应商或法定票规。

## 改签金额

当前 HTTP 改签流程通过 `evaluateQuotedChange` 使用 Fare Pricing 的 `amountDue` 和 `refundableAmount`。
两者同时为正会被拒绝。
CNY 的默认 `changeFee` 为15.00，其他币种在本地政策类中的默认费用为零。
生产流程保留报价中的净补收或净退款结果，本地费用不会再次叠加到已报价金额。
出发时间和 `changeCount` 是输入，当前费用公式不按提前时间或改签次数分档。

本地 `evaluateChange` 按新旧票价差与默认费用计算净补收或净退款。
成功改签保存应用记录，后续退改从当前已接受订单和已应用 Case 获取政策上下文。
资金执行及替代票处理见领域文档中的改签执行顺序。

## 补偿金额边界

`COMPENSATION` 使用冻结决策金额作为 `compensationAmount`，公开 `refundableAmount` 为零。
钱包执行向原订单账户发放等额额度，保留原票、座位、订单和已捕获支付。
补偿流程不使用退款阶梯或退款组成项来执行原路退款。

实现依据为 [退款政策](../../services/post-sales/src/main/java/com/trainticket/postsales/domain/RefundPolicyEngine.java)、[退款组成项](../../services/post-sales/src/main/java/com/trainticket/postsales/domain/RefundWaterfall.java)、[改签政策](../../services/post-sales/src/main/java/com/trainticket/postsales/domain/ChangePolicyEngine.java) 和 [应用服务](../../services/post-sales/src/main/java/com/trainticket/postsales/application/PostSalesApplicationService.java)。
