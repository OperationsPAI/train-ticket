# Notification Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Notification |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-notification |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/general-travel-ddd.md`, `docs/02-domains/account.md` |

## 1. 领域目标

Notification bounded context 负责把平台业务事件或显式发送指令转换成面向用户、客服、运营、企业联系人和代理人的触达任务。它统一管理 Notification、NotificationTask、Template、Channel、DeliveryAttempt、站内 Inbox、Push、Email、SMS、发送重试、送达回执、通知审计和多渠道降级策略。

Notification 的独立价值在于把“业务事实已经发生”与“应该如何触达相关人”解耦。Journey Order、Booking Orchestration、Payment、Entitlement、Post Sales、Disruption Recovery、Fulfillment 等上下文只发布事实或请求触达，不把模板、渠道、频控、重试和回执逻辑写入自身模型。Notification 失败不会回滚订单、支付、出票、售后或异常恢复事实；它只能形成告警、补偿发送、客服提示或运营介入。

本域必须覆盖火车票第一阶段的交易通知，也要能扩展到 General Travel：下单确认、支付成功或失败、出票和凭证生成、退改结果、退款进度、异常恢复方案、履约提醒、联乘风险、多旅客、多段、多代理人、企业联系人、批量运营通知以及客服协同通知。

## 2. 边界

### In Scope

- 将业务事件映射为 NotificationTask，包括收件人解析、Template 选择、Channel 选择、发送窗口和优先级。
- 维护 Template、变量契约、语言版本、渠道版本、合规文案和发布状态。
- 管理 Channel 能力：Inbox、Push、Email、SMS、客服工作台、运营站内公告及供应商可插拔适配。
- 应用 Account 提供的联系方式、Preference、Consent 和可联系性快照，决定是否允许发送以及使用哪些 Channel。
- 处理多旅客、多联系人、代理人、企业管理员、企业出行联系人和客服关注人的收件人规则。
- 执行频控、去重、合并、延迟发送、优先级抢占和多渠道降级。
- 记录 DeliveryAttempt、外部 provider 回执、用户打开或点击、退订、投诉和失败原因。
- 维护 Inbox 站内信生命周期、已读状态、归档状态和用户可见通知时间线。
- 支持异常恢复、大面积停运、批量退款、系统故障等批量触达和运营审计。
- 提供通知审计读模型，支撑客服解释“何时、通过什么渠道、向谁发送、结果如何”。

### Out of Scope

