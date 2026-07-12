# Train Ticket DDD 业务流梳理

Last updated: 2026-06-28

## 目的

这份文档面向后续整套系统重构。它不讨论 Java、Go、Node、数据库、中间件或部署形态，只先回答三个问题：

1. 这个系统到底有哪些业务能力。
2. 每条核心业务流里，谁发起、谁决策、谁记录事实。
3. 哪些领域事件和状态变化应该成为未来系统的稳定契约。

本文是设计输入，不是最终服务拆分方案。后续是否做微服务、模块化单体、多语言实现，应在业务边界稳定后再决定。

完整文档入口见 `docs/README.md`。完整火车业务流全景目录见 `docs/01-ddd-high-level/train-business-flow-catalog.md`。如果要从火车扩展到大巴、网约车、飞机、轮船和联乘场景，通用出行 DDD 边界见 `docs/01-ddd-high-level/general-travel-ddd.md`。本文继续聚焦火车票务重构时最需要先稳定的主干链路、状态模型和领域事件。

## 设计基准

当前代码里的服务边界不等于领域边界。重构时应以业务语义为中心，而不是沿用 `travel-service`、`order-service`、`preserve-service` 这类历史拆分。

本轮先采用 DDD 的几个约束：

- 用统一语言描述业务，不用当前接口名当业务名。
- 聚合负责保护一致性，不让状态被多个上下文随意修改。
- 跨边界协作用领域事件表达，不把远程调用链当业务流程本身。
- 查询模型和写入模型分开考虑。查票可以高度优化，订票必须守住一致性。
- 高铁、动车、普速车是车次分类，不应该复制出两套业务流。

## 参与角色

| 角色 | 说明 |
|---|---|
| 旅客 | 实际乘车人，可以和购票账号不是同一个人。 |
| 购票账号 | 登录系统、管理联系人、下单、支付、取消、改签。 |
| 管理员 | 维护车站、路线、车次、车型、价格、配置、用户和订单。 |
| 支付渠道 | 内部余额或外部支付机构。 |
| 票务执行方 | 取票、验票、进站、出行完成等线下履约角色。 |
| 餐饮/配送方 | 处理车上餐、车站餐和配送履约。 |
| 通知渠道 | 邮件、短信、站内信或其他消息通道。 |

## 候选限界上下文

这些上下文是业务边界，不等于未来一定要拆成独立服务。

| 上下文 | 核心职责 | 关键对象 |
|---|---|---|
| Identity & Customer | 账号、认证、旅客联系人、权限 | Account, AuthCredential, PassengerContact |
| Timetable & Catalog | 车站、路线、车型、车次、运行图、价格规则 | Station, Route, TrainType, Train, Trip, FareRule |
| Availability & Inventory | 余票、座位库存、占座、释放 | SeatInventory, SeatHold, SeatAssignment |
| Booking & Order | 订单生命周期、订单行、订单状态机 | BookingOrder, TicketItem, OrderStatus |
| Payment & Settlement | 支付、余额、退款、差价、支付结果 | PaymentIntent, Payment, Refund, BalanceAccount |
| Fulfillment | 取票、验票、进站、乘车使用 | Ticket, TicketCollection, TicketUsage |
| Change & Cancellation | 退票、取消、改签、补偿规则 | Cancellation, Rebooking, RefundQuote |
| Ancillary Sales | 保险、餐饮、托运等附加服务 | AssuranceOrder, FoodOrder, ConsignOrder |
| Notification | 业务通知、消息模板、发送记录 | NotificationRequest, DeliveryRecord |
| Backoffice | 后台维护、审核、运营配置 | AdminOperation, ChangeRequest |

## 统一语言草案

