# Fare & Pricing Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Fare & Pricing |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-fare-pricing |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/aggregate-model.md`, `docs/02-domains/offer-management.md`, `docs/02-domains/post-sales.md`, `docs/02-domains/payment.md` |

## 1. 领域目标

Fare & Pricing 负责把供应商、产品、旅客、渠道、时间、售前/售后场景和合同规则转换为可解释的价格与规则快照。它解决的是“什么规则适用、价格如何组成、税费和折扣如何计算、退改费和差价如何解释”的问题，而不是“用户最终买了什么、钱是否到账、库存是否可用”。

本领域的核心目标：

1. 管理版本化 `FareRule`：基础票价、分段价、联程价、票价族、儿童/老人/会员/企业协议价、动态价、渠道价、供应商罚金、退改规则。
2. 生成售前 `FareQuote` / `PriceQuote`：包含 base fare、`Tax`、平台服务费、供应商服务费、`Discount`、总价、币种、价格解释、有效期和规则版本。
3. 生成售后 `FeeAssessment` / `AdjustmentQuote`：计算 `RefundFee`、`ChangeFee`、差价、税费返还、服务费保留或减免、供应商罚金、应退/应补金额。
4. 为 `Offer`、`JourneyOrder`、`PostSalesCase` 提供不可变价格快照和规则快照，使历史承诺可追溯、可审计、可客服解释。
5. 支持 General Travel 的多 modal 和跨供应商组合价：火车、飞机、大巴、轮船、网约车、附加服务可以各自有规则，但对下游暴露统一价格语言。

Fare & Pricing 是支撑域，但边界必须清晰：它是价格和规则计算权威，不拥有 Offer 生命周期、订单生命周期、资金状态、库存可售性或售后 Case 执行。

## 2. 边界

### In Scope

- 维护和版本化 `FareRuleSet`、`FareRule`、税费规则、服务费规则、折扣规则、企业协议规则、会员价规则、儿童/老人/特殊人群资格规则。
- 根据 Itinerary、Segment、traveler set、channel、sales window、supplier/product rule draft 生成 `FareQuote` / `PriceQuote`。
- 计算分段价、联程价、跨供应商组合价、跨 modal 组合价和 Ancillary 价格项的组合汇总。
- 计算 `Tax`、平台服务费、供应商服务费、燃油/港建/保险类费用、动态价格调整、汇率与舍入。
- 计算 `Discount`：促销、会员、企业协议、儿童/老人、渠道、优惠券抵扣前的规则价减免。
- 生成可解释价格：命中规则、金额明细、适用资格、排除原因、公式摘要、展示文案码。
- 管理报价有效期和价格保证级别：FixedUntilExpiry、EstimatedOnly、ProviderFinalConfirmRequired。
- 为 Post Sales 计算 `RefundFee`、`ChangeFee`、差价、税费返还、服务费保留、免费退改豁免和供应商罚金。
- 发布价格、规则和费用评估事件，支持 Offer 失效、re-quote、客服审计和报表分析。

### Out of Scope

