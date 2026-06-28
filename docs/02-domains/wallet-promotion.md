# Wallet / Promotion Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Wallet / Promotion |
| Status | accepted-ddd-baseline |
| Phase | future-scope |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/03-ddd-final/decision-record.md`, `docs/03-ddd-final/domain-reduce-status.md`, `docs/02-domains/payment.md`, `docs/02-domains/finance-settlement.md` |

## 1. 领域目标

Wallet / Promotion 负责非现金权益，包括钱包余额、储值、积分、优惠券、补偿券、营销券和权益核销。它不是 Account 的子对象，也不是 Payment 的渠道状态。

独立建模的原因是：非现金权益有发行、冻结、核销、撤销、过期、会计归属和补偿成本问题。如果放进 Account，会把展示身份和资金权益混在一起；如果放进 Payment，会让现金通道与平台权益混在一起。

## 2. 边界

### In Scope

- 钱包余额、储值、积分、券包和补偿权益的生命周期。
- 权益发行、冻结、核销、撤销、过期。
- 与订单、售后、异常恢复、客服补偿的权益引用。
- 非现金权益的财务事件输出。

### Out of Scope

- 现金支付、预授权、原路退款，归 Payment。
- 登录主体和账号状态，归 Account。
- 收入确认、清结算、对账和发票，归 Finance Settlement。
- 补偿原因和责任归属，归 Post Sales / Disruption Recovery / Customer Service。

## 3. 聚合

| Aggregate | Invariants |
|---|---|
| WalletAccount | 同一 account 或 traveler 的余额账本必须可追溯；冻结和可用余额不能为负；每次变更必须有业务原因。 |
| PromotionInstrument | 券、积分、补偿权益必须有发行来源、适用范围、有效期、核销规则和撤销规则。 |
| BenefitRedemption | 同一权益同一业务原因不能重复核销；核销失败不能静默丢失。 |

## 4. 上游和下游契约

| Direction | Context | Contract |
|---|---|---|
| Upstream | Account | `WalletRef`、用户展示关系。 |
| Upstream | Post Sales / Disruption Recovery | 补偿决策、费用减免、权益发放原因。 |
| Upstream | Journey Order / Offer Management | 可用权益查询和报价展示引用。 |
| Downstream | Payment | 组合支付中的现金剩余应付金额。 |
| Downstream | Finance Settlement | 权益发行、核销、过期、撤销和成本归集事件。 |
| Downstream | Notification | 权益到账、过期提醒和核销结果通知。 |

## 5. 状态机

### PromotionInstrument

| State | Meaning | Next |
|---|---|---|
| Issued | 已发放。 | Reserved, Redeemed, Expired, Revoked |
| Reserved | 已为订单或售后临时冻结。 | Redeemed, Released, Expired |
| Redeemed | 已核销。 | Reversed |
| Released | 冻结释放。 | Reserved, Redeemed, Expired |
| Expired | 已过期。 | - |
| Revoked | 已撤销。 | - |
| Reversed | 已冲正。 | - |

## 6. 命令和事件

| Command | Event |
|---|---|
| IssueBenefit | BenefitIssued |
| ReserveBenefit | BenefitReserved |
| RedeemBenefit | BenefitRedeemed |
| ReleaseReservedBenefit | BenefitReservationReleased |
| ExpireBenefit | BenefitExpired |
| RevokeBenefit | BenefitRevoked |
| ReverseRedemption | BenefitRedemptionReversed |

## 7. Final DDD Decision

Wallet / Promotion 已裁定为独立 future-scope 上下文。第一阶段只做现金支付、原路退款和有审计的人工现金补偿，不做积分、券、钱包余额和组合支付。
