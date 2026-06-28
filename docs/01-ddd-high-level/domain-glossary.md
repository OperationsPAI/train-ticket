# 出行业务统一语言表

Last updated: 2026-06-28

## 目的

这份文档定义 Train Ticket 重构和 General Travel 扩展时使用的统一语言。后续领域模型、API、事件、数据库表、服务接口和测试用例应尽量使用这里的词，而不是沿用当前代码里历史形成的 `preserve`、`travel`、`assurance` 等含义不稳定的命名。

统一语言的目标不是追求抽象漂亮，而是减少业务误解：

1. 用户视角的一次完整出行叫 `Journey`。
2. 可选择的行程方案叫 `Itinerary`。
3. 行程中的一段运输服务叫 `Segment`。
4. 用户购买行为形成 `Journey Order`。
5. 每段供应侧确认形成 `Segment Booking`。
6. 可使用的乘车、登机、登船或乘车码权益叫 `Entitlement`。
7. 跨段连接叫 `Transfer`，连接失败后的责任边界叫 `Connection Contract`。

## 命名原则

| 原则 | 说明 |
|---|---|
| 用户意图和供应确认分开 | 用户想买一次 Journey，不代表每个 Segment 都已被供应商确认。 |
| 报价和订单分开 | Offer 是带有效期的报价快照，Journey Order 是购买事实。 |
| 预留和出票分开 | Reservation/Hold 只表示资源临时保留，Entitlement 才表示可使用权益。 |
| 订单和票证分开 | Order 表示商业交易，Ticket/Entitlement 表示履约凭证。 |
| 支付和出票分开 | PaymentCaptured 不等于 EntitlementIssued。 |
| 售后和退款分开 | Post Sales 决定能否退改，Payment 只执行资金收退。 |
| 交通方式作为维度，不作为所有模型前缀 | 不默认创建 TrainOrder、FlightOrder、BusOrder；应先看是否属于 Journey Order 或 Segment Booking。 |

## 核心出行词汇

| 术语 | 中文 | 定义 | 归属上下文 | 注意事项 |
|---|---|---|---|---|
| Trip Intent | 出行意图 | 用户表达的出发地、目的地、时间、人数、偏好和约束。 | Trip Planning | 不承诺价格，不锁库存。 |
| Journey | 完整出行 | 用户视角从起点到终点的一次端到端出行。 | Trip Planning, Journey Order | 可以包含多个 Segment 和 Transfer。 |
| Itinerary | 行程方案 | 系统为 Trip Intent 生成的候选方案。 | Trip Planning | 是售前方案，不等于订单。 |
| Segment | 出行段 | 一段可被履约的运输服务。 | Trip Planning, Booking Orchestration | 火车车次、航班、大巴班次、船班、网约车行程都可映射为 Segment。 |
| Transfer | 中转接续 | 两个 Segment 之间的换乘、换站、安检、步行、取行李或接驳过程。 | Transfer Management | 不是运输服务本身，但影响 Journey 可达性。 |
| Connection Contract | 接续保障契约 | 定义中转失败时的责任、保障和补偿规则。 | Transfer Management | 区分保障联乘、供应商保障、平台协助和用户自理。 |
| Place | 地点 | 城市、地址、车站、机场、港口、POI 的统一抽象。 | Place & Network | 不直接表达某个班次是否可售。 |
| Transport Node | 交通节点 | 车站、机场、航站楼、港口、码头、大巴站、上车点。 | Place & Network | 是 Place 的交通特化。 |
| Route | 线路 | 一组有业务意义的节点连接。 | Service Plan | 不等于具体某天某趟服务。 |
| Service Plan | 运营计划 | 车次、航班、船班、班车等服务在日期上的计划。 | Service Plan | 固定班次交通方式使用。 |
| Dispatch Plan | 调度计划 | 网约车、接驳车等按需调度资源的计划。 | Dispatch | 即时调度不应硬套 Service Plan。 |

## 报价和可售性词汇