- 不组合或展示 `Offer`，不管理 Offer Accepted/Expired/Superseded 生命周期；这属于 Offer Management。
- 不创建或修改 `JourneyOrder`，订单只保存购买时价格承诺和快照引用。
- 不创建 `PaymentIntent`、不扣款、不退款、不处理渠道回调；Payment 只执行资金。
- 不拥有 `PostSalesCase` 状态机；Post Sales 调用本域做规则和费用计算，并冻结执行决策。
- 不判断库存可售、不锁座、不释放库存；Capacity & Availability 提供可售性和库存版本。
- 不维护供应商、承运商、合同原始底稿和产品目录；Supplier Catalog 提供规则底稿和供应商能力。
- 不直接调用外部供应商原始报价/退改接口；Provider Integration 或 Supplier Catalog ACL 先转换为平台规则输入。
- 不负责路线规划、Service Plan、Entitlement、Fulfillment、Notification、Finance Settlement。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| FareRuleSet | 一组可发布、可生效、可废止的价格与规则集合。 | 按 supplier、product、mode、channel、sales window、version 管理。 |
| FareRule | 单条票价或费用规则。 | 可表达 base fare、分段价、联程价、退改费、税费、折扣资格。 |
| FareQuote | 针对一个 Itinerary 或 Segment 的售前报价结果。 | Offer 保存其快照，不重新计算。 |
| PriceQuote | 更通用的价格计算结果，可覆盖主票、附加服务、组合包和售后差价。 | 与 FareQuote 可在实现中统一。 |
| FareBreakdown | 价格组成明细。 | baseFare、Tax、fee、Discount、total。 |
| Tax | 税费或政府/场站/监管类费用。 | 需标注是否可退、是否随票价变化。 |
| Discount | 规则化减免。 | 不等同于 Payment 优惠渠道资金动作。 |
| ServiceFee | 平台或供应商服务费。 | 需标注可退、不可退或按比例退。 |
| RefundFee | 退票/取消时收取或保留的费用。 | 售后计算结果，不由 Payment 决定。 |
| ChangeFee | 改签/改程/升降级手续费。 | 可与差价同时存在。 |
| FareDifference | 新旧 PriceQuote 的可解释差额。 | 可为应补、应退或零差价。 |
| RuleSnapshot | 报价或售后评估时适用规则的不可变快照。 | 记录 ruleVersion、命中条件、解释码。 |
| AdjustmentQuote | 售后调整报价。 | 包含 RefundFee、ChangeFee、FareDifference、应退/应补金额和有效期。 |
| PriceExplanation | 面向用户、客服和审计的价格解释结构。 | 文案码 + 参数，不保存不可控长文本。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Supplier Catalog | supplier/product rule draft、contract tariff、fare family、provider fee policy、refund/change policy draft | 价格和规则底稿来源；本域版本化并发布为平台 FareRuleSet。 |
| Admin & Audit | PublishFareRuleSet、SuspendFareRuleSet、manual override approval | 高风险价格和规则变更必须审计。 |
| Trip Planning / Service Plan | Itinerary、SegmentRef、mode、serviceDate、route、distance、seat/cabin class | 售前报价需要路线、班次、席别和分段信息。 |
| Traveler Profile | traveler age、qualification、member level、enterprise affiliation、document category | 儿童/老人/会员/企业协议等资格影响价格和规则。 |
| Account / Channel | channelId、sales market、currency、locale、campaign context | 渠道价、币种、展示解释和销售限制。 |
| Capacity & Availability | availability class、inventory version、dynamic pricing signal | 动态价或余量分层可能影响报价；但库存权威不在本域。 |
| Provider Integration | normalized provider fare/tax/penalty snapshot | 外部供应商价税费和罚金需经 ACL 归一化。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Offer Management | FareQuote、RuleSnapshot、FareBreakdown、PriceExplanation、quoteExpiresAt、PriceGuaranteeLevel | Offer 冻结可交易价格和规则快照。 |
| Journey Order | PriceSnapshotRef、purchase fare commitment、fare explanation summary | 订单保存购买时价格承诺，不重新定价。 |
| Post Sales | EvaluateRefundRule、EvaluateChangeRule、FeeAssessment、AdjustmentQuote、waiver result | 售后 Case 需要退改费、差价和可解释规则结果。 |
| Payment | payable amount only through JourneyOrder/PostSales decisions | Payment 不直接调用本域；资金金额来自订单或售后决策。 |
| Customer Service | FareQuoteDetail、RuleExplanation、FeeAssessmentDetail | 解释价格、退改费、差价、规则版本和争议。 |
| Reporting / Finance Settlement | price component events、fee retained、tax refundable flags | 统计收入、折扣、费用保留、税费处理和规则效果。 |
| Notification | price changed / quote expiring signal via Offer or PostSales | 通知不直接决策，只消费事件或上游转发。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| FareRuleSet | 每个 rule set 必须有 supplier/mode/product scope、version、effective window、status；Published 后规则内容不可原地修改，只能新版本替换；同一 scope 的有效规则不能冲突；税费、折扣、服务费、退改费规则必须可解释。 | CreateFareRuleSet、AddFareRule、ValidateFareRuleSet、PublishFareRuleSet、SuspendFareRuleSet、SupersedeFareRuleSet | FareRuleSetCreated、FareRuleAdded、FareRuleSetValidated、FareRuleSetPublished、FareRuleSetSuspended、FareRuleSetSuperseded |
| FareQuote | 必须绑定 quote input hash、ruleVersion、traveler set、channel、currency、validity window；total 必须等于 baseFare + Tax + fees - Discount；Quoted 后不可改写金额；过期或被替代后不可用于新 Offer。 | CreateFareQuote、CalculateFareQuote、AcceptFareQuoteForOffer、ExpireFareQuote、SupersedeFareQuote | FareQuoteDrafted、FareQuoteCalculated、FareQuoteAcceptedForOffer、FareQuoteExpired、FareQuoteSuperseded |
| FeeAssessment | 必须引用原 PriceSnapshot 或 Entitlement/Order item；必须记录评估时刻、售后类型、ruleVersion、FeeBreakdown；同一 case + purpose + input version 幂等；应退/应补金额不能违反资金守恒。 | AssessRefundFee、AssessChangeFee、QuoteAdjustment、ExpireAdjustmentQuote、ReviseAssessmentByWaiver | RefundFeeAssessed、ChangeFeeAssessed、AdjustmentQuoteCreated、AdjustmentQuoteExpired、FeeAssessmentRevised |

