# 供应商防腐层与契约设计

Last updated: 2026-06-28

## 目的

General Travel 平台会接入铁路、航司、大巴公司、船司、网约车平台、支付渠道、保险公司、餐饮和行李服务商。每个供应商都有自己的对象、状态码、错误码和业务限制。如果这些外部语言直接进入核心域，系统会再次腐化。

这份文档定义 Provider Integration 防腐层的职责和通用契约：

1. 把供应商语言映射为平台统一语言。
2. 把供应商状态码映射为平台领域事件。
3. 处理幂等、重试、超时、查询最终状态和对账。
4. 隔离交通方式特有概念，避免污染 Journey Order。

## 防腐层边界

Provider Integration 负责：

1. 供应商认证、签名、限流、重试和熔断。
2. 外部请求和响应 DTO。
3. 供应商错误码到平台错误类型的映射。
4. 供应商确认号、PNR、票号、派单号、船票号等引用映射。
5. 查询供应商最终状态。
6. 发布平台领域事件。

Provider Integration 不负责：

1. 创建 Journey Order。
2. 决定用户是否可退改。
3. 计算平台手续费。
4. 直接修改 Payment 状态。
5. 直接修改核心域聚合字段。
6. 生成面向用户的最终业务文案。

## 平台标准供应商能力

| 能力 | 输入 | 输出 | 说明 |
|---|---|---|---|
| SearchAvailability | SegmentSearchRequest | ProviderAvailabilityResult | 可选。内部库存可不走供应商。 |
| QuoteProviderOffer | ProviderQuoteRequest | ProviderQuoteResult | 航空、网约车等价格变化快的方式需要。 |
| ReserveSegment | ProviderReservationRequest | ProviderReservationResult | 预留或确认一段服务。 |
| ConfirmReservation | ProviderConfirmRequest | ProviderConfirmResult | 某些供应商需要二阶段确认。 |
| CancelReservation | ProviderCancelRequest | ProviderCancelResult | 取消未出票或未履约预订。 |
| IssueCredential | ProviderIssueRequest | ProviderIssueResult | 外部出票、票号、登机牌或乘车码。 |
| VoidCredential | ProviderVoidRequest | ProviderVoidResult | 作废供应商凭证。 |
| ChangeReservation | ProviderChangeRequest | ProviderChangeResult | 改签、改期、改程、升舱等。 |
| QueryReservationStatus | ProviderStatusRequest | ProviderStatusResult | 超时、回调缺失或对账时使用。 |
| QueryDisruptions | ProviderDisruptionRequest | ProviderDisruptionResult | 拉取延误、取消、停运、封航等。 |
| ReconcileSettlement | ProviderSettlementRequest | ProviderSettlementResult | 供应商结算和佣金对账。 |

不是所有供应商都支持全部能力。适配器必须声明能力矩阵，核心编排根据能力选择流程。

## 标准请求头和元数据

| 字段 | 说明 |
|---|---|
| providerId | 供应商标识。 |
| providerType | Rail、Air、Coach、RideHailing、Ferry、Insurance、Payment 等。 |
| operation | Reserve、Cancel、Issue、Change、QueryStatus 等。 |
| idempotencyKey | 平台生成的幂等键。 |
| correlationId | 跨上下文追踪 ID。 |
| timeoutPolicy | 超时策略。 |
| retryPolicy | 重试策略。 |
| requestedAt | 请求时间。 |
| businessRef | JourneyOrderId、SegmentBookingId、PaymentIntentId 等引用。 |

## 平台标准结果

| 字段 | 说明 |
|---|---|
| outcome | Success、Pending、Rejected、Timeout、Unknown、Failed。 |
| providerReference | 供应商确认号、PNR、派单号、票号等。 |
| mappedStatus | 平台内部状态。 |
| rawStatus | 供应商原始状态，只在防腐层和审计中保存。 |
| priceDelta | 供应商返回的价格变化。 |
| ruleSnapshot | 供应商票规或售后规则快照。 |
| errorType | BusinessRejected、RetryableTechnicalError、NonRetryableTechnicalError、AmbiguousResult。 |
| errorMessage | 可供客服和日志使用的错误说明，不直接展示给用户。 |
| nextAction | Retry、QueryStatus、Compensate、ManualReview、Stop。 |

## 错误分类

| 错误类型 | 含义 | 默认处理 |
|---|---|---|
| BusinessRejected | 供应商明确拒绝，如无票、证件不符、规则不允许 | 不重试，返回业务失败或换方案。 |
| RetryableTechnicalError | 网络、限流、临时不可用 | 指数退避重试。 |
| NonRetryableTechnicalError | 参数错误、签名错误、权限错误 | 停止自动流程，报警或人工。 |
| AmbiguousResult | 超时、断连、供应商未明确结果 | 查询最终状态，禁止直接重复创建。 |
| ProviderStateConflict | 平台和供应商状态不一致 | 进入冲突恢复 Case。 |
| ProviderRuleChanged | 供应商价格或规则变化 | 原 Offer 失效，重新报价。 |

