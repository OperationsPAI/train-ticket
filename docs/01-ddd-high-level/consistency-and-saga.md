# 出行业务一致性与 Saga 设计

Last updated: 2026-06-28

## 目的

出行业务最危险的部分不是查询，而是下单、占座、支付、出票、退改、退款、异常恢复之间的一致性。当前系统的腐化点集中在这里：业务逻辑写死在调用链里，缺少明确事务边界，失败后不知道应该回滚、补偿还是人工处理。

这份文档定义：

1. 哪些不变量必须强一致。
2. 哪些流程可以最终一致。
3. 每条核心 Saga 的步骤、事件和补偿。
4. 幂等、Outbox、超时、晚到回调、人工兜底的处理规则。

## 一致性分层

| 层级 | 适用对象 | 要求 | 示例 |
|---|---|---|---|
| 聚合内强一致 | 单个聚合根内部 | 一个命令内必须原子成功或失败 | JourneyOrder 金额汇总、CapacityHold 区间冲突检查。 |
| 本地事务一致 | 同一上下文内的聚合和 Outbox | 状态变更和事件写入必须同事务 | PaymentCaptured 和 PaymentOutboxEvent。 |
| 跨上下文最终一致 | 订单、库存、支付、出票、通知 | 通过事件和 Saga 协调，允许短暂不一致 | 支付成功后出票稍后完成。 |
| 外部供应商最终一致 | 航司、铁路、支付渠道、网约车平台 | 以供应商最终查询结果为准，必须可重试和对账 | 支付回调晚到、PNR 确认超时。 |
| 人工一致 | 自动流程无法判断 | 进入工单和审计，不隐式修改 | 退款失败、供应商状态冲突。 |

## 必须强一致的不变量

| 不变量 | 所属聚合 | 说明 |
|---|---|---|
| 订单金额等于订单项、税费、手续费、优惠汇总 | JourneyOrder | 防止支付金额和订单金额不一致。 |
| Offer 有效期和价格快照不可变 | Offer | 防止下单时价格漂移。 |
| 同一座席同一重叠区间不能重复 Hold | CapacityHold | 火车核心库存不变量。 |
| 同一 PaymentIntent 只能被同一渠道交易成功确认一次 | PaymentIntent | 防止重复扣款。 |
| 同一退款业务原因不能重复退款 | Refund | 防止重复退款。 |
| 已作废 Entitlement 不能再登乘 | Entitlement | 防止无效票使用。 |
| 已使用 Entitlement 不能被普通退票作废 | Entitlement | 防止履约历史被改写。 |
| SegmentBooking 的供应商确认号必须幂等映射 | SegmentBooking | 防止重复外部预订。 |

## 可最终一致的关系

| 关系 | 允许的不一致 | 收敛方式 |
|---|---|---|
| JourneyOrder 和 SegmentBooking | 订单已创建但分段预订仍在确认 | Booking Saga 推进或失败补偿。 |
| PaymentIntent 和 JourneyOrder | 支付成功但订单仍在 Confirming | PaymentCaptured 事件触发后续出票。 |
| Entitlement 和 JourneyOrder | 票证已签发但订单汇总未更新 | EntitlementIssued 事件投递后汇总。 |
| Refund 和 PostSalesCase | 售后已作废票但退款仍处理中 | RefundSettled 或 RefundFailed 事件收敛。 |
| Provider 状态和平台状态 | 供应商已确认但平台未收到回调 | 主动查询、对账、幂等恢复。 |
| Notification 和业务状态 | 业务已成功但通知稍后发送 | NotificationTask 重试，不回滚业务。 |

## Saga 设计规则

1. Saga 不拥有业务事实，只编排命令和事件。
2. 每一步都必须有幂等键。
3. 每一步都必须定义超时策略。
4. 每个不可重试失败都必须有补偿或人工兜底。
5. Saga 状态必须可查询，不能只存在内存。
6. Saga 不能绕过聚合不变量直接写状态。

## Saga 1：下单、占座、支付、出票

### 正常流程

| Step | 命令 | 成功事件 | 失败事件 | 补偿 |
|---|---|---|---|---|
| 1 | CreateJourneyOrder | JourneyOrderCreated | JourneyOrderCreationRejected | 无，返回失败。 |
| 2 | RequestSegmentReservation | SegmentReservationConfirmed | SegmentReservationFailed | 释放已确认的其他 Segment。 |
| 3 | HoldCapacity | CapacityHeld | CapacityHoldFailed | 取消 SegmentBooking。 |
| 4 | CreatePaymentIntent | PaymentIntentCreated | PaymentIntentCreateFailed | 释放 Hold，取消订单。 |
| 5 | CapturePayment | PaymentCaptured | PaymentFailed/Expired | 支付失败可重试；超时释放 Hold。 |
| 6 | ConfirmHold | CapacityHoldConfirmed | CapacityConfirmFailed | 进入人工或退款。 |
| 7 | IssueEntitlement | EntitlementIssued | EntitlementIssueFailed | 重试出票；不可恢复则退款和释放。 |
| 8 | ConfirmJourneyOrder | JourneyOrderConfirmed | JourneyOrderConfirmFailed | 重放事件汇总或人工修复。 |

