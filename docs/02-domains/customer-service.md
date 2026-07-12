# Customer Service Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Customer Service |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-customer-service |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/context-map.md`, `docs/02-domains/account.md`, `docs/02-domains/journey-order.md`, `docs/02-domains/payment.md`, `docs/02-domains/post-sales.md`, `docs/02-domains/disruption-recovery.md` |

## 1. 领域目标

Customer Service bounded context 负责用户求助、客服工单、统一时间线、人工动作入口、异常兜底、补偿请求、知识库和 SLA 管理。它把“用户遇到问题后如何被识别、解释、分派、协作、处理和闭环”建模为可审计的 `SupportCase`，让客服可以跨订单、支付、售后、异常恢复、账号和通知查看事实并发起受控处理。

Customer Service 是人工协作域，不是绕过核心聚合的万能修改入口。客服不能直接改 JourneyOrder、Payment、PostSales、Entitlement、Capacity 或 Provider 数据；所有人工动作必须转化为目标 domain 的受控命令，并携带 caseRef、operator、reason、evidence、approvalRef 和幂等键。Admin & Audit 负责后台权限、审批和审计治理，Customer Service 只保存本域工单、沟通、时间线、处理建议和人工动作结果。

本 domain 覆盖订单查询、支付争议、退改失败、异常恢复、投诉、人工补偿、多渠道客服、敏感信息脱敏和 SLA。它的成功标准是：客服能看到完整、可信、脱敏的用户问题上下文；所有人工介入可追踪、可回放、可授权；用户获得明确解释和下一步处理，不因人工操作破坏核心交易不变量。

## 2. 边界

### In Scope

- `SupportCase` 生命周期：创建、分类、分派、协作、挂起、升级、解决、关闭和重开。
- `CaseTimeline`：聚合 JourneyOrder、Payment、PostSales、Disruption Recovery、Account、Notification 等上下文的用户可解释事件。
- 多渠道客服入口：App、Web、电话、在线 IM、邮件、站内信、机器人、运营后台代建。
- 订单查询和解释：订单状态、票证摘要、支付摘要、售后摘要、异常恢复摘要、通知摘要的只读展示。
- 支付争议：late payment、重复扣款、退款失败、渠道回调停放、对账差异的客服处理入口。
- 退改失败和售后异常：售后步骤失败、供应商状态 Unknown、退款人工审核、改签补偿路径。
- 异常恢复协作：停运、晚点、换乘错过、批量 Incident 的用户咨询、代选方案和人工兜底。
- `ManualAction`：把客服动作转成目标 domain 的受控命令，记录请求、审批、执行结果和失败原因。
- `CompensationRequest`：人工补偿申请、审批、执行路由和结果跟踪。
- `AuditTrail`：客服域内操作轨迹、证据引用、话术确认、敏感信息访问记录。
- `KnowledgeBase`：问题分类、标准话术、处理流程、政策解释和机器人答案来源。
- `SLA`：首响、响应、解决、升级、超时预警和不同 Case 类型的服务承诺。
- 敏感信息脱敏：证件、手机号、邮箱、支付凭据、渠道报文、供应商原文只展示最小必要摘要。

### Out of Scope

