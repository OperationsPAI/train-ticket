# 跨域设计决策

更新日期：2026 年 9 月 24 日。

DR 编号用于稳定引用当前所有权和协作规则。
部署基线为 Helm revision 279，发布证据见[当前项目状态](../00-current-status.md)。
[领域所有权索引](domain-reduce-status.md)提供各服务实际 API 源码，[业务协作与接口](phase-1-contract.md)提供链路说明。
未实现能力、进行中修改和验收工作统一见 [TODO](../TODO.md)。

## DR-001 Payment Boundary

Payment 拥有 PaymentIntent、Refund、资金状态、幂等记录和平台资金事件。
独立 Payment Channel 拥有渠道订单、渠道退款、渠道账单及差异记录。
Payment 使用渠道接口发起资金交互，并消费 `ChannelOrderSucceeded`、`ChannelRefundSucceeded` 等渠道事实，形成 `PaymentCaptured`、`RefundSettled`。
Journey Order 和 Booking 各自消费平台资金事实，推进自身订单与预订状态。

| 请求或事实 | 所有者 | 接口或消费者 |
|---|---|---|
| 创建支付、发起退款 | Payment | `POST /api/v1/payment-intents`、`POST /api/v1/refunds`。 |
| 渠道订单提交与查询 | Payment Channel | `/api/v1/channel-orders`。 |
| 渠道退款提交与查询 | Payment Channel | `/api/v1/channel-refunds`。 |
| 渠道账单 | Payment Channel | Finance 消费并执行财务对账。 |
| 订单支付摘要 | Journey Order | 消费 Payment 的资金事实。 |

## DR-002 Provider And Segment Reservation

Provider Integration 的领域模型表达供应商预留标识、供应商状态和交互证据。
Booking Orchestration 拥有平台 SegmentBooking，负责把供应商预留结果转换为平台分段预订事实。
CapacityHold 是 Capacity 的库存预留，SeatAllocation 是 Seat Assignment 的分配事实，二者通过业务引用与 SegmentBooking 关联。
Provider Integration 当前 HTTP router 注册运行端点，业务接入工作见 [TODO](../TODO.md)。

## DR-003 Search, Offer And Hold

Trip Planning 返回行程候选与查询提示。
Offer Management 保存 Offer 标识、有效期、候选引用、价格和规则快照引用。
Journey Order 接受有效 Offer 并保存购买来源，Booking 管理预订执行，Capacity 创建和推进 Hold。
可用性快照用于查询和报价；购买承诺由后续预订、资金、分配和出票事实共同确定。

## DR-004 Service Plan, Capacity And Seat Assignment

Service Plan 拥有 ScheduledService、ServiceSegment、运行日、停站顺序、区间和计划版本。
Capacity 拥有可售数量、区间占用、Hold 生命周期和释放事实。
Seat Assignment 拥有编组、SeatMap、SeatUnit、SeatAllocation、无座配额及分配证据。
Fare 拥有价格规则，Offer 保存面向购买的报价引用。

Service Plan 的 `GET /api/v1/internal/service-segments/:segmentRef/supply` 提供区间及停站版本。
Seat Assignment 的 `GET /api/v1/internal/capacity-facts` 提供已发布供给事实。
Capacity 新联合供给已在提交 `caafa668` 中实现，尚未部署，工作状态见 [TODO](../TODO.md)。

## DR-005 Supplier And Place Ownership

Supplier Catalog 保存 Supplier、Carrier、供货合同和供应商能力资料。
Place & Network 保存标准地点与 TransportNode，负责标准地点含义。
Provider Integration 负责供应商原始代码、交互证据和外部状态的归一职责。
Service Plan 引用标准地点并拥有运行计划。

## DR-006 Entitlement And Fulfillment

Entitlement & Ticketing 拥有权益标识、凭证、签发和作废状态。
出票逻辑核对订单、支付、SegmentBooking、CapacityHold 和旅客引用，并通过 Seat Assignment 获取分配结果。
座位与无座分配分别保留自己的供给和政策证据。
Fulfillment 拥有进站、完成和未乘车事实，Entitlement 据此推进权益使用状态。
当前注册的 Fulfillment 写入口为 `boarding`、`completions`、`no-show` 和 `segment-status`。

## DR-007 Disruption And Recovery

