# DDD Final Decision Record

Last updated: 2026-06-28

## 使用方式

这份文件是当前 DDD 设计的最终裁决入口。后续 domain 详细设计、`project-index.yaml` 和重构任务拆分必须以这里的决策为准。

Decision status：

- `accepted`：纳入当前全局 DDD 基线。
- `phase-1-scope`：纳入第一阶段火车票务重构范围。
- `phase-1-out-of-scope`：已经裁定不进入第一阶段。
- `future-scope`：通用出行扩展时采用该边界；当前不实现。

## 决策总览

| ID | Status | Related Conflicts | Decision |
|---|---|---|---|
| DR-001 | accepted | C-002, C-003, C-005 | Payment 拥有资金语义和资金状态；Provider Integration 拥有渠道协议和外部接入；JourneyOrder 不直接创建 PaymentIntent。 |
| DR-002 | accepted | C-006 | Provider Integration 只发布供应商事实 `ProviderReservation*`；Booking Orchestration 才发布平台内部 `SegmentReservation*`。 |
| DR-003 | phase-1-scope | C-009 | 第一阶段 Offer 不锁库存，Booking 才 Hold。 |
| DR-004 | accepted | C-010 | Service Plan 只表达计划可运行、时刻、停靠、计划性停运和版本；停售限售分别归 Capacity、Fare 和 Offer。 |
| DR-005 | accepted | C-011, C-033 | Supplier Catalog 保存供应商主数据和能力基线；Provider Integration 保存原始交互和实时健康；Place & Network 保存标准地点映射。 |
| DR-006 | phase-1-scope | C-015, C-016 | Fulfillment 是物理履约事实权威；Entitlement 是权益生命周期权威；第一阶段不拆独立 CredentialRegistry。 |
| DR-007 | accepted | C-018 | Service Plan 处理未来计划性停运；Disruption Recovery 处理已经影响用户、订单、履约或供应商事件的异常。 |
| DR-008 | accepted | C-020, C-021 | Post Sales 拥有售后 Case、执行状态和金额决策摘要；JourneyOrder 维护商业汇总；ChangeOffer 快照归 Offer Management。 |
| DR-009 | accepted | C-022, C-023 | 可选 Ancillary 失败不阻断主票；附加服务 bundle 必须有 component allocation，否则不能自动售后。 |
| DR-010 | accepted | C-024, C-025, C-026 | Account、Traveler Profile、Risk 拆分身份、旅客事实和风险决策；Risk 不直接改源域状态；账号关闭走 Saga。 |
| DR-011 | accepted | C-027, C-031 | Customer Service、Admin & Audit、Reporting、Notification 都不能成为核心业务决策或直接写状态的捷径。 |
| DR-012 | accepted | C-028 | 敏感 raw data 留源域或受控证据库，跨域事件只传最小摘要、引用和脱敏字段。 |
| DR-013 | accepted | C-029, C-030 | Finance 是财务事实和口径权威；Reporting 只消费 Finance view 和业务事件，并使用版本化 MetricDefinition。 |
| DR-014 | accepted | C-034 | 迁移必须通过 Legacy ACL 和受控命令 strangler；禁止把旧系统直接写状态能力原样迁入新模型。 |
| DR-015 | phase-1-scope | C-007 | 第一阶段默认平台内部库存权威；provider-owned inventory 的边界已定为 Capacity 统一建模，第一阶段不实现外部库存权威。 |
| DR-016 | phase-1-out-of-scope | C-008 | Waitlist 是独立支撑上下文，拥有 WaitlistRequest、队列排序和兑现策略；不进入第一阶段主票闭环。 |
| DR-017 | future-scope | C-013 | ProtectedConnection 是 Transfer Management 的合同类型，必须有明确披露、成本责任和恢复范围；第一阶段只支持 non-protected disclosure。 |
| DR-018 | future-scope | C-014 | 即时网约车进入独立 Dispatch context，不套 Service Plan；第一阶段不实现 Dispatch。 |
| DR-019 | accepted | C-017 | 离线核验冲突按事实优先级处理：可信在线事实高于签名离线事实，签名离线事实高于无凭证人工记录，人工纠错必须有授权和审计。 |
| DR-020 | phase-1-out-of-scope | C-004, C-019 | Wallet / Promotion 是独立支撑上下文；第一阶段只做现金支付、原路退款和人工可审计补偿，不做积分、券、钱包余额和组合支付。 |
| DR-021 | accepted | C-032 | Notification 分交易必要、服务提醒、营销三类；交易必要可绕过普通偏好，高优先级异常可受控突破频控和免打扰。 |