## 6. 状态机

Fare & Pricing 只拥有 `FareRuleSet`、`FareQuote`、`AdjustmentQuote` / `FeeAssessment` 的状态机。

### FareRuleSet 状态

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Draft | 管理员或导入流程正在编辑规则。 | Validated、Suspended |
| Validated | 冲突检查、金额检查和解释检查通过，等待发布。 | Published、Draft、Suspended |
| Published | 在 effective window 内可被报价命中。 | Superseded、Suspended、Expired |
| Superseded | 被新版本替代，不再用于新报价。 | 终态 |
| Suspended | 因错误、合规或供应商变更停止使用。 | Draft、终态归档 |
| Expired | 超出生效窗口自然失效。 | 终态 |

### FareQuote 状态

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Draft | 输入已接收，尚未完成规则匹配和金额计算。 | Quoted、Failed |
| Quoted | 报价完成，可在有效期内被 Offer Management 冻结。 | Accepted、Expired、Superseded |
| Accepted | 已被某个 Offer 引用为价格快照。 | Expired、Superseded |
| Expired | 超过报价有效期。 | 终态；需重新 quote |
| Superseded | 由于规则或价格变化被新报价替代。 | 终态 |
| Failed | 无适用规则、资格不满足或计算冲突。 | 终态；可用新输入重试 |

### AdjustmentQuote 状态

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Draft | 售后评估输入已接收。 | Quoted、Failed |
| Quoted | 已形成退改费、差价、应退/应补金额。 | Accepted、Expired、Superseded |
| Accepted | 已被 PostSalesCase 冻结用于执行决策。 | Superseded |
| Expired | 用户未在有效期内确认，或目标方案/规则变化。 | 终态 |
| Superseded | 被新的评估或人工豁免版本替代。 | 终态 |
| Failed | 规则禁止、输入冲突或供应商罚金不可确定。 | 终态或转 Post Sales 人工流程 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| CreateFareRuleSet | FareRuleSet | FareRuleSetCreated | supplierId + productCode + mode + draftBatchId |
| AddFareRule | FareRuleSet | FareRuleAdded | ruleSetId + ruleCode + ruleVersion |
| ValidateFareRuleSet | FareRuleSet | FareRuleSetValidated | ruleSetId + validationRunId |
| PublishFareRuleSet | FareRuleSet | FareRuleSetPublished | ruleSetId + approvalRef |
| SupersedeFareRuleSet | FareRuleSet | FareRuleSetSuperseded | oldRuleSetId + newRuleSetId |
| CalculateFareQuote | FareQuote | FareQuoteCalculated | quoteRequestId + inputHash |
| AcceptFareQuoteForOffer | FareQuote | FareQuoteAcceptedForOffer | fareQuoteId + offerId + offerVersion |
| ExpireFareQuote | FareQuote | FareQuoteExpired | fareQuoteId + expiryJobId |
| SupersedeFareQuote | FareQuote | FareQuoteSuperseded | fareQuoteId + replacementQuoteId |
| AssessRefundFee | FeeAssessment | RefundFeeAssessed | postSalesCaseId + scopeHash + ruleVersion |
| AssessChangeFee | FeeAssessment | ChangeFeeAssessed | postSalesCaseId + originalItemId + targetQuoteId |
| QuoteAdjustment | FeeAssessment | AdjustmentQuoteCreated | postSalesCaseId + adjustmentInputHash |
| ExpireAdjustmentQuote | FeeAssessment | AdjustmentQuoteExpired | adjustmentQuoteId + expiryJobId |
| ReviseAssessmentByWaiver | FeeAssessment | FeeAssessmentRevised | assessmentId + approvalRef + waiverVersion |