| 术语 | 定义 |
|---|---|
| Account | 系统登录和支付主体。 |
| Passenger | 实际乘车人。 |
| PassengerContact | Account 保存的乘车人资料。 |
| Trip | 某一天或某个运行模板下的一趟列车服务。 |
| Route | 一条经停站序列及站间距离。 |
| TrainType | 车型能力，如速度、席别容量、可用席别。 |
| FareQuote | 针对出发站、到达站、日期、车次、席别的价格报价。 |
| SeatInventory | 某车次、日期、席别、区间上的可售库存。 |
| SeatHold | 临时占座。未支付前存在有效期。 |
| BookingOrder | 购票订单，承载票项、状态、金额和履约要求。 |
| TicketItem | 订单中的一张票，绑定旅客、车次、区间、席别、座位。 |
| PaymentIntent | 待支付请求。 |
| RefundQuote | 退款试算结果。 |
| Rebooking | 改签请求和结果。 |
| TicketCollection | 取票动作。 |
| TicketUsage | 进站或乘车使用动作。 |
| AncillaryOrder | 附加服务订单，如保险、餐饮、托运。 |

## 顶层业务流

系统的主流程可以分成 12 条业务流：

1. 账号注册与联系人维护。
2. 基础运营数据维护。
3. 行程搜索与车次方案推荐。
4. 报价与余票确认。
5. 创建订票订单与占座。
6. 支付与出票确认。
7. 附加服务购买。
8. 取票与进站使用。
9. 取消订单与退款。
10. 改签与差价结算。
11. 候补订单。
12. 通知与运营审计。

下面逐条展开。

## Flow 1：账号注册与联系人维护

### 触发

- 用户注册账号。
- 用户登录。
- 用户新增、修改、删除乘车联系人。
- 管理员维护用户。

### 主流程

```text
用户提交注册信息
  -> 校验验证码和账号唯一性
  -> 创建 Account
  -> 创建认证凭据
  -> 发布 AccountRegistered

用户维护联系人
  -> 校验联系人身份证件、手机号等资料
  -> 绑定到 Account
  -> 发布 PassengerContactAdded / PassengerContactUpdated / PassengerContactRemoved
```

### 业务规则

- 一个 Account 可以有多个 PassengerContact。
- PassengerContact 可以是本人，也可以是他人。
- 认证身份和业务用户资料应有明确映射，不能各自初始化一套用户。
- 验证码、登录失败限制、密码策略属于认证策略，不应该混入订票流程。

### 领域事件

- `AccountRegistered`
- `AccountAuthenticated`
- `PassengerContactAdded`
- `PassengerContactUpdated`
- `PassengerContactRemoved`
- `AccountDisabled`

### 设计决策

- 允许未登录用户查询车次，但下单、候补、售后和保存联系人必须登录。
- 允许同一个证件被多个 Account 保存为联系人；实际购票前按证件、姓名和乘车规则做实名与冲突校验。
- 管理员不得直接修改用户敏感信息；只能通过受控命令发起冻结、解冻、纠错或脱敏查看，并写入审计。

## Flow 2：基础运营数据维护

### 触发

- 管理员维护车站、路线、车型、车次、价格规则、运行配置。
- 运营方发布新的运行图或价格表。

### 主流程

```text
管理员提交基础数据变更
  -> 校验引用关系
  -> 校验时间、站序、距离、价格规则
  -> 保存为草稿或立即生效
  -> 发布对应变更事件
  -> 触发查询模型和库存模型刷新
```

### 业务规则

- Station 是基础字典，必须有稳定标识和规范名称。
- Route 是有序经停站列表，站间距离必须单调递增。
- TrainType 描述能力，不应承载具体日期库存。
- Trip 描述一趟车的运行计划，引用 Route 和 TrainType。
- FareRule 应按路线、车次分类、席别、时间窗口生效，不应只靠硬编码费率。
- 运营数据变更需要生效时间，不能无版本覆盖。

### 领域事件

- `StationCreated`
- `RoutePublished`
- `TrainTypeConfigured`
- `TripScheduled`
- `TripCancelled`
- `FareRulePublished`
- `OperationalConfigChanged`

### 设计决策

- 运营模型必须支持临时停运、晚点、改线和停售；这些变化进入 `DisruptionCase` 或 `Operation Plan`，不能写死在查询服务。
- 车次计划按运行图定义，实际售卖和库存按具体日期生成 `TripInstance`。
- 价格规则按版本化 `FareRule` 管理，第一阶段支持固定票价和优惠规则，节假日、浮动定价、折扣作为规则扩展。

