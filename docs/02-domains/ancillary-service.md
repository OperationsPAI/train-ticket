# Ancillary Service Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Ancillary Service |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-ancillary-service |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/general-travel-ddd.md`, `docs/02-domains/fare-pricing.md`, `docs/02-domains/offer-management.md` |

## 1. 领域目标

Ancillary Service 负责 General Travel 中非主运输权益的附加服务建模，包括保险、餐食、行李/托运、选座、接送、贵宾室、快速安检、站内/港口/机场服务等。它解决的问题不是“主行程是否可售”，而是“某个旅客、某段 Segment、某个 Journey 是否有资格购买、如何报价输入、如何被选择、如何形成订单项、如何履约、主行程变化后如何联动售后”。

本领域独立的原因是附加服务有自己的商品形态、资格规则、绑定对象、履约节点和失败补偿：保险可能绑定整段 Journey，Meal 可能绑定某个车次/航班时间，Baggage 或 Consign 可能绑定旅客和 Segment，SeatSelection 可能绑定座位图和 Entitlement，接送可能绑定地址和履约调度，贵宾室可能绑定地点和时间窗口。把这些规则塞进 Journey Order、Offer Management 或 Entitlement 会导致主票模型被附加商品污染。

Ancillary Service 的目标是提供可组合但边界清晰的 `AncillaryOffer`、`AncillaryOrderItem` 和履约协作能力，让附加服务既可在主票购买时 bundle，也可在出票后追加购买或售后变更，并能在主行程取消、改签、异常恢复时正确退款、保留、重订或补偿。

## 2. 边界

### In Scope

- 管理附加服务目录中的平台商品语义：`Insurance`、`Meal`、`Baggage`、`Consign`、`SeatSelection`、`TransferPickup`、`Lounge`、`FastTrack`、`Bundle` 等。
- 计算 `Eligibility`：旅客资质、年龄、证件、Segment 类型、出发到达地、时间窗口、供应商能力、主票状态、履约阶段是否允许购买或变更。
- 生成附加服务报价输入和 `AncillaryOffer`：服务项、绑定对象、数量、旅客、Segment、价格输入、规则摘要、有效期、可退改说明。
- 支持用户选择附加服务，并产出可被 Journey Order 保存的 `AncillaryOrderItem` 数据与快照引用。
- 管理附加服务订单项生命周期：Selected、PendingConfirmation、Confirmed、FulfillmentReady、Fulfilled、Failed、Cancelled、RefundPending、Refunded。
- 参与主行程下单、支付、出票、履约、退改、异常恢复的 Saga，提供附加服务确认、取消、重订、退款建议和补偿命令。
- 处理 bundle：交通主票 + 附加服务、多个附加服务组合、保障服务包、行李 + 座位 + 餐食等组合的资格和拆分规则。
- 记录履约事实：餐食发放、行李托运/提取、贵宾室核销、快速通道使用、接送完成、保险保单生效或作废。
- 为客服、前端、售后和报表提供附加服务读模型和审计轨迹。

### Out of Scope

