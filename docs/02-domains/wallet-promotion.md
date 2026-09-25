# Wallet Promotion 当前设计

更新日期：2026年9月24日。
运行基线为 Helm revision 279，服务使用 Java 和 PostgreSQL。
统一待办见 [TODO](../TODO.md)。

## 领域边界

Wallet Promotion 拥有 `PromotionInstrument`、`WalletAccount`、`WalletBalance`、`BenefitRedemption` 和钱包账务记录。
当前服务支持权益发放、预留、核销、释放、撤销、核销冲正和到期处理。
账户身份由 Account 管理，现金支付和原路退款由 Payment 执行，补偿决策与金额由 Post Sales 提供。
Wallet 保存权益及余额变化，通过事件向其他服务提供事实。
这些接口本身不代表订单已经接入组合支付。

## 权益与余额

`benefitType` 支持 `BALANCE`、`COUPON`、`COMPENSATION_CREDIT`、`POINTS`。
`balanceType` 支持 `STORED_VALUE`、`POINTS`、`PROMOTION_CREDIT`。
每个账户按余额类型和币种记录 available、reserved 和 redeemed 金额。
金额使用 `currency` 与整数 `minorUnits`，跨币种算术被拒绝，可用和预留余额不得为负。
每次变更必须带有业务原因，并保存权益、钱包余额、账务记录及事件。

| 状态 | 当前含义 |
|---|---|
| `ISSUED` | 权益已发放，可按适用范围和有效期使用。 |
| `RESERVED` | 金额已为指定业务引用预留。 |
| `REDEEMED` | 权益已核销。 |
| `RELEASED` | 预留金额已经释放。 |
| `EXPIRED` | 权益已过期。 |
| `REVOKED` | 权益已撤销。 |
| `REVERSED` | 核销已经冲正。 |

核销检查状态、有效期、金额、单次使用及是否要求预留等规则。
适用范围保存在权益中，当前核销入口不解析外部订单来核验该范围。
同一权益和业务原因的核销通过记录去重。
到期扫描默认每5秒运行，更新符合条件的权益并发布 `BenefitExpired`。

## 售后补偿

Post Sales 通过 `POST /api/v1/benefits` 发放已批准的 `COMPENSATION`。
接收方为原订单账户，金额等于冻结的 `compensationAmount`。
请求携带 `benefitType=COMPENSATION_CREDIT`、`balanceType=PROMOTION_CREDIT`、`issuanceSource=POST_SALES_COMP` 和规范 `caseId`。
当前 Post Sales 请求使用 `ANY_TRIP`、允许多次使用且无需预留，并固定30天有效期。
Wallet 使用请求提供的有效期，不自行决定补偿期限。
零金额由 Post Sales 直接完成，不调用权益发放接口。
补偿保留原票、座位预订、订单和已捕获支付。

`POST_SALES_COMP` 按 `caseId` 和完整 `IssueBenefitCommand` 去重，与 HTTP 幂等键独立。
相同请求返回已有权益，不增加余额、账务记录或 `BenefitIssued`。
账户、金额、币种、类型、规则、有效期或业务原因发生变化时返回 HTTP 409 `CONFLICT`。
已有权益缺少原始发放请求时同样返回冲突。
PostgreSQL 使用 Case 和账户事务锁、`compensation_issuances` 及唯一索引，保证一次发放在同一事务中保存完整记录。

## API 与事件

| 入口 | 用途 |
|---|---|
| `POST /api/v1/benefits` | 发放权益。 |
| `GET /api/v1/benefits/{benefitId}` | 查询单个权益。 |
| `GET /api/v1/internal/benefits/by-case/{caseId}` | 只读查询该 Post Sales case 的唯一补偿权益，供崩溃恢复与审计使用。 |
| `GET /api/v1/benefits?byAccountId={accountId}` | 按账户分页查询，可过滤状态和类型。 |
| `POST /api/v1/benefits/{benefitId}/reserve` | 预留金额。 |
| `POST /api/v1/benefits/{benefitId}/redeem` | 核销金额。 |
| `POST /api/v1/benefits/{benefitId}/release` | 释放预留金额。 |
| `POST /api/v1/benefits/{benefitId}/revoke` | 撤销权益。 |
| `POST /api/v1/benefits/{benefitId}/reverse-redemption` | 冲正已有核销。 |
| `GET /api/v1/wallet-accounts/{accountId}` | 查询账户钱包余额。 |

HTTP 写请求要求 `Idempotency-Key`，复用键且请求不同返回 `IDEMPOTENCY_KEY_REUSED`。
领域事件包括 `BenefitIssued`、`BenefitReserved`、`BenefitRedeemed`、`BenefitReservationReleased`、`BenefitRevoked`、`BenefitRedemptionReversed` 和 `BenefitExpired`。
事件与余额变更在本地事务中保存，通过 Outbox 发布。
售后补偿发放成本由 Finance 消费 `BenefitIssued` 记录，补偿批准与完成事件不重复计入成本。

Post Sales 在发放前先按 canonical `caseId` 使用内部查询检查既有权益。
如果 Wallet 已提交而 Post Sales 尚未保存 `issued`，它复用现有权益而不再次发放。
若已存在权益与批准账户、金额、类型或 case 不一致，Post Sales 写入终态 mismatch checkpoint 并停止自动重放。

实现入口见 [服务说明](../../services/wallet-promotion/README.md)、[应用服务](../../services/wallet-promotion/src/main/java/com/trainticket/walletpromotion/application/WalletPromotionService.java) 和 [HTTP API](../08-contracts/api/wallet-promotion.md)。
