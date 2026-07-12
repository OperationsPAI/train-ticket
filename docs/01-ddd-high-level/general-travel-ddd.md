# General Travel DDD 业务流与边界划分

Last updated: 2026-06-28

## 目的

这份文档把 `docs/01-ddd-high-level/train-business-flow-catalog.md` 中的火车业务流提升到更通用的出行场景。目标不是把火车、大巴、网约车、飞机、轮船强行做成一套完全相同的模型，而是先识别哪些业务语义可以统一，哪些规则必须保留交通方式差异。

General Travel 平台至少要覆盖：

1. 固定班次出行：火车、飞机、大巴、轮船。
2. 即时或预约调度出行：网约车、出租车、接驳车。
3. 联乘和中转：单一交通方式内中转，以及跨方式的多段行程。
4. 售前、售中、履约、售后、异常、结算、客服、运营全链路。

本文是 DDD 设计输入，不是微服务拆分方案。最终可以是模块化单体、微服务或多语言组合，但业务边界应先独立于技术选型稳定下来。

## 先给结论：DDD 不应只按交通工具切

在 General Travel 里，最常见的错误是直接做：

- `train-order-service`
- `flight-order-service`
- `bus-order-service`
- `ferry-order-service`
- `ride-hailing-order-service`

这种拆法短期直观，长期会导致订单、支付、退款、通知、客服、对账在每个交通方式里重复一遍。更稳妥的划分方式是：

1. 按业务事实和一致性边界切核心上下文。
2. 在上下文内部保留交通方式特化模型。
3. 在跨方式协同时使用统一语言，但不要抹平关键差异。

也就是说，交通方式是领域模型的重要属性和策略分支，但通常不应该成为所有上下文的第一层边界。

## 核心统一语言

| 术语 | 含义 | 说明 |
|---|---|---|
| Trip Intent | 出行意图 | 用户想从哪里到哪里、什么时候出发或到达、几个人、偏好和约束。 |
| Journey | 一次完整出行 | 用户视角的一次端到端出行，可以包含多段 Segment。 |
| Itinerary | 行程方案 | 由一个或多个 Segment 和 Transfer 组成的可选方案。通常是查询和报价产物。 |
| Segment | 出行段 | 一段可被履约的运输服务，如一趟火车、一段航班、一段大巴、一段船班、一次网约车。 |
| Transfer | 中转或接续 | 两个 Segment 之间的换乘、步行、取行李、安检、换码头、换车站等过程。 |
| Offer | 报价方案 | 对某个 Itinerary 的价格、库存、规则、有效期和风险说明的快照。 |
| Reservation | 预留或占用 | 供应侧对某个 Segment 的临时或正式保留。不同交通方式语义差异很大。 |
| Booking | 预订确认 | 供应商或内部系统确认旅客获得某段服务的事实。 |
| Entitlement | 出行权益凭证 | 用户可用来乘坐或使用服务的凭证，如车票、机票、登机牌、船票、乘车码、网约车订单。 |
| Journey Order | 用户订单 | 用户面向平台购买一次或多段行程的商业订单。 |
| Segment Booking | 分段预订 | Journey Order 下每个 Segment 的供应侧确认记录。 |
| Connection Contract | 联乘保障契约 | 定义中转失败时谁负责、是否保护、是否免费改签或退款。 |
| Disruption | 出行异常 | 晚点、取消、停运、误机、误船、司机取消、道路拥堵、天气封航等。 |
| Reaccommodation | 保护性改乘 | 因异常为用户重新安排可接受的出行方案。 |

## DDD 划分原则

### 1. 先区分业务节奏

| 类型 | 交通方式 | 典型特征 | 建模影响 |
|---|---|---|---|
| 固定班次 | 火车、飞机、大巴、轮船 | 有班次、时刻、站点或港口、固定容量 | 重点建 Schedule、Capacity、Ticketing、Boarding。 |
| 即时调度 | 网约车、出租车 | 没有固定班次，先报价再匹配司机 | 重点建 Dispatch、Driver Assignment、Trip Execution。 |
| 混合预约 | 接驳车、包车、预约网约车 | 可预约，但履约前仍要调度资源 | 同时需要预约订单和调度状态机。 |

### 2. 再区分库存形态