- 不拥有主 `JourneyOrder` 聚合；Journey Order 保存订单和订单项汇总，本域只产出和更新 `AncillaryOrderItem` 相关事实。
- 不执行 `Payment`、不扣款、不退款、不处理支付渠道回调；本域只提供应收、应退、保留、补偿原因和金额依据。
- 不拥有主票 `Entitlement`；本域可请求或消费服务凭证，但不签发主票、车票、机票、船票或乘车码。
- 不拥有 Fare 的价格规则；附加服务价格、退改费、税费和折扣由 Fare & Pricing 计算，本域消费 `PriceQuote`、`RuleSnapshot` 和 `FeeAssessment`。
- 不拥有 Provider 原始 API；保险公司、餐食供应商、行李托运、座位图、接送平台、贵宾室系统由 Provider Integration 或对应履约上下文防腐后接入。
- 不决定主行程路线、库存、占座、出票、主票退改或中转保障；这些属于 Trip Planning、Capacity & Availability、Booking Orchestration、Entitlement、Post Sales、Transfer Management。
- 不发送通知，只发布事件由 Notification 消费。
- 不做财务结算和发票，只提供服务项履约、取消、退款和供应商成本事实给 Finance Settlement。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| AncillaryService | 附加服务能力或商品类型的领域概念。 | 不等于供应商原始商品编码。 |
| AncillaryOffer | 对一个或多个附加服务的资格、价格、规则、绑定对象和有效期快照。 | 可独立报价，也可进入 Offer 的 OfferItem。 |
| AncillaryOrderItem | Journey Order 下的附加服务订单项事实。 | 由 Journey Order 持有汇总，本域拥有服务项状态和履约协作。 |
| Eligibility | 某服务对旅客、Segment、Journey、时间和供应商能力是否可购买、可变更、可退款的判断结果。 | 必须记录拒绝原因和输入摘要。 |
| Insurance | 出行保险服务。 | 通常绑定 Journey 或旅客，可能在主票支付后生效。 |
| Meal | 餐食预订。 | 绑定 Segment、座席/舱位、供应时间窗口。 |
| Baggage | 行李额度或额外行李服务。 | 常见于航空、轮船、大巴，也可表达铁路行李额度。 |
| Consign | 托运服务。 | 有独立受理、装载、到达、提取履约状态。 |
| SeatSelection | 选座服务。 | 与 SeatMap、Capacity、Entitlement、Fulfillment 强相关，但不拥有主座席库存。 |
| Bundle | 多个附加服务或主票与附加服务的组合销售单元。 | 需定义拆分、退款和失败补偿规则。 |
| ServiceVoucher | 附加服务核销凭证。 | 贵宾室、快速通道、餐食、接送等可使用。 |
| FulfillmentWindow | 附加服务可履约时间窗。 | 例如发车前、值机后、到站后。 |
| AttachmentScope | 附加服务绑定范围。 | Journey、Segment、Traveler、Entitlement、Place、Transfer。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Offer Management | Itinerary/OfferItemRef、Offer validity、RiskDisclosure、accepted Offer snapshot | 附加服务可作为主 Offer 的组合项或独立追加报价。 |
| Journey Order | JourneyOrderCreated、OrderItem summary、traveler set、order status summary | 创建和关联 `AncillaryOrderItem`，感知主订单变化。 |
| Booking Orchestration | SegmentReservationConfirmed/Failed、booking status summary | 主段确认失败时取消或补偿附加服务确认。 |
| Entitlement & Ticketing | EntitlementIssued、EntitlementVoided、ticket/boarding summary | 决定选座、餐食、行李、贵宾室等是否可履约。 |
| Fulfillment | SegmentCheckInOpened、SegmentBoarded、SegmentCompleted、no-show facts | 推进服务核销、托运、餐食发放和 no-show 售后。 |
| Fare & Pricing | PriceQuote、RuleSnapshot、FeeAssessment、AdjustmentQuote | 获取附加服务价格、退改规则、bundle 拆分和费用评估。 |
| Traveler Profile | traveler age、document、special needs、preference | 资格、餐食偏好、特殊旅客服务、保险适用性。 |
| Capacity & Availability | SeatMap summary、baggage capacity signal、service slot availability | 选座、行李额度、接送时段、贵宾室容量等可用性输入。 |
| Provider Integration | normalized provider service catalog、confirmation result、voucher status | 外部服务能力和履约结果需经 ACL 归一化。 |
| Post Sales / Disruption Recovery | cancellation/change/recovery intent、waiver policy | 主行程退改、异常恢复时联动附加服务。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Offer Management | AncillaryOfferQuoted、Eligibility result、Ancillary bundle candidate | 组合到主 Offer 或追加 Offer。 |
| Journey Order | AncillaryOrderItemCreated/Confirmed/Cancelled/Fulfilled、item snapshot | 订单详情展示、状态汇总和售后入口。 |
| Booking Orchestration | ConfirmAncillaryItem、CancelAncillaryItem、CompensateAncillaryItem result | 下单编排中的附加服务确认和失败补偿。 |
| Payment | payable/refundable amount via Journey Order or Post Sales decision | Payment 只消费订单或售后决策，不直接调用本域。 |
| Post Sales | AncillaryRefundability、AncillaryCancelResult、AncillaryChangeImpact | 主票退改时判断附加服务是否随退、保留或重订。 |
| Entitlement & Ticketing | IssueServiceVoucher、VoidServiceVoucher request | 需要服务凭证的附加服务由票证上下文签发或作废凭证。 |
| Fulfillment | ServiceVoucherReady、SeatSelectionConfirmed、ConsignAccepted | 履约系统核销和执行。 |
| Customer Service | AncillaryServiceTimeline、provider ref summary、failure reason | 客服解释、人工补偿、争议处理。 |
| Finance Settlement / Reporting | service sold/fulfilled/refunded events、supplier cost component | 清结算、收入确认、供应商绩效和转化分析。 |
| Notification | AncillaryConfirmed、AncillaryFailed、ServiceVoucherReady、Refunded | 通知用户服务确认、失败、核销码或退款进度。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| AncillaryCatalogItem | 每个服务项必须有 serviceType、AttachmentScope、适用 modal、供应商能力、可售窗口、资格规则版本、履约方式；Published 后不可原地修改规则，只能新版本替代。 | CreateCatalogItem、PublishCatalogItem、SuspendCatalogItem、SupersedeCatalogItem | AncillaryCatalogItemCreated、AncillaryCatalogItemPublished、AncillaryCatalogItemSuspended、AncillaryCatalogItemSuperseded |
| AncillaryOffer | 必须绑定旅客、AttachmentScope、服务项版本、Eligibility、PriceQuote/RuleSnapshot、ValidityWindow；Quoted 后价格和规则不可改写；不可在过期或资格失效后被选择。 | QuoteAncillaryOffer、ValidateAncillarySelection、ExpireAncillaryOffer、RequoteAncillaryOffer | AncillaryOfferQuoted、AncillarySelectionValidated、AncillaryOfferExpired、AncillaryOfferRequoted |
| AncillaryOrderItem | 必须引用 JourneyOrderItem 或 JourneyOrder、traveler、Segment/Entitlement/Place 绑定对象、服务快照、价格快照；同一服务项按唯一性规则防重；取消、失败、退款必须保留原因和责任方。 | CreateAncillaryOrderItem、ConfirmAncillaryItem、MarkFulfillmentReady、RecordAncillaryFulfilled、CancelAncillaryItem、FailAncillaryItem、RequestAncillaryRefund、RecordAncillaryRefunded | AncillaryOrderItemCreated、AncillaryItemConfirmed、AncillaryFulfillmentReady、AncillaryFulfilled、AncillaryItemCancelled、AncillaryItemFailed、AncillaryRefundRequested、AncillaryRefunded |
| ServiceFulfillmentRecord | 每次核销或履约事实必须关联 AncillaryOrderItem、voucher/providerRef、时间、地点、执行方；重复核销必须幂等；失败需标注可补偿性。 | IssueServiceVoucher、RecordVoucherRedeemed、RecordConsignAccepted、RecordConsignDelivered、RecordSeatAssigned、RecordProviderFulfillmentFailed | ServiceVoucherIssued、ServiceVoucherRedeemed、ConsignAccepted、ConsignDelivered、SeatAssigned、AncillaryProviderFulfillmentFailed |