- 不决定订单、支付、出票、退改、退款、履约、异常恢复或客服工单的业务结果。
- 不创建或修改 Journey Order、Payment、Entitlement、Post Sales、Disruption Recovery 等业务状态。
- 不拥有 Account 的 UserAccount、ContactPoint、Preference、Consent 真相，只消费其版本化快照。
- 不拥有 Traveler Profile 的旅客证件、手机号归属或特殊服务资格，只引用 travelerRef 和联系方式用途。
- 不拥有 Push、Email、SMS 供应商的底层账户、资费、网关健康和运营合同，只通过 ACL 适配。
- 不把营销活动策划、优惠券发放、广告投放策略放入本域；营销触达只作为一种 NotificationTask 类型执行。
- 不用发送成功作为交易完成条件，发送失败也不能反向回滚业务事实。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| `Notification` | 一次面向业务对象的通知意图，表达为什么要触达、触达谁、展示什么主题。 | 可包含多个 NotificationTask。 |
| `NotificationTask` | 某个收件人在某个场景下需要完成的触达任务。 | 负责状态、优先级、频控和重试。 |
| `Template` | 渠道无关或渠道特定的内容定义，包含变量契约、语言、版本和合规声明。 | 只能引用上游事件载荷或允许的读模型字段。 |
| `Channel` | 可发送介质，如 Inbox、Push、Email、SMS、客服工作台。 | 由 ChannelPolicy 选择和降级。 |
| `DeliveryAttempt` | 对某个 Channel 的一次实际发送尝试。 | 记录 provider request、response、回执和错误分类。 |
| `Preference` | Account 发布的通知偏好快照在本域的应用结果。 | 权威来源仍是 Account。 |
| `Consent` | Account 发布的同意授权快照在本域的合规门禁。 | 交易必要通知与营销通知分开判断。 |
| `Recipient` | 被触达主体，可以是账户、旅客联系人、代理人、企业联系人、客服或运营角色。 | 必须有 recipientRef 和用途说明。 |
| `NotificationIntent` | 从业务事件提炼出的触达目的，如付款提醒、出票成功、退票结果、异常方案。 | 不等于业务命令。 |
| `FallbackPlan` | 主 Channel 失败或受限时的降级链路。 | 例如 Push 失败后 SMS，或 Inbox 保底。 |
| `ThrottleBucket` | 频控桶，按账户、旅客、订单、场景、渠道或企业维度限制发送。 | 紧急通知可有受控豁免。 |
| `InboxMessage` | 站内信实体，用户或客服可查看、已读、归档。 | 不依赖外部 provider。 |
| `DeliveryReceipt` | 外部 provider 或客户端返回的送达、失败、打开、点击、退订事实。 | 只能更新通知状态和审计。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Journey Order | `JourneyOrderCreated`, `JourneyConfirmed`, `JourneyCancelled`, order snapshot | 下单、整单确认、取消和用户订单时间线通知。 |
| Booking Orchestration | `SegmentReservationRequested`, `JourneyPartiallyConfirmed`, `SegmentReservationFailed` | 多段确认、部分成功、补偿和失败解释通知。 |
| Payment | `PaymentAuthorized`, `PaymentCaptured`, `PaymentFailed`, `RefundRequested`, `RefundSettled` | 支付、退款、差价和资金进度通知。 |
| Entitlement & Ticketing | `EntitlementIssued`, `EntitlementVoided`, credential display snapshot | 出票、票证作废、凭证可查看通知。 |
| Post Sales | `PostSalesRequested`, `SegmentCancelled`, `ChangeCompleted`, `RefundRuleApplied` | 退票、改签、改程、手续费和售后结果通知。 |
| Fulfillment | `SegmentCheckInOpened`, `SegmentBoarded`, `SegmentCompleted`, departure reminder input | 检票、值机、登乘、出发前提醒和履约时间线通知。 |
| Transfer Management | `TransferAtRisk`, `ConnectionMissed`, transfer risk snapshot | 联乘风险、错过接续和自助补救提示。 |
| Disruption Recovery | `DisruptionPublished`, `ReaccommodationProposed`, `ReaccommodationAccepted` | 延误、停运、取消、保护性改乘和批量异常通知。 |
| Customer Service | `NotifyCustomerByCase`, `CaseEscalated`, `CaseResolved` | 客服主动触达、补充说明和工单进度通知。 |
| Account | ContactPoint summary, `PreferenceUpdated`, `ConsentRecorded`, `ConsentWithdrawn`, account status | 联系方式、偏好、同意和账户可联系性门禁。 |
| Admin & Audit | Template approval, emergency broadcast approval, operator identity | 高风险模板发布、批量通知和人工发送审计。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| 用户、旅客、代理人、企业联系人 | Inbox、Push、Email、SMS 消息 | 最终触达对象。 |
| Customer Service | `NotificationTimelineView`, `NotificationFailed`, `RecipientUnreachable` | 客服解释通知历史并介入失败触达。 |
| Reporting | delivery metrics、open/click metrics、failure classification | 运营分析发送效果和渠道质量。 |
| Admin & Audit | `TemplatePublished`, `EmergencyNotificationSent`, audit trail | 审计模板、人工发送、批量触达和高风险操作。 |
| Account | `NotificationPreferenceObserved`, unsubscribe signal, invalid contact signal | 辅助 Account 更新偏好建议或联系方式问题；不直接改 Account。 |
| Channel Providers | provider-specific send request | 通过 ACL 发送实际消息并接收回执。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `Notification` | 必须绑定 businessRef、intent、sourceEventId 和至少一个 Recipient；不能表达业务结果判定；同一 sourceEventId 与 intent 默认只创建一次。 | `CreateNotification`, `AddRecipient`, `CancelNotification`, `CloseNotification` | `NotificationCreated`, `RecipientAdded`, `NotificationCancelled`, `NotificationClosed` |
| `NotificationTask` | 一个 task 只服务一个 Recipient 和一个 intent；发送前必须通过 Template、Preference、Consent、Throttle 校验；终态不可重试。 | `ScheduleNotificationTask`, `AuthorizeNotificationTask`, `SuppressNotificationTask`, `StartDelivery`, `MarkTaskCompleted`, `MarkTaskFailed` | `NotificationTaskScheduled`, `NotificationTaskAuthorized`, `NotificationTaskSuppressed`, `NotificationTaskDeliveryStarted`, `NotificationTaskCompleted`, `NotificationTaskFailed` |
| `Template` | active Template 必须有版本、变量 schema、渠道适配和审批引用；已发布版本不可原地修改。 | `DraftTemplate`, `ValidateTemplate`, `PublishTemplate`, `RetireTemplate` | `TemplateDrafted`, `TemplateValidated`, `TemplatePublished`, `TemplateRetired` |
| `ChannelPolicy` | 每种 intent 必须有允许 Channel、降级顺序、频控规则和紧急等级；不能绕过 Consent 门禁。 | `DefineChannelPolicy`, `UpdateFallbackPlan`, `UpdateThrottleRule`, `DisableChannelForIntent` | `ChannelPolicyDefined`, `FallbackPlanUpdated`, `ThrottleRuleUpdated`, `ChannelDisabledForIntent` |
| `DeliveryAttempt` | attempt 必须绑定 task、Channel、provider request id；同一 attempt 只记录一次 provider 结果；可重试必须创建新 attempt。 | `CreateDeliveryAttempt`, `RecordProviderAccepted`, `RecordDeliveryReceipt`, `ClassifyDeliveryFailure` | `DeliveryAttemptCreated`, `ProviderAccepted`, `DeliveryReceiptRecorded`, `DeliveryFailureClassified` |
| `InboxMessage` | Inbox 消息必须可追溯到 NotificationTask；用户删除只改变可见状态，不删除审计记录；已读状态按 recipient 维护。 | `CreateInboxMessage`, `MarkInboxRead`, `ArchiveInboxMessage`, `HideInboxMessage` | `InboxMessageCreated`, `InboxMessageRead`, `InboxMessageArchived`, `InboxMessageHidden` |
| `NotificationAudit` | 所有模板发布、人工发送、批量发送、失败重试和回执处理必须追加审计记录；审计记录不可业务覆盖。 | `AppendNotificationAudit`, `SealAuditBatch` | `NotificationAuditAppended`, `NotificationAuditBatchSealed` |