## Flow 3：行程搜索与车次方案推荐

### 触发

- 用户输入出发站、到达站、日期。
- 用户请求最便宜、最快、最少经停或换乘方案。

### 主流程

```text
用户提交出发站、到达站、日期
  -> 校验车站存在
  -> 搜索直达 Trip
  -> 过滤已停运、不可售、过期车次
  -> 计算到发时间和运行时长
  -> 查询报价和余票摘要
  -> 按策略排序
  -> 返回 TripOffer 列表
```

换乘查询：

```text
用户提交出发站、到达站、日期、可选中转站
  -> 生成第一段候选 TripOffer
  -> 生成第二段候选 TripOffer
  -> 校验换乘时间窗口
  -> 合并为 TransferOffer
```

### 业务规则

- 搜索结果应该返回 Offer，而不是直接返回订单或库存实体。
- 过期日期和已发车车次不可售。
- 排序策略是业务策略：最低价、最短耗时、最少经停、最早到达等。
- 余票摘要可以近实时，但创建订单时必须重新确认库存。
- 车次分类是 Trip 的属性，不应该决定走另一套业务流程。

### 领域事件

查询本身通常不产生领域事件，但可以产生审计或分析事件：

- `TripSearched`
- `TripOfferViewed`
- `RoutePlanRequested`

### 设计决策

- 查询侧支持模糊站名、城市站群和同城多站，统一放在 `Place & Network`。
- 支持自动中转推荐；中转站可以由 `Trip Planning` 生成，用户不需要手动指定。
- 余票展示允许短时缓存，但下单前必须通过 `Offer` 重新确认可售性和价格。

## Flow 4：报价与余票确认

### 触发

- 用户选择某个车次、区间、席别。
- 系统准备进入订票。
- 改签时查询新车次。

### 主流程

```text
用户选择 TripOffer
  -> 重新校验 Trip、Route、SeatClass、FareRule
  -> 计算 FareQuote
  -> 查询 SeatInventory
  -> 返回 ConfirmableOffer
```

### 业务规则

- FareQuote 必须包含价格、币种、税费或附加费用、有效期。
- SeatInventory 必须按日期、车次、区间、席别计算。
- ConfirmableOffer 有时效，不能长期复用。
- 查询到有余票不代表创建订单一定成功，最终以占座结果为准。

### 领域事件

- `FareQuoted`
- `AvailabilityChecked`

### 设计决策

- 需要短有效期锁价，具体时长由 `Offer` 配置，过期后必须重新报价。
- 儿童票、学生票和其他优惠票进入 `Traveler Profile` 与 `FareRule`，第一阶段至少预留资格校验和规则版本。
- 车厢、靠窗、连座、铺位等作为座席偏好处理；偏好尽力满足，不作为默认强承诺，除非 Offer 明确承诺。

## Flow 5：创建订票订单与占座

### 触发

- 用户点击提交订单。

### 主流程

```text
用户提交订票请求
  -> 校验 Account
  -> 校验 PassengerContact
  -> 校验购票限制
  -> 重新确认 Trip 和 FareQuote
  -> 请求 SeatHold
  -> 创建 BookingOrder
  -> 绑定 TicketItem
  -> 设置订单状态为 PendingPayment
  -> 发布 BookingOrderCreated
  -> 返回支付信息和支付截止时间
```

### 业务规则

- 一个 BookingOrder 可以包含一张或多张 TicketItem。当前系统基本是一单一票，但目标模型不应被这个限制绑死。
- SeatHold 必须有过期时间。
- 订单创建和占座必须保持一致：没有占座就不应创建可支付订单。
- 防黄牛规则应是明确策略，如单位时间下单数、未使用票数、异常取消率。
- 下单请求必须可幂等，避免重复点击生成多笔订单。
- 附加服务不应该影响主票订单创建的原子性。它们可以作为后续附加购买或订单扩展。

### 领域事件

- `SeatHeld`
- `SeatHoldFailed`
- `BookingOrderCreated`
- `BookingOrderCreationRejected`
- `TicketItemAdded`
- `PaymentDeadlineAssigned`

### 失败与补偿