## DR-001 Payment Boundary

Payment 的聚合边界是 `PaymentIntent`、`Refund`、资金状态、渠道回调幂等和资金事实发布。Payment 不判断是否允许退票、是否应出票、是否应该通知用户。

Provider Integration 承载支付渠道 adapter 的运行时实现，包括签名验签、渠道 Webhook、渠道账单输入、raw archive、重试和错误映射。但 Provider Integration 不能定义资金语义；渠道成功必须回到 Payment 形成 `PaymentCaptured` 或 `RefundSettled` 等内部事实。

JourneyOrder 只能发布 `JourneyOrderPendingPayment` 之类的商业事实。PaymentIntent 由 Booking Orchestration 的 Saga 或 Payment Open Host Service 创建。

## DR-002 Reservation Naming

供应商返回的预留或确认事实必须先进入 Provider Integration，并使用 `ProviderReservationConfirmed`、`ProviderReservationFailed` 等语言。Booking Orchestration 消费这些事实后，基于内部 SegmentBooking 不变量发布 `SegmentReservationConfirmed` 或 `SegmentReservationFailed`。

这个拆分避免 PNR、外部订单号、司机派单码等供应商状态码污染平台内部状态机。

## DR-003 Offer And Inventory

第一阶段 Offer 不强 Hold。Offer 只冻结 Itinerary、价格、规则、风险、AvailabilitySnapshot 引用和有效期。Booking Orchestration 在用户接受 Offer 后才请求 Capacity Hold 或供应商预留。

如果未来引入 quote-reservation，它必须作为独立 hold purpose，设置极短 TTL、配额上限和不可承诺用语，不得把 Offer 变成库存权威。

## DR-004 Service Plan Scope

Service Plan 只回答“计划是否存在、如何运行、什么时间和站点运行、是否计划性停运”。它不回答某个用户是否可以买、哪个渠道能卖、多少钱、能不能退改。

| Rule type | Owner |
|---|---|
| 渠道限售、配额限售、库存限售 | Capacity & Availability |
| 价格、票规、优惠限制 | Fare & Pricing |
| 用户可见报价窗口和失效 | Offer Management |
| 计划性未来停运 | Service Plan |
| 已影响用户的取消、延误、停运 | Disruption Recovery |

## DR-005 Supplier, Provider And Place

Supplier Catalog 保存合同、承运商、供应商、产品、外部码来源和能力基线。Provider Integration 保存实时接口健康、熔断、重试、原始请求响应和外部状态映射。Place & Network 保存标准 PlaceId、TransportNode、别名、层级、置信度和冲突处理。

未知供应商地点码不能绕过 Place & Network 直接发布到 Service Plan。允许生成草稿、映射缺口和人工审核任务。

## DR-006 Entitlement And Fulfillment

Entitlement 是可使用权益的生命周期，Fulfillment 是物理使用事实的生命周期。供应商检票或登乘事件应先归一为 Fulfillment 事实，再由 Entitlement 消费并推进 Boarded 或 Used。

第一阶段火车场景：

1. 进站或检票成功映射为 `BoardingVerified` 和 Entitlement `Boarded`。
2. 没有可信到达或完成事实时，不自动把 Entitlement 推到 `Used`。
3. 纸质票取票映射为可选 `CheckIn`，并用 `CredentialType=PAPER_TICKET` 标注。
4. `CredentialRegistry` 先作为 Entitlement 内部实体。

## DR-007 Disruption Boundary

Service Plan 发布计划性未来停运、加开、恢复和时刻调整。Disruption Recovery 处理已经影响订单、履约、用户承诺或供应商状态的异常，并生成 RecoveryCase、RecoveryOption、责任和补偿决策。

## DR-008 Post Sales And Monetary Summary

Post Sales 是退票、改签、改程、补差、应退和补偿决策的 Case owner。Fare & Pricing 计算规则和金额；Payment 执行资金动作；JourneyOrder 只维护订单商业汇总和 OrderItem 状态摘要；Finance 做收入、清结算和对账。

JourneyOrder 不能重新定价，Post Sales 不能直接写 JourneyOrder 内部状态。Offer Management 拥有 ChangeOffer 报价快照和有效期，Post Sales 只引用 `changeOfferId` 执行售后流程。

## DR-009 Ancillary Scope

附加服务可以随主票展示或购买，但可选附加服务失败不能阻断主票确认。主票 Booking 与 Ancillary Service 的状态必须分开，用户侧读模型可以汇总展示。