### 超时策略

| 等待对象 | 建议超时 | 超时处理 |
|---|---|---|
| 内部库存 Hold | 秒级 | 失败返回，用户可重新下单。 |
| 外部供应商预留 | 秒级到十几秒 | 查询供应商最终状态；未知时进入 PendingConfirmation。 |
| 用户支付 | 分钟级 | 订单取消，Hold 释放，PaymentIntent 过期。 |
| 出票 | 秒级到分钟级 | 重试；超过阈值进入人工或自动退款。 |

### 晚到事件

| 晚到事件 | 处理 |
|---|---|
| PaymentCaptured 晚于 PaymentExpired | 不确认订单，创建 LatePaymentCase，自动退款或人工确认。 |
| ProviderConfirmed 晚于订单取消 | 尝试供应商取消；若不可取消，进入供应商异常对账。 |
| EntitlementIssued 晚于退票申请 | 先冻结 Entitlement，再由 PostSales 判断作废和退款。 |

## Saga 2：取消未支付订单

| Step | 命令 | 成功事件 | 失败处理 |
|---|---|---|---|
| 1 | CancelJourneyOrder | JourneyOrderCancelled | 若订单已支付，转退票流程。 |
| 2 | CancelPaymentIntent | PaymentIntentCancelled | 若渠道已成功，转晚到支付处理。 |
| 3 | ReleaseHold | CapacityReleased | 失败则进入库存释放重试队列。 |
| 4 | CancelSegmentBooking | SegmentBookingCancelled | 供应商取消失败则进入供应商对账。 |
| 5 | ScheduleNotification | NotificationScheduled | 通知失败不影响取消。 |

关键不变量：取消未支付订单不能产生 Refund，除非发现晚到支付成功。

## Saga 3：退票和退款

| Step | 命令 | 成功事件 | 失败处理 |
|---|---|---|---|
| 1 | RequestRefundByRule | PostSalesRequested | 不符合规则则 Rejected。 |
| 2 | EvaluatePostSalesRule | PostSalesEvaluated | 规则引擎不可用则稍后重试或人工。 |
| 3 | VoidEntitlement | EntitlementVoided | 作废失败则停止退款，进入人工。 |
| 4 | CancelSegmentBooking | SegmentBookingCancelled | 外部取消失败则供应商对账。 |
| 5 | ReleaseCapacity | CapacityReleased | 失败重试，不阻塞退款请求创建。 |
| 6 | RequestRefund | RefundRequested | 退款请求创建失败则 PostSales CompensationPending。 |
| 7 | SubmitRefundToChannel | RefundProcessing | 渠道失败可重试。 |
| 8 | SettleRefund | RefundSettled | 退款失败进入 ManualReview 或重试。 |
| 9 | ApplyPostSalesResult | PostSalesApplied | 汇总失败可重放事件。 |

关键规则：如果 Entitlement 已经 Voided，Refund 失败不能恢复 Entitlement，除非通过人工创建新的 Entitlement 并留下审计。

## Saga 4：改签

### 保守顺序

| Step | 命令 | 成功事件 | 失败处理 |
|---|---|---|---|
| 1 | RequestChange | ChangeRequested | 规则不允许则 Rejected。 |
| 2 | QuoteChangeOffer | ChangeOfferQuoted | 无目标方案则结束。 |
| 3 | HoldReplacementCapacity | ReplacementCapacityHeld | 原票保持不变。 |
| 4 | SettleChangeDifference | ChangeDifferenceSettled | 释放新 Hold，原票保持不变。 |
| 5 | VoidOriginalEntitlement | OriginalEntitlementVoided | 释放新 Hold，原票保持不变或人工。 |
| 6 | ConfirmReplacementBooking | ReplacementBookingConfirmed | 如果失败，按旧票是否作废决定恢复或退款。 |
| 7 | IssueReplacementEntitlement | ReplacementEntitlementIssued | 重试出票；不可恢复则人工。 |
| 8 | MarkSegmentChanged | SegmentBookingChanged | 汇总订单状态。 |

### 差价规则

| 场景 | 处理 |
|---|---|
| 新票更贵 | 创建 PaymentIntent 补差价，成功后继续。 |
| 新票更便宜 | 创建 Refund，但不阻塞新票出票，除非业务要求先退款。 |
| 价格相同 | 无资金动作，直接切换。 |
| 支付失败 | 新 Hold 释放，原票保持有效。 |
| 退款失败 | 改签可完成，退款进入重试或人工。 |

