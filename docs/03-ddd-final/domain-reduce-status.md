# Domain Final Status

Last updated: 2026-06-28

## 状态定义

| Status | Meaning |
|---|---|
| `phase-1-core` | 第一阶段火车票务闭环核心上下文。 |
| `phase-1-support` | 第一阶段需要的支撑或治理上下文。 |
| `phase-1-limited` | 第一阶段只实现明确裁剪后的能力。 |
| `future-scope` | 已裁定为后续 General Travel 扩展上下文，当前不实现。 |

这里的状态是最终裁决。实现和任务拆分以本文件为准。

## Domain 状态表

| Domain | Priority | Final Status | Phase 1 Decision | Decision Refs |
|---|---|---|---|---|
| Place & Network | P0 | `phase-1-core` | 标准站点、节点、别名、供应商地点码映射、低置信度解释。 | DR-005 |
| Service Plan | P0 | `phase-1-core` | 车次、运营日历、时刻、停靠、计划性停运和版本；不拥有停售限售。 | DR-004 |
| Capacity & Availability | P0 | `phase-1-core` | 平台内部火车区间库存、Hold、Confirm、Release、Expire、配额；provider-owned inventory 不进入第一阶段。 | DR-003, DR-015 |
| Fare & Pricing | P0 | `phase-1-core` | 主票基础票价、退改费、差价、规则快照；bundle allocation 规则已定但复杂附加服务自动化不进入第一阶段。 | DR-004, DR-009, DR-013 |
| Trip Planning | P0 | `phase-1-core` | 售前候选、可达性、排序、PriceHint 和 AvailabilitySnapshot 引用；不拥有 Offer 冻结和 Transfer 运行中风险。 | DR-003, DR-017 |
| Offer Management | P0 | `phase-1-core` | 主票 Offer、ChangeOffer 快照、有效期、风险披露、no-hold 报价。 | DR-003, DR-008, DR-017 |
| Journey Order | P0 | `phase-1-core` | 商业订单、OrderItem 摘要、订单汇总状态、待支付商业事实；不直接创建 PaymentIntent。 | DR-001, DR-008 |
| Booking Orchestration | P0 | `phase-1-core` | 下单 Saga、SegmentBooking、供应商结果映射、补偿和晚到事件；禁止删单式改签。 | DR-002, DR-014 |
| Payment | P0 | `phase-1-core` | 现金支付、原路退款、渠道回调幂等、资金事件；不做钱包、积分、券和组合支付。 | DR-001, DR-020 |
| Provider Integration | P0 | `phase-1-support` | 最小 ACL、支付渠道 adapter、供应商状态映射、raw archive、签名验签和错误归一。 | DR-001, DR-002, DR-005, DR-012 |
| Entitlement & Ticketing | P0 | `phase-1-core` | 主票 Entitlement、Issue、Void、Suspend、Boarded 消费；CredentialRegistry 不独立拆分。 | DR-006 |
| Fulfillment | P1 | `phase-1-limited` | 至少记录检票、进站、BoardingVerified；无可信完成事实时不自动 Used。 | DR-006, DR-019 |
| Post Sales | P0 | `phase-1-core` | 主票退票、改签、差价、应退应补、Case 状态和执行编排。 | DR-008, DR-020 |
| Notification | P1 | `phase-1-support` | 交易通知、模板、渠道、幂等、回执、重试；交易必要通知可绕过普通偏好。 | DR-011, DR-021 |
| Admin & Audit | P1 | `phase-1-support` | 权限、审批、审计、人工命令网关、敏感访问审计。 | DR-011, DR-012, DR-014 |
| Customer Service | P1 | `phase-1-support` | SupportCase、证据引用、人工协作和受控命令入口；不能直接改业务状态。 | DR-011, DR-014, DR-019 |
| Traveler Profile | P0 | `phase-1-support` | 旅客、证件、出行偏好、资格事实；价格解释归 Fare。 | DR-010 |
| Risk & Compliance | P1 | `phase-1-support` | 风险评估、挑战、拒绝、合规建议和证据摘要；不直接改 Account、Traveler 或 Payment。 | DR-010, DR-019 |
| Account | P1 | `phase-1-limited` | 登录主体、会话、账号偏好、账号状态事实；冻结和注销走 AccountClosureSaga；不拥有 Wallet。 | DR-010, DR-020 |
| Supplier Catalog | P1 | `phase-1-support` | 供应商、承运商、合同、产品、外部码和能力基线；实时健康归 Provider Integration。 | DR-005 |
| Finance Settlement | P1 | `phase-1-limited` | 消费 Payment、Order、PostSales 事件；拥有收入确认、清结算、发票和对账口径，第一阶段不阻塞交易闭环。 | DR-013 |
| Reporting | P2 | `phase-1-limited` | 只读消费事件和 Finance view；使用版本化 MetricDefinition；不提供写入口。 | DR-011, DR-013 |
| Disruption Recovery | P1 | `future-scope` | 边界已定：处理已经影响用户、订单或履约的异常；第一阶段只保留事件和恢复入口，不做完整恢复平台。 | DR-007, DR-020 |
| Transfer Management | P1 | `future-scope` | 边界已定：拥有 ConnectionContract 和运行中风险；第一阶段只做 non-protected disclosure。 | DR-017 |
| Ancillary Service | P1 | `future-scope` | 边界已定：可选附加服务独立 OrderItem，不阻断主票；复杂 bundle、保险激活和自动售后后续实现。 | DR-009 |
| Waitlist | P1 | `future-scope` | 新增裁决上下文：独立 Waitlist，不塞进 Capacity；第一阶段不实现。 | DR-016 |
| Wallet / Promotion | P1 | `future-scope` | 新增裁决上下文：独立非现金权益，不属于 Account 或 Payment；第一阶段不实现。 | DR-020 |
| Dispatch | P1 | `future-scope` | 新增裁决上下文：即时网约车派单，不套 Service Plan；第一阶段不实现。 | DR-018 |

## 第一阶段并行设计批次

### Batch A：交易闭环

Journey Order、Booking Orchestration、Payment、Capacity & Availability、Entitlement & Ticketing、Post Sales、Fulfillment。

这些 domain 的详细设计必须共同遵守 `phase-1-contract.md` 的顺序、失败处理和红线。

### Batch B：查询和供给

Place & Network、Service Plan、Fare & Pricing、Trip Planning、Offer Management、Provider Integration、Supplier Catalog。

Provider Integration 只做最小 ACL，不把外部状态泄漏给核心域。

### Batch C：治理和辅助

Traveler Profile、Risk & Compliance、Notification、Customer Service、Admin & Audit、Account、Finance Settlement、Reporting。

这些上下文在第一阶段的实现应围绕交易闭环最小可用能力，不做完整平台化。

### Batch D：后续扩展

Transfer Management、Disruption Recovery、Ancillary Service、Waitlist、Wallet / Promotion、Dispatch。

这些上下文边界已经裁定，但不进入第一阶段主票现金支付闭环。
