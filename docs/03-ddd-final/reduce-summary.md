# 当前设计摘要

更新日期：2026 年 9 月 24 日。

部署基线为 Helm revision 279，发布记录见[当前项目状态](../00-current-status.md)。
[跨域设计决策](decision-record.md)定义所有权，[业务协作与接口](phase-1-contract.md)说明命令和事件路由，[TODO](../TODO.md)记录未完成工作。

## 交易与供给

Trip Planning 在 `services/trip-planning-rs` 使用 Rust，提供行程搜索和候选查询。
Offer Management 保存报价、规则和可用性引用。
搜索与报价均不建立 CapacityHold。
Journey Order 保存订单商业事实，Booking Orchestration 管理分段预订与执行状态。
Capacity 管理可售数量、区间 Hold、确认和释放。
Seat Assignment 使用 Go，拥有编组、SeatMap、SeatUnit、座位分配和无座配额证据。
Entitlement 在核对订单、预订、支付和 Hold 后获取座位分配并签发权益。
Fulfillment 保存进站、完成和未乘车事实。

Service Plan 已提供带有停站顺序、区间和版本的 segment supply 接口。
Seat Assignment 的供给事实和无座配额执行已有部署证据。
Capacity 新联合供给仍未提交、未部署，完整接入工作见 [TODO](../TODO.md)。

## 资金、售后与发票

| 业务事实 | 负责服务 | 协作规则 |
|---|---|---|
| 平台收款与退款 | Payment | 保存 PaymentIntent、Refund、资金状态和支付事件。 |
| 渠道订单、渠道退款、渠道账单 | Payment Channel | 独立 Go 服务，通过渠道事实与 Payment 和 Finance 协作。 |
| 退票、改签、服务质量补偿 | Post Sales | 拥有 Case、金额决策、执行计划和完成证据。 |
| 钱包权益及核销 | Wallet / Promotion | 保存 benefit、余额、预留、核销和撤销记录。 |
| 收入确认、结算和补偿成本 | Finance Settlement | 消费业务事实，维护财务口径和对账记录。 |
| 电子发票、抬头、红冲、行程凭证 | Invoicing | 独立 Rust 服务，使用订单和金额投影处理申请。 |
| 经营指标 | Reporting | 金额统计采用 gross captured payment，唯一金额来源为 PaymentCaptured。 |

服务质量 `COMPENSATION` 通过 Wallet 发放钱包补偿额度。
当前发放字段为 `benefitType=COMPENSATION_CREDIT`、`balanceType=PROMOTION_CREDIT`、`issuanceSource=POST_SALES_COMP`。
补偿保留原票、座位分配、Hold、原支付和附加服务。
Finance 将 `BenefitIssued` 记为补偿成本，Invoicing 保留原票对应的金额依据。
原路退款和改签失败后的差额退款使用 Payment 的 Refund 流程。
Reporting 的 `totalRevenue`、`revenue` 等现有字段表示 gross captured payment，财务已确认收入由 Finance 提供。

## 身份、恢复与支撑服务

Account 拥有账号状态和通知设置，Traveler Profile 拥有旅客档案。
Identity Verification 和 Risk & Compliance 都使用 Python，分别拥有实名核验与资格事实、风险评估与处置建议。
Disruption Recovery 管理受影响旅程的恢复 Case，Transfer Management 管理接续规则、保障责任和运行风险。
Waitlist 使用 TypeScript，拥有候补请求和接受入口。
Ancillary Service、Travel Insurance、Loyalty Membership、Marketing Campaign、Group Booking、Corporate Travel 和 Dispatch 的职责与源码入口见[领域所有权索引](domain-reduce-status.md)。
接口存在说明该入口可在源码中定位，能力覆盖与未完成项见 [TODO](../TODO.md)。

Notification 消费业务事实并保存触达记录，发送结果不改变源业务的成功状态。
Notification durable 发送队列为 WIP、未部署，相关工作见 [TODO](../TODO.md)。
Customer Service 与 Admin & Audit 通过受控命令协作，目标服务保有状态转换和业务校验责任。

## 目录与引用

catalog 当前登记 32 个业务服务。
源码中另外存在 Corporate Travel、Group Booking、Loyalty Membership、Marketing Campaign 和 Travel Insurance。
全部 37 个服务的目录与语言见[语言及源码目录](../05-service-architecture/language-selection.md)，登记差异见 [TODO T10](../TODO.md#t10-reconcile-repository-metadata-and-static-checks)。
本目录保留稳定路径，工作状态统一通过 [TODO](../TODO.md)维护。