## 6. 状态机

### `NotificationTask` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Planned` | 已从业务事件生成，但尚未完成收件人和策略校验。 | `Authorized`, `Suppressed`, `Cancelled` |
| `Authorized` | Template、Preference、Consent 和频控已通过，可进入发送。 | `Delivering`, `ScheduledForLater`, `Cancelled` |
| `ScheduledForLater` | 因发送窗口、提醒时间或频控延迟等待。 | `Delivering`, `Suppressed`, `Cancelled` |
| `Delivering` | 正在通过一个或多个 Channel 发送。 | `Delivered`, `PartiallyDelivered`, `RetryWaiting`, `Failed` |
| `RetryWaiting` | 可重试失败后等待下一次尝试或降级 Channel。 | `Delivering`, `Failed`, `Suppressed` |
| `PartiallyDelivered` | 多渠道或多端中部分送达，仍可继续补充发送。 | `Delivered`, `Failed`, `Closed` |
| `Delivered` | 达到策略定义的送达条件。 | `Closed` |
| `Suppressed` | 因 Consent、Preference、频控、重复或业务取消被抑制。 | `Closed` |
| `Failed` | 所有允许尝试耗尽或收件人不可达。 | `Closed` |
| `Cancelled` | 上游撤销发送意图或任务尚未发送前被取消。 | `Closed` |
| `Closed` | 审计完成后的终态。 | 终态 |

