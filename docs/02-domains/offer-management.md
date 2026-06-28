# Offer Management Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Offer Management |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-offer-management |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/domain-glossary.md`, `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/aggregate-model.md`, `docs/01-ddd-high-level/state-machines.md`, `docs/01-ddd-high-level/event-storming.md`, `docs/01-ddd-high-level/consistency-and-saga.md`, `docs/01-ddd-high-level/order-inventory-payment-model.md`, `docs/01-ddd-high-level/general-travel-ddd.md` |

## 1. 领域目标

Offer Management 的目标是把用户可选择的 Itinerary 转化为可交易、可解释、带有效期的 Offer。它位于搜索规划和下单之间，解决三个核心问题：

1. 防止“查询价、下单价、支付价”漂移：Offer 冻结 FareQuote、RuleSnapshot、AvailabilitySnapshot、风险披露和有效期。
2. 把售前承诺与最终供应确认分开：Offer 表示可购买报价，不表示库存已锁、供应商已确认或票证已签发。
3. 为 Journey Order、Booking Orchestration、Post Sales 提供稳定快照引用：订单必须引用有效 Offer；失效后必须 re-quote。

Offer Management 是独立边界，因为它保护的是“用户接受报价时看到什么、平台承诺了什么、哪些风险已披露”的售前交易不变量。Trip Planning 负责生成 Itinerary；Fare & Pricing 负责价格和规则来源；Capacity & Availability 负责可售性判断；Booking Orchestration 才负责锁库存和供应确认。Offer Management 只把这些输入在某个时间点组合成 Offer 快照。

第一阶段 Train Ticket 范围中，Offer Management 主要覆盖火车直达和换乘方案的报价快照、席别/区间可售性快照、退改规则快照、有效期、失效和重新报价。未来 General Travel 范围中，它扩展到飞机、大巴、轮船、网约车、跨方式联乘、保障服务、Ancillary Service 和复杂风险披露。

## 2. 边界

### In Scope

- 创建、保存、查询 Offer 和 OfferItem。
- 绑定 Itinerary、Segment、Transfer、Connection Contract 的报价视图。
- 冻结 FareQuote、价格明细、税费、服务费、优惠和总价。
- 冻结 RuleSnapshot，包括退改、签转、资格、停售、证件、儿童/优惠规则等。
- 冻结 AvailabilitySnapshot，包括可售、余量区间、库存来源、可售置信度、供应商可用性版本。
- 管理 Offer validity window、倒计时、过期、主动失效和 re-quote。
- 记录 user-facing risk disclosure，例如非保障联乘、自助中转、价格可能变化、供应商最终确认风险、票规限制。
- 支持单 Segment、多 Segment、组合 Offer、改签 ChangeOffer、未来 Ancillary bundle Offer。
- 发布 OfferQuoted、OfferExpired、OfferRequoted、OfferUnavailable 等事件。
- 为 Journey Order 提供下单前的 ValidateOfferForOrder 能力。
- 为 Post Sales 的改签预览提供 QuoteChangeOffer 和差价快照。
- 为 Notification、客服、前端提供 OfferDetail、OfferStatusView 等读模型。

### Out of Scope