Bundle 必须由 Fare & Pricing 产出 component allocation。没有 component allocation 的 bundle 不允许进入自动退款、自动补偿或自动收入拆分流程。SeatSelection 只拥有选座商品，真实座位占用归 Capacity，主票凭证归 Entitlement。保险默认在 `EntitlementIssued` 后激活；若险种要求支付后立即生效，必须在 Ancillary activation policy 中显式声明。

## DR-010 Identity, Traveler, Risk And Account Closure

Account 管登录主体、会话、账号状态和通知偏好。Traveler Profile 管乘车人、证件、旅客级偏好和资格事实。Risk & Compliance 管风险评估、挑战、拒绝和合规建议。

Risk 不能直接冻结账号或修改旅客资料，只能发布决策或建议事件，由 Account 或 Traveler Profile 接受命令并维护自身状态。

账号冻结和注销裁决：

| Scenario | Decision |
|---|---|
| Account frozen | 阻断新下单、新支付、新旅客资料修改；不阻断已存在订单通知、退款、发票和客服处理。 |
| Account deletion requested | 创建 AccountClosureSaga，向 Journey Order、Payment、Finance、Customer Service 拉取阻断摘要。 |
| Active journey / refund / invoice / complaint exists | 关闭请求进入 pending closure，不删除业务引用。 |
| Closure completed | 删除或匿名化可删个人数据，保留合规所需快照和审计引用。 |

## DR-011 Human, Read-only And Notification Boundaries

Customer Service 拥有 SupportCase、用户沟通、证据引用和人工协作流程。Admin & Audit 拥有权限、审批、四眼原则、审计保全和全局查询。Reporting 只读。Notification 只触达。

任何人工修正都必须进入目标 domain 的受控命令。目标 domain 有权拒绝不满足不变量的命令。

Notification 分类：

| Type | Preference Handling | Example |
|---|---|---|
| Transactional required | 可绕过普通 Push/Email/SMS 偏好，但必须最小内容和审计。 | 支付、出票、退票、停运。 |
| Service reminder | 尊重偏好和免打扰；高优先级异常可带 approvalRef 突破。 | 出行提醒、检票提醒。 |
| Marketing | 必须有明确 consent，不得突破偏好。 | 促销、推荐。 |

## DR-012 Sensitive Data Boundary

Provider raw request/response、证件号、手机号、支付报文、供应商原文和小样本报表 cohort 都必须按最小必要原则处理。

| Data Class | Rule |
|---|---|
| Public / operational | 可进入普通读模型。 |
| Internal business | 可跨域事件传递必要字段。 |
| Sensitive personal | 只传 masked value、hash、reference；原文留源域或证据库。 |
| Payment / credential secret | 不进入普通事件；只能以 token/ref 方式跨域。 |
| Provider raw payload | 加密存储、保留期、访问理由和审计必需。 |

敏感查看必须有权限、原因、时间限制和审计事件。Customer Service、Reporting、Admin 只能按角色访问裁剪后的视图。

## DR-013 Finance And Reporting

Finance Settlement 是收入确认、清结算、发票、对账差异、记账汇率和财务口径的 owner。Reporting 只消费 Finance view 和业务事件，并用版本化 MetricDefinition 管理指标口径。

默认财务策略：

| Topic | Decision |
|---|---|
| Cash received | `PaymentCaptured` 后记为现金收到和待履约负债。 |
| Revenue recognition | 默认在 `SegmentCompleted` 或计划完成宽限期后确认；无可信 Fulfillment 时使用计划完成加人工可纠正规则。 |
| Refund | 按 Post Sales / Disruption 的原因和金额冲减；Payment 只执行资金。 |
| FX | Payment 保存渠道原币资金事实；Finance 使用记账汇率入账，差异归 Finance。 |
| Invoice | 第一阶段作为 Finance 子能力，不独立拆 bounded context。 |

默认 Reporting 口径：

| Metric | Default Definition |
|---|---|
| GMV | 已创建并进入支付或确认流程的订单 gross amount，按版本化规则排除测试和失败重复单。 |
| Revenue | Finance 已确认收入，不从订单表或支付表直接推导。 |
| Ticketing success rate | 分母为满足出票前置条件的 SegmentBooking，分子为 EntitlementIssued。 |
| Search conversion | 同一 account/session 在 30 分钟内从 ItineraryProposed 到 OfferAccepted 或 JourneyOrderCreated。 |
| Recovery success | 拆为 adoption、execution、financial completion、case closure 四个指标。 |

## DR-014 Legacy Strangler

旧系统中的删单式改签、直接改状态、硬编码退改规则、同步副作用和 ad-hoc SQL 不能直接迁入新设计。迁移期必须通过 Legacy ACL 把旧操作转成受控命令，并记录 sourceEventId、operator、reason 和 audit reference。