| 库存形态 | 示例 | 关键不变量 |
|---|---|---|
| 区间座席库存 | 火车、大巴 | 同一座位可以在不同区间复用，不能只按总座位数扣减。 |
| 航段舱位库存 | 飞机 | 舱位、票价族、航段组合和超售规则影响可售性。 |
| 舱房/座席/车辆甲板库存 | 轮船 | 乘客票、车辆票、舱房、铺位可能绑定或分开售卖。 |
| 动态运力库存 | 网约车 | 库存是附近司机、车型、ETA、供需状态，不是固定座位。 |
| 虚拟配额 | 渠道、代理、企业票 | 配额是销售权，不一定等于物理座席。 |

### 3. 再区分凭证和履约

| 凭证类型 | 示例 | 履约事实 |
|---|---|---|
| 车票 | 火车、大巴 | 取票、检票、上车、到达、出站。 |
| 机票/登机牌 | 飞机 | 出票、值机、托运行李、安检、登机、抵达。 |
| 船票/登船牌 | 轮船 | 港口安检、登船、车辆上船、靠港、下船。 |
| 乘车订单 | 网约车 | 司机接单、到达上车点、乘客上车、行程开始、行程结束。 |
| 服务券或接驳凭证 | 接驳、站内服务 | 核销、服务开始、服务完成。 |

### 4. 最后区分责任边界

| 边界 | 说明 |
|---|---|
| 平台自营库存 | 平台直接维护库存和出票规则，强一致要求更高。 |
| 外部供应商库存 | 平台通过供应商 API、GDS、NDC、代理接口或人工同步获取库存。 |
| 平台订单 | 用户只认平台订单和售后承诺。 |
| 供应商确认 | 每一段是否真实可用，最终取决于供应商确认。 |
| 保障联乘 | 平台承诺中转失败时兜底。 |
| 非保障联乘 | 平台只售卖组合方案，用户承担中转失败风险，必须明确告知。 |

## 候选限界上下文

| 上下文 | 通用职责 | 是否交通方式特化 |
|---|---|---|
| Account | 账号、登录、渠道账号绑定、账号状态 | 通用 |
| Traveler Profile | 旅客、证件、年龄、优惠资格、出行偏好 | 通用，但证件规则按方式和国家地区特化 |
| Place & Network | 城市、地址、站点、机场、港口、车站、POI、地理关系 | 通用核心，节点类型特化 |
| Supplier Catalog | 供应商、承运商、车队、航司、船司、车站运营方 | 通用 |
| Service Plan | 班次、航班、车次、船班、路线、运营日历 | 固定班次特化 |
| Dispatch | 司机、车辆、派单、接驾、ETA | 网约车和接驳特化 |
| Capacity & Availability | 座席、舱位、票额、车辆容量、司机供给 | 高度特化 |
| Fare & Pricing | 票价、动态定价、税费、手续费、优惠、加价 | 通用框架，规则特化 |
| Trip Planning | 多方式路线搜索、联程、中转、排序 | General Travel 核心域 |
| Offer Management | 报价快照、有效期、规则、风险提示、组合报价 | General Travel 核心域 |
| Booking Orchestration | 多段预订编排、占位、确认、补偿 | General Travel 核心域 |
| Provider Integration | 航司、铁路、大巴、船司、网约车平台适配 | 防腐层，强特化 |
| Journey Order | 用户订单、订单项、状态汇总、售后入口 | 通用 |
| Entitlement & Ticketing | 出票、票号、登机牌、乘车码、服务凭证 | 通用抽象，凭证类型特化 |
| Payment | 收款、退款、预授权、担保、差价 | 通用 |
| Fulfillment | 值机、检票、登船、司机接驾、行程开始结束 | 通用抽象，事件特化 |
| Transfer Management | 中转保障、最短换乘时间、错过接续、行李衔接 | General Travel 核心域 |
| Post Sales | 取消、退票、改签、改程、退差价 | 通用编排，规则特化 |
| Disruption Recovery | 晚点、取消、停运、封航、司机取消、保护性改乘 | General Travel 核心域 |
| Ancillary Service | 行李、保险、餐饮、座位、接送、优先登乘、车站服务 | 通用框架，商品特化 |
| Notification | 通知模板、发送记录、订阅偏好、失败重试 | 通用 |
| Customer Service | 工单、申诉、人工干预、凭证补发 | 通用 |
| Risk & Compliance | 反刷、黑名单、证件合规、重复行程、支付风控 | 通用框架，策略特化 |
| Finance Settlement | 对账、清算、佣金、税费、收入确认、发票 | 通用 |
| Reporting | 销售、客流、履约、异常、收入和库存报表 | 通用 |