- 不直接修改 JourneyOrder 的订单项、金额汇总、生命周期或售后入口；必须发 Journey Order 命令或消费其读模型。
- 不直接创建、捕获、退款或修正 PaymentIntent、Refund、LatePaymentCase；资金动作归 Payment。
- 不计算退改资格、手续费、差价、补偿规则或执行售后步骤；这些归 Post Sales 和 Fare & Pricing。
- 不作废、签发、恢复或核验 Entitlement；票证生命周期归 Entitlement & Ticketing。
- 不释放库存、锁新库存、调整配额或修正候补队列；这些归 Capacity & Availability。
- 不直接调用供应商售后、订票或状态查询原始接口；供应商交互归 Provider Integration 或相关编排域。
- 不拥有账号登录、安全策略、身份验证或风控判定；Account 和 Risk & Compliance 提供事实与受控命令。
- 不拥有后台权限、审批流模板、全局审计保全和操作员权限模型；这些归 Admin & Audit。
- 不负责通知模板、渠道发送和重试；Notification 消费客服事件或发送指令。
- 不拥有 Reporting 的经营分析模型；客服只发布工单事实和人工处理结果。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| `CustomerService` | 客服协作上下文，负责工单、时间线、人工动作入口、知识库和 SLA。 | 不等于后台万能管理端。 |
| `SupportCase` | 用户问题或运营代处理事项的聚合根。 | 可绑定 accountRef、journeyOrderId、paymentRef、postSalesCaseId、recoveryCaseId。 |
| `CaseTimeline` | 面向客服和用户解释的跨域时间线投影。 | 只读汇总，不推进其他 domain 状态。 |
| `ManualAction` | 客服发起的受控人工动作请求。 | 必须路由到目标 domain 命令。 |
| `CompensationRequest` | 人工补偿或安抚申请。 | 执行可能路由到 Post Sales、Payment、Promotion、Wallet 或人工赔付流程。 |
| `AuditTrail` | 客服域内不可变操作、访问、审批和证据记录。 | 全局审计治理仍归 Admin & Audit。 |
| `KnowledgeBase` | 标准问题、政策解释、处理流程和机器人答案库。 | 内容版本需要可追溯。 |
| `SLA` | 针对 Case 类型、优先级和渠道的响应与解决承诺。 | 影响升级和告警，不改变交易状态。 |
| `SensitiveDataMask` | 面向客服展示的脱敏规则和访问最小化策略。 | 完整敏感信息不落入工单正文。 |
| `CaseClassification` | 问题类型、责任域、优先级和处理队列的分类结果。 | 可由机器人、规则或人工修正。 |
| `EvidenceRef` | 用户截图、通话录音、聊天记录、渠道回执、供应商摘要的引用。 | 文件与权限由 Drive/Admin/Audit 或附件服务治理。 |
| `CustomerPromise` | 客服对用户给出的处理承诺。 | 必须绑定可执行动作或明确解释，不得替代业务事件。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Account | account profile view、联系方式掩码、账户安全时间线、`AccountFrozen`、受控账户命令结果 | 确认来访者身份、解释账户问题、处理盗号或联系方式申诉。 |
| Journey Order | OrderSummary、OrderDetail、OrderTimeline、`JourneyOrderCreated/Confirmed/Cancelled/Disrupted/Failed` | 客服查询订单和解释用户可见状态。 |
| Payment | `PaymentStatusView`、`RefundView`、`PaymentOperationTimeline`、`LatePaymentCaseView`、payment dispute events | 处理支付争议、退款异常、重复扣款和 late payment。 |
| Post Sales | PostSalesCaseView、PostSalesExecutionTimeline、ManualExceptionQueue、`PostSalesManualReviewRequired` | 处理退票、改签、退款和售后执行失败。 |
| Disruption Recovery | RecoveryCaseView、ManualRecoveryQueue、ServiceAlertFeed、batch impact view | 处理停运、晚点、换乘错过和批量异常咨询。 |
| Notification | delivery summary、message history、failed delivery events | 判断用户是否收到关键通知，必要时发起重发请求。 |
| Risk & Compliance | risk hold summary、abuse signal、客服可见风险结果 | 高风险工单进入人工审核或限制自动补偿。 |
| Admin & Audit | operator identity、permission decision、approval result、audit policy | 判断客服能否执行高风险动作，并记录治理证据。 |
| 用户和渠道 | 问题描述、附件、会话记录、联系方式、满意度反馈 | 创建 Case、补全证据和关闭反馈循环。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Journey Order | `ManualOrderActionRequested`、caseRef、operator、reason、evidenceRef、approved command payload | 人工取消、解释性标记或订单异常处理必须由 Journey Order 执行。 |
| Payment | `ManualPaymentActionRequested`、refund/late payment/dispute handling request | 渠道查询、退款重试、争议解决和事件补发由 Payment 保护资金不变量。 |
| Post Sales | `ManualPostSalesActionRequested`、waiver/approval/exception context | 人工退改、豁免、补偿售后和执行重试由 Post Sales Case 处理。 |
| Disruption Recovery | `ManualRecoveryActionRequested`、代选恢复方案、人工 Incident 证据 | 异常恢复代办、人工赔付或批量问题回填到 RecoveryCase。 |
| Account | `CustomerServiceAccountActionRequested` | 冻结、解冻、恢复联系方式、账号申诉处理由 Account 执行。 |
| Notification | `CustomerMessageRequested`、case update、promise reminder | 发送工单进展、补充材料请求、解决通知和满意度调查。 |
| Admin & Audit | `CustomerServiceActionRecorded`、approval request、sensitive access log | 高风险操作审批、审计保全和权限治理。 |
| Reporting | support case events、classification、SLA metrics、compensation outcome | 分析客服质量、投诉原因、SLA 达成和补偿成本。 |
| Knowledge consumers | versioned article、answer recommendation、handling playbook | 机器人、客服工作台和运营培训复用知识。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `SupportCase` | 每个 Case 必须有 requester、channel、classification、priority、owner queue 和关联业务引用；同一 account/order/problem 在去重窗口内不得重复创建多个 active Case；关闭前必须有 resolution 或明确无法处理原因；高风险操作必须先有授权结果。 | `OpenSupportCase`、`ClassifySupportCase`、`AssignSupportCase`、`LinkBusinessReference`、`AddCustomerMessage`、`RecordCaseNote`、`EscalateSupportCase`、`ResolveSupportCase`、`CloseSupportCase`、`ReopenSupportCase` | `SupportCaseOpened`、`SupportCaseClassified`、`SupportCaseAssigned`、`BusinessReferenceLinked`、`CustomerMessageAdded`、`CaseNoteRecorded`、`SupportCaseEscalated`、`SupportCaseResolved`、`SupportCaseClosed`、`SupportCaseReopened` |
| `CaseTimeline` | 时间线条目必须引用来源事件或人工记录；跨域事实只可追加，不可改写原 domain 事实；用户可见和客服可见内容必须区分脱敏级别。 | `AppendTimelineEntry`、`AttachExternalEvent`、`MaskTimelineEntry`、`PublishCustomerVisibleUpdate` | `CaseTimelineEntryAppended`、`ExternalEventAttachedToCase`、`CaseTimelineEntryMasked`、`CustomerVisibleCaseUpdatePublished` |
| `ManualAction` | 每个人工动作必须绑定 SupportCase、targetDomain、commandType、operator、reason、evidenceRef、permissionDecision；不得直接写目标域数据库；重复提交必须幂等；执行结果必须回填。 | `RequestManualAction`、`ApproveManualAction`、`DispatchManualActionCommand`、`RecordManualActionResult`、`RejectManualAction`、`CancelManualAction` | `ManualActionRequested`、`ManualActionApproved`、`ManualActionCommandDispatched`、`ManualActionResultRecorded`、`ManualActionRejected`、`ManualActionCancelled` |
| `CompensationRequest` | 补偿必须有责任来源、用户诉求、金额或权益、上限、执行上下文、审批结果；同一 Case 同一 purpose 不得重复补偿；补偿承诺不可早于审批或规则允许。 | `CreateCompensationRequest`、`QuoteCompensation`、`ApproveCompensationRequest`、`RouteCompensationExecution`、`RecordCompensationOutcome`、`RejectCompensationRequest` | `CompensationRequestCreated`、`CompensationQuoted`、`CompensationRequestApproved`、`CompensationExecutionRouted`、`CompensationOutcomeRecorded`、`CompensationRequestRejected` |
| `KnowledgeBase` | 文章必须有版本、适用场景、责任域、审核人和生效时间；下架版本不得继续推荐；客服话术不得承诺未授权业务结果。 | `CreateKnowledgeArticle`、`PublishKnowledgeArticle`、`RetireKnowledgeArticle`、`RecordArticleFeedback`、`RecommendKnowledgeArticle` | `KnowledgeArticleCreated`、`KnowledgeArticlePublished`、`KnowledgeArticleRetired`、`KnowledgeArticleFeedbackRecorded`、`KnowledgeArticleRecommended` |
| `SLA` | SLA 必须按 Case 类型、优先级、渠道和责任队列计算；暂停、升级、超时和恢复必须有原因；SLA 不能改变业务处理结果。 | `StartSLAClock`、`PauseSLAClock`、`ResumeSLAClock`、`EscalateSLA`、`RecordSLAOutcome` | `SLAClockStarted`、`SLAClockPaused`、`SLAClockResumed`、`SLAEscalated`、`SLAOutcomeRecorded` |
| `AuditTrail` | 访问敏感数据、人工动作、审批、承诺和关闭必须追加审计；审计记录不可被客服修改；证据只保存引用和摘要。 | `RecordSensitiveDataAccess`、`RecordOperatorAction`、`AttachEvidenceRef`、`SealCaseAuditTrail` | `SensitiveDataAccessRecorded`、`OperatorActionRecorded`、`EvidenceRefAttached`、`CaseAuditTrailSealed` |