第一阶段裁决：

1. `preserve` 链路拆成 CreateJourneyOrder、StartBookingSaga、HoldCapacity、CreatePaymentIntent、IssueEntitlement。
2. `cancel` 链路拆成 PostSalesCase、VoidEntitlement、CancelSegmentBooking、ReleaseCapacity、RequestRefund。
3. `rebook` 不允许删旧单建新单，必须用 replacement SegmentBooking 和旧 Entitlement void。
4. 支付回调先进入 Payment callback record，不允许直接写 order/ticket/notification。
5. 客服后台旧直接改状态能力必须变成 ManualAction + target domain command。

## DR-015 Provider-owned Inventory

Capacity & Availability 统一拥有库存可用性解释，即使真实库存权威在供应商。第一阶段只实现平台内部库存权威。

未来 provider-owned inventory 使用以下状态语义：

| State | Meaning |
|---|---|
| SnapshotAvailable | 供应商或缓存显示可售，只能用于搜索和报价提示。 |
| ProviderHoldRequested | 已向供应商请求保留。 |
| ProviderHeld | 供应商确认临时保留，有 provider evidence。 |
| ProviderConfirmed | 供应商最终确认，可作为出票前置条件。 |
| ProviderRejected / Unknown | 不允许出票，进入重试、替代方案或人工。 |

`CapacityHoldConfirmed` 在 provider-owned 场景必须引用 `ProviderConfirmed` evidence。

## DR-016 Waitlist

Waitlist 是独立支撑上下文，不放进 Capacity 内部，也不由 Booking Orchestration 临时实现。它拥有 WaitlistRequest、排序、公平性、优先窗口、互斥候补和到期策略。Capacity 只发布 `CapacityReleased` 等释放事实，并按 Waitlist 授权执行 Hold。

第一阶段主票闭环不实现 Waitlist。若产品要求候补进入第一阶段，必须把 Waitlist 作为独立 domain 加入第一阶段范围，而不是塞回 Capacity。

## DR-017 Connection Contract

Transfer Management 拥有 ConnectionContract。合同类型裁决为：

| Type | Meaning |
|---|---|
| SelfTransfer | 用户自理，平台只展示风险。 |
| PlatformAssisted | 平台可提示和协助重订，但默认不承担费用。 |
| ProtectedConnection | 平台或供应商承诺恢复范围和成本责任，必须明确披露和保存 acceptedDisclosureIds。 |
| SupplierProtected | 供应商自身保障，平台只映射和展示合同来源。 |

第一阶段火车主链路只支持 SelfTransfer / PlatformAssisted 的风险披露，不承诺 ProtectedConnection。

## DR-018 Dispatch

固定班次、预约共享班车和有 headway 的服务可以用 Service Plan。即时网约车、司机匹配、车辆 ETA、等待费和司机取消必须进入 Dispatch context。

Fulfillment 只从 driver arrived、pickup、ride started、ride ended 之后记录履约事实，不拥有派单。第一阶段不实现 Dispatch。

## DR-019 Offline Verification Conflict Priority

离线核验冲突裁决为：

1. 可信在线供应商或平台核验事实优先级最高。
2. 带签名、设备身份和未过期核验包的离线成功事实可临时接受。
3. 离线事实与在线事实冲突时，创建 `FulfillmentEvidenceDispute`，不直接改写历史。
4. 人工纠错必须由 Customer Service 发起、Admin & Audit 授权，并产生 `FulfillmentFactCorrected`。
5. Risk 可以发布 Suspend 建议，但 Fulfillment 和 Entitlement 分别维护自身状态。

## DR-020 Wallet, Promotion And Compensation

Wallet / Promotion 是独立支撑上下文。Account 只保存 `WalletRef` 和展示摘要；Payment 只处理现金通道；Finance 只做入账和对账。

补偿执行路由：

| Compensation Type | Decision Owner | Execution Owner |
|---|---|---|
| 原路退款 | Post Sales / Disruption Recovery | Payment Refund |
| 现金人工赔付 | Customer Service / Post Sales | Payment controlled disbursement + Admin Audit |
| 券、积分、余额 | Post Sales / Disruption Recovery | Wallet / Promotion |
| 餐饮、住宿、接送服务 | Disruption Recovery / Customer Service | Ancillary Service |
| 费用减免 | Post Sales | Fare & Pricing decision + Payment/Fund adjustment |

第一阶段只做原路退款和有审计的人工现金补偿，不做积分、券、钱包余额和组合支付。