## 交通方式差异矩阵

| 维度 | 火车 | 飞机 | 大巴汽车 | 网约车 | 轮船 |
|---|---|---|---|---|---|
| 服务形态 | 固定车次 | 固定航班 | 固定班次或线路 | 即时/预约调度 | 固定船班 |
| 起终点 | 车站 | 机场/航站楼 | 车站/站点/上车点 | 地址/POI/经纬度 | 港口/码头 |
| 中间节点 | 经停站 | 经停/中转机场 | 经停站点 | 可变路线 | 经停港 |
| 库存 | 区间座席/无座 | 舱位/票价族/座位 | 座席/余座/无座或站票 | 司机和车辆供给 | 舱房/座席/车辆甲板 |
| 报价 | 相对稳定 | 税费和票规复杂，动态更强 | 通常较简单 | 预估价或一口价，受供需影响 | 舱房和车辆费用复杂 |
| 预订确认 | 占座后出票 | PNR/出票/票号 | 座位或电子票确认 | 司机接单后才强确认 | 船票/舱房确认 |
| 履约凭证 | 身份证/车票/二维码 | 机票、登机牌、证件 | 电子票/二维码/身份证 | 车牌、司机、订单 | 船票、登船牌、证件 |
| 履约节点 | 取票、检票、上车、到站 | 值机、托运、安检、登机、到达 | 检票、上车、到站 | 司机到达、上车、开始、结束 | 安检、登船、靠港、下船 |
| 售后 | 退票、改签、变更到站 | 退票、改签、升舱、误机 | 退票、改签、改班次 | 取消、等待费、争议退款 | 退票、改签、改舱房 |
| 异常 | 晚点、停运、调图 | 延误、取消、备降、联程保护 | 延误、停班、站点变更 | 司机取消、绕路、迟到 | 天气封航、停航、靠港变化 |
| 联乘风险 | 同站/换站/跨城站 | 航站楼、行李、安检、最短衔接 | 站点位置和准点率 | 可作为首末段接驳 | 港口位置、安检、天气 |

## General Travel 业务流目录

### A. 供给接入和运营准备

| ID | 等级 | 业务流 | 核心结果 | 主要上下文 |
|---|---|---|---|---|
| A01 | P0 | 地点和交通节点建模 | 城市、地址、车站、机场、港口、站点、POI 形成统一 Place Graph | Place & Network |
| A02 | P0 | 供应商和承运商接入 | 航司、铁路、大巴公司、船司、网约车平台被识别和管理 | Supplier Catalog |
| A03 | P0 | 固定班次计划导入 | 车次、航班、班车、船班的运行日历和时刻可查询 | Service Plan |
| A04 | P0 | 库存和可用性同步 | 票额、舱位、余座、舱房、司机供给可被查询或估算 | Capacity & Availability |
| A05 | P0 | 价格和票规同步 | 票价、税费、手续费、取消改签规则可计算 | Fare & Pricing |
| A06 | P1 | 供应商 API 健康和降级 | 外部接口异常时可熔断、缓存、重试或隐藏方案 | Provider Integration |
| A07 | P1 | 渠道和配额管理 | 不同销售渠道有明确可售范围和限额 | Capacity & Availability |
| A08 | P1 | 运营日历和停售配置 | 节假日、管控、罢工、天气、政策导致的停售可表达 | Service Plan |
| A09 | P2 | 合同和佣金配置 | 不同供应商和渠道的结算合同可管理 | Finance Settlement |

### B. 旅客、账号和合规