## 6. 状态机

Ancillary Service 只拥有附加服务目录、附加服务报价、附加服务订单项和服务履约记录的状态，不定义主订单、主票、支付或主 Segment Booking 状态。

### AncillaryCatalogItem 状态

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Draft | 运营或导入流程正在配置服务能力。 | Published、Suspended |
| Published | 可用于 Eligibility 和报价。 | Suspended、Superseded、Expired |
| Suspended | 因供应商、合规、库存或运营原因暂停销售。 | Published、Superseded |
| Superseded | 被新版本替代。 | 终态 |
| Expired | 超出销售或服务有效期。 | 终态 |

### AncillaryOffer 状态

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Drafting | 正在收集资格、价格和可用性输入。 | Quoted、Ineligible、Failed |
| Quoted | 可在有效期内被用户选择。 | Selected、Expired、Requoted、Ineligible |
| Selected | 已被用于创建或更新 `AncillaryOrderItem`。 | Expired、Requoted |
| Expired | 报价超时。 | Requoted |
| Ineligible | 资格不满足或主行程状态不允许。 | Requoted |
| Failed | 价格、供应商或规则输入异常。 | 终态或重新报价 |

### AncillaryOrderItem 状态

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Selected | 用户已选择，尚未随订单确认。 | PendingConfirmation、Cancelled |
| PendingConfirmation | 等待供应商、座位、凭证或主票条件确认。 | Confirmed、Failed、Cancelled |
| Confirmed | 服务已确认，但尚未到履约窗口。 | FulfillmentReady、Cancelled、RefundPending |
| FulfillmentReady | 凭证、座位、托运或服务窗口已准备好。 | Fulfilled、Failed、Cancelled |
| Fulfilled | 服务已核销或完成。 | 终态，售后只能按规则补偿或争议处理 |
| Failed | 服务确认或履约失败。 | Cancelled、RefundPending、Compensated |
| Cancelled | 用户、系统、主行程变化或供应商取消。 | RefundPending、终态 |
| RefundPending | 已形成退款或费用保留决策，等待 Payment 执行。 | Refunded、Compensated |
| Refunded | 附加服务资金处理完成。 | 终态 |
| Compensated | 通过替代服务、优惠券或人工补偿闭环。 | 终态 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| CreateCatalogItem | AncillaryCatalogItem | AncillaryCatalogItemCreated | serviceType + supplierId + catalogBatchId |
| PublishCatalogItem | AncillaryCatalogItem | AncillaryCatalogItemPublished | catalogItemId + version + approvalRef |
| SuspendCatalogItem | AncillaryCatalogItem | AncillaryCatalogItemSuspended | catalogItemId + reasonCode + operatorOrEventId |
| QuoteAncillaryOffer | AncillaryOffer | AncillaryOfferQuoted | journeyOrOfferId + travelerSetHash + serviceType + requestId |
| ValidateAncillarySelection | AncillaryOffer | AncillarySelectionValidated | ancillaryOfferId + offerVersion + clientRequestId |
| ExpireAncillaryOffer | AncillaryOffer | AncillaryOfferExpired | ancillaryOfferId + expiryJobId |
| CreateAncillaryOrderItem | AncillaryOrderItem | AncillaryOrderItemCreated | journeyOrderId + ancillaryOfferId + clientRequestId |
| ConfirmAncillaryItem | AncillaryOrderItem | AncillaryItemConfirmed | ancillaryOrderItemId + confirmationAttemptId |
| MarkFulfillmentReady | AncillaryOrderItem | AncillaryFulfillmentReady | ancillaryOrderItemId + entitlementOrVoucherVersion |
| RecordAncillaryFulfilled | AncillaryOrderItem | AncillaryFulfilled | ancillaryOrderItemId + fulfillmentEventId |
| CancelAncillaryItem | AncillaryOrderItem | AncillaryItemCancelled | ancillaryOrderItemId + cancelReason + sourceEventId |
| FailAncillaryItem | AncillaryOrderItem | AncillaryItemFailed | ancillaryOrderItemId + failureCode + sourceEventId |
| RequestAncillaryRefund | AncillaryOrderItem | AncillaryRefundRequested | ancillaryOrderItemId + postSalesCaseId + assessmentVersion |
| RecordAncillaryRefunded | AncillaryOrderItem | AncillaryRefunded | ancillaryOrderItemId + paymentRefundId |
| IssueServiceVoucher | ServiceFulfillmentRecord | ServiceVoucherIssued | ancillaryOrderItemId + voucherPurpose + issueAttemptId |
| RecordVoucherRedeemed | ServiceFulfillmentRecord | ServiceVoucherRedeemed | voucherId + redeemProviderEventId |
| RecordSeatAssigned | ServiceFulfillmentRecord | SeatAssigned | ancillaryOrderItemId + seatMapVersion + providerSeatRef |
| RecordConsignAccepted | ServiceFulfillmentRecord | ConsignAccepted | ancillaryOrderItemId + providerConsignRef |
| RecordConsignDelivered | ServiceFulfillmentRecord | ConsignDelivered | ancillaryOrderItemId + deliveryEventId |