### `DeliveryAttempt` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Created` | 已创建发送尝试，尚未提交 provider。 | `Submitted`, `Abandoned` |
| `Submitted` | 请求已提交 Channel provider。 | `Accepted`, `Rejected`, `Timeout` |
| `Accepted` | provider 已接收，等待异步回执或客户端行为。 | `Delivered`, `Bounced`, `Expired` |
| `Rejected` | provider 同步拒绝。 | 终态 |
| `Timeout` | provider 未在窗口内响应。 | 终态 |
| `Delivered` | provider 或客户端确认送达。 | 终态 |
| `Bounced` | 地址无效、退订、黑名单、运营商失败等不可直接送达。 | 终态 |
| `Expired` | 超过业务有效时间仍无最终回执。 | 终态 |
| `Abandoned` | 任务取消或策略变更后放弃尝试。 | 终态 |

### `Template` 与 `InboxMessage` 状态

- `Template`: `Draft`、`Validated`、`Published`、`Retired`。Published 版本不可修改，只能发布新版本或 Retired。
- `InboxMessage`: `Visible`、`Read`、`Archived`、`Hidden`。Hidden 不删除审计，客服仍可在授权视图查看。
- `ChannelPolicy`: `Active`、`Degraded`、`Disabled`。Channel provider 故障只影响发送路径，不改变业务事实。

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `CreateNotification` | `Notification` | `NotificationCreated` | source context + source event id + intent |
| `AddRecipient` | `Notification` | `RecipientAdded` | notificationId + recipientRef + role |
| `ScheduleNotificationTask` | `NotificationTask` | `NotificationTaskScheduled` | notificationId + recipientRef + intent |
| `AuthorizeNotificationTask` | `NotificationTask` | `NotificationTaskAuthorized` | taskId + policy version + account snapshot version |
| `SuppressNotificationTask` | `NotificationTask` | `NotificationTaskSuppressed` | taskId + suppression reason + version |
| `StartDelivery` | `NotificationTask` | `NotificationTaskDeliveryStarted` | taskId + delivery plan version |
| `CreateDeliveryAttempt` | `DeliveryAttempt` | `DeliveryAttemptCreated` | taskId + channel + attempt sequence |
| `RecordProviderAccepted` | `DeliveryAttempt` | `ProviderAccepted` | provider + provider request id |
| `RecordDeliveryReceipt` | `DeliveryAttempt` | `DeliveryReceiptRecorded` | provider + receipt id |
| `ClassifyDeliveryFailure` | `DeliveryAttempt` | `DeliveryFailureClassified` | attemptId + failure code + classifier version |
| `CreateInboxMessage` | `InboxMessage` | `InboxMessageCreated` | taskId + recipientRef + inbox thread key |
| `MarkInboxRead` | `InboxMessage` | `InboxMessageRead` | inboxMessageId + recipientRef + read timestamp bucket |
| `DraftTemplate` | `Template` | `TemplateDrafted` | template key + draft version |
| `PublishTemplate` | `Template` | `TemplatePublished` | template key + version + approval id |
| `DefineChannelPolicy` | `ChannelPolicy` | `ChannelPolicyDefined` | intent + policy version |
| `AppendNotificationAudit` | `NotificationAudit` | `NotificationAuditAppended` | actor/source + businessRef + audit sequence |

## 8. 策略和 Saga 参与点