| ID | 等级 | 业务流 | 核心结果 | 主要上下文 |
|---|---|---|---|---|
| B01 | P0 | 账号注册、登录和身份会话 | 用户身份可用于查询、下单和售后 | Account |
| B02 | P0 | 旅客资料维护 | 姓名、证件、手机号、出生日期、偏好可被订单引用 | Traveler Profile |
| B03 | P0 | 出行资格和证件校验 | 实名、年龄、签证或地区规则、优惠资质通过校验 | Traveler Profile |
| B04 | P1 | 重复行程和冲突行程校验 | 同一旅客不会被售卖明显冲突的服务 | Risk & Compliance |
| B05 | P1 | 黑名单、限购和风控 | 异常账号、证件、设备、支付方式被限制 | Risk & Compliance |
| B06 | P1 | 特殊旅客服务资格 | 儿童、老人、残障、携宠、车辆上船等资格被表达 | Traveler Profile, Ancillary Service |
| B07 | P2 | 企业、代理、团队旅客管理 | 团队名单、企业额度、代理权限可管理 | Account |

### C. 搜索、规划和联乘

| ID | 等级 | 业务流 | 核心结果 | 主要上下文 |
|---|---|---|---|---|
| C01 | P0 | 单方式直达搜索 | 用户获得某方式的直达方案，如火车直达、航班直飞 | Trip Planning |
| C02 | P0 | 多方式方案搜索 | 用户获得火车、飞机、大巴、网约车、轮船的可比方案 | Trip Planning |
| C03 | P0 | 行程方案排序 | 按时间、价格、准点率、换乘次数、风险、舒适度排序 | Trip Planning |
| C04 | P0 | 报价前可用性检查 | 排除明显无库存、停售、证件不符、时间不可达的方案 | Offer Management |
| C05 | P1 | 同方式中转 | 火车换乘、航班中转、大巴换乘、轮船换船 | Trip Planning, Transfer Management |
| C06 | P1 | 跨方式联乘 | 飞机+火车、火车+网约车、大巴+轮船等组合方案 | Trip Planning, Transfer Management |
| C07 | P1 | 首末段接驳 | 机场/车站/港口到地址的网约车、接驳巴士或步行方案 | Trip Planning, Dispatch |
| C08 | P1 | 最短换乘时间校验 | 中转需要考虑步行、换站、取行李、安检、出入境、候车 | Transfer Management |
| C09 | P1 | 中转风险说明 | 明确保障联乘、非保障联乘、用户自担风险 | Offer Management |
| C10 | P2 | 智能组合和替代方案 | 在直达无票、太贵、太慢时推荐替代日期、邻近节点和绕行 | Trip Planning |

### D. 报价、组合和锁定

| ID | 等级 | 业务流 | 核心结果 | 主要上下文 |
|---|---|---|---|---|
| D01 | P0 | 单段报价 | 某一 Segment 的价格、税费、规则和有效期明确 | Offer Management |
| D02 | P0 | 多段组合报价 | 多个 Segment 组合成 Journey Offer，总价和分段价明确 | Offer Management |
| D03 | P0 | 报价快照生成 | 下单时使用稳定快照，避免查询价和支付价混乱 | Offer Management |
| D04 | P0 | 报价有效期管理 | 航班、网约车、外部供应商价格变化有失效机制 | Offer Management |
| D05 | P1 | 预留、占座或预授权 | 固定班次可锁库存，网约车可预估或预约 | Booking Orchestration |
| D06 | P1 | 组合方案可售性再确认 | 多段行程下单前重新确认每段仍可售 | Booking Orchestration |
| D07 | P1 | 联乘保障定价 | 保障服务、误点保护、改乘权益可作为价格组成 | Fare & Pricing |
| D08 | P2 | 套餐和捆绑销售 | 交通、行李、保险、接送、酒店等组合成包 | Offer Management |

### E. 下单、预订和确认

| ID | 等级 | 业务流 | 核心结果 | 主要上下文 |
|---|---|---|---|---|
| E01 | P0 | 创建 Journey Order | 用户视角的一次完整购买被记录 | Journey Order |
| E02 | P0 | 创建 Segment Booking | 每段服务生成独立供应侧预订记录 | Booking Orchestration |
| E03 | P0 | 外部供应商确认 | 航司、铁路、大巴、船司、网约车平台返回确认或失败 | Provider Integration |
| E04 | P0 | 支付或预授权 | 用户完成收款、担保或授权 | Payment |
| E05 | P0 | 出票或生成凭证 | 车票、机票、船票、乘车码、网约车订单生成 | Entitlement & Ticketing |
| E06 | P1 | 多段预订补偿 | 某段确认失败时取消已确认段、退款或给替代方案 | Booking Orchestration |
| E07 | P1 | 幂等下单 | 重复提交不会重复预订、重复扣款或重复出票 | Journey Order |
| E08 | P1 | 部分成功订单处理 | 部分 Segment 成功时明确用户可选择保留、补齐或全退 | Booking Orchestration |
| E09 | P2 | 团队和批量预订 | 多旅客、多段、多供应商批量确认和支付 | Journey Order |