| 失败点 | 处理 |
|---|---|
| 乘车人无效 | 拒绝创建订单 |
| 购票限制不通过 | 拒绝创建订单 |
| 价格过期 | 要求重新报价 |
| 占座失败 | 拒绝创建订单 |
| 订单创建失败但已占座 | 释放 SeatHold |

## Flow 6：支付与出票确认

### 触发

- 用户对 PendingPayment 订单发起支付。
- 支付渠道返回支付结果。
- 支付超时。

### 主流程

```text
用户发起支付
  -> 创建 PaymentIntent
  -> 选择内部余额或外部支付渠道
  -> 支付渠道处理
  -> 记录 PaymentSucceeded 或 PaymentFailed
  -> 支付成功后确认 SeatHold
  -> BookingOrder 状态改为 Paid
  -> 生成正式 Ticket
  -> 发布 TicketIssued
```

支付超时：

```text
支付截止时间到达
  -> 关闭 PaymentIntent
  -> 取消 BookingOrder
  -> 释放 SeatHold
  -> 发布 BookingOrderExpired
```

### 业务规则

- PaymentIntent 是支付请求，不等于支付成功。
- 支付成功和订单确认必须有一致性保护。
- 外部支付回调必须幂等。
- 内部余额和外部支付渠道应共享同一个支付结果语义。
- 支付失败不一定取消订单，只有超时或用户取消才释放占座。

### 领域事件

- `PaymentIntentCreated`
- `PaymentSucceeded`
- `PaymentFailed`
- `PaymentExpired`
- `SeatHoldConfirmed`
- `BookingOrderPaid`
- `TicketIssued`
- `BookingOrderExpired`

### 失败与补偿

| 失败点 | 处理 |
|---|---|
| 内部余额不足 | 转外部支付或提示充值 |
| 外部支付失败 | 保持订单待支付，直到支付截止 |
| 支付成功但确认占座失败 | 进入人工处理或自动退款 |
| 重复支付回调 | 幂等返回已处理结果 |

## Flow 7：附加服务购买

### 触发

- 用户在订票时或出票后选择保险、餐饮、托运。

### 主流程

```text
用户选择附加服务
  -> 校验主票订单和 TicketItem
  -> 校验服务可售范围
  -> 创建 AncillaryOrder
  -> 如需支付，创建 PaymentIntent
  -> 支付或确认成功后发布 AncillaryOrderConfirmed
```

餐饮：

```text
选择车上餐或车站餐
  -> 校验车次和经停站
  -> 创建 FoodOrder
  -> 通知餐饮/配送履约
```

托运：

```text
提交托运信息
  -> 校验重量、路线、区域
  -> 计算托运费用
  -> 创建 ConsignOrder
```

保险：

```text
选择保险产品
  -> 校验产品和订单
  -> 创建 AssuranceOrder
```

### 业务规则

- 附加服务订单应和主票订单有关联，但不应破坏主票订单的一致性。
- 附加服务可以有独立取消和退款规则。
- 餐饮服务依赖经停站和配送窗口。
- 托运服务依赖重量、路线、区域和办理时间。
- 保险服务依赖产品条款和乘车人。

### 领域事件

- `AncillaryOrderCreated`
- `AssuranceSelected`
- `FoodOrderPlaced`
- `ConsignOrderPlaced`
- `AncillaryOrderConfirmed`
- `AncillaryOrderCancelled`
- `DeliveryRequested`

### 设计决策

- 附加服务允许在出票后追加，但必须形成独立 `AncillaryOrder`，不能修改主票交易事实。
- 主票取消时，附加服务按各自规则联动取消；不能假设全部自动退款。
- 餐饮、托运、保险等支持部分退款能力，但是否可退和费用由对应附加服务规则决定。

## Flow 8：取票与进站使用

### 触发

- 用户取票。
- 票务执行方验票。
- 旅客进站或乘车。

### 主流程

```text
用户请求取票
  -> 校验 BookingOrder 已支付或已改签确认
  -> 校验 Ticket 未取票、未取消、未使用
  -> 标记 TicketCollected
  -> 发布 TicketCollected

旅客进站
  -> 校验 Ticket 已取票
  -> 校验日期、车次、区间
  -> 标记 TicketUsed
  -> 发布 TicketUsed
```

