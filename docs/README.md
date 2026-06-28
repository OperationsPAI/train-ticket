# Train Ticket / General Travel DDD 文档索引

Last updated: 2026-06-28

## 当前入口

当前 DDD 设计已经完成收敛。最终文档从下面六份开始读：

1. `docs/03-ddd-final/reduce-summary.md`
2. `docs/03-ddd-final/decision-record.md`
3. `docs/03-ddd-final/phase-1-contract.md`
4. `docs/03-ddd-final/domain-reduce-status.md`
5. `docs/03-ddd-final/implementation-roadmap.md`
6. `docs/03-ddd-final/change-routing.md`

## 文档分层

| 层级 | 文档 | 作用 |
|---|---|---|
| Final DDD | `docs/03-ddd-final/*.md` | 当前跨域边界、第一阶段契约、最终裁决和 domain 状态。 |
| Domain Details | `docs/02-domains/*.md` | 每个 domain 的详细设计。 |
| High-level DDD | `docs/01-ddd-high-level/*.md` | 统一语言、上下文地图、聚合、状态机、事件和 Saga。 |

## 第一阶段重构建议

第一阶段不做多交通方式全量平台，而是先把火车固定班次票务闭环做可信：

1. Search 不锁库存，Offer 冻结报价、规则、风险和可用性快照。
2. JourneyOrder、SegmentBooking、CapacityHold、PaymentIntent、Entitlement 分开建模。
3. PaymentCaptured 不直接改订单、出票、库存或通知。
4. 已出票退票必须先作废 Entitlement，再发起 Refund。
5. 改签不能删旧单建新单，必须走 replacement booking 和旧权益作废。
6. 旧服务副作用必须通过 Legacy ACL 转成受控命令。

## 后续工程化产物

这些不属于当前 DDD 文档本身：

1. `project-index.yaml`：基于 Final DDD 生成需求索引。
2. API 契约：在实现语言和服务形态确定后补 OpenAPI、AsyncAPI 或 protobuf。
3. 数据库 schema：在聚合和存储策略确定后设计。
4. 任务拆分：从 `implementation-roadmap.md`、`phase-1-contract.md` 和 `domain-reduce-status.md` 生成。
