# DDD Reduce Conflict Log

Last updated: 2026-06-28

## 使用规则

Map 阶段发现跨域冲突时，在这里新增记录。Reduce 阶段必须给每条冲突一个明确处理结果。

| ID | Status | Source Domain | Related Domains | Conflict | Proposed Resolution | Decision |
|---|---|---|---|---|---|---|
| C-001 | resolved | initial setup | all | 暂无实际冲突；此行用于固定表结构。 | 后续新增真实冲突时从 C-002 开始。 | keep table format |