### 业务规则

- 只有已出票且未取消的票可以取票。
- 只有已取票的票可以进站使用，除非未来支持电子票直接验票。
- 已使用票不能取消、不能改签。
- 取票和进站应记录操作时间、地点和执行方。

### 领域事件

- `TicketCollected`
- `TicketCollectionRejected`
- `TicketUsed`
- `TicketUsageRejected`

### 设计决策

- 电子票优先，纸质取票作为 `Entitlement` 的一种展示或核验方式，不作为履约必需条件。
- 需要出站或到达事件，用于行程完成、收入确认、越站检查和售后限制。
- 部分区间乘车、越站、车上补票作为 `Fulfillment` 和 `Post Sales` 扩展流处理，第一阶段至少保留事件和状态位。

## Flow 9：取消订单与退款

### 触发

- 用户取消未支付订单。
- 用户退票。
- 系统因支付超时取消订单。
- 管理员强制取消。

### 主流程

```text
用户请求取消订单
  -> 校验订单存在
  -> 校验订单状态允许取消
  -> 计算 RefundQuote
  -> 取消 TicketItem
  -> 释放库存或作废 Ticket
  -> 创建 Refund
  -> BookingOrder 状态改为 Cancelled
  -> 发布 BookingOrderCancelled
```

支付超时取消：

```text
PaymentExpired
  -> BookingOrder 自动取消
  -> SeatHold 自动释放
  -> 不产生退款
```

### 业务规则

- 未支付订单取消不退款，只释放占座。
- 已支付未使用订单可以按规则退款。
- 已取票、已使用、已过发车时间的退款规则需要明确。
- 退款金额应由 RefundQuote 决定，不能散落在取消服务里。
- 退款处理和订单取消必须幂等。

### 领域事件

- `RefundQuoted`
- `BookingOrderCancelled`
- `TicketCancelled`
- `SeatReleased`
- `RefundRequested`
- `RefundSucceeded`
- `RefundFailed`

### 设计决策

- 退票手续费按发车前时间段、票种、渠道和异常原因配置化，规则版本进入 `RuleSnapshot`。
- 改签后的票仍可按新票规则退票，但需记录改签历史并应用对应限制。
- 附加服务不随主票无条件自动退款；由 `Ancillary Service` 按服务规则和履约状态处理。

## Flow 10：改签与差价结算

### 触发

- 用户对已支付订单发起改签。

### 主流程

```text
用户请求改签
  -> 校验原订单和原 Ticket
  -> 校验改签次数和时间窗口
  -> 搜索新 TripOffer
  -> 生成 RebookingQuote
  -> 请求新 SeatHold
  -> 计算差价
  -> 差价为正：要求补款
  -> 差价为负：创建退款
  -> 确认新 SeatHold
  -> 取消原 Ticket
  -> 创建新 Ticket
  -> 原 BookingOrder 标记为 Changed 或追加改签记录
  -> 发布 TicketRebooked
```

补差价流程：

```text
RebookingQuoteCreated
  -> PaymentIntentCreated
  -> PaymentSucceeded
  -> RebookingConfirmed
```

退差价流程：

```text
RebookingQuoteCreated
  -> RefundRequested
  -> RebookingConfirmed
```

### 业务规则

- 改签只改变车次、日期、席别、座位，不改变乘车人和出发/到达区间，除非业务明确允许。
- 一个 Ticket 是否只能改签一次，需要作为政策配置。
- 新票确认前，原票不应提前作废，避免失败后用户两边都没票。
- 改签跨车次分类不应导致跨订单库迁移。目标模型里订单和票应统一。
- 改签是一个有状态过程，不只是一次更新订单。

### 领域事件

- `RebookingRequested`
- `RebookingQuoteCreated`
- `RebookingPaymentRequired`
- `RebookingRefundRequired`
- `RebookingConfirmed`
- `RebookingRejected`
- `TicketRebooked`

### 失败与补偿

