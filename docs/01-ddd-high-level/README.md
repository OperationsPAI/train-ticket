# DDD High-Level Design

Last updated: 2026-06-28

## 状态

这层文档是当前已经完成的 DDD high-level 设计基线。后续 domain 详细设计和工程任务拆分应把这里作为输入，不直接修改这些文件。

如果后续实现阶段发现高层设计需要调整，应新增或修改 `docs/03-ddd-final/decision-record.md` 中的 decision record，再回写相关文档。

## 文档清单

| 文档 | 作用 |
|---|---|
| `train-ddd-business-flows.md` | 火车票务主干业务流、状态和事件。 |
| `train-business-flow-catalog.md` | 火车完整业务流目录。 |
| `general-travel-ddd.md` | 通用出行 DDD 抽象和交通方式差异。 |
| `domain-glossary.md` | 统一语言。 |
| `context-map.md` | 限界上下文和上下游关系。 |
| `aggregate-model.md` | 聚合根、不变量、命令和事件。 |
| `state-machines.md` | 核心业务对象状态机。 |
| `event-storming.md` | 主链路事件风暴。 |
| `consistency-and-saga.md` | 跨上下文一致性和补偿。 |
| `acl-provider-contracts.md` | 供应商防腐层契约。 |
| `order-inventory-payment-model.md` | 第一阶段核心交易一致性模型。 |

## 对 domain 详细设计的约束

1. 每个领域设计必须使用 `domain-glossary.md` 的术语。
2. 每个领域设计必须尊重 `context-map.md` 的上下游边界。
3. 领域内部可以细化聚合、状态、命令和事件，但不能把其他领域的职责拿进来。
4. 跨域分歧不要直接改 high-level 文档，先写入 reduce 冲突日志。