所有本域事件必须通过 Outbox 发布；消费 Journey Order、Booking、Entitlement、Fulfillment、Post Sales、Provider Integration 事件必须通过 Inbox 去重。

## 8. 策略和 Saga 参与点

### 售前和 bundle 策略

- 主 Offer 页面请求附加服务时，本域根据 Itinerary、Segment、Traveler、Channel、主 Offer 风险披露和供应商能力执行 `Eligibility`。
- `AncillaryOffer` 可独立存在，也可作为 Offer Management 的 OfferItem 候选；如果进入主 Offer，Offer Management 冻结组合展示，本域保留附加服务资格和服务规则快照。
- `Bundle` 必须明确组合价来源、失败拆分、可退改拆分和服务之间的依赖。例如餐食失败不应取消主票，选座失败可降级为随机座位或退款。
- 附加服务有效期取供应商可用性、价格 TTL、服务截止购买时间和主 Offer 有效期的最短约束。

### 下单确认 Saga

- Journey Order 创建后，本域按 `AncillaryOffer` 创建 `AncillaryOrderItem`，但不代表服务已履约。
- Booking Orchestration 在主 Segment 预订成功后触发附加服务确认；若主 Segment 失败，本域取消依赖该 Segment 的附加服务并给出退款或无需扣款结果。
- Payment 成功不自动使保险、餐食、行李或选座生效；是否生效取决于主票确认、供应商确认、凭证签发和服务规则。
- 如果附加服务确认失败，按服务级策略执行重试、替代、降级、退款或人工介入，不反向改写主订单历史。