### F. 出行前准备和履约

| ID | 等级 | 业务流 | 核心结果 | 主要上下文 |
|---|---|---|---|---|
| F01 | P0 | 行程凭证展示 | 用户看到每段的凭证、地点、时间、注意事项 | Entitlement & Ticketing |
| F02 | P0 | 出行提醒 | 发车、登机、登船、司机到达前发送提醒 | Notification |
| F03 | P0 | 到达上车/登乘点 | 用户按地点导航到车站、机场、港口、上车点 | Fulfillment |
| F04 | P0 | 检票、登机、登船或上车 | Entitlement 被核验，Segment 进入履约中 | Fulfillment |
| F05 | P0 | Segment 完成 | 某段到达、下车、下船、降落或网约车结束 | Fulfillment |
| F06 | P1 | 值机和选座 | 飞机或部分长途服务需要提前值机、选座 | Fulfillment |
| F07 | P1 | 行李托运和提取 | 航空、轮船、大巴、铁路托运形成独立履约状态 | Ancillary Service |
| F08 | P1 | 中转过程跟踪 | 第一段完成后，系统判断是否能赶上下一段 | Transfer Management |
| F09 | P1 | 首末段接驳履约 | 网约车或接驳车完成机场、车站、港口接送 | Dispatch, Fulfillment |
| F10 | P2 | 站内、港口、机场服务核销 | 贵宾厅、快速安检、无障碍协助等服务完成 | Ancillary Service |

### G. 售后、变更和取消

| ID | 等级 | 业务流 | 核心结果 | 主要上下文 |
|---|---|---|---|---|
| G01 | P0 | 取消未确认订单 | 用户或系统取消未完成预订 | 释放库存或停止供应商确认，资金不重复扣减 | Journey Order |
| G02 | P0 | 退票或取消已确认 Segment | 按规则作废凭证并发起退款 | Post Sales |
| G03 | P0 | 改签、改期或改程 | 替换某段或多段 Segment，并完成差价结算 | Post Sales |
| G04 | P0 | 退款和差价结算 | 退还、补收或保留手续费，资金状态闭环 | Payment |
| G05 | P1 | 部分退改 | 多人、多段订单只变更一部分 | Post Sales, Journey Order |
| G06 | P1 | No-show 和误乘处理 | 用户未登机、未上车、未登船或司机等待超时 | Post Sales, Fulfillment |
| G07 | P1 | 附加服务联动退改 | 主行程退改时处理行李、保险、接送等服务 | Ancillary Service |
| G08 | P1 | 供应商售后同步 | 外部供应商退改结果和平台状态一致 | Provider Integration |
| G09 | P2 | 人工例外售后 | 特殊原因超规则退改，由客服审批 | Customer Service |

### H. 异常、联乘保护和恢复

| ID | 等级 | 业务流 | 核心结果 | 主要上下文 |
|---|---|---|---|---|
| H01 | P1 | 延误或晚点发布 | 受影响 Segment 标记异常并通知用户 | Disruption Recovery |
| H02 | P1 | 取消、停运、停航或司机取消 | 原服务不可履约，用户获得退款或替代方案 | Disruption Recovery |
| H03 | P1 | 错过中转检测 | 上一段延误导致下一段风险升高或已错过 | Transfer Management |
| H04 | P1 | 保护性改乘 | 保障联乘场景下免费或优先安排替代方案 | Disruption Recovery |
| H05 | P1 | 非保障联乘提示和自助补救 | 自助组合场景中提供可买替代方案但不承诺兜底 | Offer Management |
| H06 | P1 | 批量通知和批量退款 | 大面积取消、天气、罢工、系统故障时批处理 | Notification, Payment |
| H07 | P1 | 供应商异常对账 | 供应商已取消但平台未同步，或平台状态不一致 | Provider Integration |
| H08 | P2 | 复杂扰动优化 | 多段旅客在成本、时间、权益间自动寻找最佳恢复方案 | Disruption Recovery |

