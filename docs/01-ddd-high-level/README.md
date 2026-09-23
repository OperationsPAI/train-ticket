# Business design index

Last updated: 2026-09-24.

The implementation oriented design starts with [cross domain ownership](../03-ddd-final/README.md) and [domain details](../02-domains/README.md).
[Current status](../00-current-status.md) records the actual release and measured verification.
[TODO](../TODO.md) is the single remaining work list.

| Document | Purpose |
|---|---|
| [Context map](context-map.md) | Owning services and collaboration boundaries. |
| [Aggregates](aggregate-model.md) | State ownership and financial separation. |
| [State models](state-machines.md) | Implemented state definitions and interface mappings. |
| [Consistency](consistency-and-saga.md) | Transactions, replay and post sales execution. |
| [Order and inventory](order-inventory-payment-model.md) | Purchase facts and supply boundaries. |
| [Events](event-storming.md) | Main command and event relationships. |
| [Terminology](domain-glossary.md) | Business vocabulary; concrete wire fields are defined by the service APIs. |
| [Train flow catalog](train-business-flow-catalog.md) | Business scenario taxonomy, not implementation certification. |
| [Detailed train flows](train-ddd-business-flows.md) | Business analysis, not deployed endpoint or event definitions. |
| [General travel](general-travel-ddd.md) | General travel concepts; support for a mode requires implementation evidence. |
| [Provider integration](acl-provider-contracts.md) | Supplier boundary concepts; actual operations are in owning API and event references. |

Conceptual scenario catalogs do not override current endpoint names, state enums or deployed boundaries.
Design changes must accompany corresponding code and interface changes.
