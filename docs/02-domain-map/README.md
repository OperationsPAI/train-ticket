# Domain Map 工作台

Last updated: 2026-06-28

## 目的

DDD high-level 设计完成后，下一步采用 map/reduce 方式推进详细设计：

1. Map：每个 agent 负责一个限界上下文，独立产出详细领域设计文档。
2. Reduce：统一检查跨域术语、事件、聚合所有权、Saga、接口契约和冲突。
3. Accept：冲突收敛后，把确认后的设计作为后续重构和需求索引输入。

## 工作规则

1. 一个 agent 一次只负责一个 domain 文件。
2. Map 阶段只编辑 `docs/02-domain-map/domains/<domain>.md`。
3. 不直接改 `docs/01-ddd-high-level`，除非 reduce 阶段已经确认需要回写。
4. 如果发现 high-level 设计冲突，记录到 `docs/03-ddd-reduce/conflict-log.md`。
5. 跨域接口只写自己的发布语言和消费需求，不替其他域做内部设计。

## 输入

每个 domain agent 至少阅读：

1. `docs/01-ddd-high-level/domain-glossary.md`
2. `docs/01-ddd-high-level/context-map.md`
3. `docs/01-ddd-high-level/aggregate-model.md`
4. `docs/01-ddd-high-level/state-machines.md`
5. `docs/01-ddd-high-level/event-storming.md`
6. `docs/01-ddd-high-level/consistency-and-saga.md`
7. `docs/02-domain-map/_domain-design-template.md`

需要理解当前代码迁移影响时，再读：

1. `docs/00-current-state/functional-recovery.md`
2. `docs/00-current-state/service-dependency-map.md`

## 输出

每个 domain 文件必须包含：

1. 领域目标和边界。
2. 负责的业务能力。
3. 不负责的能力。
4. 上游和下游契约。
5. 聚合、实体和值对象。
6. 命令、领域事件和策略。
7. 状态机。
8. 一致性和 Saga 参与点。
9. 读模型和查询需求。
10. 外部供应商或防腐层需求。
11. 当前服务迁移影响。
12. 待 reduce 的冲突或开放问题。

## 状态流转

| 状态 | 含义 |
|---|---|
| not-started | 只有占位文档，尚未设计。 |
| map-draft | domain agent 已完成第一版。 |
| map-reviewed | domain 内部自检通过。 |
| reduce-conflict | reduce 阶段发现跨域冲突。 |
| accepted | reduce 阶段确认可作为实现输入。 |

## 文档入口

领域列表见 `domain-index.md`。每个领域文档位于 `domains/` 目录。