## 6. 状态机

### `SupportCase` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Opened` | Case 已创建，等待分类或补充关键信息。 | `Classifying`, `WaitingCustomer`, `Closed` |
| `Classifying` | 正在识别问题类型、责任域、优先级和去重关系。 | `Assigned`, `WaitingCustomer`, `Closed` |
| `Assigned` | 已进入处理队列或分配给客服。 | `InProgress`, `Escalated`, `WaitingCustomer`, `Resolved` |
| `InProgress` | 客服正在查询、沟通或发起受控动作。 | `WaitingCustomer`, `WaitingExternal`, `Escalated`, `Resolved` |
| `WaitingCustomer` | 等待用户补充材料或确认方案。 | `InProgress`, `Resolved`, `Closed` |
| `WaitingExternal` | 等待目标 domain、审批或供应商间接结果。 | `InProgress`, `Escalated`, `Resolved` |
| `Escalated` | 已升级到专家、运营、财务、风险或主管队列。 | `InProgress`, `WaitingExternal`, `Resolved` |
| `Resolved` | 已给出解决方案或明确解释，等待关闭或评价。 | `Closed`, `Reopened` |
| `Closed` | 工单归档，审计封存。 | `Reopened` |
| `Reopened` | 用户反馈未解决或新事实出现。 | `InProgress`, `Escalated`, `Closed` |

