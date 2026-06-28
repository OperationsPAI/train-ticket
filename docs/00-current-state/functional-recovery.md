# Train Ticket 功能恢复与腐化点盘点

Last updated: 2026-06-28

## 目的

这份文档是重构前的第一版业务恢复说明。它只描述当前代码实际做了什么，不代表合理的目标设计。

恢复原则：

- 代码事实优先于 README 和历史描述。
- 先按业务链路梳理，再按服务列清单。
- 对明显写死、重复、失真的业务逻辑单独标出，作为后续重构输入。
- 暂不创建 `project-index.yaml`。等依赖链和业务边界稳定后，再把这里的条目转成正式需求索引。

## 总体判断

当前系统表面上是 40+ 个微服务，实际业务更像一个被拆散的单体：Controller 多数只是薄转发，Service 层直接用 `RestTemplate` 拼接服务名和 URL 做同步调用，领域规则散落在各个服务里。

最核心的业务主线是：

```text
用户/联系人
  -> 查询车次和余票
  -> 预订车票
  -> 创建订单
  -> 可选保险/餐饮/托运
  -> 支付
  -> 取票/进站
  -> 取消/改签/退款
```

代码里把高铁/动车和普通车拆成两套近似重复的服务：

- 高铁/动车：`ts-travel-service`、`ts-order-service`、`ts-preserve-service`
- 普通车：`ts-travel2-service`、`ts-order-other-service`、`ts-preserve-other-service`

分流规则散落在多个服务里，基本都是 `tripId.startsWith("G") || tripId.startsWith("D")`。这不是稳定的领域模型，而是硬编码路由策略。

## 业务域划分

### 1. 用户与认证

相关服务：

- `ts-auth-service`
- `ts-user-service`
- `ts-contacts-service`
- `ts-admin-user-service`
- `ts-verification-code-service`
- `ts-avatar-service`

当前功能：

- `ts-auth-service` 负责登录、JWT token、账号权限初始化。
- `ts-user-service` 保存用户资料，提供按用户名/用户 ID 查询、注册、删除。
- `ts-contacts-service` 保存乘车联系人，供订票和后台基础信息使用。
- `ts-admin-user-service` 代理 `ts-user-service` 做后台用户管理。
- `ts-verification-code-service` 生成图片验证码，并用本地 Guava cache + cookie 保存验证码。
- `ts-avatar-service` 存在 Dockerfile，但没有在本次 Java API 扫描里看到核心 Spring controller。

腐化点：

- 认证用户和业务用户是两套数据：`ts-auth-service` 和 `ts-user-service` 都初始化 `fdse_microservice`。
- 默认账号、密码、邮箱、UUID 写在 `InitUser` 里。
- 验证码只保存在单实例本地缓存里，多副本部署会失效。
- `ts-user-service` 反向依赖 `ts-auth-service`，用户注册/认证边界不清。

### 2. 基础目录数据

相关服务：

- `ts-station-service`
- `ts-train-service`
- `ts-route-service`
- `ts-price-service`
- `ts-config-service`
- `ts-basic-service`
- `ts-admin-basic-info-service`
- `ts-admin-route-service`
- `ts-admin-travel-service`

当前功能：

- `ts-station-service` 管车站和站名/站 ID 转换。
- `ts-train-service` 管列车类型，包括座位容量和平均速度。
- `ts-route-service` 管路线、经停站、距离。
- `ts-price-service` 管路线 + 车型的计价配置。
- `ts-config-service` 管少量全局配置，比如直达票分配比例。
- `ts-basic-service` 是查询聚合器：校验车站、车型、路线，计算区间价格，返回 `TravelResult`。
- 后台基础信息服务只是代理上述基础服务做 CRUD。

腐化点：

- 车站、路线、车型、价格配置大量由 `CommandLineRunner` 初始化。
- 车型容量使用 `Integer.MAX_VALUE`，真实库存语义失真。
- 价格计算失败时回退到固定值：二等座 `95.0`，一等座 `120.0`。
- `ts-basic-service` 同时承担校验、聚合、价格计算，边界过重。
- 站名既有 `Shang Hai`，又有 `shanghai`，代码中大小写和空格规范不统一。

### 3. 车次查询与旅行规划

相关服务：

- `ts-travel-service`
- `ts-travel2-service`
- `ts-route-plan-service`
- `ts-travel-plan-service`
- `ts-seat-service`

当前功能：

