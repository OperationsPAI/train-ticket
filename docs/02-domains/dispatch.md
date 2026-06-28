# Dispatch Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Dispatch |
| Status | accepted-ddd-baseline |
| Phase | future-scope |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/03-ddd-final/decision-record.md`, `docs/03-ddd-final/domain-reduce-status.md`, `docs/01-ddd-high-level/general-travel-ddd.md` |

## 1. 领域目标

Dispatch 负责即时网约车、即时接送、司机匹配、车辆分配、ETA、司机取消、等待费和行程开始前的动态供给确认。它不套用固定班次 Service Plan，也不归 Fulfillment。

独立建模的原因是：即时供给没有固定车次、固定座席和固定运行日历。司机接单、到达、等待、取消和重新派单是动态调度生命周期，和固定班次库存 Hold 是不同问题。

## 2. 边界

### In Scope

- `RideRequest`、司机匹配、车辆分配和派单状态。
- ETA、司机到达、等待开始、司机取消、用户取消和重新派单。
- 预估价和实际费用输入，不直接定价。
- Dispatch 与 Fulfillment 的交接事件。

### Out of Scope

- 固定班次运行计划，归 Service Plan。
- 真实乘坐过程、上车、行程开始、行程结束，归 Fulfillment。
- 价格规则、等待费规则和取消费规则，归 Fare & Pricing / Post Sales。
- 现金预授权和扣款，归 Payment。

## 3. 聚合

| Aggregate | Invariants |
|---|---|
| RideRequest | 同一用户同一出行意图不能有多个 active dispatch；必须绑定 pickup/dropoff、时间窗口、乘客和业务引用。 |
| RideAssignment | 一个 active assignment 只能绑定一个司机车辆组合；司机取消、用户取消和超时必须可追踪。 |

## 4. 上游和下游契约

| Direction | Context | Contract |
|---|---|---|
| Upstream | Trip Planning / Offer Management | Ride Segment、pickup/dropoff、时间窗口、预估价引用。 |
| Upstream | Payment | 预授权成功、授权失败、授权过期。 |
| Upstream | Provider Integration | 外部网约车平台司机、车辆、ETA 和取消事件。 |
| Downstream | Fulfillment | `DriverArrived`、`RideStarted`、`RideEnded`，作为履约事实输入。 |
| Downstream | Notification | 司机接单、到达、取消、重新派单。 |
| Downstream | Post Sales | 用户取消、司机取消、等待费争议。 |

## 5. 状态机

| State | Meaning | Next |
|---|---|---|
| Requested | 已请求派单。 | Matching, Cancelled, Failed |
| Matching | 正在匹配司机。 | Assigned, Failed, Cancelled |
| Assigned | 已分配司机车辆。 | DriverArriving, DriverCancelled, UserCancelled |
| DriverArriving | 司机前往上车点。 | DriverArrived, DriverCancelled, UserCancelled |
| DriverArrived | 司机到达，等待乘客。 | PickedUp, NoShow, UserCancelled |
| PickedUp | 乘客已上车，交给 Fulfillment。 | Completed |
| DriverCancelled | 司机取消。 | Matching, Failed |
| UserCancelled | 用户取消。 | Closed |
| NoShow | 用户未出现。 | Closed |
| Completed | Dispatch 生命周期完成。 | Closed |

## 6. 命令和事件

| Command | Event |
|---|---|
| RequestDispatch | DispatchRequested |
| AssignDriver | DriverAssigned |
| UpdateEta | DriverEtaUpdated |
| MarkDriverArrived | DriverArrived |
| StartRide | RideStarted |
| CompleteDispatch | DispatchCompleted |
| CancelByDriver | DriverCancelled |
| CancelByUser | DispatchUserCancelled |
| RecordNoShow | DispatchNoShowRecorded |

## 7. Final DDD Decision

Dispatch 已裁定为独立 future-scope 上下文。第一阶段火车固定班次票务不实现 Dispatch；后续接入网约车或即时接送时，不能把派单生命周期塞进 Service Plan 或 Fulfillment。
