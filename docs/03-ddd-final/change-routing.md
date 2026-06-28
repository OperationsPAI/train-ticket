# Feature Change Routing

Last updated: 2026-06-28

## 目的

这份文档回答“后续新增功能时应该改哪里”。原则是先定位业务事实的 owner，再决定需要改哪些 domain 文档、命令、事件和读模型，避免把逻辑写回某个顺手的服务里。

## 改动路由规则

1. 改业务状态，先找 owning aggregate。
2. 改跨域协作，新增或修改领域事件和命令，不直接写别的 domain。
3. 改展示查询，优先改 read model，不反向污染写模型。
4. 改外部供应商或渠道，先改 Provider Integration ACL，再映射成内部事件。
5. 改人工处理，必须经过 Customer Service 或 Admin & Audit，再进入目标 domain 命令。
6. 改财务口径，先改 Finance Settlement；Reporting 只消费结果。

## Feature 到 Domain 路由表

| Feature / Change | Primary Domain | Also Touch | Do Not Put In |
|---|---|---|---|
| 新增车站、站点别名、供应商站点码 | Place & Network | Provider Integration, Service Plan | Trip Planning |
| 新增车次、停靠、运行日历、计划停运 | Service Plan | Place & Network, Capacity | Journey Order |
| 新增停售、限售、渠道配额 | Capacity & Availability | Offer Management, Admin & Audit | Service Plan |
| 新增票价、退改费、儿童票规则 | Fare & Pricing | Traveler Profile, Offer Management, Post Sales | Payment |
| 新增搜索排序、无票解释、邻近站推荐 | Trip Planning | Place & Network, Capacity, Fare | Offer Management |
| 新增报价有效期、风险披露、改签报价 | Offer Management | Fare & Pricing, Transfer Management, Journey Order | Trip Planning |
| 新增订单字段、订单项、订单汇总状态 | Journey Order | Booking, Payment, Entitlement, Post Sales | Payment |
| 新增预订步骤、补偿步骤、部分成功策略 | Booking Orchestration | Capacity, Provider Integration, Payment, Entitlement | Journey Order |
| 新增库存 Hold 规则、区间复用、配额释放 | Capacity & Availability | Service Plan, Booking, Post Sales | Offer Management |
| 新增支付渠道、回调、退款渠道 | Payment | Provider Integration, Finance Settlement | Journey Order |
| 新增供应商 API、供应商状态码映射 | Provider Integration | Booking, Capacity, Payment, Fulfillment | Core domains raw status |
| 新增出票凭证、作废、冻结、票面展示 | Entitlement & Ticketing | Booking, Fulfillment, Post Sales | Payment |
| 新增检票、进站、NoShow、离线核验 | Fulfillment | Entitlement, Risk, Customer Service | Journey Order |
| 新增退票、改签、差价、手续费、售后流程 | Post Sales | Fare, Payment, Capacity, Entitlement, Journey Order | Payment |
| 新增交易通知、模板、发送策略、回执 | Notification | Account, Journey Order, Payment, Post Sales | Source domain state machine |
| 新增乘车人证件、资质、出行偏好 | Traveler Profile | Account, Risk, Fare | Journey Order |
| 新增风控规则、重复行程限制、实名拦截 | Risk & Compliance | Account, Traveler, Payment, Journey Order | Source domain state |
| 新增客服工单、代用户操作、投诉证据 | Customer Service | Admin & Audit, target domain | Direct database update |
| 新增后台权限、审批、审计、人工修正 | Admin & Audit | Customer Service, target domain | Target domain invariants |
| 新增供应商合同、承运商、产品能力基线 | Supplier Catalog | Provider Integration, Fare, Service Plan | Provider runtime health |
| 新增收入确认、发票、对账、汇率 | Finance Settlement | Payment, Fare, Post Sales, Reporting | Reporting |
| 新增经营报表、KPI、漏斗分析 | Reporting | Finance, source events | Transaction write model |
| 新增候补 | Waitlist | Capacity, Payment, Booking, Notification | Capacity internals |
| 新增钱包、积分、券、组合支付 | Wallet / Promotion | Payment, Account, Finance, Post Sales | Account or Payment |
| 新增网约车派单、司机 ETA、司机取消 | Dispatch | Provider Integration, Fulfillment, Payment | Service Plan |
| 新增保障联乘权益 | Transfer Management | Offer, Disruption Recovery, Finance, Customer Service | Trip Planning |
| 新增异常恢复、保护性改乘、批量停运处理 | Disruption Recovery | Service Plan, Offer, Post Sales, Notification | Service Plan |
| 新增保险、餐饮、行李、选座 | Ancillary Service | Offer, Journey Order, Fare, Post Sales | Main ticket entitlement |

## 常见改动示例

### 增加一种新退票规则

改动路径：

1. `Fare & Pricing`：新增或修改 FareRuleSet / RefundFee 计算。
2. `Post Sales`：消费新规则快照，调整 PostSalesCase 决策。
3. `Payment`：只接收最终 Refund 请求，不判断规则。
4. `Finance Settlement`：如果影响收入冲减口径，更新财务策略。

不应改：Payment 里硬编码退票手续费。

### 增加一个支付渠道

改动路径：

1. `Provider Integration`：新增渠道 adapter、签名验签、Webhook raw archive。
2. `Payment`：新增 channel contract 映射、PaymentIntent / Refund 幂等处理。
3. `Finance Settlement`：新增渠道账单输入和对账映射。

不应改：Journey Order 状态机。

### 增加候补

改动路径：

1. `Waitlist`：实现 WaitlistRequest、队列、公平性、兑现策略。
2. `Capacity & Availability`：发布 CapacityReleased，接受 Waitlist 授权 Hold。
3. `Payment`：处理候补预授权或扣款。
4. `Booking Orchestration`：候补命中后复用正常 Booking。
5. `Notification`：发送候补状态变化。

不应改：Capacity 内部临时维护候补队列。

### 增加网约车

改动路径：

1. `Dispatch`：新增 RideRequest、RideAssignment、司机生命周期。
2. `Provider Integration`：接入网约车平台 API。
3. `Fulfillment`：消费 DriverArrived、RideStarted、RideEnded。
4. `Payment`：支持预授权和行程结束 Capture。
5. `Fare & Pricing`：等待费、取消费、预估价和最终价规则。

不应改：Service Plan 固定班次模型。

### 增加保障联乘

改动路径：

1. `Transfer Management`：新增 ProtectedConnection 合同和责任边界。
2. `Offer Management`：展示和冻结保障披露，保存 acceptedDisclosureIds。
3. `Disruption Recovery`：错过接续后生成恢复方案和责任成本。
4. `Finance Settlement`：归集平台承担的恢复成本。
5. `Customer Service`：人工兜底和解释。

不应改：Trip Planning 直接承诺费用责任。

## 修改文档的最低要求

新增功能时至少更新：

1. 对应 `docs/02-domains/<domain>.md`。
2. 如果跨域边界变化，更新 `docs/03-ddd-final/decision-record.md`。
3. 如果进入或退出第一阶段范围，更新 `docs/03-ddd-final/domain-reduce-status.md`。
4. 如果影响第一阶段链路验收，更新 `docs/03-ddd-final/phase-1-contract.md`。
5. 如果会影响任务拆分，更新 `docs/03-ddd-final/implementation-roadmap.md`。