### `ManualAction` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Draft` | 客服正在准备人工动作，尚未提交。 | `Requested`, `Cancelled` |
| `Requested` | 已提交权限和审批检查。 | `Approved`, `Rejected`, `Cancelled` |
| `Approved` | 已获得权限或审批，可派发目标命令。 | `Dispatched`, `Cancelled` |
| `Dispatched` | 受控命令已发送到目标 domain。 | `Succeeded`, `Failed`, `WaitingExternal` |
| `WaitingExternal` | 等待目标 domain 异步结果。 | `Succeeded`, `Failed` |
| `Succeeded` | 目标 domain 确认动作完成或接受。 | 终态 |
| `Failed` | 目标 domain 拒绝、超时或执行失败。 | 终态，可创建新动作重试 |
| `Rejected` | 权限、审批或规则拒绝。 | 终态 |
| `Cancelled` | 提交人取消且未产生不可逆副作用。 | 终态 |

### `CompensationRequest` 与 `SLA` 状态

- `CompensationRequest`: `Created`、`Quoted`、`PendingApproval`、`Approved`、`Routed`、`Settled`、`Rejected`、`Cancelled`。现金类执行结果来自 Payment 或 Finance Settlement；券、积分或人工安抚结果来自对应执行上下文。
- `SLA`: `Running`、`Paused`、`Breached`、`Escalated`、`Met`、`Waived`。SLA 超时只能触发升级、告警和报表，不允许自动改写交易事实。
- `KnowledgeBase`: `Draft`、`Reviewing`、`Published`、`Retired`。机器人和客服工作台只能推荐 `Published` 版本。

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `OpenSupportCase` | `SupportCase` | `SupportCaseOpened` | requesterRef + channel + problemHash + clientRequestId |
| `ClassifySupportCase` | `SupportCase` | `SupportCaseClassified` | caseId + classifierVersion + inputHash |
| `AssignSupportCase` | `SupportCase` | `SupportCaseAssigned` | caseId + queueId + assignmentAttempt |
| `LinkBusinessReference` | `SupportCase` | `BusinessReferenceLinked` | caseId + targetDomain + businessRef |
| `AddCustomerMessage` | `SupportCase` | `CustomerMessageAdded` | caseId + channelMessageId |
| `AppendTimelineEntry` | `CaseTimeline` | `CaseTimelineEntryAppended` | caseId + sourceEventId/timelineDigest |
| `RequestManualAction` | `ManualAction` | `ManualActionRequested` | caseId + targetDomain + commandType + requestDigest |
| `ApproveManualAction` | `ManualAction` | `ManualActionApproved` | manualActionId + approvalRef |
| `DispatchManualActionCommand` | `ManualAction` | `ManualActionCommandDispatched` | manualActionId + targetDomain + commandId |
| `RecordManualActionResult` | `ManualAction` | `ManualActionResultRecorded` | manualActionId + targetEventId/resultDigest |
| `CreateCompensationRequest` | `CompensationRequest` | `CompensationRequestCreated` | caseId + compensationPurpose + claimantRef |
| `ApproveCompensationRequest` | `CompensationRequest` | `CompensationRequestApproved` | compensationRequestId + approvalRef |
| `RouteCompensationExecution` | `CompensationRequest` | `CompensationExecutionRouted` | compensationRequestId + executionTarget + routeAttempt |
| `RecordCompensationOutcome` | `CompensationRequest` | `CompensationOutcomeRecorded` | compensationRequestId + targetEventId/outcomeDigest |
| `StartSLAClock` | `SLA` | `SLAClockStarted` | caseId + slaPolicyVersion |
| `EscalateSLA` | `SLA` | `SLAEscalated` | caseId + breachType + escalationAttempt |
| `CreateKnowledgeArticle` | `KnowledgeBase` | `KnowledgeArticleCreated` | articleKey + author + draftVersion |
| `PublishKnowledgeArticle` | `KnowledgeBase` | `KnowledgeArticlePublished` | articleId + version + reviewer |
| `RecordSensitiveDataAccess` | `AuditTrail` | `SensitiveDataAccessRecorded` | caseId + operatorId + dataClass + accessTimeBucket |
| `ResolveSupportCase` | `SupportCase` | `SupportCaseResolved` | caseId + resolutionVersion |
| `CloseSupportCase` | `SupportCase` | `SupportCaseClosed` | caseId + closeReason + closeAttempt |
| `ReopenSupportCase` | `SupportCase` | `SupportCaseReopened` | caseId + reopenReason + requesterRef |