- `ts-travel-service` 管高铁/动车车次，提供车次 CRUD、按路线查车次、查余票、查车次详情。
- `ts-travel2-service` 管普通车车次，接口和 `ts-travel-service` 基本重复。
- `ts-route-plan-service` 对高铁/动车和普通车查询结果做合并，返回最便宜、最快、最少经停的前 5 个方案。
- `ts-travel-plan-service` 在 route-plan 的结果上补充余票，并支持换乘查询。
- `ts-seat-service` 根据订单里已售票据计算余票和分配座位。

典型查询链路：

```text
travel-plan
  -> route-plan
    -> travel-service / travel2-service
      -> basic-service
        -> station-service
        -> train-service
        -> route-service
        -> price-service
      -> seat-service
        -> order-service / order-other-service
        -> config-service
```

腐化点：

- 查询逻辑遍历所有车次，再逐个调用下游服务，缺少可扩展的搜索模型。
- `route-plan` 使用手写选择排序取前 5 个结果。
- 高铁/普通车的分流规则硬编码在多个服务中。
- 余票计算依赖订单服务返回已售 seat set，没有库存表、锁、占座、过期释放模型。
- `travel-plan` 的 `getRestTicketNumber` 把 `destStation` 设置成 start、`startStation` 设置成 end，疑似参数反了。

### 4. 预订与订单

相关服务：

- `ts-preserve-service`
- `ts-preserve-other-service`
- `ts-order-service`
- `ts-order-other-service`
- `ts-seat-service`
- `ts-security-service`
- `ts-assurance-service`
- `ts-food-service`
- `ts-consign-service`
- `ts-user-service`

高铁/动车预订链路：

```text
preserve-service
  -> security-service        检查黄牛/下单限制
  -> contacts-service        查乘车人
  -> travel-service          查车次详情和余票
  -> basic-service           补齐路线、车型、价格
  -> seat-service            分配座位
  -> order-service           创建未支付订单
  -> assurance-service       可选保险
  -> food-service            可选餐饮
  -> consign-service         可选托运
  -> user-service            查用户邮箱，用于通知
```

普通车链路基本相同，只是入口和订单/车次服务换成 `preserve-other-service`、`travel2-service`、`order-other-service`。

订单状态目前由多个服务共同修改：

- 预订：创建 `NOTPAID`
- 支付：改成 `PAID`
- 取票：改成 `COLLECTED`
- 进站：改成 `USED`
- 取消：改成 `CANCEL`
- 改签：改成 `CHANGE`

腐化点：

- `preserve-service` 是同步大编排，跨多个服务产生副作用，没有事务、补偿、幂等键。
- 保险、餐饮、托运失败后，订单已经创建，返回消息只写 `"Success.But ... Fail."`。
- 发送通知的代码被 TODO 注释掉。
- `ts-order-service.create` 会重新生成订单 ID，覆盖上游已经生成的 ID；`order-other` 又保留上游 ID，两边行为不一致。
- 高铁/普通车订单服务重复，但实现细节已经漂移。
- 订单查询过滤逻辑有手写日期过滤，部分字段疑似用错。

### 5. 支付、退款与余额

相关服务：

- `ts-inside-payment-service`
- `ts-payment-service`
- `ts-cancel-service`
- `ts-rebook-service`

当前功能：

- `ts-inside-payment-service` 负责内部余额、支付记录、补差价、退款。
- `ts-payment-service` 模拟站外支付。
- 支付时先查订单，只有 `NOTPAID` 可支付。余额不足时调用站外支付。
- 支付成功后通过订单服务修改订单状态。
- 取消和改签都直接调用 inside-payment 进行退款或补差价。

腐化点：

- 余额不是账户余额表，而是充值流水和支付流水临时相减。
- 多处 `BigDecimal.add(...)` 没有赋值，余额计算存在明显 bug 风险。
- 站外支付永远只是保存一条记录，没有真实支付状态机。
- 支付成功与订单状态更新不是事务。
- 退款规则写死：未支付退款 0；发车后退款 0；发车前退票按票价 80% 退款。

### 6. 取消、改签、取票、进站

相关服务：

- `ts-cancel-service`
- `ts-rebook-service`
- `ts-execute-service`
- `ts-order-service`
- `ts-order-other-service`
- `ts-inside-payment-service`

当前功能：