## Saga 5：候补兑现

| Step | 命令 | 成功事件 | 失败处理 |
|---|---|---|---|
| 1 | EnqueueWaitlist | WaitlistQueued | 不符合条件则拒绝。 |
| 2 | AuthorizeWaitlistPayment | PaymentAuthorized | 授权失败则候补不入队。 |
| 3 | OnCapacityReleased | CapacityReleased | 触发候补匹配。 |
| 4 | TryFulfillWaitlist | WaitlistFulfillmentStarted | 队首不符合则跳过并记录原因。 |
| 5 | HoldCapacity | CapacityHeld | 失败则尝试下一个候补。 |
| 6 | CapturePayment | PaymentCaptured | 扣款失败则释放 Hold，候补失败或降级。 |
| 7 | IssueEntitlement | EntitlementIssued | 出票失败则退款或重试。 |
| 8 | CompleteWaitlist | WaitlistFulfilled | 通知用户。 |

候补的一致性难点是排序公平和库存释放后的并发抢占。候补队列应由单独聚合或分区锁保护，不应让普通购票和候补同时无序竞争同一释放库存。

## Saga 6：联乘异常恢复

| Step | 命令 | 成功事件 | 失败处理 |
|---|---|---|---|
| 1 | MarkTransferAtRisk | TransferAtRisk | 通知用户准备动作。 |
| 2 | MarkConnectionMissed | ConnectionMissed | 判断 Connection Contract。 |
| 3 | ProposeRecoveryOptions | ReaccommodationProposed | 无可行方案则转客服。 |
| 4 | AcceptRecoveryOption | ReaccommodationAccepted | 用户超时未选则按规则默认退款或人工。 |
| 5 | RequestProtectedChange | PostSalesRequested | 保护性改乘失败则找替代或退款。 |
| 6 | ApplyRecovery | RecoveryApplied | 部分失败则 DisruptionCase PartiallyRecovered。 |

关键规则：非保障联乘不能自动使用免手续费或平台兜底规则，除非客服人工补偿。

## Saga 7：供应商状态冲突恢复

| 冲突 | 检测来源 | 恢复策略 |
|---|---|---|
| 平台未确认，供应商已出票 | 主动查询或对账 | 若用户仍需要则补建 SegmentBooking 和 Entitlement；否则供应商取消或退款。 |
| 平台已取消，供应商仍有效 | 对账 | 再次取消供应商订单；若不可取消，进入财务损失或人工。 |
| 平台已退款，供应商未取消 | 对账 | 紧急冻结 Entitlement，供应商取消或人工追偿。 |
| 支付渠道成功，平台失败 | 支付对账 | 创建 LatePaymentCase，退款或恢复订单。 |
| 供应商取消，平台仍显示有效 | 供应商事件 | 标记 Disruption，通知用户恢复或退款。 |

## 幂等设计

| 操作 | 幂等键 | 存储位置 |
|---|---|---|
| 创建订单 | accountId + offerId + clientRequestId | JourneyOrder |
| 请求分段预订 | orderId + segmentId + travelerId | SegmentBooking |
| 锁库存 | segmentId + capacityUnit + orderId + travelerId | CapacityHold |
| 支付确认 | paymentIntentId + channelTxnId | PaymentIntent |
| 退款请求 | businessReason + originalPaymentId + amount | Refund |
| 出票 | segmentBookingId + travelerId + issuePurpose | Entitlement |
| 供应商调用 | provider + operation + businessKey | Provider Integration |
| 通知发送 | eventId + channel + recipient | NotificationTask |

## Outbox 和 Inbox

### Outbox

每个写上下文必须把聚合状态变更和待发布事件写在同一事务中：

1. 保存聚合新状态。
2. 保存 OutboxEvent。
3. 本地事务提交。
4. 发布器异步发布。
5. 发布成功后标记事件已发布。

### Inbox

每个消费者必须记录已处理事件：

1. 读取 eventId。
2. 如果已处理，直接确认。
3. 如果未处理，执行业务命令。
4. 业务状态和 Inbox 记录同事务提交。

## 人工兜底

自动流程不能覆盖所有异常。进入人工兜底时必须满足：

1. 有明确 Case 类型：LatePaymentCase、ProviderConflictCase、RefundFailureCase、EntitlementIssueFailureCase。
2. 有完整事件时间线。
3. 有可执行的人工命令，而不是直接改数据库。
4. 有权限校验和审计。
5. 人工命令也必须产生领域事件。

## 第一阶段实现建议

第一阶段至少建立这些机制：

1. Outbox/Inbox。
2. 下单 Saga。
3. 取消未支付订单 Saga。
4. 退票退款 Saga。
5. 改签 Saga。
6. 支付晚到回调处理。
7. 供应商状态冲突 Case。
8. 人工命令和审计事件。