事件必须包含 `eventId`、`occurredAt`、`caseId`、`accountRef`、`channel`、`operatorRef`、`correlationId`、`causationId`、`schemaVersion`。ManualAction 事件还必须包含 `targetDomain`、`targetCommandType`、`approvalRef`、`evidenceRef` 和脱敏后的 reason summary。

## 8. 策略和 Saga 参与点

- 订单查询策略：Customer Service 消费 Journey Order、Payment、Entitlement、Post Sales、Disruption Recovery 的读模型拼装 `CaseTimeline`；客服解释状态，但不直接推进订单。
- 支付争议策略：重复扣款、late payment、退款失败或渠道状态冲突时，客服创建 `ManualAction` 请求 Payment 主动查询、重试退款、打开或解决 dispute；Payment 决定资金动作是否可执行。
- 退改失败策略：Post Sales 执行步骤失败或进入人工队列时，Customer Service 展示失败原因、收集证据、请求人工豁免或重试；Post Sales 保护售后不变量。
- 异常恢复策略：Disruption Recovery 生成恢复方案后，客服可代用户选择、解释 waiver、收集补偿证据或升级人工恢复；恢复决策仍由 RecoveryCase 记录。
- 投诉处理策略：投诉类 Case 需要关联原业务对象、责任域和证据，必要时创建 `CompensationRequest`，但补偿执行必须路由到 Payment、Post Sales、Promotion、Wallet 或人工赔付流程。
- 多渠道会话策略：同一用户同一问题在 App、电话、IM、邮件重复进入时按 problemHash 合并或关联，避免不同客服重复承诺和重复人工动作。
- 敏感信息策略：客服默认只见脱敏摘要；查看完整证据或敏感字段需要权限、原因和 `SensitiveDataAccessRecorded`。
- SLA 策略：Case 创建时按类型和渠道启动 SLA；超时触发升级、提醒和报表，不自动退款、改签或补偿。
- 知识库策略：机器人优先使用 `KnowledgeBase` 发布版本回答；若答案涉及交易承诺，必须引导到受控 Case 或目标 domain 查询结果。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `SupportCaseView` | `SupportCaseOpened`、`SupportCaseClassified`、`SupportCaseAssigned`、`SupportCaseResolved`、`SupportCaseClosed` | 客服工作台、用户工单列表、Reporting。 |
| `CaseTimelineView` | Customer Service events、JourneyOrder、Payment、PostSales、Disruption、Notification、Account 摘要事件 | 客服详情页、用户进度页、人工兜底。 |
| `Customer360SupportView` | Account profile view、近期订单、近期 Case、投诉、风险摘要、偏好 | 客服识别用户和历史问题；按权限脱敏。 |
| `ManualActionQueue` | `ManualActionRequested`、approval events、target result events | 主管、专家队列、Admin & Audit、运营处理台。 |
| `PaymentDisputeSupportView` | Payment dispute、LatePaymentCase、Refund events、ManualAction events | 支付专员、客服、财务协作。 |
| `PostSalesExceptionSupportView` | PostSalesManualReview、step failure、refund progress、change progress | 售后专员、客服、运营。 |
| `DisruptionSupportConsole` | Incident、RecoveryCase、ServiceAlert、ManualRecovery events | 异常客服、运营指挥台、批量处理团队。 |
| `CompensationRequestView` | compensation request、approval、execution outcome events | 客服、主管、财务、Reporting。 |
| `SLAWorkQueue` | SLA events、case priority changes、assignment events | 客服主管、排班系统、运营管理。 |
| `KnowledgeRecommendationView` | article publish/retire、case classification、feedback events | 机器人、客服工作台、培训。 |
| `SensitiveAccessAuditView` | `SensitiveDataAccessRecorded`、operator actions、evidence refs | Admin & Audit、安全、合规。 |

