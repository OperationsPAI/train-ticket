# 当前跨域设计

更新日期：2026 年 9 月 24 日。

本目录说明当前业务边界、事实所有权和命令路由。
部署基线为 Helm revision 279，发布证据见[当前项目状态](../00-current-status.md)。
功能进度、未实现能力和验收缺口统一见 [TODO](../TODO.md)。

| 文档 | 内容 |
|---|---|
| [当前设计摘要](reduce-summary.md) | 交易、供给、资金、权益和支撑服务之间的关系。 |
| [跨域设计决策](decision-record.md) | 带有稳定 DR 编号的所有权与协作规则。 |
| [业务协作与接口](phase-1-contract.md) | 购票、退改、保票补偿、履约和资金事实路由。 |
| [领域所有权索引](domain-reduce-status.md) | 服务职责、已注册 API 入口和对应源码。 |
| [工作状态入口](implementation-roadmap.md) | TODO 统一入口。 |
| [功能改动路由](change-routing.md) | 按业务事实定位负责服务及相关消费者。 |

服务标识、目录和语言核对 [service-catalog.json](../../service-catalog.json) 与[语言及源码目录](../05-service-architecture/language-selection.md)。
接口名称和可调用范围以各服务实际注册的 handler、controller、router 为依据。
[API 文档](../08-contracts/api/README.md)和[领域文档](../02-domains/README.md)提供详细说明。
[project-index.yaml](../../project-index.yaml)保留需求与文件的引用关系。

这些文件路径供 catalog、project index 和领域文档持续引用。
设计约束表达职责要求，功能是否完成以 TODO 和对应发布证据为准。
Capacity 新联合供给仍未提交、未部署；Notification durable 发送队列为 WIP、未部署，具体工作见 [TODO](../TODO.md)。
