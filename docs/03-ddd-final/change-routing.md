# 功能改动路由

更新日期：2026 年 9 月 24 日。

功能改动按照业务事实的所有者选择服务。
负责服务校验命令并保存状态，协作服务通过接口、业务引用和事件取得结果。
各服务实际接口与源码见[领域所有权索引](domain-reduce-status.md)，目录与语言见[语言及源码目录](../05-service-architecture/language-selection.md)。
未完成事项统一维护在 [TODO](../TODO.md)。

## 需求与负责服务

| 修改内容 | 负责服务 | 相关协作 |
|---|---|---|
| 地点、车站和标准节点 | Place & Network | Service Plan 使用标准地点引用，Provider Integration 解释外部地点代码。 |
| 车次、运行日、停站顺序、延误和取消 | Service Plan | Capacity、Trip Planning、Transfer 和 Disruption 消费对应运行事实。 |
| 可用性数量、区间占用和 Hold 生命周期 | Capacity & Availability | 引用 Service Plan 运行区间、Seat Assignment 供给，向 Booking 提供占用结果。 |
| 编组、座位、无座分配和分配重放 | Seat Assignment | Capacity 管理预留，Entitlement 引用实际分配。 |
| 票价、退改费和资格适用规则 | Fare & Pricing | Identity Verification 提供资格，Offer 和 Post Sales 使用报价。 |
| 行程搜索、候选排序和提示 | Trip Planning | 读取运行、容量和价格信息，向 Offer 提供候选引用。 |
| Offer 有效期、报价引用和目标 Offer | Offer Management | Journey Order 保存接受结果，Post Sales 引用改签目标。 |
| 商业订单、订单项、金额摘要和购买来源 | Journey Order | Booking、Payment、Entitlement 和 Post Sales 提供自身业务事实。 |
| 分段预订、执行进度和替代预订清理 | Booking Orchestration | Capacity、Entitlement、Payment 和 Post Sales 协作。 |
| PaymentIntent、Refund、资金幂等和晚到收款 | Payment | Payment Channel 提供渠道事实，Finance 执行财务处理。 |
| 渠道提交、查询、退款、账单和渠道差异 | Payment Channel | Payment 维护平台资金状态，Finance 对账。 |
| 供应商预留模型与外部证据归一 | Provider Integration | Booking 管理平台预订，Supplier Catalog 管理供应商资料。 |
| 出票、凭证、作废和权益使用状态 | Entitlement & Ticketing | 核对 Booking、Capacity、Payment，调用 Seat Assignment，消费 Fulfillment。 |
| 进站、完成和未乘车事实 | Fulfillment | Entitlement 推进使用状态，Transfer 与 Disruption 处理旅程影响。 |
| 退票、改签和服务质量补偿的决策与执行 | Post Sales | Fare、Offer、Booking、Entitlement、Payment、Wallet、Journey Order。 |
| 钱包补偿额度、benefit 余额与核销 | Wallet / Promotion | Post Sales 或 Customer Service 提供授权业务来源，Finance 记录权益成本。 |
| 账号状态、通知设置和关闭请求 | Account | Traveler Profile 管理旅客，Notification 使用触达设置。 |
| 旅客档案与证件资料 | Traveler Profile | Identity Verification 核验，Fare 与 Risk 使用授权事实。 |
| 实名核验、资格证明和购买限制使用记录 | Identity Verification | 下单及售后流程确认或释放使用事实。 |
| 风险评估、阻断与人工覆盖 | Risk & Compliance | 目标业务服务校验命令并维护自身状态。 |
| 通知模板、渠道、发送和 trail | Notification | 消费源业务事件，保存独立发送结果。 |
| 工单、投诉证据、升级与人工请求 | Customer Service | Admin & Audit 授权，目标服务执行命令。 |
| 操作员、审批和审计 | Admin & Audit | Customer Service 和业务服务保留关联操作记录。 |
| 供应商、承运商和供货合同 | Supplier Catalog | Provider Integration 使用接入资料，Service Plan 使用供给资料。 |
| 收入确认、清结算、对账与补偿成本 | Finance Settlement | 消费 Payment、Payment Channel、Wallet、订单、售后和履约事实。 |
| 电子发票抬头、申请、红冲与行程凭证 | Invoicing | 使用订单与金额投影，关联 Finance 和售后事实。 |
| 经营指标、gross captured payment 和统计维度 | Reporting | PaymentCaptured 提供收款金额，业务引用提供统计维度。 |
| 候补请求、队列、接受和取消 | Waitlist | Capacity、Booking、Payment 和 Notification 协作。 |
| 接续、MCT、保障责任和接续风险 | Transfer Management | Trip Planning 提供候选，Disruption 管理受影响旅程。 |
| 异常、恢复选项、选择与人工处理 | Disruption Recovery | Service Plan、Transfer、Post Sales 和 Notification 协作。 |
| 附加商品、报价、附加订单项和附加履约 | Ancillary Service | Payment 提供资金事实，Journey Order 提供订单引用。 |
| 保单与理赔 Case | Travel Insurance | 使用旅程和履约事实，关联支付及售后结果。 |
| 会员、积分、等级与兑换 | Loyalty Membership | 引用 Account、Payment 和旅程事实，Wallet 管理发放的 benefit。 |
| 活动、券模板、发放批次和预算 | Marketing Campaign | Wallet 管理已发放 benefit 的余额和核销。 |
| 团体请求、成员、确认与取消 | Group Booking | 使用旅客引用并关联普通订单和预订。 |
| 企业协议、授权、规则检查和月结账单 | Corporate Travel | Fare 提供价格规则，Payment 和 Finance 提供资金及财务事实。 |
| 司机分配、ETA、网约车运行与取消 | Dispatch | Fulfillment 使用履约事实，Fare 和 Payment 处理费用与资金。 |

## 支付渠道与平台资金

渠道协议和渠道订单改动进入 Payment Channel。
Payment 保有 PaymentIntent、Refund 和平台资金状态。
渠道结果通过 `ChannelOrderSucceeded`、`ChannelRefundSucceeded` 等事件进入 Payment。
Finance 接收平台资金和渠道账单事实，维护对账结果。
电子发票的业务改动进入独立 Invoicing。

## 服务质量补偿

Post Sales 修改补偿资格、金额决策和 `execute-compensation` 执行。
Wallet 修改 `COMPENSATION_CREDIT` 发放、Case 幂等与余额记录。
当前钱包补偿额度的发放保留原票、原 Hold、座位分配、原支付和附加服务。
Finance 根据 `BenefitIssued` 记录补偿成本，Journey Order 和 Invoicing 保留原订单对应权益与金额依据。
改签失败后的替代预订清理与差额退款分别进入 Booking、Entitlement、Capacity 和 Payment。

## 供给与通知

Service Plan 负责运行区间，Seat Assignment 负责编组供给和分配，Capacity 负责占用与可用性计算。
Capacity 新联合供给仍未提交、未部署，工作状态见 [TODO](../TODO.md)。
Notification 负责消息触达与发送记录。
Notification durable 发送队列为 WIP、未部署，工作状态见 [TODO](../TODO.md)。

## 文档维护

所有权变化更新[跨域设计决策](decision-record.md)和对应[领域文档](../02-domains/README.md)。
调用或事件变化更新[业务协作与接口](phase-1-contract.md)、[API 文档](../08-contracts/api/README.md)及对应事件说明。
服务路径与语言变化同步[语言及源码目录](../05-service-architecture/language-selection.md)和 catalog。
功能进度与剩余工作只在 [TODO](../TODO.md)维护。