| 术语 | 中文 | 定义 | 归属上下文 | 注意事项 |
|---|---|---|---|---|
| Availability | 可用性 | 某方案当前是否可以购买或预订。 | Capacity & Availability | 可以来自内部库存或外部供应商。 |
| Capacity | 运力容量 | 座席、舱位、舱房、车辆甲板、司机供给等可服务能力。 | Capacity & Availability | 是供给能力，不一定全部可售。 |
| Inventory | 可售库存 | 已扣除限制、配额、锁定和售卖规则后的可销售资源。 | Capacity & Availability | 火车要按区间座席建模。 |
| Quota | 配额 | 分配给渠道、代理、企业或策略的销售额度。 | Capacity & Availability | 配额不是物理库存。 |
| Fare | 基础票价 | 不含或少含动态费用的基础价格。 | Fare & Pricing | 不同交通方式计算方式不同。 |
| Fare Rule | 票规 | 退改、签转、手续费、优惠和限制规则。 | Fare & Pricing | 决定售后规则，不属于 Payment。 |
| Fee | 费用 | 手续费、服务费、燃油费、等待费、改签费等附加费用。 | Fare & Pricing, Payment | 要有明确原因。 |
| Tax | 税费 | 航空、跨境、港口等场景下的税费。 | Finance Settlement | 影响发票和结算。 |
| Offer | 报价方案 | 对一个 Itinerary 的价格、库存、规则、风险和有效期快照。 | Offer Management | Offer 失效后必须重新报价。 |
| Offer Item | 报价项 | Offer 中某一 Segment 或附加服务的报价组成。 | Offer Management | 支持多段和组合售卖。 |
| Price Snapshot | 价格快照 | 下单时冻结的价格、规则和有效期。 | Offer Management | 订单必须引用快照，不能重新随意计算。 |

## 订单、预订和票证词汇

| 术语 | 中文 | 定义 | 归属上下文 | 注意事项 |
|---|---|---|---|---|
| Journey Order | 行程订单 | 用户购买一次完整 Journey 的商业订单。 | Journey Order | 汇总状态，不直接操作供应商库存。 |
| Order Item | 订单项 | Journey Order 下的收费或服务项。 | Journey Order | 可以是 Segment、保险、行李、接送等。 |
| Segment Booking | 分段预订 | 某个 Segment 的供应侧确认记录。 | Booking Orchestration | 对应火车票、航班 PNR、大巴票、船票、网约车订单等。 |
| Reservation | 预留 | 供应或库存对资源的临时保留。 | Booking Orchestration, Capacity & Availability | 不一定已支付，不一定可履约。 |
| Hold | 锁定 | 强调有过期时间的库存临时占用。 | Capacity & Availability | 下单超时必须释放。 |
| Supplier Confirmation | 供应商确认 | 外部供应商返回的预订成功事实。 | Provider Integration | 不等于平台订单状态。 |
| Entitlement | 出行权益凭证 | 用户可用于履约的凭证或权益。 | Entitlement & Ticketing | 火车票、机票、船票、乘车码、登机牌都属于 Entitlement。 |
| Ticket | 票 | 固定班次出行凭证的通称。 | Entitlement & Ticketing | 在通用模型中是 Entitlement 的一种。 |
| Boarding Pass | 登机牌或登船牌 | 飞机、轮船等登乘凭证。 | Entitlement & Ticketing, Fulfillment | 通常在出票后、登乘前生成。 |
| Ride Assignment | 网约车派单 | 司机和车辆被分配给 Ride Segment 的事实。 | Dispatch | 司机接单前不应视为强确认。 |

## 支付、售后和结算词汇