### I. 财务、对账和结算

| ID | 等级 | 业务流 | 核心结果 | 主要上下文 |
|---|---|---|---|---|
| I01 | P0 | 收款和退款流水 | 每笔订单、退款、差价都有资金流水 | Payment |
| I02 | P1 | 支付渠道对账 | 平台流水和支付渠道账单一致 | Finance Settlement |
| I03 | P1 | 供应商结算 | 承运商、网约车平台、代理、渠道佣金可结算 | Finance Settlement |
| I04 | P1 | 税费和发票 | 不同交通方式和地区的税费、发票、报销凭证可处理 | Finance Settlement |
| I05 | P1 | 收入确认 | 出票、履约、退款、取消后收入状态正确 | Finance Settlement |
| I06 | P2 | 多币种和跨境结算 | 国际航班、跨境轮船、境外供应商可结算 | Finance Settlement |

### J. 客服、运营和治理

| ID | 等级 | 业务流 | 核心结果 | 主要上下文 |
|---|---|---|---|---|
| J01 | P1 | 订单和行程全局查询 | 客服能按旅客、订单、供应商确认号查询完整 Journey | Customer Service |
| J02 | P1 | 工单和争议处理 | 投诉、退款争议、司机纠纷、行李丢失可追踪 | Customer Service |
| J03 | P1 | 后台人工干预 | 高权限操作可修正状态、补发凭证、发起人工退款 | Admin & Audit |
| J04 | P1 | 操作审计 | 所有高风险人工操作记录前后值和原因 | Admin & Audit |
| J05 | P1 | 业务监控 | 查询成功率、下单成功率、出票成功率、履约异常可观察 | Reporting |
| J06 | P2 | 供应商绩效评估 | 准点率、取消率、投诉率、结算差异影响排序和合作 | Reporting |

## 联乘和中转建模

### 联乘不是简单的多个 Segment 相加

联乘的核心对象不是 `Segment[]`，而是 `Itinerary + Transfer + Connection Contract`：

1. `Itinerary` 描述用户可选择的端到端方案。
2. `Segment` 描述每段运输服务。
3. `Transfer` 描述两段之间的连接过程。
4. `Connection Contract` 描述连接失败后谁负责。

如果没有 Connection Contract，系统只能把多段票卖出去，却无法解释中转失败时应该怎么处理。

### Transfer 应表达的要素

| 要素 | 例子 |
|---|---|
| 转换地点 | 同站换乘、换航站楼、换车站、换码头、从机场打车去火车站。 |
| 连接时间 | 第一段到达和第二段出发之间的时间差。 |
| 最小换乘时间 | 步行、安检、出入境、取行李、重新托运、交通拥堵。 |
| 行李衔接 | 行李直挂、需要自取、车辆上船、托运转运。 |
| 证件和安检 | 国内、国际、港口、机场、实名检票规则。 |
| 风险等级 | 充足、紧张、高风险、不可达。 |
| 保障类型 | 平台保障、供应商保障、用户自理。 |

### Connection Contract 类型

| 类型 | 含义 | 售后责任 |
|---|---|---|
| Protected Connection | 平台或供应商承诺中转保护 | 错过接续时提供免费改乘、住宿、退款或补偿。 |
| Supplier Protected | 同一供应商或联盟内保护 | 平台协助，但规则以供应商为准。 |
| Platform Assisted | 平台不承诺完全兜底，但提供重订、客服和部分补偿 | 需要明确服务边界和费用责任。 |
| Self Transfer | 用户自行承担中转风险 | 下单前必须提示风险，售后按单段规则处理。 |

### 联乘订单状态要分层

Journey Order 的状态不能简单等于所有 Segment 状态的并集。建议分三层：

| 层级 | 状态示例 | 说明 |
|---|---|---|
| Journey Order | Pending, Confirmed, PartiallyConfirmed, InTravel, Completed, Disrupted, Cancelled | 用户视角的整体订单。 |
| Segment Booking | Holding, Confirmed, Ticketed, Failed, Cancelled, Changed, Completed | 每段供应侧确认。 |
| Transfer | Feasible, Tight, AtRisk, Missed, Recovered, SelfHandled | 两段之间的连接状态。 |

## 推荐聚合和状态边界

