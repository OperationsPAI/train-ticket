# Phase 1 Implementation Roadmap

Last updated: 2026-06-28

## 目的

这份文档把 DDD 设计转成第一阶段可拆任务。它不是排期，而是工程拆解顺序、验收口径和依赖关系。拆 `project-index.yaml` 或 issue 时，以这里的 work package 为基础。

## 拆分原则

1. 每个任务必须有明确 owning domain。
2. 跨域任务只能通过事件、命令或 ACL 契约连接，不能直接写别的 domain 内部状态。
3. 先搭交易闭环，再补治理和读模型。
4. 每个 task 都必须能落到一个或多个聚合、命令、事件、读模型或 ACL。
5. 任何旧服务迁移都必须走 Legacy ACL，不保留直接改状态路径。

## Work Packages

| ID | Work Package | Owning Domain | Main Outputs | Depends On | Acceptance |
|---|---|---|---|---|---|
| WP-01 | 统一 ID、值对象和事件 Envelope | Shared Kernel / Platform | Money、TimeWindow、PlaceRef、TravelerRef、SegmentRef、eventId、causationId、correlationId、occurredAt。 | none | 所有 domain 文档中的事件可使用同一 envelope 表达。 |
| WP-02 | Place 和站点主数据 | Place & Network | Place、TransportNode、ProviderPlaceMapping、低置信度映射处理。 | WP-01 | Service Plan 不能发布未知站点；供应商站点码有标准映射或缺口记录。 |
| WP-03 | Service Plan 运行计划 | Service Plan | Route、ServicePlan、Calendar、Timetable、PlanVersion、计划性停运。 | WP-02 | 可表达车次、运行日、停靠、时刻和计划版本；不包含停售限售和价格规则。 |
| WP-04 | Fare 和规则快照 | Fare & Pricing | FareRuleSet、FareQuote、RefundFee、ChangeFee、RuleSnapshot。 | WP-02, WP-03 | 主票报价、退票费、改签差价可解释且带规则版本。 |
| WP-05 | 内部区间库存 | Capacity & Availability | InventoryPool、CapacityHold、Quota、区间重叠检查、Hold TTL。 | WP-03 | 同一座席重叠区间不能重复 Hold；Hold 可 Confirm、Release、Expire。 |
| WP-06 | Trip Planning 查询候选 | Trip Planning | TripIntent、Itinerary、SearchResult、PriceHint、AvailabilitySnapshot 引用。 | WP-02, WP-03, WP-04, WP-05 | 查询不锁库存；结果能解释无票、停售、无可达方案。 |
| WP-07 | Offer 报价快照 | Offer Management | Offer、OfferItem、PriceSnapshot、RiskDisclosure、Offer TTL、ChangeOffer。 | WP-04, WP-05, WP-06 | Offer 不 Hold；下单必须引用有效 Offer；ChangeOffer 归 Offer Management。 |
| WP-08 | Journey Order 商业订单 | Journey Order | JourneyOrder、OrderItem、MonetarySummary、OrderTimeline projection。 | WP-07 | 订单只保存商业事实和引用；不直接扣库存、支付或出票。 |
| WP-09 | Booking Saga 和 SegmentBooking | Booking Orchestration | BookingSaga、SegmentBooking、ProviderReservation 映射、补偿状态。 | WP-05, WP-08, WP-11 | ProviderReservation* 映射成 SegmentReservation*；Saga 状态可查询、可重放。 |
| WP-10 | Payment 资金闭环 | Payment | PaymentIntent、Refund、callback record、late payment case、幂等键。 | WP-08 | PaymentCaptured 不直接改订单、库存、票证或通知；晚到回调进入补偿。 |
| WP-11 | Provider Integration 最小 ACL | Provider Integration | 支付渠道 adapter、供应商状态映射、签名验签、raw archive、错误归一。 | WP-01 | 外部状态不能直接泄漏给核心域；raw payload 受控存储。 |
| WP-12 | Entitlement 主票权益 | Entitlement & Ticketing | Entitlement、Credential 内部实体、Issue、Void、Suspend、Boarded 消费。 | WP-09, WP-10 | 出票必须满足 Booking、Payment、Capacity、Traveler、Risk 条件；Used 不可普通 Voided。 |
| WP-13 | Fulfillment 最小事实 | Fulfillment | FulfillmentRecord、BoardingVerified、NoShow、offline evidence dispute。 | WP-12 | 进站或检票映射为 Boarded；无可信完成事实时不自动 Used。 |
| WP-14 | Post Sales 退票改签 | Post Sales | PostSalesCase、RefundDecision、ChangeExecutionPlan、ReplacementBooking 引用。 | WP-04, WP-05, WP-09, WP-10, WP-12 | 退票先 Void Entitlement 再 RequestRefund；改签不能删旧单建新单。 |
| WP-15 | Notification 交易触达 | Notification | NotificationTask、template、recipient policy、send idempotency、receipt。 | WP-08, WP-10, WP-12, WP-14 | 通知失败不回滚业务；交易必要通知可绕过普通偏好。 |
| WP-16 | Traveler 和资格事实 | Traveler Profile | TravelerProfile、Document、EligibilitySummary、偏好快照。 | WP-01 | Fare 只消费资格事实，不重复拥有旅客资料。 |
| WP-17 | Risk 最小决策 | Risk & Compliance | RiskAssessment、Challenge、Block/Allow decision、evidence summary。 | WP-08, WP-10, WP-16 | Risk 不直接修改 Account、Traveler 或 Payment 状态。 |
| WP-18 | Account 最小能力 | Account | UserAccount、Session、Preference、Freeze、AccountClosureSaga shell。 | WP-15, WP-16 | 冻结阻断新交易，不阻断既有退款、通知、发票和客服。 |
| WP-19 | Admin & Audit 人工命令治理 | Admin & Audit | OperatorIdentity、Approval、ManualAction、AuditTrail。 | WP-01 | 客服和后台修正必须走受控命令；目标 domain 可拒绝。 |
| WP-20 | Customer Service 工单入口 | Customer Service | SupportCase、EvidenceRef、ManualActionRequest、CaseTimeline。 | WP-08, WP-10, WP-12, WP-14, WP-19 | 工单只协作和取证，不直接改业务状态。 |
| WP-21 | Finance 最小消费 | Finance Settlement | Payment/Order/PostSales event consumer、RevenueRecognition default、Reconciliation shell。 | WP-08, WP-10, WP-14 | Revenue 默认来自 Finance view，不直接从订单或支付表推导。 |
| WP-22 | Reporting 最小读模型 | Reporting | MetricDefinition、Order/Funnel/Ticketing basic dashboards。 | WP-06, WP-08, WP-10, WP-12, WP-21 | Reporting 只读，不提供修订单、修库存、补退款入口。 |
| WP-23 | Legacy ACL Strangler | Legacy ACL / All P0 domains | preserve/cancel/rebook/payment/execute 旧接口到新命令映射。 | WP-08-WP-14, WP-19 | 旧副作用被拆成受控命令、事件和审计；无直接写状态路径。 |