- 不生成路线、不排序方案、不决定 Trip Intent 是否可达；这些属于 Trip Planning。
- 不维护车站、路线、车次、航班、船班或司机供给；这些属于 Place & Network、Service Plan、Dispatch。
- 不锁库存、不释放库存、不确认 CapacityHold；这些属于 Capacity & Availability 和 Booking Orchestration。
- 不调用供应商执行最终 Reservation、出票、取消或改签；这些属于 Booking Orchestration、Post Sales 和 Provider Integration。
- 不创建 Journey Order，不推进订单状态，不汇总支付、出票、履约状态。
- 不执行支付、预授权、退款或补差价；这些属于 Payment。
- 不签发 Entitlement，不作废票证，不处理检票/登乘。
- 不执行售后规则，只保存报价时使用的 RuleSnapshot；售后判定属于 Post Sales。
- 不拥有风控最终决策，只消费 Risk & Compliance 的可售/限制结果并披露阻断或提醒。
- 不发送通知，只发布可被 Notification 消费的事件。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| Offer | 对一个 Itinerary 的价格、规则、可售性、风险和有效期快照。 | 下单必须引用有效 Offer；Offer 不等于 Reservation。 |
| OfferItem | Offer 中某个 Segment、Transfer protection、Ancillary Service 或费用项的报价组成。 | 第一阶段主要是火车 Segment 票价项；未来支持保险、行李、接送等。 |
| FareQuote | Fare & Pricing 返回的价格计算结果。 | Offer 保存快照和来源版本，不拥有定价规则。 |
| RuleSnapshot | 报价时适用规则的不可变快照。 | 包含退改规则、资格规则、停售限制、Connection Contract 摘要。 |
| AvailabilitySnapshot | 报价时可售性结果的不可变快照。 | 只表示当时可售，不表示库存已 Hold。 |
| ValidityWindow | Offer 可被接受的时间窗口。 | 过期后只能 re-quote。 |
| RiskDisclosure | 面向用户展示并留痕的风险说明。 | 包括自助中转、余票紧张、价格波动、供应商确认风险。 |
| QuoteBundle | 一个 Journey 下多个 OfferItem 的组合报价。 | 用于多段联乘和未来交通+附加服务打包。 |
| RequoteReason | 触发 re-quote 的原因。 | 如过期、价格变化、不可售、规则版本变化、用户改旅客。 |
| OfferAcceptanceToken | Journey Order 创建时用于证明用户接受某版本 Offer 的令牌或版本引用。 | 防止客户端用旧价格或篡改快照下单。 |
| PriceGuaranteeLevel | 报价承诺强度。 | 如 FixedUntilExpiry、EstimatedOnly、ProviderFinalConfirmRequired。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Trip Planning | Itinerary、Segment、Transfer、Journey candidate、ranking metadata | Offer 只对已生成的方案报价，不负责 route search。 |
| Fare & Pricing | FareQuote、Fare Rule、fee/tax/discount breakdown、pricing rule version | 价格和票规来源；Offer 冻结快照。 |
| Capacity & Availability | AvailabilitySnapshot、sellability result、inventory version、availability TTL | 判断报价时是否可售；不锁库存。 |
| Transfer Management | TransferRiskEvaluated、Connection Contract、minimum connection risk | 在 Offer 中披露保障联乘、自助中转和连接风险。 |
| Traveler Profile | TravelerRef、document type、age/qualification summary | 影响实名、儿童票、优惠票、跨境资质等报价资格。 |
| Risk & Compliance | RiskDecision、challenge/deny/allow、duplicate journey warning、restriction reason | 阻断或披露风险，不在 Offer 内实现风控策略。 |
| Ancillary Service | Ancillary quote candidate、service eligibility | 未来组合保险、行李、餐食、接送和站内服务。 |
| Provider Integration | provider price/availability version via Fare & Pricing or Capacity | 只消费已映射为平台语言的供应商快照；不直接依赖原始状态码。 |
| Account / Channel | channelId、sales policy、currency/locale | 影响渠道价、展示语言、币种和合规提示。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Journey Order | OfferQuoted、OfferExpired、ValidateOfferForOrder、OfferAcceptanceToken、PriceSnapshot | 订单必须基于有效 Offer 创建并保存快照引用。 |
| Booking Orchestration | AcceptedOfferView、OfferItemRef、SegmentRef、AvailabilitySnapshotRef、PriceGuaranteeLevel | 下单后编排用它请求 SegmentReservation，但不能把 Offer 当作 Hold。 |
| Payment | PriceSnapshot、payable amount、currency、payment expiry hint | Payment 创建 PaymentIntent 时使用订单保存的 Offer 金额，不直接重新定价。 |
| Post Sales | RuleSnapshotRef、original FareQuote、QuoteChangeOffer、ChangeOfferQuoted | 退改规则追溯和改签差价预览。 |
| Transfer Management | accepted risk disclosure、Connection Contract snapshot reference | 后续中转争议需要知道售前披露与承诺。 |
| Notification | OfferExpired、OfferRequoted、OfferUnavailable | 可提醒用户报价失效或需要重新确认。 |
| Customer Service | OfferDetailView、RiskDisclosure audit trail | 客服解释价格、票规、失效和风险提示。 |
| Reporting | OfferQuoted/Accepted/Expired metrics | 分析报价转化率、失效率、价格漂移和供应质量。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| Offer | 必须引用一个 Itinerary；至少包含一个 OfferItem；FareQuote、RuleSnapshot、AvailabilitySnapshot 在同一版本 Offer 内不可变；必须有 ValidityWindow；过期、Unavailable、Withdrawn 后不能被接受；总价等于 OfferItem、Fee、Tax、Discount 汇总；风险披露必须覆盖所有非默认 Connection Contract 和阻断型限制。 | QuoteOffer、ValidateOfferForOrder、ExpireOffer、MarkOfferUnavailable、RequoteOffer、WithdrawOffer、RecordOfferAccepted | OfferQuoted、OfferValidatedForOrder、OfferExpired、OfferUnavailable、OfferRequoted、OfferWithdrawn、OfferAccepted |
| ChangeOffer | 必须引用原 JourneyOrder/原 Entitlement/目标 Itinerary；必须包含旧新价格差、手续费、应补/应退金额；必须记录售后规则版本；过期后不能用于改签执行。 | QuoteChangeOffer、ValidateChangeOffer、ExpireChangeOffer、RecordChangeOfferAccepted | ChangeOfferQuoted、ChangeOfferValidated、ChangeOfferExpired、ChangeOfferAccepted |
| OfferDisclosure | 每条用户必须确认的风险说明必须绑定 OfferVersion；强提示必须有展示和确认记录；披露文本来自模板版本，不允许事后改写历史含义。 | AttachRiskDisclosure、RecordDisclosureShown、RecordDisclosureAccepted | OfferRiskDisclosed、OfferDisclosureShown、OfferDisclosureAccepted |

