# 出行业务状态机设计

Last updated: 2026-06-28

## 目的

这份文档定义核心业务对象的状态机。当前系统最大的问题之一是订单、库存、支付、出票、售后状态互相混用，导致“支付成功是否等于出票成功”“订单取消是否等于退款成功”“改签失败后旧票是否还有效”等问题不可解释。

状态机设计原则：

1. 每个聚合拥有自己的状态机。
2. 状态变化只能通过命令和领域事件推进。
3. 聚合之间通过 Saga 和事件最终一致。
4. 读模型可以汇总多个状态，但不能把汇总状态写回聚合。

## JourneyOrder 状态机

### 状态定义

| 状态 | 含义 |
|---|---|
| Draft | 内部草稿或人工创建中，用户尚未提交。 |
| PendingConfirmation | 订单已创建，正在确认分段预订或锁定库存。 |
| PendingPayment | 必要分段已确认或可担保，等待支付。 |
| Confirming | 支付已完成，正在出票或确认最终权益。 |
| Confirmed | 订单所有必要 Segment 已确认，必要 Entitlement 已签发。 |
| PartiallyConfirmed | 多段订单部分成功，等待用户选择或补偿。 |
| InTravel | 至少一个 Segment 已开始履约，Journey 未完成。 |
| Completed | 所有必需 Segment 完成。 |
| Cancelled | 订单整体取消，必要释放和退款已进入流程或完成。 |
| Disrupted | 订单受异常影响，等待恢复方案或售后处理。 |
| Failed | 订单无法完成且不能自动补偿，需要人工或终止。 |

### 允许转换

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| Draft | JourneyOrderCreated | PendingConfirmation |
| PendingConfirmation | AllSegmentReservationsConfirmed | PendingPayment |
| PendingConfirmation | NoPaymentRequired | Confirming |
| PendingConfirmation | SomeSegmentReservationFailed | PartiallyConfirmed |
| PendingConfirmation | AllSegmentReservationsFailed | Failed |
| PendingPayment | PaymentCaptured | Confirming |
| PendingPayment | PaymentFailed | PendingPayment |
| PendingPayment | PaymentExpired | Cancelled |
| Confirming | EntitlementsIssued | Confirmed |
| Confirming | SomeEntitlementIssueFailed | PartiallyConfirmed |
| Confirmed | FirstSegmentBoarded | InTravel |
| Confirmed | JourneyCancelledByPostSales | Cancelled |
| Confirmed | DisruptionAffectsJourney | Disrupted |
| InTravel | AllSegmentsCompleted | Completed |
| InTravel | DisruptionAffectsJourney | Disrupted |
| Disrupted | RecoveryAccepted | Confirming |
| Disrupted | DisruptionRefundApplied | Cancelled |
| PartiallyConfirmed | UserAcceptsPartialJourney | Confirming |
| PartiallyConfirmed | UserCancelsPartialJourney | Cancelled |
| PartiallyConfirmed | CompensationFailed | Failed |

### 禁止转换

| 禁止转换 | 原因 |
|---|---|
| PendingPayment -> Confirmed | 必须经过支付确认和出票确认。 |
| Confirmed -> PendingPayment | 已确认订单不能回到待支付；补差价应创建新的 PaymentIntent。 |
| Completed -> Cancelled | 已完成 Journey 不能整体取消，只能走补偿或争议。 |
| Failed -> Confirmed | 失败后若重试，应创建新的确认流程或人工恢复事件。 |

## SegmentBooking 状态机

| 状态 | 含义 |
|---|---|
| Requested | 已请求预订，等待内部库存或供应商处理。 |
| Holding | 资源已临时保留，等待支付或最终确认。 |
| Confirmed | 供应侧确认成功，但未必已出票。 |
| Ticketed | Entitlement 已签发。 |
| InFulfillment | 该段正在履约。 |
| Completed | 该段履约完成。 |
| CancelRequested | 正在取消或退票。 |
| Cancelled | 该段已取消。 |
| ChangeRequested | 正在改签或改程。 |
| Changed | 原 Booking 已被新 Booking 替换。 |
| Failed | 预订失败或供应确认失败。 |

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| Requested | CapacityHeld 或 SupplierHoldConfirmed | Holding |
| Requested | SupplierRejected | Failed |
| Holding | SupplierReservationConfirmed | Confirmed |
| Holding | HoldExpired | Failed |
| Confirmed | EntitlementIssued | Ticketed |
| Ticketed | SegmentBoarded | InFulfillment |
| InFulfillment | SegmentCompleted | Completed |
| Holding | CancelSegmentBooking | CancelRequested |
| Confirmed | CancelSegmentBooking | CancelRequested |
| Ticketed | CancelSegmentBooking | CancelRequested |
| CancelRequested | SupplierCancellationConfirmed | Cancelled |
| Confirmed | ChangeSegmentBooking | ChangeRequested |
| Ticketed | ChangeSegmentBooking | ChangeRequested |
| ChangeRequested | ReplacementBookingConfirmed | Changed |
| ChangeRequested | ReplacementBookingFailed | Ticketed |

