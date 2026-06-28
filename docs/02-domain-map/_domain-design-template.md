# Domain Design Template

Last updated: 2026-06-28

把本模板复制到 `docs/02-domain-map/domains/<domain>.md` 后填写。保持章节顺序，便于 reduce 阶段统一检查。

## Metadata

| Field | Value |
|---|---|
| Domain | TBD |
| Status | not-started |
| Owner Agent | TBD |
| Last Updated | TBD |
| High-Level Inputs | `docs/01-ddd-high-level/domain-glossary.md`, `docs/01-ddd-high-level/context-map.md` |

## 1. 领域目标

说明这个 domain 解决什么业务问题，为什么它是独立边界。

## 2. 边界

### In Scope

- TBD

### Out of Scope

- TBD

## 3. 统一语言补充

只补充本 domain 内部术语。跨域通用术语必须回到 high-level glossary。

| Term | Definition | Notes |
|---|---|---|
| TBD | TBD | TBD |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| TBD | TBD | TBD |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| TBD | TBD | TBD |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| TBD | TBD | TBD | TBD |

## 6. 状态机

列出本 domain 拥有的状态机。不要定义其他 domain 的状态。

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| TBD | TBD | TBD | TBD |

## 8. 策略和 Saga 参与点

说明本 domain 如何响应外部事件，以及会触发哪些跨域命令。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| TBD | TBD | TBD |

## 10. 外部系统和防腐层

说明是否需要供应商适配、支付渠道、通知渠道、后台系统或遗留服务防腐层。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| TBD | TBD |

## 12. Reduce 阶段问题

| Type | Description | Related Contexts | Proposed Resolution |
|---|---|---|---|
| TBD | TBD | TBD | TBD |

## 13. 验收标准

- 本 domain 的聚合所有权明确。
- 本 domain 发布和消费的事件明确。
- 不变量和状态机没有依赖其他 domain 内部状态。
- 与 high-level 设计冲突的地方已进入 reduce 冲突日志。