### 履约和跨 modal 差异

- 火车：Meal、Insurance、部分 Consign 可绑定车次/区间；SeatSelection 可能只表达偏好，真实座位以出票或检票系统为准。
- 飞机：Baggage、SeatSelection、Meal 与值机、PNR、票号、登机牌强相关，需等待 Provider Integration 和 Entitlement 归一化后确认。
- 大巴：SeatSelection 和行李通常规则较简单，但上车点、车型和运营方变更会影响履约。
- 轮船：Baggage、Consign、舱房/车辆甲板服务需区分旅客、车辆和舱位绑定。
- 网约车/接送：附加服务更接近 Dispatch 任务，报价可能是 EstimatedOnly，履约失败需按司机取消、等待费、地址错误等原因补偿。

### 售后和异常联动

- 主行程取消或改签时，Post Sales 请求本域评估每个 `AncillaryOrderItem`：随主票自动退、可保留、需重订、不可退、仅人工处理。
- Disruption Recovery 提供平台责任或供应商责任的 waiver policy 时，本域调用 Fare & Pricing 评估费用豁免，但不直接退款。
- 已 Fulfilled 的服务通常不随主票退款，但可因供应商失败、服务未提供、保障承诺或客服裁决进入补偿。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| AncillaryCatalogView | AncillaryCatalogItemCreated、Published、Suspended、Superseded | 运营后台、Offer Management、客服。 |
| AncillaryEligibilityView | AncillaryOfferQuoted、AncillarySelectionValidated、AncillaryOfferExpired | 前端加购页、Offer Management、Journey Order。 |
| AncillaryOfferDetail | AncillaryOfferQuoted、AncillaryOfferRequoted、AncillaryOfferExpired | 加购页、订单确认页、客服。 |
| AncillaryOrderItemView | AncillaryOrderItemCreated、Confirmed、Cancelled、Failed、Refunded | Journey Order 详情、Post Sales、Customer Service。 |
| AncillaryFulfillmentTimeline | ServiceVoucherIssued、ServiceVoucherRedeemed、ConsignAccepted、ConsignDelivered、SeatAssigned、AncillaryFulfilled | Fulfillment、客服、用户行程页。 |
| AncillaryRefundabilityView | AncillaryItemCancelled、AncillaryRefundRequested、AncillaryRefunded、AncillaryFulfilled | Post Sales、Payment、客服。 |
| BundleComponentView | AncillaryOfferQuoted、AncillaryOrderItemCreated、AncillaryItemFailed、AncillaryRefundRequested | Offer Management、Post Sales、Finance Settlement。 |
| AncillaryFailureDashboard | AncillaryItemFailed、AncillaryProviderFulfillmentFailed、provider timeout events | 运营、Reporting、供应商管理。 |
| AncillaryRevenueAndAttachRate | AncillaryOfferQuoted、AncillaryOrderItemCreated、Confirmed、Fulfilled、Refunded | Reporting、Finance Settlement、产品运营。 |

读模型允许冗余车次、航班、站点、机场、港口、座位、餐食名称、保险名称和供应商展示名；写入和状态推进必须回到聚合命令。

## 10. 外部系统和防腐层

Ancillary Service 不直接暴露或依赖供应商原始 API。所有外部服务先由 Provider Integration 或特化履约能力转换为平台语言：

- 保险公司：原始险种、保单号、投保失败原因、退保规则映射为 `Insurance`、policyRef、Eligibility、confirmation result 和 refundability。
- 餐食供应商：菜单、库存、配送车次/航班、截单时间映射为 `Meal` catalog、service slot 和 fulfillment event。
- 行李/托运系统：额度、重量、件数、标签号、提取状态映射为 `Baggage`、`Consign`、providerConsignRef 和履约状态。
- 座位系统：SeatMap、座位号、偏好、锁座失败映射为 `SeatSelection` 和 SeatAssigned；真实主座席库存仍归 Capacity/Entitlement。
- 接送和贵宾室：外部预约号、核销码、司机/车辆、门店容量映射为 ServiceVoucher、FulfillmentWindow 和 provider confirmation。
- 遗留服务：`ts-assurance-service`、`ts-food-service`、`ts-consign-service` 通过 LegacyAncillaryACL 接入，输出统一 `AncillaryOffer` 和 `AncillaryOrderItem` 事件，避免 preserve 下单副作用继续扩散。