关键规则：改签失败后旧票是否恢复，取决于旧票作废发生在新票确认前还是确认后。第一阶段建议采用“先锁新票，支付差价，再作废旧票并出票”的保守流程。

## CapacityHold 状态机

| 状态 | 含义 |
|---|---|
| Requested | 正在检查库存和配额。 |
| Held | 资源已临时锁定。 |
| Confirmed | 资源已转为正式占用。 |
| Released | 资源已释放。 |
| Expired | Hold 超时失效。 |
| Failed | 库存不足或规则不允许。 |

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| Requested | CapacityAvailable | Held |
| Requested | CapacityUnavailable | Failed |
| Held | PaymentCaptured 或 SupplierConfirmed | Confirmed |
| Held | OrderCancelled | Released |
| Held | PaymentExpired | Expired |
| Held | BookingFailed | Released |
| Confirmed | TicketCancelled | Released |

火车区间库存规则：同一座位在区间重叠时不能重复 Hold；区间不重叠时可以复用。

## PaymentIntent 状态机

| 状态 | 含义 |
|---|---|
| Created | 支付意图已创建，未提交渠道。 |
| PendingAction | 等待用户操作或渠道处理。 |
| Authorized | 资金已授权，尚未扣款。 |
| Captured | 扣款成功。 |
| Failed | 支付失败。 |
| Cancelled | 支付意图取消。 |
| Expired | 支付超时。 |

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| Created | SubmitToChannel | PendingAction |
| PendingAction | ChannelAuthorized | Authorized |
| PendingAction | ChannelCaptured | Captured |
| Authorized | CaptureAuthorizedPayment | Captured |
| PendingAction | ChannelFailed | Failed |
| Created | CancelPaymentIntent | Cancelled |
| PendingAction | CancelPaymentIntent | Cancelled |
| PendingAction | PaymentTimeout | Expired |
| Authorized | AuthorizationExpired | Expired |

晚到回调规则：如果 PaymentIntent 已 Cancelled 或 Expired，晚到成功回调不能直接确认订单，必须进入 `PaymentLateSuccessDetected` 并由补偿流程处理退款或人工确认。

## Refund 状态机

| 状态 | 含义 |
|---|---|
| Requested | 业务上下文请求退款。 |
| Accepted | Payment 接受退款请求并创建渠道退款。 |
| Processing | 渠道处理中。 |
| Settled | 退款成功到账或渠道确认成功。 |
| Failed | 渠道退款失败。 |
| Cancelled | 退款被取消。 |
| ManualReview | 退款异常，进入人工处理。 |

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| Requested | RefundAccepted | Accepted |
| Accepted | SubmitRefundToChannel | Processing |
| Processing | ChannelRefundSettled | Settled |
| Processing | ChannelRefundFailed | Failed |
| Failed | RetryRefund | Processing |
| Failed | ManualReviewRequired | ManualReview |
| Requested | CancelRefund | Cancelled |

退款失败不应该回滚已经作废的票。票证作废和退款是两个事实，需要通过工单或重试补偿。

## Entitlement 状态机

| 状态 | 含义 |
|---|---|
| PendingIssue | 等待签发。 |
| Issued | 凭证已签发，可展示或核验。 |
| CheckedIn | 已值机、签到或取票。 |
| Boarded | 已检票、登机、登船或上车。 |
| Used | 对应 Segment 已完成。 |
| Voided | 凭证已作废。 |
| Suspended | 凭证被风控、争议或人工冻结。 |

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| PendingIssue | EntitlementIssued | Issued |
| Issued | CheckInSucceeded | CheckedIn |
| Issued | BoardingVerified | Boarded |
| CheckedIn | BoardingVerified | Boarded |
| Boarded | SegmentCompleted | Used |
| Issued | VoidEntitlement | Voided |
| CheckedIn | VoidEntitlementWithRule | Voided |
| Issued | SuspendEntitlement | Suspended |
| Suspended | ResumeEntitlement | Issued |

禁止规则：`Used` 不能转 `Voided`。已使用后的争议应走 Compensation，不应改写凭证历史。

## PostSalesCase 状态机

