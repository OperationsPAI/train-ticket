# DDD Final Design

Last updated: 2026-06-28

## 目的

这个目录保存当前 DDD map/reduce 后的最终设计入口。中间过程文档已经收敛到最终裁决，不再保留未裁决台账。

## 最终文档

| 文档 | 作用 |
|---|---|
| `reduce-summary.md` | 当前最终结论、第一阶段范围和全局决策摘要。 |
| `decision-record.md` | 所有跨域冲突和范围问题的最终裁决。 |
| `phase-1-contract.md` | 第一阶段火车票务重构的业务契约、链路、失败处理和验收 gate。 |
| `domain-reduce-status.md` | 28 个 domain 的最终状态。 |
| `implementation-roadmap.md` | 从 DDD 设计拆第一阶段工程任务的 work package。 |
| `change-routing.md` | 后续新增功能时应该修改哪个 domain 的路由表。 |

## 阅读顺序

1. `reduce-summary.md`
2. `decision-record.md`
3. `phase-1-contract.md`
4. `domain-reduce-status.md`
5. `implementation-roadmap.md`
6. `change-routing.md`

## 使用规则

1. 后续详细设计和重构任务以 `decision-record.md` 为跨域边界权威。
2. 第一阶段工程任务以 `phase-1-contract.md` 为验收契约。
3. domain 优先级和是否进入第一阶段以 `domain-reduce-status.md` 为准。
4. 第一阶段 issue 和 `project-index.yaml` 以 `implementation-roadmap.md` 为拆分输入。
5. 新功能需求先查 `change-routing.md`，再改对应 domain 文档。
6. 如果实现阶段发现新的跨域矛盾，新增 decision record，而不是恢复中间过程台账。