### 聚合内部结构建议

- Offer Root：offerId、offerVersion、itineraryId、account/channel、travelerRefs、status、validityWindow、totalPrice、currency、priceGuaranteeLevel、createdAt、expiresAt。
- OfferItem：offerItemId、segmentRef/ancillaryRef、mode、fareQuoteRef、fareBreakdown、availabilitySnapshotRef、ruleSnapshotRef、itemPrice、itemStatus。
- Snapshot References：pricingVersion、ruleVersion、availabilityVersion、transferRiskVersion、riskDecisionId。
- RiskDisclosure：disclosureId、severity、messageCode、templateVersion、relatedSegment/Transfer、mustAccept、shownAt、acceptedAt。

## 6. 状态机

Offer Management 拥有 Offer 和 ChangeOffer 的状态机，不定义 Journey Order、SegmentBooking、CapacityHold、PaymentIntent、Entitlement 或 PostSalesCase 的状态。

### Offer 状态

| 状态 | 含义 |
|---|---|
| Drafting | 正在收集 FareQuote、RuleSnapshot、AvailabilitySnapshot 和风险结果。 |
| Quoted | 已形成完整报价，可在有效期内被用户接受。 |
| Accepted | 已被 Journey Order 使用一次或多次，取决于幂等策略；快照仍不可变。 |
| Expired | 超过 ValidityWindow 或 TTL，不可继续下单。 |
| Unavailable | 关键 Segment、资格、规则或供应结果变化导致不可售。 |
| Requoted | 已基于原 Offer 生成新 Offer；原 Offer 不再被接受。 |
| Withdrawn | 运营、风控、供应商异常或合规原因主动撤回。 |

### Offer 状态转换

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| Drafting | OfferQuoted | Quoted |
| Drafting | QuoteFailed | Unavailable |
| Quoted | OfferAccepted | Accepted |
| Quoted | OfferExpired | Expired |
| Quoted | OfferUnavailable | Unavailable |
| Quoted | OfferWithdrawn | Withdrawn |
| Quoted | OfferRequoted | Requoted |
| Accepted | OfferExpired | Expired |
| Accepted | OfferRequoted | Requoted |
| Expired | RequoteOffer | Requoted |
| Unavailable | RequoteOffer | Requoted |

禁止转换：