防腐层必须做错误码映射、幂等键转换、超时重试、供应商状态归一化和审计留痕；供应商原始报文只可作为 providerSnapshotRef 或审计附件，不进入核心聚合不变量。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-assurance-service` | 迁移为 `Insurance` 的 Eligibility、AncillaryOffer、AncillaryOrderItem 和保单确认协作；不再由 preserve 直接创建保险副作用。 |
| `ts-food-service` | 迁移为 `Meal` catalog、截单时间、餐食确认和履约核销；价格输入交给 Fare & Pricing。 |
| `ts-consign-service` | 迁移为 `Consign` / `Baggage` 服务项、托运受理、交付和退款协作；供应商状态经 ACL 归一化。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 下单时不得直接调用保险、餐食、托运服务生成副作用；改为使用 `AncillaryOffer` 和 `AncillaryOrderItem`，随 Booking Saga 确认或补偿。 |
| `ts-order-service`, `ts-order-other-service` | 订单保留附加服务订单项摘要和状态投影，不拥有附加服务履约状态机。 |
| `ts-price-service` | 附加服务价格、bundle 拆分、退改费迁入 Fare & Pricing；本域只消费 PriceQuote 和 FeeAssessment。 |
| `ts-cancel-service`, `ts-rebook-service` | 主票退改必须调用 Ancillary Service 评估附加服务影响，再由 Post Sales 汇总执行。 |
| `ts-payment-service`, `ts-inside-payment-service` | 支付只处理订单或售后决策中的金额；不直接判断附加服务是否可退。 |
| `ts-seat-service` | 选座相关能力拆分：座位可用性和 SeatMap 归 Capacity/Provider ACL，`SeatSelection` 商品、资格、订单项和履约协作归本域。 |
| `ts-travel-service`, `ts-travel2-service`, `ts-travel-plan-service` | 查询和方案结果可提供附加服务候选输入，但不直接销售附加服务。 |
| `ts-notification-service` | 消费附加服务确认、失败、核销、退款事件发送提醒。 |
| `ts-admin-*` | 附加服务目录、供应商能力、截单规则、bundle 配置迁移为受审计的 Catalog 发布和暂停命令。 |
| `ts-reports-service` 或报表能力 | 接入附加服务 attach rate、履约失败率、退款率、供应商绩效事件。 |

迁移顺序建议：先将保险、餐食、托运从 preserve 副作用中抽离为独立 `AncillaryOrderItem`；再接入 Offer/PriceQuote；最后补齐履约事件、bundle 和主票售后联动。

## 12. 验收标准

- Ancillary Service 的聚合所有权明确：拥有 `AncillaryCatalogItem`、`AncillaryOffer`、`AncillaryOrderItem`、`ServiceFulfillmentRecord`，不拥有主 `JourneyOrder`、主票 `Entitlement`、`PaymentIntent`、FareRule、Provider 原始 API 或主 Segment Booking。
- `Insurance`、`Meal`、`Baggage`、`Consign`、`SeatSelection`、接送、贵宾室、快速通道和 `Bundle` 的资格、绑定范围、履约和售后差异已覆盖。
- 与 Offer Management 边界明确：本域提供附加服务资格和报价候选，Offer Management 负责主 Offer 组合、展示和有效期管理。
- 与 Fare & Pricing 边界明确：本域不拥有价格规则，只消费 `PriceQuote`、`RuleSnapshot`、`FeeAssessment` 和 bundle 拆分结果。
- 与 Journey Order 边界明确：订单保存 `AncillaryOrderItem` 摘要和入口，本域推进附加服务确认、履约、失败、取消和退款协作。
- 与 Payment 边界明确：本域不扣款不退款，只发布应收、应退、保留和补偿依据。
- 与 Entitlement/Fulfillment 边界明确：本域可请求服务凭证和记录核销事实，但不签发或作废主票凭证。
- 与 Provider Integration 边界明确：供应商目录、确认、核销和失败原因必须经 ACL 归一化，不污染领域模型。
- 主行程绑定、bundle、可退改、失败补偿、跨 modal 差异、售后联动均有明确策略和事件。
