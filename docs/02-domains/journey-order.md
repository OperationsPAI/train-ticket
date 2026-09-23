# Journey Order 当前设计

更新日期：2026年9月24日。
运行基线为 Helm revision 279，服务使用 Java、PostgreSQL 和 Redis 事件处理。
统一待办见 [TODO](../TODO.md)。

## 领域边界

Journey Order 拥有商业订单 `JourneyOrder`、`OrderItem`、旅客和行程快照、`MonetarySummary`、确认条件与订单时间线。
它保存用户接受的商品、账户、金额和外部业务引用。
库存 Hold 由 Capacity 管理，供应侧预订由 Booking 管理，支付与退款由 Payment 管理，票证由 Entitlement Ticketing 管理。
售后规则、差价计算和钱包补偿分别通过 Post Sales、Fare Pricing 和 Wallet Promotion 执行。

## 创建与确认

创建入口读取 Offer 对应的报价和行程，校验 Offer 有效期、版本、账户、旅客集合和行程段集合与请求一致。
旅客类型、实际出发到达时间及金额明细保存在订单快照中。
账户状态检查决定是否允许创建订单。

创建前调用 Identity Verification 的 pre order check，传入稳定的 `orderIntentId`、幂等键，以及报价中的 `productCode` 和 `eligibilityUsages`。
拒绝或要求人工审核的结果阻止订单创建。
成功创建保存身份检查引用，重复创建请求返回原结果，键相同且请求不同返回 `IDEMPOTENCY_KEY_REUSED`。
创建保存失败时会请求释放已取得的身份检查。

`JourneyOrderCreated` 提供预订启动事实。
Booking、Capacity、Payment、Entitlement 和风险结果更新订单确认条件。
`PaymentCaptured` 将支付事实加入确认条件并推进到内部 `CONFIRMING`，商业确认还要求必要的预订、库存、票证和风险条件满足。
订单状态、时间线与 Outbox 在本地事务内保存，外部事件通过持久化消费记录去重。

## 状态与售后结果

聚合状态包括 `PENDING_CONFIRMATION`、`PENDING_PAYMENT`、`CONFIRMING`、`CONFIRMED`、`PARTIALLY_CONFIRMED`、`IN_TRAVEL`、`COMPLETED`、`CANCELLED`、`DISRUPTED`、`FAILED` 和 `POST_SALES_ADJUSTED`。
公开 API 将 `PENDING_CONFIRMATION` 映射为 `CREATED`，将 `POST_SALES_ADJUSTED` 映射为 `ADJUSTED`，其他状态保留对应名称。
订单详情提供商业金额、旅客、行程、附加服务摘要和风险结果。
状态枚举的存在不代表所有交通方式和全部恢复分支都已完成生产验证。

未支付取消记录商业取消事实，外部资源和支付意图由各自服务处理。
普通 `PostSalesApplied` 根据行程段、旅客范围和保留费用更新订单项及商业金额。
范围缺失时使用事件中的订单项引用处理取消。
订单应用结果不替代 Payment 的退款结算状态。

改签通过 `POST /api/v1/journey-orders/{orderId}/apply-change` 接收 `caseId`，由 `OrderChangeService` 核验替代票、原票、资金结算及目标 Offer 后更新订单。
原订单身份继续保留，当前 Offer、行程、票证引用和金额反映已应用的改签。
`PostSalesApplied` 中的 `CHANGE` 由专用改签流程处理。
`COMPENSATION` 完成事件跳过商业取消处理，订单状态、金额、订单项和原票引用保持不变。
补偿额度由 Wallet Promotion 发放，原支付继续保留。

## 原始购买来源

`GET /api/v1/internal/journey-orders/{orderId}/purchase-source` 从持久化的 `JourneyOrderCreated` 时间线事实返回原始已接受 Offer。
返回字段为 `orderId`、`accountId`、`offerId`、`timelineFactId` 和 `acceptedAt`。
改签更新当前 Offer 后，原始购买来源仍保持稳定。
读取不修改订单，也不发布事件。
创建事实缺失、不唯一或缺少 Offer 引用返回 `PRECONDITION_FAILED`，未知订单返回 `NOT_FOUND`。

该来源可用于核对缺少供应引用的初始预订，替代预订使用其改签目标。
Helm revision 279 的 Booking accepted supply 集成尚未在生产启用，原始来源查询本身不证明历史库存已完成核对。

## API 与关联输入

| 入口 | 用途 |
|---|---|
| `POST /api/v1/journey-orders` | 从有效 Offer 创建订单。 |
| `GET /api/v1/journey-orders/{orderId}` | 查询商业金额、旅客、行程、状态和引用。 |
| `GET /api/v1/journey-orders` | 按账户和状态分页查询订单。 |
| `POST /api/v1/journey-orders/{orderId}/cancel` | 请求商业取消。 |
| `POST /api/v1/journey-orders/{orderId}/apply-change` | 应用已核验的改签结果。 |
| `GET /api/v1/internal/journey-orders/{orderId}/purchase-source` | 读取原始购买来源。 |

事件处理还保存账户状态、附加服务、接续关系、身份验证和资格使用结果的关联摘要。
这些输入用于订单展示与本域条件检查，各关联对象的权威状态仍由所属服务维护。

实现入口见 [服务说明](../../services/journey-order/README.md)、[订单应用服务](../../services/journey-order/src/main/java/com/trainticket/journeyorder/application/service/OrderManagementService.java)、[改签应用服务](../../services/journey-order/src/main/java/com/trainticket/journeyorder/application/service/OrderChangeService.java) 和 [HTTP API](../08-contracts/api/journey-order.md)。