- Expired/Unavailable/Withdrawn -> Accepted：必须先 RequoteOffer 生成新版本。
- Accepted -> Drafting：历史快照不可回退。
- Requoted -> Accepted：只能接受新 Offer，不接受已被替代的旧 Offer。

### ChangeOffer 状态

| 状态 | 含义 |
|---|---|
| Quoted | 改签或改程差价报价已生成。 |
| Accepted | 用户接受差价和规则提示。 |
| Expired | 改签报价失效。 |
| Unavailable | 目标方案不可售或原票规则不允许。 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| QuoteOffer | Offer | OfferQuoted | itineraryId + travelerSetHash + channelId + quoteRequestId |
| ValidateOfferForOrder | Offer | OfferValidatedForOrder | offerId + offerVersion + accountId + clientRequestId |
| RecordOfferAccepted | Offer | OfferAccepted | offerId + offerVersion + journeyOrderId |
| ExpireOffer | Offer | OfferExpired | offerId + offerVersion + expiryJobId |
| MarkOfferUnavailable | Offer | OfferUnavailable | offerId + offerVersion + sourceEventId |
| RequoteOffer | Offer | OfferRequoted | originalOfferId + requoteRequestId |
| WithdrawOffer | Offer | OfferWithdrawn | offerId + reasonCode + operatorOrEventId |
| AttachRiskDisclosure | OfferDisclosure | OfferRiskDisclosed | offerId + disclosureCode + templateVersion |
| RecordDisclosureShown | OfferDisclosure | OfferDisclosureShown | offerId + disclosureId + sessionId |
| RecordDisclosureAccepted | OfferDisclosure | OfferDisclosureAccepted | offerId + disclosureId + accountId |
| QuoteChangeOffer | ChangeOffer | ChangeOfferQuoted | postSalesCaseId + originalEntitlementId + targetItineraryId + requestId |
| ValidateChangeOffer | ChangeOffer | ChangeOfferValidated | changeOfferId + postSalesCaseId + clientRequestId |
| ExpireChangeOffer | ChangeOffer | ChangeOfferExpired | changeOfferId + expiryJobId |
| RecordChangeOfferAccepted | ChangeOffer | ChangeOfferAccepted | changeOfferId + postSalesCaseId |

所有事件必须通过本上下文 Outbox 发布；所有消费外部事件必须通过 Inbox 幂等处理。

## 8. 策略和 Saga 参与点

Offer Management 通常不拥有长事务 Saga，但参与多个 Saga 的前置校验和快照生成。

### 售前报价策略

1. 当 Trip Planning 产生 ItineraryProposed 后，QuoteOffer 拉取 FareQuote、AvailabilitySnapshot、Transfer risk、RiskDecision 和 Traveler qualification。
2. 如果任一必需 Segment 不可售，Offer 不进入 Quoted，返回不可售原因或替代 re-quote 建议。
3. 如果 Transfer Management 返回 Self Transfer 或 Platform Assisted，Offer 必须附带 RiskDisclosure。
4. 如果 Risk & Compliance 返回 challenge 或 deny，Offer 应标记为需要挑战或不可售；不能绕过风控直接报价。

### 下单 Saga 参与点

- Journey Order 在 CreateJourneyOrder 前调用 ValidateOfferForOrder。
- ValidateOfferForOrder 只确认 Offer 版本、有效期、金额、旅客集合、渠道、披露接受状态；不锁库存。
- OfferAccepted 事件可用于转化率分析和客服审计，但不代表订单创建成功。
- Booking Orchestration 后续如果发现库存或供应商拒绝，应推进 SegmentReservationFailed，而不是修改 Offer 历史。

### Re-quote 策略

触发 re-quote 的常见原因：

- ValidityWindow 到期。
- Fare & Pricing 发布价格或规则版本变化。
- Capacity & Availability 告知关键 Segment 不可售或 TTL 过期。
- Traveler Profile 变化，例如旅客、证件、优惠资格变化。
- Risk & Compliance 决策变化。
- Transfer Management 风险等级或 Connection Contract 变化。

RequoteOffer 必须生成新 offerId 或新 offerVersion，并保留 originalOfferId 链接；旧 Offer 不可被静默覆盖。

### 改签 Saga 参与点