| 状态 | 含义 |
|---|---|
| Requested | 售后申请已创建。 |
| Evaluating | 正在计算规则、费用和可行性。 |
| WaitingUserConfirm | 需要用户确认手续费、差价或替代方案。 |
| Approved | 售后规则通过，准备执行。 |
| Applying | 正在作废票证、释放库存、锁新库存或发起退款。 |
| Applied | 售后业务动作完成。 |
| CompensationPending | 部分动作失败，等待补偿。 |
| Rejected | 规则不允许。 |
| Cancelled | 用户撤销售后申请。 |
| Failed | 无法自动完成且需要人工。 |

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| Requested | StartEvaluation | Evaluating |
| Evaluating | RuleRejected | Rejected |
| Evaluating | UserConfirmationRequired | WaitingUserConfirm |
| Evaluating | RuleApproved | Approved |
| WaitingUserConfirm | UserConfirmed | Approved |
| WaitingUserConfirm | UserCancelled | Cancelled |
| Approved | ApplyPostSales | Applying |
| Applying | AllStepsApplied | Applied |
| Applying | StepFailedButCompensable | CompensationPending |
| CompensationPending | CompensationSucceeded | Applied |
| CompensationPending | CompensationFailed | Failed |

## Transfer 状态机

| 状态 | 含义 |
|---|---|
| Feasible | 计划上可达。 |
| Tight | 可达但连接时间紧张。 |
| AtRisk | 延误或履约变化导致存在错过风险。 |
| Missed | 已错过接续。 |
| Recovered | 已通过改乘、改签或其他方案恢复。 |
| SelfHandled | 非保障联乘，用户选择自行处理。 |

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| Feasible | ConnectionTimeBelowComfortThreshold | Tight |
| Feasible | UpstreamDelayDetected | AtRisk |
| Tight | UpstreamDelayDetected | AtRisk |
| AtRisk | DownstreamDeparted | Missed |
| AtRisk | UserCatchesConnection | Feasible |
| Missed | RecoveryAccepted | Recovered |
| Missed | SelfTransferNoProtection | SelfHandled |

## DisruptionCase 状态机

| 状态 | 含义 |
|---|---|
| Published | 异常已发布。 |
| ImpactAssessing | 正在识别受影响订单、票证和附加服务。 |
| RecoveryPlanning | 正在生成退款、改乘、补偿选项。 |
| WaitingUserDecision | 等待用户选择恢复方案。 |
| ApplyingRecovery | 正在执行恢复方案。 |
| PartiallyRecovered | 部分受影响对象已恢复，仍有遗留。 |
| Recovered | 所有影响已处理。 |
| Closed | 异常案件关闭。 |

| 当前状态 | 触发 | 目标状态 |
|---|---|---|
| Published | StartImpactAssessment | ImpactAssessing |
| ImpactAssessing | AffectedJourneysIdentified | RecoveryPlanning |
| RecoveryPlanning | UserChoiceRequired | WaitingUserDecision |
| RecoveryPlanning | AutoRecoverySelected | ApplyingRecovery |
| WaitingUserDecision | UserAcceptedRecovery | ApplyingRecovery |
| ApplyingRecovery | SomeRecoveryFailed | PartiallyRecovered |
| ApplyingRecovery | AllRecoveryApplied | Recovered |
| PartiallyRecovered | RemainingMovedToManualReview | Recovered |
| Recovered | CloseDisruptionCase | Closed |

## AncillaryOrder 状态机

| 状态 | 含义 |
|---|---|
| Created | 附加服务订单创建。 |
| Confirming | 等待供应商或内部确认。 |
| Confirmed | 附加服务确认。 |
| Fulfilled | 附加服务已履约。 |
| CancelRequested | 取消中。 |
| Cancelled | 已取消。 |
| Failed | 确认或履约失败。 |

附加服务不得直接决定主 JourneyOrder 状态，但主行程取消或改签可以触发附加服务联动取消。

## 状态汇总规则

| 汇总对象 | 汇总方式 |
|---|---|
| 用户订单列表 | 读取 JourneyOrder 状态，并显示最重要的 Segment 异常摘要。 |
| 客服订单时间线 | 汇总 JourneyOrder、SegmentBooking、Payment、Refund、Entitlement、Fulfillment、PostSales、Disruption 事件。 |
| 财务状态 | 以 PaymentIntent、Refund、Settlement 为准，不以订单状态推断。 |
| 履约状态 | 以 Entitlement 和 FulfillmentRecord 为准，不以支付状态推断。 |
| 库存状态 | 以 CapacityHold 和正式占用为准，不以订单状态推断。 |

## 第一阶段最小状态机

火车票务第一阶段至少要实现：

1. JourneyOrder
2. SegmentBooking
3. CapacityHold
4. PaymentIntent
5. Refund
6. Entitlement
7. PostSalesCase

Transfer 和 Disruption 可以先设计事件和状态，等联乘与异常恢复进入实现阶段再完整落地。