| 聚合 | 保护的不变量 | 不建议承担的职责 |
|---|---|---|
| Trip Intent | 用户查询条件、旅客人数、偏好和约束 | 不持久锁库存，不承诺价格。 |
| Offer | 报价快照、有效期、规则、风险提示 | 不直接处理支付，不代表最终供应商确认。 |
| Journey Order | 用户购买意图、订单项、总体状态、售后入口 | 不直接修改供应商库存。 |
| Segment Booking | 某一段服务的确认、凭证、供应商状态 | 不汇总整单财务，不处理其他 Segment。 |
| Transfer Plan | 换乘可达性、连接风险、保障契约 | 不负责出票或支付。 |
| Payment Intent | 应收金额、支付方式、授权和捕获 | 不决定退改规则。 |
| Refund | 应退金额、退款原因、退款渠道状态 | 不作废票证，票证作废由 Post Sales 编排。 |
| Entitlement | 票、登机牌、乘车码、船票等可使用凭证 | 不负责用户订单总状态。 |
| Disruption Case | 异常影响范围、恢复方案、用户选择 | 不直接改库存，通过编排触发改签或退款。 |

## 防腐层设计

每种交通方式和供应商都有自己的语言。General Travel 平台需要统一体验，但不能让外部语言污染核心模型。

| 外部语言 | 平台内部映射 | 注意事项 |
|---|---|---|
| 航空 PNR、票号、票价族、舱位 | Segment Booking、Entitlement、Fare Rule | 不要把 PNR 当成平台订单。 |
| 铁路车次、席别、区间票额 | Service Segment、Capacity Hold、Rail Entitlement | 区间库存是铁路特有核心规则。 |
| 大巴班次、上车点、电子票码 | Service Segment、Boarding Point、Coach Entitlement | 上车点可能不是标准车站。 |
| 网约车司机、车辆、派单、预估价 | Dispatch Assignment、Ride Segment、Price Estimate | 司机接单前不应承诺强确认。 |
| 轮船船班、舱房、车辆甲板 | Sailing Segment、Cabin/Deck Capacity、Ferry Entitlement | 人和车可能是两个绑定权益。 |

Provider Integration 应只负责翻译、重试、幂等、错误映射和供应商状态同步。核心订单和售后规则不应直接依赖供应商原始状态码。

## 领域事件目录草案

| 事件 | 产生上下文 | 说明 |
|---|---|---|
| TripIntentCreated | Trip Planning | 用户发起一次出行搜索。 |
| ItineraryProposed | Trip Planning | 系统生成一个或多个方案。 |
| TransferRiskEvaluated | Transfer Management | 换乘风险和保障类型已计算。 |
| OfferQuoted | Offer Management | 报价快照生成。 |
| OfferExpired | Offer Management | 报价失效，需要重新确认。 |
| JourneyOrderCreated | Journey Order | 用户提交订单。 |
| SegmentReservationRequested | Booking Orchestration | 开始向库存或供应商请求预留。 |
| ProviderReservationConfirmed | Provider Integration | 外部供应商预留成功，仍需 Booking 映射为内部分段事实。 |
| ProviderReservationFailed | Provider Integration | 外部供应商预留失败，仍需 Booking 映射为内部分段事实。 |
| SegmentReservationConfirmed | Booking Orchestration | 某段平台预留成功。 |
| SegmentReservationFailed | Booking Orchestration | 某段平台预留失败。 |
| PaymentAuthorized | Payment | 支付授权成功。 |
| PaymentCaptured | Payment | 收款成功。 |
| EntitlementIssued | Entitlement & Ticketing | 凭证生成。 |
| JourneyPartiallyConfirmed | Booking Orchestration | 多段订单部分成功。 |
| JourneyConfirmed | Journey Order | 整体订单确认。 |
| SegmentCheckInOpened | Fulfillment | 某段可值机或可检票。 |
| SegmentBoarded | Fulfillment | 用户已登乘或上车。 |
| SegmentCompleted | Fulfillment | 某段完成。 |
| TransferAtRisk | Transfer Management | 连接时间不足或异常升高。 |
| ConnectionMissed | Transfer Management | 用户错过接续。 |
| DisruptionPublished | Disruption Recovery | 供应侧异常发布。 |
| ReaccommodationProposed | Disruption Recovery | 替代方案已生成。 |
| ReaccommodationAccepted | Disruption Recovery | 用户接受替代方案。 |
| SegmentCancelled | Post Sales | 某段取消。 |
| RefundRequested | Payment | 退款发起。 |
| RefundSettled | Payment | 退款完成。 |
| ProviderSettlementReconciled | Finance Settlement | 供应商结算完成。 |