Service Plan 管理按车次与日期记录的延误、取消、恢复和临时运行安排。
Disruption Recovery 管理异常事件对既有旅程的影响、RecoveryCase、恢复选项和选择结果。
WAIT、REFUND、COMPENSATION 和 MANUAL 选择保留各自的执行含义。
涉及售后、资金或权益变更时，恢复流程调用负责服务并记录关联结果。
Transfer Management 提供受影响接续及保障责任信息。

## DR-008 Post Sales And Monetary Summary

Post Sales 拥有售后 Case、评估、审批、金额决策、目标 Offer 引用和执行进度。
Fare & Pricing 计算 AdjustmentQuote 和退改费用，Offer Management 保存目标报价。
Booking 拥有 replacement booking，Entitlement 拥有新旧票状态，Capacity 与 Seat Assignment 分别处理占用和分配。
Payment 执行差额收款与退款，Journey Order 接收售后结果并维护自身商业摘要。
Finance 与 Invoicing 分别维护财务结果和发票金额依据。
改签失败后的补偿保留原票并清理替代预订、替代占用和已收差额，具体执行证据见[业务协作与接口](phase-1-contract.md)。

## DR-009 Ancillary, Seat And Insurance Ownership

Ancillary Service 拥有附加商品、AncillaryOffer、附加订单项和附加履约记录。
附加订单项通过订单和旅客引用与主票关联，保有独立执行状态。
Fare 管理对应价格与费用规则，Payment 管理资金事实。
Seat Assignment 拥有座位及无座分配，Entitlement 拥有主票权益。
Travel Insurance 拥有保单和理赔 Case，理赔金额与支付结果分别归对应业务与资金所有者。
组件金额分配及附加服务联动的未完成工作统一见 [TODO](../TODO.md)。

## DR-010 Account, Traveler, Identity And Risk

| 领域 | 所有权 | 交给下游的内容 |
|---|---|---|
| Account | 账号、冻结状态、通知设置、关闭请求。 | 账号引用与状态。 |
| Traveler Profile | 旅客档案、证件资料和资格摘要。 | 旅客引用与资料快照。 |
| Identity Verification | 实名核验、资格证明、购买前检查和限购使用记录。 | 核验结果、资格与使用事实。 |
| Risk & Compliance | 风险评估、规则结论、阻断和人工覆盖记录。 | 风险结果与处置建议。 |
| Fare & Pricing | 价格及资格适用规则。 | 报价和规则解释。 |

Identity Verification 与 Risk & Compliance 使用 Python。
接收方负责把核验和风险结果应用于自身命令校验。
账号关闭入口归 Account，各域持有自身业务记录与保留责任。
完整关闭协作和其他未完成能力见 [TODO](../TODO.md)。

## DR-011 Controlled Operations And Read Models

Customer Service 管理工单、证据、沟通、升级和人工操作请求。
Admin & Audit 管理操作员、审批、ManualAction 和审计。
目标服务执行通过授权的业务命令，并校验自身状态转换。
Reporting 管理查询投影和指标，Notification 管理触达任务。
各服务只通过负责域的接口改变业务状态。

## DR-012 Sensitive Evidence Ownership

旅客原始资料保存在 Traveler Profile 或负责核验的受控存储中。
支付与供应商原始交互由对应接入服务保存。
跨域协作使用必要的业务引用、脱敏字段和证据引用。
客服、审计与报表按职责读取所需内容。
新增证据字段时，同时定义生产者、消费者、访问权限和保留规则。
敏感数据治理的实现进度统一见 [TODO](../TODO.md)。

## DR-013 Finance, Invoicing And Reporting

Finance Settlement 拥有收入确认、财务冲减、结算、渠道对账和权益成本。
Invoicing 是独立 Rust 服务，拥有 InvoiceTitle、EInvoice、RedFlush 和 ItineraryReceipt。
电子发票申请调用 Invoicing 的 `POST /api/v1/e-invoice-requests`。
Finance 的 `/api/v1/invoices` 处理本域 Invoice 记录。
调用方根据服务身份及资源含义选择入口。

Reporting 的经营金额口径为 gross captured payment。
金额唯一来源为 `PaymentCaptured`，按维度汇总已收款总额。
现有 `totalRevenue`、`revenue`、`routeRevenue`、`revenue_per_hour` 字段沿用其 API 名称。
gross captured payment 保留收款总额，退款净额和财务已确认收入使用各自口径。
其他指标与维度的未完成工作见 [TODO](../TODO.md)。

服务质量补偿的 `BenefitIssued` 在 Finance 形成补偿成本。
`COMPENSATION` Case 的完成保持订单原支付与发票金额依据，发放详情见 DR-020。

## DR-014 Business Commands And References

