# DDD Reduce 工作台

Last updated: 2026-06-28

## 目的

Reduce 阶段负责把 map 阶段的独立 domain 设计收敛成一致的全局 DDD 设计。它不重新发明业务模型，而是检查各 domain 之间是否可以协同。

## 输入

1. `docs/01-ddd-high-level/` 下的 high-level 设计。
2. `docs/02-domain-map/domain-index.md` 中列出的所有 domain 文档。
3. `docs/03-ddd-reduce/conflict-log.md` 中的冲突记录。

## 输出

1. 每个 domain 文档状态推进到 `accepted`，或标记 `reduce-conflict`。
2. 冲突日志有明确处理结果。
3. `integration-checklist.md` 全部通过。
4. 必要时回写 high-level 设计。
5. 形成后续 `project-index.yaml` 和重构任务拆分输入。

## Reduce 检查顺序

1. 统一语言一致性。
2. 上下文边界和聚合所有权。
3. 命令、事件、状态命名。
4. 跨域 Saga 和补偿。
5. 读模型和查询需求。
6. 外部供应商防腐层契约。
7. 当前服务迁移影响。
8. 未解决冲突和工程化待办。

## 决策规则

1. 如果 domain 设计和 high-level 冲突，先判断 high-level 是否过粗或 domain 是否越界。
2. 如果两个 domain 同时声明同一个聚合所有权，必须二选一，另一个只能保存引用或读模型。
3. 如果事件名称相近但语义不同，必须拆开命名。
4. 如果 Saga 补偿依赖人工操作，必须定义人工命令和审计事件。
5. 如果争议无法自动收敛，记录为 reduce 决策项，不隐藏在 domain 文档里。