- `cancel-service` 先查高铁订单，找不到再查普通车订单；状态允许时取消并退款。
- `rebook-service` 检查原订单状态、改签时间窗口、新车次余票和新旧票价差额。
- `execute-service` 执行取票和进站：`PAID/CHANGE -> COLLECTED -> USED`。

腐化点：

- 所有状态跳转散在各服务里，没有统一的订单状态机。
- `execute-service` 直接把传入 headers 置空，绕过认证传播。
- `rebook-service` 删除订单时对 DELETE endpoint 使用 POST 调用。
- 改签跨高铁/普通车时会删除原订单再在另一边创建新订单，没有失败补偿。
- 改签只允许一次、发车后两小时等规则写在代码里，不可配置。

### 7. 餐饮与配送

相关服务：

- `ts-food-service`
- `ts-train-food-service`
- `ts-station-food-service`
- `ts-food-delivery-service`
- `ts-delivery-service`

当前功能：

- `ts-food-service` 查询列车餐和经停车站餐饮店，创建餐饮订单，并通过 RabbitMQ 发送配送消息。
- `ts-train-food-service` 提供车上餐食。
- `ts-station-food-service` 提供车站餐饮店。
- `ts-food-delivery-service` 管餐饮配送单。
- `ts-delivery-service` 接收 MQ 并保存一般配送信息。

腐化点：

- 餐饮类型用数字区分，语义散落在 preserve/food 服务。
- `food-service` 查询路线只调用 `ts-travel-service`，普通车路线可能被漏掉。
- 站点列表裁剪时边遍历边 remove，容易跳项。
- 配送消息和餐饮订单保存没有事务一致性。

### 8. 托运与保险

相关服务：

- `ts-consign-service`
- `ts-consign-price-service`
- `ts-assurance-service`

当前功能：

- `ts-consign-service` 保存托运单，并调用 `ts-consign-price-service` 计算价格。
- `ts-consign-price-service` 以 index 0 的配置记录计算初始重量、区域内/区域外价格。
- `ts-assurance-service` 管保险类型和订单保险记录。

腐化点：

- 托运计价配置固定使用 `repository.findByIndex(0)`，没有版本、区域、路线维度。
- 保险类型固定在枚举/代码中，缺少产品配置模型。
- 预订链路里托运/保险失败不回滚订单。

### 9. 等候订单

相关服务：

- `ts-wait-order-service`
- `ts-preserve-service`

当前功能：

- 创建 waitlist order 后启动 `PollThread` 轮询。
- 查询全部订单或只查询未支付/已支付的等待队列订单。
- `PollThread` 依赖 preserve 服务尝试订票。

腐化点：

- 每个等待订单启动独立线程，不适合多实例部署和重启恢复。
- TODO 提到需要比较 `waitUntilTime`，说明等待超时逻辑不完整。
- 没有统一排队、优先级、库存释放事件或消息驱动模型。

### 10. 通知与消息

相关服务：

- `ts-notification-service`
- `ts-preserve-service`
- `ts-preserve-other-service`
- `ts-cancel-service`
- `ts-food-service`
- `ts-delivery-service`

当前功能：

- notification 提供邮件发送 endpoint 和 MQ receiver。
- preserve/cancel 构造 `NotifyInfo`，但实际发送被注释或 TODO。
- food 通过 MQ 发配送消息，delivery 侧接收。

腐化点：

- 通知链路不完整，部分同步 HTTP、部分 MQ、部分直接注释。
- 消息体是 JSON 字符串，缺少 schema、幂等键、重试语义。

## 服务清单