客户端使用目标服务注册的 HTTP 接口。
Journey Order 提供订单命令和 accepted purchase source 查询。
Booking 提供 Saga、SegmentBooking、改签预留与执行补偿接口。
Post Sales 提供退改评估、审批、改签执行和钱包补偿执行入口。
跨域请求携带各接口要求的业务引用、幂等键和关联标识。
重试读取原业务结果，并保留 Case、Order、Booking、Hold、Payment 与 Entitlement 的关联。

## DR-015 Supply Evidence And Reservation Identity

供给证据分别由 Service Plan 和 Seat Assignment 产生，Capacity 拥有预留记录。
Booking 身份连接旅客、分段、日期、席别与占用结果。
Journey Order 的 accepted purchase source 接口提供订单创建时接受的 Offer。
订单改签后，原购买来源继续支持原预订事实核对。
Seat Assignment 重放使用已记录编组和政策证据。
Capacity 新联合供给已在提交 `caafa668` 中实现，尚未部署，其引用固化和接入工作见 [TODO](../TODO.md)。

## DR-016 Waitlist

Waitlist 使用 TypeScript，拥有 WaitlistRequest、队列项、接受、取消和归档操作。
`POST /api/v1/waitlist/entries/:entryId/accept` 是当前接受入口。
候补兑现协作分别使用 Capacity 的占用、Booking 的预订和 Payment 的资金能力。
自动匹配及其他未完成工作见 [TODO](../TODO.md)。

## DR-017 Transfer Responsibility

Transfer Management 拥有 TransferPlan、Connection、ConnectionContract、MCT 规则和风险策略。
Trip Planning 拥有售前候选搜索，Offer 保存购买时的报价与披露引用。
运行状态进入 Transfer 后形成接续判断，受影响旅程由 Disruption Recovery 管理恢复选择。
保障接续的责任归 ConnectionContract，其执行结果关联售后、资金和权益事实。
完整能力与验收状态统一见 [TODO](../TODO.md)。

## DR-018 Dispatch

Dispatch 拥有 RideRequest、司机分配、ETA、到达、开始、完成和取消状态。
Service Plan 拥有固定班次及运行计划。
Fulfillment 拥有旅程使用事实，Payment 拥有资金结果，Fare 拥有收费规则。
Dispatch 的实际入口位于 `/api/v1/ride-requests`。

## DR-019 Fulfillment Evidence

Fulfillment 按业务记录保存进站、完成、未乘车和运行状态事实。
Entitlement 消费这些事实并维护自身权益状态。
客服更正请求保留原业务引用，授权由 Admin & Audit 管理，状态修改归目标服务。
离线核验及证据冲突处理的未完成工作统一见 [TODO](../TODO.md)。

## DR-020 Wallet And Compensation

Wallet / Promotion 拥有 benefit、钱包余额、权益预留、核销、释放和撤销。
Post Sales 决定服务质量补偿的对象、原因和金额，通过 `execute-compensation` 驱动执行。
执行器核对 Case、原订单、原票、已收款和已有退款记录，使用稳定业务标识调用 Wallet 的 `POST /api/v1/benefits`。

| 字段或结果 | 当前含义 |
|---|---|
| `benefitType=COMPENSATION_CREDIT` | 服务质量补偿权益。 |
| `balanceType=PROMOTION_CREDIT` | 钱包补偿额度的余额类型。 |
| `issuanceSource=POST_SALES_COMP` | 来源为 Post Sales 补偿。 |
| `caseId` | 审批与发放的业务关联。 |
| 原旅程权益 | 保留原票、SeatAllocation、CapacityHold、原支付和附加服务。 |
| 财务事实 | Finance 消费 BenefitIssued，记录补偿成本。 |
| 发票事实 | Invoicing 保留原订单金额依据。 |

原路退款由 Payment Refund 执行。
Loyalty Membership 拥有会员积分和等级，Marketing Campaign 拥有活动与发放批次，Wallet 拥有已发放 benefit 的余额及核销。
这些域的扩展与未完成联动统一见 [TODO](../TODO.md)。

## DR-021 Notification

Notification 消费订单、支付、出票、售后等业务事实，选择模板和渠道，记录通知状态与发送尝试。
`GET /api/v1/notifications/:id/trail` 提供触达记录查询。
业务服务拥有业务完成状态，Notification 拥有发送结果。
Notification durable 发送队列已在提交 `41917b85` 中实现、尚未部署，工作状态见 [TODO](../TODO.md)。
