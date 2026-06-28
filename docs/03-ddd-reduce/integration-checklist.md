# Reduce Integration Checklist

Last updated: 2026-06-28

## Domain 完整性

- [ ] 每个 P0 domain 都有独立设计文档。
- [ ] 每个 domain 文档状态明确。
- [ ] 每个 domain 都声明 In Scope 和 Out of Scope。
- [ ] 每个 domain 都列出上游和下游契约。
- [ ] 每个 domain 都列出聚合、不变量、命令和事件。
- [ ] 每个 domain 都说明状态机或明确不拥有状态机。
- [ ] 每个 domain 都说明读模型。
- [ ] 每个 domain 都说明当前服务迁移影响。

## 全局一致性

- [ ] 所有文档使用 high-level glossary 的术语。
- [ ] 没有两个 domain 同时拥有同一个聚合。
- [ ] 没有 domain 直接修改其他 domain 的内部状态。
- [ ] 跨域事件名称唯一且语义明确。
- [ ] Saga 每一步都有成功事件、失败事件和补偿策略。
- [ ] Payment、Inventory、Ticketing、Order 状态没有混用。
- [ ] Provider Integration 没有把供应商原始状态泄漏到核心域。
- [ ] Notification 和 Reporting 不参与核心业务决策。

## 接受条件

- [ ] `conflict-log.md` 中没有 unresolved 冲突。
- [ ] P0 domain 全部 accepted。
- [ ] P1 domain 至少完成 map-reviewed，并记录后续实现优先级。
- [ ] 可以基于 domain 文档生成 `project-index.yaml`。
- [ ] 可以基于 accepted domain 文档拆第一阶段重构任务。