| 服务 | 当前职责 | 主要依赖 | 腐化风险 |
|---|---|---|---|
| `ts-auth-service` | 登录、JWT、认证用户 | verification-code | 与 user-service 重复用户数据 |
| `ts-user-service` | 业务用户资料 | auth-service | 用户注册边界不清 |
| `ts-contacts-service` | 乘车联系人 | 无 | 示例联系人写死 |
| `ts-verification-code-service` | 图片验证码 | 无 | 单实例本地缓存 |
| `ts-station-service` | 车站 CRUD 和 ID/name 转换 | 无 | 站名规范混乱 |
| `ts-train-service` | 车型、席位容量、平均速度 | 无 | 容量写成无限大 |
| `ts-route-service` | 路线、经停站、距离 | 无 | 路线数据写死 |
| `ts-price-service` | 路线 + 车型计价配置 | 无 | 价格规则过薄 |
| `ts-config-service` | 全局配置 | 无 | 配置项极少且缺 schema |
| `ts-basic-service` | 查询聚合与区间价格计算 | station, train, route, price | 聚合过重，默认价格写死 |
| `ts-travel-service` | 高铁/动车车次查询和详情 | basic, seat, route, train | 与 travel2 重复 |
| `ts-travel2-service` | 普通车车次查询和详情 | basic, seat, route, train | 与 travel 重复 |
| `ts-seat-service` | 座位分配、余票计算 | order, order-other, config | 随机分配，无库存锁 |
| `ts-order-service` | 高铁/动车订单 | station | 与 order-other 重复且行为漂移 |
| `ts-order-other-service` | 普通车订单 | station | 与 order 重复 |
| `ts-preserve-service` | 高铁/动车订票编排 | security, contacts, travel, basic, seat, order, assurance, food, consign, user | 巨型同步编排 |
| `ts-preserve-other-service` | 普通车订票编排 | security, contacts, travel2, basic, seat, order-other, assurance, food, consign, user | preserve 重复 |
| `ts-inside-payment-service` | 内部余额、支付、退款、补差价 | order, order-other, payment | 余额模型和事务不可靠 |
| `ts-payment-service` | 外部支付模拟 | 无 | 只是保存支付记录 |
| `ts-cancel-service` | 取消订单、退款 | order, order-other, inside-payment, user, notification | 退款规则写死 |
| `ts-rebook-service` | 改签、补差价/退款 | order, order-other, travel, travel2, seat, train, route, inside-payment | 跨库迁移无补偿 |
| `ts-execute-service` | 取票、进站 | order, order-other | 清空 headers，状态机散落 |
| `ts-security-service` | 下单限流/黄牛检查 | order, order-other | 默认阈值为无限大 |
| `ts-route-plan-service` | 最便宜/最快/最少站方案 | route, travel, travel2 | 手写排序和前 5 固定 |
| `ts-travel-plan-service` | 高级旅行方案、换乘、补余票 | route-plan, travel, travel2, seat, train | 参数疑似反置 |
| `ts-food-service` | 餐饮查询、餐饮订单、配送消息 | train-food, station-food, travel | 普通车路线可能漏查 |
| `ts-train-food-service` | 车上餐食 | 无 | 静态餐食数据 |
| `ts-station-food-service` | 车站餐饮店 | 无 | 静态餐饮店数据 |
| `ts-food-delivery-service` | 餐饮配送单 | station-food | 配送状态模型薄 |
| `ts-delivery-service` | 普通配送消息接收和保存 | 无 | MQ 消息缺幂等 |
| `ts-consign-service` | 托运单 | consign-price | 计价维度过少 |
| `ts-consign-price-service` | 托运价格配置 | 无 | 固定 index 0 |
| `ts-assurance-service` | 保险类型和订单保险 | 无 | 产品规则硬编码 |
| `ts-wait-order-service` | 候补订单 | preserve | 线程轮询，不适合分布式 |
| `ts-admin-basic-info-service` | 后台基础数据代理 | contacts, station, train, config, price | 纯代理，聚合边界弱 |
| `ts-admin-order-service` | 后台订单代理 | order, order-other | 聚合两个订单库 |
| `ts-admin-route-service` | 后台路线代理 | route, station | 只做薄校验 |
| `ts-admin-travel-service` | 后台车次代理 | travel, travel2, station, train, route | 高铁/普通车分流硬编码 |
| `ts-admin-user-service` | 后台用户代理 | user | 纯代理 |
| `ts-news-service` | 新闻公告 Go 服务 | 未纳入 Java 链路 | 与主链路弱关联 |
| `ts-ticket-office-service` | Node 票务点服务 | 未纳入 Java 链路 | 旧 Express 栈，和主链路弱关联 |
| `ts-ui-dashboard` | Angular 静态前端 | 各后端 API | 旧前端资产 |
| `ts-voucher-service` | 票据/凭证服务 | 未见核心 Java API | 需单独确认 |
| `ts-avatar-service` | 用户头像服务 | 未见核心 Java API | 需单独确认 |

## 当前依赖矩阵

这部分来自代码里的 `getServiceUrl("...")` 调用。