| 术语 | 中文 | 定义 | 归属上下文 | 注意事项 |
|---|---|---|---|---|
| Payment Intent | 支付意图 | 对某订单或差价的待支付请求。 | Payment | 可走支付、预授权或担保。 |
| Authorization | 支付授权 | 用户资金被授权但未最终捕获。 | Payment | 常见于网约车、酒店、候补担保。 |
| Capture | 扣款确认 | 支付渠道确认资金捕获成功。 | Payment | 触发订单推进，但不直接出票。 |
| Refund | 退款 | 对原支付或差价的资金退回。 | Payment | 退款原因来自 Post Sales 或 Disruption。 |
| Settlement | 清算结算 | 平台、渠道、供应商之间的账务确认。 | Finance Settlement | 不应阻塞用户出票链路。 |
| Reconciliation | 对账 | 平台记录和渠道或供应商账单比对。 | Finance Settlement | 发现差异后进入处理流程。 |
| Invoice | 发票 | 用户或企业报销凭证。 | Finance Settlement | 与 Entitlement 不同。 |
| Post Sales Request | 售后申请 | 用户或系统发起取消、退票、改签、改程、补偿等请求。 | Post Sales | 不直接执行资金操作。 |
| Cancellation | 取消 | 关闭未完成订单或作废已确认服务。 | Post Sales | 未支付取消和已出票退票要区分。 |
| Change | 变更 | 改签、改期、改程、升舱、变更到站等。 | Post Sales | 通常涉及新旧 Segment、库存和差价。 |
| No-show | 未出现 | 旅客未按时上车、登机、登船或网约车等待超时。 | Fulfillment, Post Sales | 售后规则不同于主动取消。 |

## 履约和异常词汇

| 术语 | 中文 | 定义 | 归属上下文 | 注意事项 |
|---|---|---|---|---|
| Fulfillment | 履约 | 用户实际使用 Segment 或附加服务的过程。 | Fulfillment | 不应只由订单支付状态推断。 |
| Check-in | 值机或签到 | 用户在出行前确认乘坐资格和座位。 | Fulfillment | 飞机强依赖，其他方式可选。 |
| Boarding | 登乘 | 用户通过检票、登机、登船或上车进入服务。 | Fulfillment | 是重要履约事实。 |
| Arrival | 到达 | Segment 到达目的节点。 | Fulfillment | 可能触发 Transfer 风险评估。 |
| Completion | 完成 | Segment 或 Journey 履约完成。 | Fulfillment | 影响收入确认和售后限制。 |
| Disruption | 出行异常 | 延误、取消、停运、封航、司机取消、备降、站点变更等。 | Disruption Recovery | 异常来源可以是供应商、运营或系统。 |
| Reaccommodation | 保护性改乘 | 因异常为用户重新安排可接受方案。 | Disruption Recovery | 可能免费或有成本承担规则。 |
| Recovery Option | 恢复方案 | 面向用户或客服的替代出行、退款或补偿选项。 | Disruption Recovery | 需要可解释成本和权益。 |
| Compensation | 补偿 | 因服务失败给予用户的赔付、优惠或费用减免。 | Customer Service, Payment | 不等同退款。 |

## 附加服务词汇

| 术语 | 中文 | 定义 | 归属上下文 | 注意事项 |
|---|---|---|---|---|
| Ancillary Service | 附加服务 | 与主行程关联但不是主运输服务的商品或服务。 | Ancillary Service | 如保险、行李、餐饮、接送、贵宾厅。 |
| Baggage Service | 行李服务 | 托运、额外行李、行李转运、行李赔付。 | Ancillary Service | 航空和轮船差异较大。 |
| Insurance Policy | 保险保单 | 与行程相关的保险权益。 | Ancillary Service | 需要独立供应商确认和售后规则。 |
| Station Service | 站内服务 | 贵宾厅、快速安检、无障碍协助、接送站。 | Ancillary Service | 履约节点和主票不同。 |

## 供应商和防腐层词汇

