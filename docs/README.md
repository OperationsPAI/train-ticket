# Train Ticket / General Travel 业务设计文档索引

Last updated: 2026-06-28

## 目录结构

这组文档用于支撑 Train Ticket 重构，并为后续 General Travel 平台扩展保留边界。当前组织按 DDD 设计推进阶段划分：

| 目录 | 作用 |
|---|---|
| `docs/00-current-state/` | 当前仓库事实恢复、服务清单、依赖矩阵和腐化点。 |
| `docs/01-ddd-high-level/` | 已完成的 high-level DDD 设计基线。 |
| `docs/02-domain-map/` | map 阶段工作台，每个 domain 一个独立设计文档。 |
| `docs/03-ddd-reduce/` | reduce 阶段工作台，用于跨域集成审查和冲突收敛。 |

## 推荐阅读顺序

1. `docs/00-current-state/functional-recovery.md`
2. `docs/00-current-state/service-dependency-map.md`
3. `docs/01-ddd-high-level/README.md`
4. `docs/01-ddd-high-level/domain-glossary.md`
5. `docs/01-ddd-high-level/context-map.md`
6. `docs/01-ddd-high-level/general-travel-ddd.md`
7. `docs/01-ddd-high-level/train-ddd-business-flows.md`
8. `docs/01-ddd-high-level/train-business-flow-catalog.md`
9. `docs/01-ddd-high-level/aggregate-model.md`
10. `docs/01-ddd-high-level/state-machines.md`
11. `docs/01-ddd-high-level/event-storming.md`
12. `docs/01-ddd-high-level/consistency-and-saga.md`
13. `docs/01-ddd-high-level/acl-provider-contracts.md`
14. `docs/01-ddd-high-level/order-inventory-payment-model.md`
15. `docs/02-domain-map/README.md`
16. `docs/02-domain-map/domain-index.md`
17. `docs/03-ddd-reduce/README.md`

## 文档分层

| 层级 | 文档 | 作用 |
|---|---|---|
| 现状恢复 | `functional-recovery.md`, `service-dependency-map.md` | 从当前代码恢复事实，识别腐化点和依赖链。 |
| 业务流 | `ddd-business-flows.md`, `train-business-flow-catalog.md`, `general-travel-ddd.md` | 回答系统有哪些业务和跨方式出行如何抽象。 |
| DDD 基础 | `domain-glossary.md`, `context-map.md` | 统一语言和上下文边界。 |
| 模型设计 | `aggregate-model.md`, `state-machines.md`, `event-storming.md` | 定义聚合、状态和事件。 |
| 一致性设计 | `consistency-and-saga.md`, `order-inventory-payment-model.md` | 定义交易闭环、补偿和失败恢复。 |
| 外部集成 | `acl-provider-contracts.md` | 定义供应商防腐层和状态映射。 |

## Map/Reduce 设计流程

High-level DDD 设计已经放在 `docs/01-ddd-high-level/`。接下来详细设计按 map/reduce 推进：

1. Map 阶段：每个 agent 认领 `docs/02-domain-map/domain-index.md` 中的一个 domain，只编辑对应 `docs/02-domain-map/domains/<domain>.md`。
2. Domain agent 使用 `docs/02-domain-map/_domain-design-template.md` 输出详细设计。
3. 跨域冲突不直接改 high-level 文档，先写入 `docs/03-ddd-reduce/conflict-log.md`。
4. Reduce 阶段使用 `docs/03-ddd-reduce/integration-checklist.md` 检查所有 domain 设计。
5. Reduce 通过后，再把 accepted 设计转成 `project-index.yaml` 和重构任务。

## 第一阶段重构建议

第一阶段不建议直接做多交通方式全量平台。建议用火车业务实现一个完整、可信的固定班次票务闭环：

1. Offer 报价快照。
2. JourneyOrder 商业订单。
3. SegmentBooking 分段预订。
4. CapacityHold 区间库存锁。
5. PaymentIntent 支付意图。
6. Entitlement 票证权益。
7. PostSalesCase 退票和改签。
8. Outbox/Inbox 和下单 Saga。

完成后再逐步接入大巴、轮船、飞机、网约车和联乘中转。

## 仍未进入本文档集的内容

这些不是业务设计缺口，而是后续工程化产物：

1. `project-index.yaml`：需求索引，等业务设计稳定后再把 P0/P1/P2 需求正式索引化。
2. API 契约：需要在实现语言和服务形态确定后补 OpenAPI、AsyncAPI 或 protobuf。
3. 数据库 schema：需要等聚合和存储策略确定后再设计。
4. 详细 domain 设计：由 `docs/02-domain-map/` 下的 map 阶段文档逐个补齐。