## 状态映射

### 标准 SegmentBooking 状态映射

| 平台状态 | 供应商可能状态 | 说明 |
|---|---|---|
| Requested | created、submitted、received | 请求已到达或已提交。 |
| Holding | held、reserved、pending_payment | 资源临时保留。 |
| Confirmed | confirmed、booked、assigned | 供应侧确认。 |
| Ticketed | ticketed、issued、credential_generated | 凭证已生成。 |
| InFulfillment | boarded、picked_up、checked_in | 已进入履约。 |
| Completed | arrived、completed、closed | 服务完成。 |
| Cancelled | cancelled、voided、refunded_by_provider | 已取消或作废。 |
| Failed | rejected、expired、failed | 预订失败。 |
| Unknown | timeout、unknown、processing | 必须查询最终状态。 |

### 标准 Entitlement 状态映射

| 平台状态 | 供应商可能状态 |
|---|---|
| PendingIssue | pending_issue、issuing |
| Issued | issued、ticketed、code_generated |
| CheckedIn | checked_in、printed、collected |
| Boarded | boarded、gate_passed、picked_up |
| Used | used、completed |
| Voided | voided、cancelled、invalidated |
| Suspended | suspended、frozen、under_review |

## 铁路适配

| 外部概念 | 平台概念 | 说明 |
|---|---|---|
| 车次 | Segment | 固定班次的一段运输服务。 |
| 席别 | CapacityUnit / Fare Product | 一等座、二等座、硬卧等。 |
| 区间票额 | Capacity & Availability | 必须支持区间重叠判断。 |
| 票号 | Entitlement credentialNo | 票证凭证号。 |
| 候补 | WaitlistRequest | 可作为库存上下文扩展。 |
| 检票状态 | Fulfillment Event | EntitlementBoarded 或 SegmentBoarded。 |

铁路防腐层重点：

1. 区间库存不要退化成总余票。
2. 高铁、动车、普速是 Service 分类，不是不同订单模型。
3. 候补兑现要和退票释放、改签释放事件协作。
4. 停运和晚点必须映射为 DisruptionPublished。

## 航空适配

| 外部概念 | 平台概念 | 说明 |
|---|---|---|
| PNR | ProviderReference | 供应商预订引用，不是平台订单。 |
| 票号 | Entitlement credentialNo | 出票后生成。 |
| 舱位 | CapacityUnit / Fare Product | 经济舱、公务舱及子舱位。 |
| 票价族 | FareRule / RuleSnapshot | 决定退改、行李和权益。 |
| 值机 | Fulfillment Event | EntitlementCheckedIn。 |
| 登机牌 | Entitlement | 可作为票证衍生凭证。 |
| 行李 | Ancillary Service | 托运和额外行李可独立履约。 |

航空防腐层重点：

1. PNR 创建成功不一定等于出票成功。
2. 航班价格和规则变化快，Offer 有效期要短。
3. 值机、登机、误机和行李状态不能塞进 JourneyOrder。
4. 航班取消、备降、延误要进入 Disruption Recovery。

## 大巴汽车适配

| 外部概念 | 平台概念 | 说明 |
|---|---|---|
| 班线 | Route / Service Plan | 起终点和经停站点。 |
| 班次 | Segment | 某天某时的一班车。 |
| 上车点 | Transport Node | 可能是站外点位或临时站点。 |
| 电子票码 | Entitlement credentialNo | 检票使用。 |
| 座位 | CapacityUnit | 也可能不支持选座。 |

大巴防腐层重点：

1. 上车点和车站不是同一概念，Place & Network 要支持 POI。
2. 供应商可能只返回余座，不返回具体座位。
3. 班次取消和站点调整要映射为 Disruption。

## 网约车适配

| 外部概念 | 平台概念 | 说明 |
|---|---|---|
| 预估价 | Offer | 可能失效或变成动态价。 |
| 一口价 | Offer | 有明确价格承诺。 |
| 司机接单 | SegmentReservationConfirmed / RideAssignment | 这时才有较强确认。 |
| 车牌和车型 | Fulfillment detail | 展示和履约核验。 |
| 上车点/下车点 | PlaceRef | 地址和经纬度。 |
| 等待费/取消费 | Fee | Payment 或 Post Sales 处理。 |
| 行程开始/结束 | Fulfillment Event | SegmentBoarded、SegmentCompleted。 |

网约车防腐层重点：

1. 没有固定库存，不要套 CapacityHold 的座位模型。
2. 司机未接单前只能是 Pending 或 Searching。
3. 司机取消、乘客取消、等待超时要进入不同售后规则。
4. 网约车适合作为首末段接驳 Segment。

## 轮船适配