| 术语 | 中文 | 定义 | 归属上下文 | 注意事项 |
|---|---|---|---|---|
| Supplier | 供应商 | 提供运输或附加服务的外部或内部主体。 | Supplier Catalog | 如铁路、航司、大巴公司、船司、网约车平台。 |
| Carrier | 承运商 | 实际承运旅客的运输主体。 | Supplier Catalog | 可能与销售供应商不同。 |
| Provider Adapter | 供应商适配器 | 对接供应商 API 或数据源的适配组件。 | Provider Integration | 属于防腐层，不应承载核心订单规则。 |
| Anti-Corruption Layer | 防腐层 | 把供应商语言翻译成平台统一语言的边界。 | Provider Integration | 防止 PNR、司机派单码等污染核心模型。 |
| Published Language | 发布语言 | 上下文之间稳定共享的事件和契约语言。 | 多上下文 | 优先用领域事件和明确 DTO。 |

## 弃用或限制使用的历史词

| 历史词 | 问题 | 建议替代 |
|---|---|---|
| Preserve | 当前代码中用于订票，但语义不清 | Booking Orchestration、Reservation、Journey Order 按场景替代。 |
| Travel | 可指车次查询、行程、服务、出行本身 | Trip Intent、Itinerary、Segment、Journey 按语义替代。 |
| Assurance | 当前代码中用于保险，但容易被理解成保障 | Insurance Policy 或 Ancillary Service。 |
| Order | 太泛，容易混淆商业订单和供应商预订 | Journey Order、Segment Booking、Payment Intent。 |
| Ticket | 在通用出行中过窄 | 对外可说票；领域模型优先用 Entitlement。 |
| Seat | 对飞机、船、网约车不通用 | Capacity Unit、Seat、Cabin、Vehicle、Driver Supply 分场景使用。 |

## 事件命名规则

领域事件使用过去式，表达已经发生的业务事实：

| 好的事件名 | 不好的事件名 | 原因 |
|---|---|---|
| JourneyOrderCreated | CreateOrder | 事件应是事实，不是命令。 |
| SegmentReservationConfirmed | ReserveSuccess | 明确对象和业务含义。 |
| PaymentCaptured | PayDone | 使用支付领域标准语义。 |
| EntitlementIssued | TicketGenerated | 兼容火车、飞机、船和网约车。 |
| TransferAtRisk | TransferWarning | 表达中转风险状态已发生变化。 |
| ReaccommodationAccepted | ChangeDone | 明确是异常恢复场景。 |

命令使用祈使语义，例如 `CreateJourneyOrder`、`ConfirmSegmentReservation`、`IssueEntitlement`。查询使用意图语义，例如 `SearchItineraries`、`GetJourneyOrder`、`ListAvailableOffers`。

## 状态命名规则

状态应描述实体当前生命周期，不描述 UI 文案：

| 对象 | 推荐状态 |
|---|---|
| Journey Order | Draft, PendingConfirmation, PendingPayment, Confirmed, PartiallyConfirmed, InTravel, Completed, Cancelled, Disrupted, Failed |
| Segment Booking | Requested, Holding, Confirmed, Ticketed, InFulfillment, Completed, Cancelled, Changed, Failed |
| Entitlement | PendingIssue, Issued, CheckedIn, Boarded, Used, Voided, Suspended |
| Payment Intent | Created, Authorized, Captured, Failed, Cancelled, Expired |
| Refund | Requested, Accepted, Processing, Settled, Failed, Cancelled |
| Transfer | Feasible, Tight, AtRisk, Missed, Recovered, SelfHandled |

## 与文档集的关系

后续文档都应引用本统一语言：

1. `docs/01-ddd-high-level/context-map.md`：用这些术语定义上下文边界。
2. `docs/01-ddd-high-level/aggregate-model.md`：用这些术语定义聚合和不变量。
3. `docs/01-ddd-high-level/state-machines.md`：用这些术语定义状态机。
4. `docs/01-ddd-high-level/event-storming.md`：用这些术语定义命令、事件、策略和读模型。
5. `docs/01-ddd-high-level/consistency-and-saga.md`：用这些术语定义跨上下文一致性和补偿。
6. `docs/01-ddd-high-level/acl-provider-contracts.md`：用这些术语定义供应商防腐层映射。
7. `docs/01-ddd-high-level/order-inventory-payment-model.md`：用这些术语定义第一阶段核心交易模型。