| 调用方 | 被调用服务 |
|---|---|
| `ts-admin-basic-info-service` | config, contacts, price, station, train |
| `ts-admin-order-service` | order, order-other |
| `ts-admin-route-service` | route, station |
| `ts-admin-travel-service` | route, station, train, travel, travel2 |
| `ts-admin-user-service` | user |
| `ts-auth-service` | verification-code |
| `ts-basic-service` | price, route, station, train |
| `ts-cancel-service` | inside-payment, notification, order, order-other, user |
| `ts-consign-service` | consign-price |
| `ts-execute-service` | order, order-other |
| `ts-food-delivery-service` | station-food |
| `ts-food-service` | station-food, train-food, travel |
| `ts-inside-payment-service` | order, order-other, payment |
| `ts-order-service` | station |
| `ts-order-other-service` | station |
| `ts-preserve-service` | assurance, basic, consign, contacts, food, order, seat, security, station, travel, user |
| `ts-preserve-other-service` | assurance, basic, consign, contacts, food, order-other, seat, security, station, travel2, user |
| `ts-rebook-service` | inside-payment, order, order-other, route, seat, train, travel, travel2 |
| `ts-route-plan-service` | route, travel, travel2 |
| `ts-seat-service` | config, order, order-other |
| `ts-security-service` | order, order-other |
| `ts-travel-plan-service` | route-plan, seat, train, travel, travel2 |
| `ts-travel-service` | basic, route, seat, train |
| `ts-travel2-service` | basic, route, seat, train |
| `ts-user-service` | auth |
| `ts-wait-order-service` | preserve |

## 重点腐化问题

### 1. 高铁/普通车是复制粘贴出来的边界

重复服务：

- `travel-service` vs `travel2-service`
- `order-service` vs `order-other-service`
- `preserve-service` vs `preserve-other-service`

分流散落在：

- `seat-service`
- `inside-payment-service`
- `rebook-service`
- `route-plan-service`
- `admin-travel-service`
- `preserve*`

建议目标：把“车次类型”变成领域字段和路由策略，取消服务级复制。

### 2. 订票缺少事务边界

订票链路一次同步调用多个服务。任何中间失败都可能留下半成品：

- 订单已创建，但保险失败。
- 订单已创建，但餐饮失败。
- 订单已创建，但托运失败。
- 通知根本没有发送。

建议目标：先定义订单聚合根和订单生命周期，再决定使用 Saga、事件驱动还是本地事务 + outbox。

### 3. 订单状态机没有中心

订单状态由 payment/cancel/rebook/execute/order 服务共同修改。规则分散后，很难判断某个状态能不能进入下一个状态。

建议目标：集中定义状态机：

```text
NOTPAID -> PAID -> COLLECTED -> USED
NOTPAID -> CANCEL
PAID -> CANCEL
PAID -> CHANGE
CHANGE -> COLLECTED
```

然后把退款、改签、取票、进站都约束到这个状态机上。

### 4. 座位库存不可信

当前余票来自订单服务的已售票集合。座位分配用随机数，靠扫描 sold tickets 避免冲突，没有并发锁、占座、过期释放、库存版本。

建议目标：

- 引入 seat inventory / ticket inventory 概念。
- 预订阶段先创建 hold，占座过期自动释放。
- 支付成功后 hold 转 confirmed。
- 取消/改签释放库存。

### 5. 业务配置和样例数据混在代码里

写死数据包括：

- 默认用户、管理员、密码。
- 联系人、订单、支付记录。
- 车站、路线、车次、车型、价格配置。
- `DirectTicketAllocationProportion=0.5`。
- 安全限制为 `Integer.MAX_VALUE`。
- 托运价格固定 index 0。

建议目标：把 seed data 迁移到版本化 SQL/YAML/fixture，业务规则迁移到配置表或策略代码。

### 6. API 契约弱

常见问题：

- `Response.status` 用 `0/1/2` 表示业务状态，语义不统一。
- GET endpoint 修改状态，例如支付后改订单状态、取票/进站。
- typo 已进入契约：`refound`、`confortClass`、`serivce`。
- 部分接口缺少前导 `/`，如 config/contact/travel-plan 的 base mapping。
- `RestTemplate.exchange(..., Response.class)` 后再手动 JSON 转对象，类型安全弱。

建议目标：先冻结现有 API contract，再逐步迁移到明确 DTO + HTTP status + OpenAPI contract。

### 7. 安全和认证传播不稳定

`RestTemplateConfig` 已经提供 JWT 传播，但部分服务手动复制 header，`execute-service` 甚至直接把 headers 设为 null。