- 下单通知策略：消费 `JourneyOrderCreated` 后生成订单提交成功或待支付 NotificationTask；若订单由代理人或企业成员创建，收件人包括下单人、实际出行人可联系账户和企业出行联系人，具体可见内容按授权裁剪。
- 支付通知策略：Payment 成功、失败、退款和差价事件只触发资金进度通知；Notification 不判断是否应出票，也不把支付通知成功作为资金确认条件。
- 出票通知策略：`EntitlementIssued` 触发凭证可查看、取票或乘车码提醒；多旅客订单按 travelerRef 生成可裁剪内容，代理人可收到汇总，旅客可收到自己的凭证提示。
- 退改通知策略：Post Sales 事件触发退票、改签、改程、手续费、补收或退款进度通知；Notification 不计算手续费和差价。
- 履约提醒策略：Fulfillment 或计划时间驱动出发前、检票前、值机开放、登乘点变化等提醒；提醒任务可延迟调度，并在上游取消或变更时取消旧任务。
- 异常恢复策略：`DisruptionPublished`、`TransferAtRisk`、`ConnectionMissed`、`ReaccommodationProposed` 触发高优先级多渠道通知，可突破普通频控但仍记录合规依据和审计。
- 多渠道降级策略：交易关键通知优先 Inbox 保底，再按 Preference 选择 Push、Email、SMS；Push 不可达时可降级 SMS，SMS 失败可创建 Customer Service 关注提示。
- 频控和合并策略：同一订单短时间内多个低优先级变化可合并为一条 Inbox 或 Push；支付失败、出票失败、停运和中转错过不得被低优先级合并吞掉。
- 客服协同策略：当 `RecipientUnreachable`、高优先级发送耗尽或用户投诉未收到通知时，向 Customer Service 发布可处理事件和完整通知时间线。
- 偏好和同意策略：Account 的 Preference 和 Consent 变化只影响后续任务或尚未发送任务；历史 DeliveryAttempt 和审计不回写删除。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `NotificationTimelineView` | `NotificationCreated`, `NotificationTaskScheduled`, `DeliveryReceiptRecorded`, `NotificationTaskCompleted`, `NotificationTaskFailed` | 用户端、客服、订单时间线 |
| `RecipientInboxView` | `InboxMessageCreated`, `InboxMessageRead`, `InboxMessageArchived`, `InboxMessageHidden` | 用户端、企业后台、代理人工作台 |
| `DeliveryStatusView` | `DeliveryAttemptCreated`, `ProviderAccepted`, `DeliveryReceiptRecorded`, `DeliveryFailureClassified` | 客服、运营、Reporting |
| `TemplateCatalogView` | `TemplateDrafted`, `TemplateValidated`, `TemplatePublished`, `TemplateRetired` | 运营后台、Admin & Audit、发送引擎 |
| `ChannelPolicyView` | `ChannelPolicyDefined`, `FallbackPlanUpdated`, `ThrottleRuleUpdated`, `ChannelDisabledForIntent` | 发送引擎、运营后台、应急控制台 |
| `RecipientReachabilityView` | Account contact snapshots, `DeliveryFailureClassified`, unsubscribe signals | 发送引擎、Account、Customer Service |
| `NotificationAuditView` | `NotificationAuditAppended`, `EmergencyNotificationSent`, `NotificationAuditBatchSealed` | Admin & Audit、Legal、Customer Service |
| `DisruptionNotificationDashboard` | Disruption events, task events, delivery receipts | 运营、客服、应急指挥 |
| `ChannelHealthView` | provider callbacks, attempt failures, degraded policy events | 运营、SRE、发送策略服务 |

读模型可以冗余订单号、出发时间、车次或航班摘要、旅客掩码、企业名称、渠道状态和失败分类，但不能成为订单、支付、票证、退改或异常恢复的事实来源。

## 10. 外部系统和防腐层