所有事件必须通过 Outbox 发布；消费供应商规则、库存动态信号、后台审批结果时必须通过 Inbox 幂等处理。

## 8. 策略和 Saga 参与点

### 售前报价策略

- Offer Management 发起报价时，本域按 Segment、traveler、channel、supplier、seat/cabin class、sales time 选择 FareRuleSet。
- 单 Segment 优先生成分段价；多 Segment 根据规则选择分段相加、联程价、跨供应商组合价或 mode-specific bundle 价。
- 多 modal 场景允许各段使用不同 rule set，但输出统一 `FareBreakdown` 和组合 `PriceExplanation`。
- 动态价只消费 Capacity & Availability 或供应商的归一化 signal；本域不推断真实库存。
- 报价有效期取价格规则有效期、供应商价 TTL、动态价 TTL 和 Offer 策略中的最短约束。

### 售后费用策略

- Post Sales 请求退票时，本域根据原 PriceSnapshot、RuleSnapshot、当前时间、履约/票证摘要和 waiver policy 计算 `RefundFee`、税费返还和应退金额。
- Post Sales 请求改签时，本域同时计算原票 `ChangeFee`、目标 `PriceQuote`、`FareDifference`、应补或应退金额。
- 保障联乘、供应商取消、平台责任或 Disruption Recovery 可带入 waiver policy；本域只计算费用豁免结果，不决定 Case 执行。
- 非保障自助中转按各 Segment 原规则独立计算，组合展示由 Post Sales / Offer Management 冻结。

### 跨域触发

- `FareRuleSetPublished` 或 `FareRuleSetSuperseded` 可触发 Offer Management 使未接受 Offer re-quote 或失效。
- `FareQuoteExpired` 不取消订单；已创建 `JourneyOrder` 使用购买时价格承诺。
- `AdjustmentQuoteExpired` 只影响 PostSalesCase 的用户确认或重新评估，不触发 Payment 自动退款。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| FareRuleCatalogView | FareRuleSetCreated、FareRuleAdded、FareRuleSetPublished、FareRuleSetSuperseded | Admin & Audit、运营、客服。 |
| FareQuoteDetailView | FareQuoteCalculated、FareQuoteAcceptedForOffer、FareQuoteExpired | Offer Management、客服、价格解释页。 |
| PriceBreakdownView | FareQuoteCalculated、AdjustmentQuoteCreated、FeeAssessmentRevised | 前端、Journey Order、Post Sales、Customer Service。 |
| RuleExplanationView | FareRuleSetPublished、FareQuoteCalculated、RefundFeeAssessed、ChangeFeeAssessed | 客服解释、争议处理、合规审计。 |
| AdjustmentQuoteView | RefundFeeAssessed、ChangeFeeAssessed、AdjustmentQuoteCreated、AdjustmentQuoteExpired | Post Sales 用户确认页、客服。 |
| PricingChangeFeed | FareRuleSetPublished、FareRuleSetSuperseded、FareQuoteSuperseded | Offer Management、Reporting、运营监控。 |
| DiscountEffectivenessView | FareQuoteCalculated、Discount applied events | Reporting、市场运营、企业客户运营。 |
| TaxAndFeeAuditView | FareQuoteCalculated、RefundFeeAssessed、ChangeFeeAssessed | Finance Settlement、审计、合规。 |

读模型可以冗余车次、站点、供应商展示名、旅客类别和价格文案；规则判断必须回到聚合命令或已冻结快照。

## 10. 外部系统和防腐层

### Supplier Catalog ACL

Supplier Catalog 提供供应商、产品、合同、票价族和规则底稿。本域通过 ACL 将底稿转换为平台 `FareRuleSet`：

- 外部 fare family、booking class、seat class、cabin class 映射为平台 fare category。
- 供应商退改罚金、免费窗口、no-show 条款映射为 RefundFee / ChangeFee 规则。
- 供应商合同价、企业协议价、渠道价映射为可版本化 FareRule。
- 无法解释或冲突的底稿不得发布为 Published rule set。

### Provider Pricing ACL