建议目标：所有内部调用统一用一个 client wrapper，禁止业务代码直接拼 URL 和手动处理认证头。

## 重构建议顺序

### Phase 0：冻结当前事实

产物：

- 本文档。
- 服务依赖图。
- 核心链路时序图。
- 关键 API contract 快照。

注意：这一步仍然不改业务。

### Phase 1：建立领域词汇表

先统一这些概念：

- User / Account / AuthUser
- Passenger / Contact
- TrainType / TrainNumber / Trip
- Route / Station / Stop
- SeatClass / SeatInventory / Ticket
- Order / Payment / Refund / Rebook
- FoodOrder / Consign / Assurance

输出一份 `docs/01-ddd-high-level/domain-glossary.md`，作为后续 `project-index.yaml` 的前置材料。

### Phase 2：绘制依赖链并标出聚合边界

重点链路：

- 查询余票链路
- 订票链路
- 支付链路
- 取消退款链路
- 改签链路
- 取票/进站链路
- 餐饮配送链路
- 候补订单链路

输出一份 `docs/00-current-state/service-dependency-map.md`。

### Phase 3：合并重复业务模型

先不急着合并服务部署单元，先合并代码里的业务抽象：

- 高铁/普通车订单用同一个订单模型。
- 高铁/普通车车次用同一个 trip model。
- 分流规则从 `startsWith("G") || startsWith("D")` 改为可配置 train category。

### Phase 4：重做订单状态机和库存模型

这是核心业务重构，不应和代码清理混在一起。

先定义：

- 状态机。
- 座位库存。
- 占座和过期释放。
- 支付成功/失败回调。
- 取消/改签补偿。

### Phase 5：再创建 `project-index.yaml`

等依赖链和目标模型确认后，把本文档拆成需求条目：

- 查询类需求
- 订票类需求
- 支付类需求
- 订单状态类需求
- 库存类需求
- 后台管理类需求
- 通知/配送类需求

## 未确认项

这些服务或模块需要下一轮单独确认：

- `ts-voucher-service`
- `ts-avatar-service`
- `ts-news-service`
- `ts-ticket-office-service`
- `ts-ui-dashboard` 与后端 API 的真实调用覆盖面
- Helm chart 中哪些服务实际部署，哪些只是历史残留
- 当前生产/实验环境是否依赖这些 seed data

## 参考代码入口

核心编排：

- `ts-preserve-service/src/main/java/preserve/service/PreserveServiceImpl.java`
- `ts-preserve-other-service/src/main/java/preserveOther/service/PreserveOtherServiceImpl.java`
- `ts-rebook-service/src/main/java/rebook/service/RebookServiceImpl.java`
- `ts-cancel-service/src/main/java/cancel/service/CancelServiceImpl.java`
- `ts-execute-service/src/main/java/execute/serivce/ExecuteServiceImpl.java`

查询与库存：

- `ts-travel-service/src/main/java/travel/service/TravelServiceImpl.java`
- `ts-travel2-service/src/main/java/travel2/service/TravelServiceImpl.java`
- `ts-basic-service/src/main/java/fdse/microservice/service/BasicServiceImpl.java`
- `ts-seat-service/src/main/java/seat/service/SeatServiceImpl.java`
- `ts-route-plan-service/src/main/java/plan/service/RoutePlanServiceImpl.java`
- `ts-travel-plan-service/src/main/java/travelplan/service/TravelPlanServiceImpl.java`

支付与订单：

- `ts-order-service/src/main/java/order/service/OrderServiceImpl.java`
- `ts-order-other-service/src/main/java/other/service/OrderOtherServiceImpl.java`
- `ts-inside-payment-service/src/main/java/inside_payment/service/InsidePaymentServiceImpl.java`
- `ts-payment-service/src/main/java/com/trainticket/service/PaymentServiceImpl.java`

写死数据：

- `ts-station-service/src/main/java/fdse/microservice/init/InitData.java`
- `ts-route-service/src/main/java/route/init/InitData.java`
- `ts-train-service/src/main/java/train/init/InitData.java`
- `ts-travel-service/src/main/java/travel/init/InitData.java`
- `ts-travel2-service/src/main/java/travel2/init/InitData.java`
- `ts-price-service/src/main/java/price/init/InitData.java`
- `ts-config-service/src/main/java/config/init/InitData.java`
- `ts-auth-service/src/main/java/auth/init/InitUser.java`
- `ts-user-service/src/main/java/user/init/InitUser.java`