| 失败点 | 处理 |
|---|---|
| 原票不可改签 | 拒绝 |
| 新票无库存 | 拒绝 |
| 补差价失败 | 保留原票 |
| 新票确认失败 | 释放新占座，保留原票，必要时退款 |
| 原票取消失败 | 保留人工处理标记 |

## Flow 11：候补订单

### 触发

- 用户在无票时提交候补请求。
- 库存释放。
- 候补等待时间到期。

### 主流程

```text
用户提交候补请求
  -> 校验账号、乘车人、车次、席别、区间
  -> 创建 WaitlistRequest
  -> 可选预授权或预支付
  -> 进入等待队列
  -> 发布 WaitlistRequested

库存释放
  -> 匹配等待队列
  -> 为最高优先级请求创建 SeatHold
  -> 通知用户支付或自动确认
```

候补过期：

```text
等待截止时间到达
  -> WaitlistRequest 标记 Expired
  -> 释放预授权或退款
  -> 发布 WaitlistExpired
```

### 业务规则

- 候补不是线程轮询，而是队列和事件驱动的业务流程。
- 优先级规则必须明确：提交时间、会员等级、是否全程票、是否接受无座等。
- 候补可以和支付绑定，也可以只在有票后通知用户支付。
- 候补成功后应复用正常订票流程。

### 领域事件

- `WaitlistRequested`
- `WaitlistMatched`
- `WaitlistConfirmed`
- `WaitlistExpired`
- `WaitlistCancelled`

### 设计决策

- 候补支持预支付或预授权，具体方式由候补规则和支付渠道能力决定。
- 允许多个车次或席别组成候补方案，但同一乘车人同一出行意图下成功兑现后必须取消互斥候补。
- 允许部分匹配和降级席别，但必须在候补 Offer 中提前声明并由用户选择授权。

## Flow 12：通知与运营审计

### 触发

- 账号、订单、支付、改签、取消、附加服务、候补等领域事件发生。

### 主流程

```text
领域事件发生
  -> 通知策略判断是否需要通知
  -> 生成 NotificationRequest
  -> 选择渠道和模板
  -> 发送通知
  -> 记录 DeliveryRecord
```

### 业务规则

- 通知不应阻塞核心订单交易。
- 通知必须幂等，避免重复发送。
- 通知失败应可重试和人工补发。
- 审计记录应保留谁在何时触发了关键业务变更。

### 领域事件

- `NotificationRequested`
- `NotificationSent`
- `NotificationFailed`
- `AdminOperationRecorded`

## 关键状态模型

### 订单状态

目标状态应围绕业务语义，而不是当前整数码：

```text
Draft
  -> PendingPayment
  -> Paid
  -> Fulfilled
  -> Completed

PendingPayment
  -> Expired
  -> Cancelled

Paid
  -> Cancelled
  -> RebookingInProgress

RebookingInProgress
  -> Paid
  -> Cancelled

Fulfilled
  -> Completed
```

解释：

- `Draft`：订单尚未形成可支付承诺。
- `PendingPayment`：已占座，等待支付。
- `Paid`：已支付，已出票或待出票确认。
- `Fulfilled`：已取票或已进入履约状态。
- `Completed`：乘车完成。
- `Cancelled`：用户或系统取消。
- `Expired`：支付超时自动关闭。
- `RebookingInProgress`：改签过程状态，避免把改签简化成一次订单覆盖。

### 座位库存状态

```text
Available
  -> Held
  -> Confirmed
  -> Used

Held
  -> Released
  -> Expired

Confirmed
  -> Released by cancellation
  -> Replaced by rebooking
```

### 支付状态

```text
PaymentIntentCreated
  -> Pending
  -> Succeeded
  -> Failed
  -> Expired
  -> Refunded
```

## 领域事件目录