- Push ACL：隔离 APNs、FCM、厂商 Push 和 App 内推送 token 细节，统一映射为 accepted、invalidToken、rateLimited、providerUnavailable、delivered、opened。
- Email ACL：隔离邮件服务商模板语法、退信、垃圾投诉、退订列表和域名信誉，把外部回执映射为 DeliveryReceipt。
- SMS ACL：隔离短信供应商签名、模板备案、运营商错误码、国际短信限制和计费规则，统一失败分类用于重试或降级。
- Inbox ACL：站内信可以是内部实现，但对客户端暴露稳定 API，支持按 recipientRef、businessRef、threadKey 查询。
- 客服工作台 ACL：将通知失败、用户未读、人工补发请求转成 Customer Service 可处理事件，不让客服直接改 DeliveryAttempt。
- Account ACL：消费 ContactPoint、Preference、Consent 和账户状态视图；联系方式无效信号只能作为建议事件返回 Account。
- Admin & Audit ACL：模板发布、紧急广播、人工补发和批量导入必须携带 approvalRef、operatorRef、reason 和证据附件引用。
- Legacy ACL：旧 `ts-notification-service`、短信表、邮件表、站内信表、模板配置表在迁移期通过转换层发布 Notification 事件，禁止新域直接依赖旧状态码。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-notification-service` | 收敛为 Notification、NotificationTask、DeliveryAttempt、Template、ChannelPolicy 和 Inbox 的主要实现，拆除业务状态判断。 |
| `ts-order-service` | 只发布订单事实或发送指令，不再拼接通知文案、选择短信模板或判断发送重试。 |
| `ts-preserve-service` / Booking 模块 | 多段预订、占座失败、部分成功和补偿事件进入 Notification，不直接调用短信网关。 |
| `ts-payment-service` / `ts-inside-payment-service` | 支付和退款事件发布给 Notification；支付服务不关心 Push、Email、SMS 发送结果。 |
| `ts-ticketinfo-service` / Entitlement 模块 | 出票、票证作废和凭证摘要由事件提供，Notification 只负责触达和 Inbox 展示。 |
| `ts-cancel-service` / `ts-rebook-service` | 退改结果、手续费、差价和退款进度以事件驱动通知，不在售后服务中维护模板。 |
| `ts-travel-service` / Fulfillment 模块 | 出发提醒、检票提醒、值机开放和履约变化转成可调度 NotificationTask。 |
| `ts-user-service` / Account 模块 | 联系方式、Preference、Consent 归 Account；Notification 保存快照版本和应用结果。 |
| `ts-consign-service`, `ts-food-service`, `ts-assurance-service` | 附加服务的确认、核销、退款事件复用 NotificationTask，不单独实现发送链路。 |
| `ts-admin-order-service` | 人工补发、批量通知和模板操作转为受控命令，并写入 NotificationAudit。 |
| 外部短信、邮件、Push 网关 | 统一接入 Channel provider ACL，旧 provider 错误码迁移到标准失败分类。 |
| 客服系统 | 消费 NotificationTimelineView 和 RecipientUnreachable，不再查询多套短信、邮件、站内信表。 |

## 12. 验收标准

- [x] Notification 聚合所有权明确：`Notification`、`NotificationTask`、`Template`、`ChannelPolicy`、`DeliveryAttempt`、`InboxMessage`、`NotificationAudit` 由本域负责。
- [x] 明确 Notification 不决定订单、支付、售后、出票、履约或异常恢复结果，只消费事件或发送指令。
- [x] 明确发送失败不回滚业务事实，只形成重试、降级、客服介入、运营告警和审计记录。
- [x] 明确 Account 提供 ContactPoint、Preference、Consent 和账户状态权威快照，Notification 只应用这些快照。
- [x] 覆盖下单、支付、出票、退改、退款、履约提醒、异常恢复、联乘风险和批量运营通知。
- [x] 覆盖多旅客、代理人、企业联系人、客服和运营角色的 Recipient 建模及隐私裁剪。
- [x] 覆盖 Inbox、Push、Email、SMS 多 Channel 选择、降级、频控、去重、合并和紧急豁免。
- [x] 给出 NotificationTask、DeliveryAttempt、Template、InboxMessage 和 ChannelPolicy 状态边界。
- [x] 命令、领域事件、幂等键、读模型、ACL 和迁移影响清晰，跨域协作使用 Published Language。