## 建议实施顺序

### Phase A：领域基础

WP-01 到 WP-05。目标是先让“有什么车、在哪里、多少钱、有没有库存”成为稳定模型。

### Phase B：交易主链路

WP-06 到 WP-12。目标是完成查询、报价、下单、锁库存、支付、出票的最小闭环。

### Phase C：售后和履约

WP-13 到 WP-15。目标是检票事实、退票、改签和交易通知可解释。

### Phase D：治理和迁移

WP-16 到 WP-23。目标是把用户、风控、客服、后台、财务、报表和旧系统迁移接上主链路。

## 第一阶段不拆任务的能力

| Capability | Reason |
|---|---|
| Waitlist | 已裁定为独立 future-scope；第一阶段主票闭环不实现。 |
| Wallet / Promotion | 已裁定为独立 future-scope；第一阶段不做非现金权益和组合支付。 |
| Dispatch | 已裁定为独立 future-scope；第一阶段不做网约车派单。 |
| ProtectedConnection | 第一阶段只做风险披露，不承诺保障联乘。 |
| Full Ancillary Automation | 第一阶段只保证可选附加失败不阻断主票。 |

## 任务验收模板

拆 issue 时使用以下字段：

| Field | Required Content |
|---|---|
| Domain | 唯一 owning domain。 |
| Aggregate / Read Model | 被修改的聚合、读模型或 ACL。 |
| Commands | 新增或修改的命令。 |
| Events | 新增或修改的领域事件。 |
| Invariants | 需要保护的不变量。 |
| Upstream / Downstream | 需要消费或发布的契约。 |
| Migration | 旧服务入口如何映射，是否需要 Legacy ACL。 |
| Acceptance | 可验证业务结果。 |