## 重构建议

### 第一阶段：建立 General Travel 的稳定主干

先不要把每种交通方式各做一套完整交易系统。建议先稳定这些通用上下文：

1. Place & Network：统一地点、站点、机场、港口、POI。
2. Trip Planning：统一 Trip Intent、Itinerary、Segment、Transfer。
3. Offer Management：统一 Offer、报价快照、有效期、风险提示。
4. Journey Order：统一用户订单和多段订单状态。
5. Booking Orchestration：统一多段确认和补偿编排。
6. Payment：统一支付、退款、预授权和差价。
7. Notification：统一交易和异常通知。

### 第二阶段：保留交通方式特化上下文

在主干稳定后，为每种交通方式建立特化能力：

1. Rail Capacity & Ticketing：车次、席别、区间库存、检票、退改。
2. Air Booking & Fulfillment：航班、PNR、票号、值机、行李、登机。
3. Coach Booking：大巴线路、站点、座位、电子票、检票。
4. Ride Dispatch：派单、司机、车辆、ETA、取消费、行程计费。
5. Ferry Booking：船班、舱房、车辆票、港口登船、天气停航。

这些上下文对上暴露统一的 Segment Booking 和 Entitlement 语义，对内保留各自领域规则。

### 第三阶段：把联乘和异常恢复做成核心能力

General Travel 平台真正区别于单一票务系统的能力，是跨方式组合和异常恢复：

1. Transfer Management 负责最短换乘时间、风险评估和保障契约。
2. Disruption Recovery 负责延误、取消、错过中转后的替代方案。
3. Booking Orchestration 负责多段预订的 Saga 和补偿。
4. Customer Service 负责自动流程无法覆盖的人工兜底。

## 判断边界的实用规则

如果两个能力满足下面任一条件，就倾向拆成不同限界上下文：

1. 使用不同统一语言：例如 `PNR` 和 `司机派单` 不应出现在同一个核心模型里。
2. 不变量不同：火车区间库存和网约车司机匹配不是同一种一致性规则。
3. 状态机不同：航班值机和网约车接驾不是同一个履约状态机。
4. 变更原因不同：航空票规变化不应迫使网约车派单模型修改。
5. 团队或供应商不同：外部航司、铁路、大巴、船司、网约车平台都需要防腐层。
6. 失败补偿不同：多段出票失败、司机取消、航班取消、船班停航的补偿策略不同。

如果两个能力满足下面条件，则可以放在通用上下文：

1. 用户心智一致：用户只关心一个 Journey Order，而不是五种订单。
2. 资金规则一致：收款、退款、预授权、对账可以统一。
3. 通知机制一致：事件触发、模板渲染、发送和重试可以统一。
4. 查询组合一致：Trip Intent、Itinerary、Transfer 可以作为跨方式搜索语言。
5. 售后入口一致：用户从同一订单进入退改，但内部再路由到特化规则。

## 与火车文档的关系

火车业务流仍然是最确定、最适合作为第一版重构基准的交通方式。原因是：

1. 火车有固定班次、固定站点、固定票价和相对确定的履约流程。
2. 火车也包含复杂的区间库存、退改、候补、检票和停运，足够验证核心模型。
3. 一旦火车的 `Journey Order + Segment Booking + Inventory + Payment + Entitlement + Post Sales` 稳定，再扩展到大巴和轮船会更自然。
4. 飞机和网约车的差异更大，适合作为第二阶段验证防腐层和特化上下文。

因此，推荐顺序是：

1. 用火车建出最小但完整的固定班次票务闭环。
2. 加大巴和轮船，验证固定班次但不同库存/凭证的扩展性。
3. 加飞机，验证外部供应商、PNR、值机、行李、复杂票规。
4. 加网约车，验证即时调度、动态报价、司机履约和首末段接驳。
5. 最后强化联乘、中转保护和异常恢复，把平台从单票售卖升级为端到端出行服务。