读模型可以聚合跨域状态和脱敏摘要，但不得成为交易、资金、票证、库存或售后规则的权威来源。任何修正必须通过 `ManualAction` 转为目标 domain 命令。

## 10. 外部系统和防腐层

Customer Service 需要多渠道会话、工单附件、电话录音、机器人、知识库、客服工作台和遗留后台的防腐层。

| External / Legacy Boundary | ACL Need | Customer Service Design |
|---|---|---|
| 在线客服、IM、邮件、电话、App 反馈 | 渠道消息格式、会话 ID、用户身份、附件差异 | 统一映射为 `OpenSupportCase`、`AddCustomerMessage`、`EvidenceRef`。 |
| 电话系统和录音 | 通话 ID、录音地址、坐席状态、质检标签 | 只保存录音引用和摘要，敏感访问进入 `AuditTrail`。 |
| 机器人和智能客服 | 意图识别、答案置信度、转人工原因 | 输出 `CaseClassification` 和 Knowledge article 引用，不直接执行交易动作。 |
| 附件和截图存储 | 文件权限、病毒扫描、保留期、脱敏预览 | 工单只保存 `EvidenceRef`、摘要和访问级别。 |
| Admin & Audit 审批 | 操作员权限、审批流、风险阈值、审计保全 | ManualAction 高风险步骤先取审批结果，再派发目标命令。 |
| 遗留 `ts-admin-order-service` | 后台直接改订单、退款、售后状态 | LegacySupportACL 将旧操作拆成 Customer Service Case 和目标 domain 受控命令。 |
| 遗留客服备注表 | 自由文本、隐含承诺、无结构分类 | 迁移为 Case note、CustomerPromise、EvidenceRef 和脱敏时间线。 |