| 外部概念 | 平台概念 | 说明 |
|---|---|---|
| 船班 | Segment | 固定班次。 |
| 舱房/铺位 | CapacityUnit | 可多人共享或整舱售卖。 |
| 车辆甲板 | CapacityUnit | 车辆票和人票可能绑定。 |
| 港口/码头 | Transport Node | 可能涉及安检和车辆排队。 |
| 船票/登船牌 | Entitlement | 登船核验。 |
| 封航/停航 | Disruption | 天气影响较大。 |

轮船防腐层重点：

1. 容量单元不仅是座位，还可能是舱房、铺位、车辆空间。
2. 人和车辆可以有绑定关系，退改时要一起处理。
3. 天气和港口管制要进入 Disruption Recovery。

## 支付渠道适配

虽然 Payment 是通用域，支付渠道仍需要防腐层。

| 外部概念 | 平台概念 | 说明 |
|---|---|---|
| 渠道交易号 | PaymentIntent providerReference | 幂等确认。 |
| 预授权号 | Authorization reference | 捕获或撤销。 |
| 退款单号 | Refund providerReference | 退款查询和对账。 |
| 回调通知 | PaymentCaptured / RefundSettled | 必须验签并幂等。 |
| 账单文件 | Reconciliation input | 对账使用。 |

支付防腐层重点：

1. 回调验签和幂等。
2. 晚到成功回调不能直接推进已取消订单。
3. 对账结果必须能创建 LatePaymentCase 或 RefundFailureCase。

## 保险和附加服务适配

| 外部概念 | 平台概念 |
|---|---|
| 保单号 | AncillaryOrder providerReference / Entitlement |
| 投保成功 | AncillaryServiceConfirmed |
| 退保 | AncillaryServiceCancelled / RefundRequested |
| 餐饮订单号 | AncillaryOrder providerReference |
| 托运单号 | AncillaryOrder providerReference / FulfillmentRecord |

附加服务重点：

1. 主票退改不一定自动退附加服务，必须按附加服务规则处理。
2. 附加服务履约失败不应直接取消主 JourneyOrder。
3. 附加服务供应商状态也必须映射为平台事件。

## 能力矩阵示例

| Provider Type | SearchAvailability | Reserve | Confirm | Issue | Change | Cancel | QueryStatus | Disruption |
|---|---|---|---|---|---|---|---|---|
| Rail | yes | yes | yes | yes | yes | yes | yes | yes |
| Air | yes | yes | sometimes | yes | yes | yes | yes | yes |
| Coach | yes | yes | sometimes | yes | sometimes | yes | yes | sometimes |
| RideHailing | yes | yes | no | yes | no | yes | yes | yes |
| Ferry | yes | yes | sometimes | yes | yes | yes | yes | yes |
| Insurance | no | yes | no | yes | no | yes | yes | no |

代码实现时不要假设所有供应商能力一致。Booking Orchestration 应根据能力矩阵选择流程。

## 供应商事件映射

| 供应商事件 | 平台事件 |
|---|---|
| reservation_confirmed | SegmentReservationConfirmed |
| reservation_failed | SegmentReservationFailed |
| ticket_issued | EntitlementIssued |
| ticket_voided | EntitlementVoided |
| service_cancelled | DisruptionPublished |
| service_delayed | DisruptionPublished |
| boarding_completed | SegmentBoarded |
| trip_completed | SegmentCompleted |
| driver_cancelled | DisruptionPublished 或 SegmentBookingCancelled |
| refund_completed | RefundSettled |

## 对账契约

| 对账对象 | 主键 | 差异处理 |
|---|---|---|
| 供应商预订 | providerReference + segmentBookingId | 创建 ProviderStateConflict。 |
| 票证 | credentialNo + entitlementId | 修复 Entitlement 或供应商取消。 |
| 支付 | channelTxnId + paymentIntentId | 创建 LatePaymentCase 或 PaymentMismatchCase。 |
| 退款 | refundProviderRef + refundId | 重试退款或人工处理。 |
| 结算 | settlementPeriod + providerId | 生成差异报表和财务工单。 |

## 防腐层测试要求

每个 Provider Adapter 至少需要覆盖：

1. 成功预订。
2. 业务拒绝。
3. 可重试技术失败。
4. 超时但供应商实际成功。
5. 超时但供应商实际失败。
6. 重复请求幂等。
7. 状态查询恢复。
8. 原始状态码映射。
9. 取消和退款。
10. 供应商异常事件。

这些测试可以先使用契约测试和模拟供应商实现，不需要真实外部接口。

## 第一阶段落地建议

1. 先定义平台标准 Provider Contract。
2. 为当前火车能力写 Rail Adapter，把现有服务调用包进防腐层。
3. 把订单核心改为只依赖 SegmentBooking 和 Entitlement，不依赖历史服务返回结构。
4. 再接入 Coach/Ferry 这类固定班次交通方式验证扩展性。
5. 最后接入 Air 和 RideHailing，验证 PNR、动态派单和复杂外部状态。