- Provider Integration 返回的实时价、税费、燃油费、动态价、供应商罚金必须先归一化为 provider price snapshot。
- 原始供应商状态码、报文、PNR、外部订单号不进入 FareQuote 聚合，只保存 providerSnapshotRef 和摘要。
- 跨供应商组合价不得假设供应商之间可互相补偿；组合规则必须显式标注责任和可退改拆分方式。

### Legacy Pricing ACL

- 当前散落在 basic、price、cancel、rebook、preserve 中的价格和退改逻辑需通过 LegacyPricingACL 接入，统一转换为 FareRuleSet、FareQuote 和 FeeAssessment。
- 旧系统默认价格或硬编码退票比例只能作为迁移输入，发布前必须补齐版本、适用范围、解释码和审批记录。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-price-service` | 迁移为 FareRuleSet 和 FareQuote 的核心来源；移除默认回退价格，增加版本、有效期、解释和 Outbox。 |
| `ts-basic-service` | 当前聚合 station/train/route/price；价格部分迁入 Fare & Pricing，基础路网和车次信息留给 Place/Service Plan。 |
| `ts-travel-service`, `ts-travel2-service` | 查询返回中的票价不得直接作为交易承诺；改为请求 FareQuote，并携带 SegmentRef 与席别。 |
| `ts-travel-plan-service`, `ts-route-plan-service` | 换乘和推荐方案只输出 Itinerary；联程价、分段汇总价和跨供应商组合价由本域计算。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 下单前不得散算价格；必须使用 Offer 冻结的 FareQuote，订单保存 PriceSnapshotRef。 |
| `ts-order-service`, `ts-order-other-service` | 订单金额来自购买时快照；历史订单不因 FareRuleSet 新版本自动改价。 |
| `ts-cancel-service` | 退票比例、手续费、税费返还迁移到 AssessRefundFee；取消服务只消费 Post Sales 决策。 |
| `ts-rebook-service` | 改签差价和 ChangeFee 迁移到 AssessChangeFee / QuoteAdjustment；不再删除订单后重新散算。 |
| `ts-inside-payment-service`, `ts-payment-service` | 不再参与规则或金额计算，只执行 JourneyOrder/PostSalesCase 提供的金额。 |
| `ts-seat-service` | 余票和席别可作为动态价输入信号，但不可在 seat 服务内决定票价。 |
| `ts-security-service` | 风控或实名限制可影响报价资格，但价格规则归本域，拒绝/挑战归 Risk & Compliance。 |
| `ts-admin-*` | 价格和规则后台操作必须迁移为受审计的 FareRuleSet 发布、暂停、替换命令。 |
| `ts-assurance-service`, `ts-food-service`, `ts-consign-service` | 附加服务价格可作为 PriceQuote 扩展；服务资格和履约仍归各商品域。 |

迁移优先级：先将 `ts-price-service` 和退改硬编码规则收敛为版本化 rule set；再让 Offer 使用 FareQuote；最后让 Post Sales 使用 FeeAssessment 取代 cancel/rebook 内部算法。

## 12. 验收标准

- Fare & Pricing 的聚合所有权明确：拥有 `FareRuleSet`、`FareQuote`、`FeeAssessment` / `AdjustmentQuote`，不拥有 `Offer`、`JourneyOrder`、`PaymentIntent`、`PostSalesCase`、CapacityHold 或供应商原始目录。
- 票价规则、税费、折扣、服务费、退改费、差价、价格快照、报价有效期和价格解释均有明确模型和契约。
- 与 Offer Management 边界明确：本域产出价格和规则快照，Offer 负责组合、展示、选择和生命周期。
- 与 Journey Order 边界明确：订单只保存购买时价格承诺，不重新计算历史价格。
- 与 Payment 边界明确：Payment 只执行资金，不判断价格、优惠、退改费或差价。
- 与 Post Sales 边界明确：Post Sales 拥有售后 Case，调用本域计算 `RefundFee`、`ChangeFee`、差价和应退/应补金额。
- 与 Capacity & Availability 边界明确：库存和可售性不归本域，动态价只消费归一化信号。
- 与 Supplier Catalog 边界明确：供应商/产品/合同底稿来自 Supplier Catalog，本域发布平台可执行 FareRuleSet。
- 多 modal、跨供应商组合价、分段价、联程价、儿童/老人/会员/企业协议、动态价和退改费差异均已覆盖。