- Post Sales 发起 RequestChange 后调用 QuoteChangeOffer。
- ChangeOffer 冻结目标方案价格、原票规则、手续费、差价和新旧规则版本。
- ChangeOfferAccepted 后由 Post Sales/Booking Orchestration 锁新库存、处理差价、作废旧 Entitlement、签发新 Entitlement。
- Offer Management 不执行旧票作废、新库存锁定或资金动作。

### 异常和联乘恢复参与点

- TransferAtRisk、ConnectionMissed 或 DisruptionPublished 后，Disruption Recovery 可请求 Offer Management 生成恢复方案报价或自助补救 Offer。
- 对 Self Transfer，Offer Management 只能展示可购买替代方案和风险说明，不能自动承诺免手续费或平台兜底。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| OfferDetail | OfferQuoted、OfferRiskDisclosed、OfferDisclosureShown、OfferDisclosureAccepted、OfferExpired、OfferUnavailable | 前端报价确认页、Journey Order、客服。 |
| OfferStatusView | OfferQuoted、OfferAccepted、OfferExpired、OfferUnavailable、OfferRequoted、OfferWithdrawn | 前端倒计时、订单创建校验、Notification。 |
| OfferPriceBreakdownView | OfferQuoted、ChangeOfferQuoted | 支付页、订单详情、客服价格解释。 |
| OfferRiskDisclosureView | OfferRiskDisclosed、OfferDisclosureAccepted | 前端风险提示、客服争议处理、Transfer Management。 |
| OfferConversionFunnel | OfferQuoted、OfferAccepted、JourneyOrderCreated、OfferExpired | Reporting、产品分析、供应质量分析。 |
| RequoteHistoryView | OfferRequoted、OfferExpired、OfferUnavailable | 客服、运营排查价格/库存变化。 |
| ChangeOfferPreview | ChangeOfferQuoted、ChangeOfferAccepted、ChangeOfferExpired | Post Sales 改签预览、用户确认页。 |
| OfferAuditTimeline | 所有 Offer Management 事件 | Customer Service、Admin & Audit、争议处理。 |

读模型允许冗余 Itinerary 摘要、Segment 展示名、站点名、价格明细和风险文案，但写入必须回到 Offer 聚合命令。

## 10. 外部系统和防腐层

Offer Management 原则上不直接对接外部供应商。供应商价格、库存、规则必须先通过 Provider Integration、Fare & Pricing、Capacity & Availability 映射为平台统一语言后再进入 Offer。

### 防腐要求

- 不在 Offer 聚合中保存供应商原始状态码、PNR、外部订单号或原始报文；只保存 providerSnapshotRef、pricingVersion、availabilityVersion 等引用。
- 对铁路场景，可保存 trainNo、seatClass、station interval 等平台 SegmentRef 展示字段，但不把 seat 分配当成 Offer 职责。
- 对航空场景，PNR、ticketNo、fare family 的原始差异由 Provider Integration 和 Fare & Pricing 处理；Offer 只保存 FareQuote 和 RuleSnapshot。
- 对网约车场景，Offer 可以是 EstimatedOnly 或 FixedUntilExpiry；司机派单和最终计费不属于 Offer。
- 对轮船和大巴，舱房、车辆甲板、上车点等必须先映射为 CapacityUnit/SegmentRef/RuleSnapshot。

### Outbox / Inbox