防腐规则：外部渠道原始用户文本可以保留为证据引用，但结构化 Case 字段必须使用平台统一分类；供应商原始报文、支付渠道凭据和完整证件不得写入工单正文；机器人置信度不足或涉及资金/票证动作时必须转人工或受控命令。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-admin-order-service` | 从直接后台改订单、退款、改签状态迁移为 Customer Service / Admin & Audit 受控入口；具体变更路由到 Journey Order、Payment、Post Sales。 |
| `ts-order-service`, `ts-order-other-service` | 提供 OrderTimeline 和 OrderDetail 读模型给客服；不接受客服直接写库。 |
| `ts-inside-payment-service`, `ts-payment-service` | 暴露 PaymentOperationTimeline、RefundView、LatePaymentCaseView；人工资金动作改为 Payment 命令。 |
| `ts-cancel-service`, `ts-rebook-service` | 售后异常、人工豁免、执行重试和补偿入口迁移到 Post Sales Case + Customer Service Case 协作。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 订票失败或供应商确认异常进入 SupportCase 时间线；重新预订或补偿由 Booking/Post Sales/Recovery 执行。 |
| `ts-execute-service` | 取票、进站、已使用等履约事实只进入时间线和售后判断输入，不由客服改写。 |
| `ts-notification-service` | 客服只请求发送工单进展和补充材料消息；发送状态作为时间线摘要。 |
| `ts-user-service`, `ts-auth-service` | 账号申诉、冻结、联系方式恢复转为 Account 受控命令，客服展示脱敏账号安全摘要。 |
| `ts-security-service` | 高风险工单、补偿滥用、敏感访问异常接入 Risk & Compliance；客服不拥有风控策略。 |
| `ts-assurance-service`, `ts-food-service`, `ts-consign-service` | 附加服务投诉和退款在 CaseTimeline 聚合，规则和执行归 Ancillary Service/Post Sales/Payment。 |
| 旧客服表、备注表、工单系统 | 迁移为 `SupportCase`、`CaseTimeline`、`ManualAction`、`AuditTrail`、`SLA` 和 KnowledgeBase 事件流。 |

迁移切片建议：先建立只读 Customer Service 工作台和统一 CaseTimeline；再把后台高风险写操作封装为 ManualAction；随后接入 Payment dispute、Post Sales exception、Disruption manual queue；最后迁移知识库、SLA、敏感访问审计和多渠道合并。

## 12. 验收标准

- [x] Customer Service 聚合所有权明确：`SupportCase`、`CaseTimeline`、`ManualAction`、`CompensationRequest`、`KnowledgeBase`、`SLA`、`AuditTrail` 属于本 domain。
- [x] 明确 Customer Service 是人工协作域，不直接修改 JourneyOrder、Payment、PostSales、Entitlement、Capacity 或 Provider 内部状态。
- [x] 人工动作必须转成目标 domain 受控命令，并携带 caseRef、operator、reason、evidenceRef、approvalRef 和幂等键。
- [x] Admin & Audit 负责权限、审批和全局审计治理；Customer Service 只保存工单内协作和操作轨迹。
- [x] 覆盖订单查询、支付争议、退改失败、异常恢复、投诉、人工补偿、多渠道客服和敏感信息脱敏。
- [x] `SupportCase`、`ManualAction`、`CompensationRequest`、`SLA`、`KnowledgeBase` 的状态机只定义本 domain 自有状态。
- [x] CaseTimeline 和客服读模型明确为只读解释层，不成为交易、资金、票证、库存或售后规则的事实来源。
- [x] 当前服务迁移影响覆盖后台订单、订单、支付、取消改签、订票、履约、通知、账号、安全、附加服务和旧工单系统。