| 事件 | 来源上下文 | 主要消费者 |
|---|---|---|
| `AccountRegistered` | Identity & Customer | Notification, Backoffice |
| `PassengerContactAdded` | Identity & Customer | Booking |
| `TripScheduled` | Timetable & Catalog | Search, Inventory |
| `FareRulePublished` | Timetable & Catalog | Search, Booking |
| `AvailabilityChecked` | Availability & Inventory | Search |
| `SeatHeld` | Availability & Inventory | Booking, Payment |
| `SeatHoldExpired` | Availability & Inventory | Booking |
| `BookingOrderCreated` | Booking & Order | Payment, Notification |
| `PaymentSucceeded` | Payment & Settlement | Booking, Inventory, Notification |
| `PaymentFailed` | Payment & Settlement | Booking, Notification |
| `TicketIssued` | Booking & Order | Fulfillment, Notification |
| `TicketCollected` | Fulfillment | Booking, Notification |
| `TicketUsed` | Fulfillment | Booking |
| `RefundQuoted` | Change & Cancellation | Payment |
| `BookingOrderCancelled` | Change & Cancellation | Inventory, Payment, Ancillary, Notification |
| `RefundSucceeded` | Payment & Settlement | Booking, Notification |
| `RebookingRequested` | Change & Cancellation | Inventory, Payment |
| `RebookingConfirmed` | Change & Cancellation | Booking, Notification |
| `FoodOrderPlaced` | Ancillary Sales | Delivery, Notification |
| `ConsignOrderPlaced` | Ancillary Sales | Fulfillment, Notification |
| `WaitlistRequested` | Availability & Inventory | Notification |
| `WaitlistMatched` | Availability & Inventory | Booking, Notification |
| `NotificationSent` | Notification | Audit |

## 业务流之间的依赖方向

建议依赖方向：

```text
Identity & Customer
  -> Booking & Order

Timetable & Catalog
  -> Search
  -> Availability & Inventory
  -> Booking & Order

Availability & Inventory
  -> Booking & Order
  -> Waitlist

Booking & Order
  -> Payment & Settlement
  -> Fulfillment
  -> Change & Cancellation
  -> Ancillary Sales
  -> Notification

Payment & Settlement
  -> Booking & Order
  -> Change & Cancellation

Ancillary Sales
  -> Delivery/Fulfillment
  -> Notification
```

注意：这是业务协作方向，不是代码 import 方向。未来实现时，跨上下文应优先通过事件、命令或明确的防腐层交互。

## 未来重构切入点

### 第一优先级：订单、库存、支付三角

这是整个系统最核心的一致性边界。建议先把以下业务流设计稳定：

- 创建订单与占座。
- 支付成功与出票。
- 支付超时释放占座。
- 取消订单与退款。
- 改签与差价结算。

### 第二优先级：车次目录和搜索报价

稳定基础概念：

- Station
- Route
- TrainType
- Trip
- FareRule
- TripOffer
- FareQuote

再决定查询侧如何实现高性能搜索。

### 第三优先级：附加服务和通知

保险、餐饮、托运应作为附加服务上下文，不应塞进订票主交易。通知应由事件驱动，不阻塞订单。

## 配套设计文档

1. `docs/README.md`：业务设计文档索引和推荐阅读顺序。
2. `docs/01-ddd-high-level/train-business-flow-catalog.md`：完整火车业务流全景目录。
3. `docs/01-ddd-high-level/general-travel-ddd.md`：通用出行业务流和 DDD 边界，覆盖大巴、网约车、飞机、轮船和联乘中转。
4. `docs/01-ddd-high-level/domain-glossary.md`：统一领域词汇表。
5. `docs/01-ddd-high-level/context-map.md`：限界上下文和上下游关系。
6. `docs/01-ddd-high-level/aggregate-model.md`：聚合根、不变量、命令和领域事件。
7. `docs/01-ddd-high-level/state-machines.md`：核心业务对象状态机。
8. `docs/01-ddd-high-level/event-storming.md`：命令、事件、策略、读模型。
9. `docs/01-ddd-high-level/consistency-and-saga.md`：跨上下文一致性、Saga 和补偿。
10. `docs/01-ddd-high-level/acl-provider-contracts.md`：供应商防腐层和状态映射。
11. `docs/01-ddd-high-level/order-inventory-payment-model.md`：订单、库存、支付、出票核心一致性模型。

`project-index.yaml` 仍建议稍后创建。等这些业务设计被确认后，再把 P0/P1/P2 能力索引化为正式重构任务。