- Offer Management 发布事件必须使用 Outbox，与聚合状态同事务提交。
- 消费 FareChanged、AvailabilityChanged、RiskDecisionChanged、TransferRiskEvaluated 等外部事件必须走 Inbox 去重。
- 外部事件只能触发 MarkOfferUnavailable、ExpireOffer 或 RequoteOffer，不能改写已发布 Offer 的历史价格。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-preserve-service`, `ts-preserve-other-service` | 订票入口当前直接查车次、查余票、算价格、分配座位、创建订单。迁移后必须先接受 Offer，再创建 Journey Order；价格和规则不得在 preserve 内重新散算。 |
| `ts-travel-service`, `ts-travel2-service` | 当前查询车次详情和余票混在一起。迁移后它们提供或迁入 Service Plan/Trip Planning 输入，不能直接生成可交易报价。高铁/普通车分裂应变成 Segment 属性。 |
| `ts-travel-plan-service`, `ts-route-plan-service` | 当前生成换乘/推荐方案并补余票。迁移后只输出 Itinerary 候选和排序信息，由 Offer Management 统一报价和风险披露。 |
| `ts-basic-service` | 当前聚合 station/train/route/price 并计算价格。迁移后价格计算进入 Fare & Pricing；Offer 只消费 FareQuote。 |
| `ts-price-service` | 当前票价配置简单且有默认回退价格。迁移后需要版本化 FareQuote 和 RuleSnapshot，禁止默认价格静默进入 Offer。 |
| `ts-seat-service` | 当前从订单反推余票并随机分配座位。迁移后 Offer 只消费 AvailabilitySnapshot；真实 Hold 和座位确认进入 Capacity & Availability/Booking Orchestration。 |
| `ts-order-service`, `ts-order-other-service` | 当前订单保存价格和状态。迁移后 Journey Order 必须保存 OfferId、OfferVersion、PriceSnapshot 引用；不能自己重算报价。 |
| `ts-rebook-service` | 当前改签跨 travel/order/seat/payment 直接操作。迁移后改签预览先调用 QuoteChangeOffer，后续由 Post Sales 和 Booking Orchestration 执行。 |
| `ts-cancel-service` | 当前取消退款规则写死。迁移后取消/退票使用 Post Sales 规则；Offer 仅提供原始 RuleSnapshot 用于追溯。 |
| `ts-inside-payment-service`, `ts-payment-service` | 当前支付直接改订单。迁移后 Payment 只使用订单中的 PriceSnapshot 创建 PaymentIntent，不直接访问 Offer 重新计价。 |
| `ts-security-service` | 当前下单限制直接由 preserve 调用。迁移后 Risk & Compliance 决策可在 QuoteOffer/ValidateOfferForOrder 阶段消费，并进入风险披露或阻断。 |
| `ts-food-service`, `ts-assurance-service`, `ts-consign-service` | 当前作为 preserve 附加副作用。未来作为 Ancillary Service 产生可报价项，OfferItem 可纳入组合报价；第一阶段可先不纳入主 Offer。 |
| `ts-notification-service` | 当前通知不完整。迁移后消费 OfferExpired/OfferUnavailable 等事件进行提醒，不参与报价决策。 |
| `ts-admin-*` | 后台价格、车次、路线调整会影响 FareQuote/AvailabilitySnapshot 版本。迁移后必须通过 Admin & Audit 受控命令触发 Offer 失效或 re-quote，而不是直接改历史 Offer。 |
| `ts-wait-order-service` | 候补当前轮询 preserve。未来候补兑现不复用旧 Offer，需在 CapacityReleased 后重新 quote 或按候补规则生成专用 Offer。 |

## 12. 验收标准

- Offer Management 的聚合所有权明确：拥有 Offer、OfferItem、ChangeOffer、OfferDisclosure，不拥有 Journey Order、SegmentBooking、CapacityHold、PaymentIntent、Entitlement 或 PostSalesCase。
- Offer 必须冻结 FareQuote、RuleSnapshot、AvailabilitySnapshot、RiskDisclosure 和 ValidityWindow。
- Offer 失效后必须 re-quote；旧 Offer 不可被静默改价或继续下单。
- Offer 不负责 route search、inventory locking、final supplier reservation、payment execution、ticket issuing 或 post-sales rule execution。
- 上游 Trip Planning、Fare & Pricing、Capacity & Availability、Transfer Management、Risk & Compliance 的输入契约明确。
- 下游 Journey Order、Booking Orchestration、Post Sales、Payment、Notification、Customer Service 的发布契约明确。
- 命令、事件、幂等键和 Outbox/Inbox 要求明确。
- 状态机只覆盖 Offer Management 自己拥有的状态。
- 第一阶段 Train Ticket 范围和未来 General Travel 多交通方式扩展点均已说明。
- 当前服务迁移影响覆盖 preserve、travel、route-plan、basic、price、seat、order、rebook、cancel、payment、security、ancillary、notification、admin 和 wait-order。
- 跨域冲突和开放问题只记录在第 12 节。
