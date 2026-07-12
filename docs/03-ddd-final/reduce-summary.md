# DDD Final Summary

Last updated: 2026-06-28

## 当前结论

DDD map/reduce 已收敛为当前重构基线。此前需要裁决的事项已经归入三类：

1. 纳入第一阶段。
2. 第一阶段明确不做。
3. 作为 General Travel 后续扩展边界。

这意味着当前文档可以作为 `project-index.yaml`、第一阶段重构任务拆分和后续 domain 详细设计的输入。

## 最终文档集

| Document | Role |
|---|---|
| `reduce-summary.md` | 当前最终结论和第一阶段范围。 |
| `decision-record.md` | 全局 DDD 裁决和跨域边界。 |
| `phase-1-contract.md` | 第一阶段火车票务重构契约、链路和验收 gate。 |
| `domain-reduce-status.md` | 28 个 domain 的最终状态、阶段归属和裁决引用。 |

原 reduce 工作台中的冲突日志、集成检查清单和问题列表已经被最终文档取代。

## Reduce 状态

| 项 | 结果 |
|---|---|
| Domain 数量 | 28 |
| 真实冲突数量 | 33 |
| 已裁决冲突 | 33 |
| 未裁决冲突 | 0 |
| 当前 gate | `accepted-for-phase-1-baseline` |

## 第一阶段火车重构范围

第一阶段目标是把固定班次火车票务闭环做可信，而不是一次性实现 General Travel 全部能力。纳入第一阶段的上下文如下：

1. Place & Network
2. Service Plan
3. Capacity & Availability
4. Fare & Pricing
5. Trip Planning
6. Offer Management
7. Journey Order
8. Booking Orchestration
9. Payment
10. Provider Integration
11. Entitlement & Ticketing
12. Fulfillment
13. Post Sales
14. Notification
15. Admin & Audit
16. Customer Service
17. Traveler Profile
18. Risk & Compliance

Provider Integration 第一阶段只做最小 ACL：支付渠道协议适配、供应商状态映射、幂等、签名验签、raw archive 和错误归一，不承载核心订单规则。

## 第一阶段明确不做

这些是已裁定的 scope：

| Capability | Decision |
|---|---|
| Wallet / Promotion / Points | 独立支撑上下文；第一阶段不做积分、券、钱包余额和组合支付。 |
| Waitlist | 独立支撑上下文；第一阶段主票闭环不做候补。 |
| Dispatch / Ride-hailing | 独立 Dispatch context；第一阶段不做网约车派单。 |
| ProtectedConnection | Transfer Management 的合同类型；第一阶段只做 non-protected disclosure，不承诺保障联乘。 |
| Provider-owned inventory | Capacity 统一建模；第一阶段默认平台内部库存权威。 |
| Independent CredentialRegistry | 后续多凭证、动态码或票纸库存再拆；第一阶段作为 Entitlement 内部实体。 |
| Full ancillary automation | 第一阶段只保证可选附加失败不阻断主票；复杂 bundle、保险激活和自动售后不做。 |

## 已形成的全局决策

| Area | Decision |
|---|---|
| Payment / Provider Integration | Payment 拥有资金语义；Provider Integration 拥有渠道协议 adapter、签名验签、Webhook 接入、raw archive 和渠道账单输入。 |
| Payment callback | 支付回调只推进 Payment 内部状态并发布资金事实，不直接改订单、出票、库存或通知。 |
| PaymentIntent creation | JourneyOrder 只发布待支付商业事实；Booking Orchestration 或 Payment OHS 发起 PaymentIntent。 |
| Provider vs Segment reservation | Provider Integration 发布 `ProviderReservation*`，Booking Orchestration 映射并发布 `SegmentReservation*`。 |
| Offer / Capacity | 第一阶段 Offer 不锁库存，只消费 AvailabilitySnapshot 和 TTL；真正 Hold 发生在 Booking。 |
| Service Plan | Service Plan 只拥有运行计划、时刻、计划性停运和版本，不拥有停售限售。 |
| Place mapping | Provider Integration 接收原始地点码；Place & Network 拥有标准映射和冲突处理。 |
| Transfer boundary | Trip Planning 做售前候选和粗筛；Transfer Management 拥有 ConnectionContract、MCT 版本和运行中风险状态。 |
| Fulfillment / Entitlement | Fulfillment 是物理履约事实权威；Entitlement 消费履约事实推进权益生命周期。 |
| Disruption boundary | 计划性未来停运归 Service Plan；已影响订单、履约或用户的异常归 Disruption Recovery。 |
| Monetary summary | Post Sales 发布售后决策和金额摘要；JourneyOrder 维护自身商业汇总；Finance 做对账。 |
| ChangeOffer | Offer Management 拥有 ChangeOffer 快照；Post Sales 拥有售后 Case 和执行状态。 |
| Ancillary optional failure | 可选附加服务失败不阻断主票 Confirmed；无 component allocation 的 bundle 不能自动售后。 |
| Preference / Eligibility | Account 保存账号偏好；Traveler Profile 保存旅客偏好和资格事实；Fare 解释规则适用性。 |
| Account closure | 冻结阻断新交易但不阻断既有订单通知、退款、发票和客服；注销走 AccountClosureSaga。 |
| Risk boundary | Risk 只发布评估、挑战、拒绝或建议；源 domain 拥有自身状态机。 |
| Sensitive data | 原始敏感数据留源域或受控证据库；跨域事件只带摘要、引用和脱敏字段。 |
| Finance / Reporting | Finance 是财务事实和口径权威；Reporting 使用版本化 MetricDefinition，不直接从订单或支付表推导最终口径。 |
| Manual operation | Customer Service、Admin & Audit、Reporting 不直接写业务状态，所有修正进入目标 domain 命令。 |
| Notification | Notification 只负责触达，不参与核心业务决策；交易必要通知可绕过普通偏好。 |
| Supplier master data | Supplier Catalog 保存供应商主数据和能力基线；Provider Integration 保存实时健康和熔断。 |
| Legacy migration | 旧 preserve/cancel/rebook/payment 副作用必须通过 Legacy ACL 转受控命令。 |

## High-level 回写

本轮 reduce 已回写 high-level 文档中的明显冲突：

1. `context-map.md`：Service Plan 不再拥有停售限售；第一阶段加入最小 Provider Integration；候补从 Capacity 默认职责中移出。
2. `domain-glossary.md`：Compensation 的决策归 Disruption Recovery / Post Sales / Customer Service，资金或权益执行按类型路由。
3. `order-inventory-payment-model.md`：JourneyOrder 不直接创建 PaymentIntent。
4. `event-storming.md`：Provider Integration 产生 `ProviderReservation*`，Booking Orchestration 产生 `SegmentReservation*`。
5. `general-travel-ddd.md`：事件目录拆分供应商预留事实和平台分段预留事实。

## 下一步

1. 用 `phase-1-contract.md` 拆第一阶段重构任务。
2. 用 `decision-record.md` 约束所有 domain 详细设计。
3. 用 `domain-reduce-status.md` 生成 `project-index.yaml`。
4. 在实现语言和服务形态确定后补 API 契约、AsyncAPI 或 protobuf。
