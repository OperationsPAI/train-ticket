# Admin & Audit Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Admin & Audit |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-admin-audit |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/context-map.md`, `docs/02-domains/customer-service.md`, `docs/02-domains/risk-compliance.md` |

## 1. 领域目标

Admin & Audit bounded context 负责后台权限、审批、人工操作治理、审计证据、配置发布审计、ManualOverride 策略和 OperatorAction 不可抵赖记录。它为运营、客服、财务、风控、配置管理员和合规审计员提供统一的授权、Approval、Policy、生效控制、Evidence 保全和 AuditTrail 查询能力。

本 domain 独立的原因是：后台操作横跨 Service Plan、Fare & Pricing、Capacity & Availability、Journey Order、Payment、Post Sales、Customer Service、Risk & Compliance、Finance Settlement 等上下文；若每个业务域自行实现权限、审批和审计，容易产生权限漂移、审批口径不一致、人工直改状态、证据丢失和追责困难。Admin & Audit 将“谁在什么上下文、基于什么理由和证据、经过什么审批、向哪个 domain 发出什么受控命令、产生什么结果”建模为可追溯、不可抵赖的治理链路。

Admin & Audit 不直接修改业务聚合内部状态。任何后台动作必须先通过 RBAC/ABAC、Policy、Approval 和 ManualOverride 校验，再转换为目标 domain 的受控命令，由目标 domain 自己保护不变量并发布结果事件。Customer Service 是工单协作和用户沟通域；Risk & Compliance 是风险决策和合规判断域；二者都不替代 Admin & Audit 的通用权限、审批、操作证据和审计治理。

## 2. 边界

### In Scope

- `OperatorIdentity`、角色、组织、岗位、值班、委托、临时授权和后台会话治理。
- RBAC/ABAC：基于角色、资源、数据范围、风险等级、时间窗口、工单、审批、环境和设备的访问决策。
- `Policy`：后台操作策略、敏感数据访问策略、ManualOverride 策略、四眼审批规则和配置发布规则。
- `Approval`：四眼审批、多人审批、职责分离、审批撤回、过期、升级、拒绝和结果证明。
- `OperatorAction`：每一次后台查询、敏感访问、命令派发、配置发布、审批和导出行为的不可抵赖记录。
- `ManualOverride`：价格、规则、库存、订单、退款、票证、风控、配置等人工干预的授权和执行治理。
- `ChangeRequest`：Service Plan、Fare & Pricing、Risk Policy、后台权限、配置开关和运营规则的变更申请、评审、发布与回滚审计。
- `Evidence`：工单、截图、录音、供应商摘要、渠道回执、审批意见、命令结果、读模型快照和哈希指纹保全。
- 敏感数据访问审计：证件、手机号、邮箱、支付摘要、供应商原文、风控证据、财务数据和批量导出的最小化与留痕。
- 审计查询和导出：按 operator、resource、domain、businessRef、approvalRef、policyVersion、timeWindow、reasonCode 查询。
- 遗留后台直接写操作的收敛：把旧接口包装成受控命令和审计事件。

### Out of Scope

- 不拥有 Journey Order、Payment、Post Sales、Capacity、Entitlement、Service Plan、Fare & Pricing 等业务聚合的内部状态机。
- 不计算票价、退改费、库存余量、订单状态、退款金额、合规拒绝或风险评分；这些由目标 domain 或 Risk & Compliance 决定。
- 不替代 Customer Service 的 `SupportCase`、用户沟通、SLA、客服知识库和工单协作流程。
- 不替代 Risk & Compliance 的 `RiskAssessment`、`ComplianceCheck`、`RiskHold`、`Challenge` 和风控规则语义。
- 不直接发送通知；Notification 根据 Admin & Audit 事件或命令发送审批提醒、发布通知和审计告警。
- 不作为 Reporting 的经营分析模型；审计读模型服务追责和治理，不反向修正业务统计。
- 不保存完整支付凭据、完整证件原文或供应商原始报文；只保存受控 Evidence 引用、摘要、哈希和访问级别。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| `OperatorIdentity` | 后台操作者身份，包含员工、系统账号、机器人、外包坐席和临时委托身份。 | 与 Account 用户身份分离。 |
| `RBAC` | 基于角色的权限控制。 | 解决“能否进入功能和执行动作”。 |
| `ABAC` | 基于属性的权限控制。 | 结合资源、数据范围、风险等级、时间、设备、工单和审批上下文。 |
| `Policy` | 后台治理策略，定义权限、审批、敏感访问、发布和 ManualOverride 条件。 | 已发布版本不可变。 |
| `Approval` | 对高风险操作或配置发布的审批聚合。 | 支持四眼原则和职责分离。 |
| `FourEyes` | 至少由提交人之外的独立审批人确认。 | 高风险资金、库存、价格和规则变更默认启用。 |
| `ManualOverride` | 人工覆盖自动流程或业务规则的受控请求。 | 只能派发目标 domain 命令。 |
| `ChangeRequest` | 配置、规则、权限或运营数据的变更申请。 | 包含评审、发布、回滚和影响范围。 |
| `OperatorAction` | 操作者执行的后台行为记录。 | 不可抵赖，必须绑定 actor、reason、resource、result。 |
| `AuditTrail` | 按业务对象或操作链串联的审计轨迹。 | 支撑追溯、监管和内部问责。 |
| `Evidence` | 支撑审批、人工动作或审计结论的证据引用和摘要。 | 原件由证据存储或源系统保管。 |
| `SensitiveAccess` | 访问敏感字段、原始报文、风控证据或批量导出的行为。 | 需要最小权限、原因和留痕。 |
| `Impersonation` | 运营以受控方式代用户或代系统执行动作。 | 必须标识真实 operator，不得覆盖用户身份。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Operator Directory / IAM | operator profile、组织、岗位、雇佣状态、MFA、设备可信状态、值班信息 | 形成 `OperatorIdentity` 和访问上下文。 |
| Customer Service | `SupportCase` 摘要、caseRef、ManualAction 请求、客服 Evidence、敏感访问请求 | 工单驱动的人工操作需要权限、Approval 和审计保全。 |
| Risk & Compliance | risk decision、risk level、policyVersion、EvidenceBundle、ReviewCase 结果 | 高风险动作、强合规拒绝和规则发布需要风险约束。 |
| Service Plan | schedule/config change draft、service disruption fact、发布结果事件 | 班次、停售、限售和运营日历变更需要治理。 |
| Fare & Pricing | fare rule draft、price adjustment request、售后规则版本、发布结果 | 价格和规则变更需要审批、发布审计和回滚证据。 |
| Capacity & Availability | inventory adjustment request、quota view、hold/release result events | 人工库存动作需避免绕过库存不变量。 |
| Journey Order / Post Sales / Payment | order/payment/refund/post-sales views、受控命令结果事件 | 后台订单、退款、售后动作需要执行结果和审计闭环。 |
| Finance Settlement | reconciliation exception、invoice/settlement evidence、财务导出请求 | 财务敏感操作和审计查询需要权限与证据。 |
| Provider Integration | provider response summary、manual provider operation result | 供应商人工交互只保存摘要和引用。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Customer Service | permission decision、approval result、auditRef、manual action status、sensitive access decision | 客服工作台依据治理结果展示和派发受控动作。 |
| Risk & Compliance | `OperatorActionRecorded`、manual override signals、policy change audit、abnormal access events | 风控使用后台行为信号识别内外部滥用。 |
| Service Plan | `ApplyServicePlanChange`、`PublishScheduleChange`、approvalRef、evidenceRef | 班次和运营日历变更由 Service Plan 执行。 |
| Fare & Pricing | `ApplyFareRuleChange`、`PublishPricingPolicy`、`ApprovePriceOverride` | 价格、税费、手续费和售后规则由 Fare & Pricing 执行。 |
| Capacity & Availability | `RequestInventoryAdjustment`、`ReleaseManualHold`、`AdjustQuota` | 人工库存动作由 Capacity 保护库存不变量。 |
| Journey Order | `RequestManualOrderAction`、auditRef、operatorRef、reasonCode | 订单人工动作由 Journey Order 自身状态机处理。 |
| Payment | `RequestManualPaymentAction`、approvalRef、evidenceRef | 退款重试、资金修正和渠道查询由 Payment 执行。 |
| Post Sales | `RequestManualPostSalesAction`、waiver context、approvalRef | 人工退改、豁免和售后重试由 Post Sales 执行。 |
| Reporting / Audit Consumers | audit export、operator metrics、policy compliance view | 治理分析和监管导出，不反向写业务域。 |
| Notification | approval reminder、change published notice、audit alert request | 通知审批人、发布干系人和安全团队。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `OperatorIdentity` | 每个 operator 必须绑定真实主体、认证强度、组织范围和有效期；离职、停用或委托过期后不能执行新动作；系统账号必须有 owner 和用途。 | `RegisterOperator`、`UpdateOperatorAttributes`、`GrantDelegation`、`RevokeDelegation`、`SuspendOperator` | `OperatorRegistered`、`OperatorAttributesUpdated`、`OperatorDelegationGranted`、`OperatorDelegationRevoked`、`OperatorSuspended` |
| `AccessPolicy` | 已发布 Policy 版本不可变；权限决策必须可复算；高风险动作必须要求 reason、resource、dataScope 和认证上下文。 | `DraftAccessPolicy`、`ApproveAccessPolicy`、`ActivateAccessPolicy`、`RetireAccessPolicy`、`EvaluateAccess` | `AccessPolicyApproved`、`AccessPolicyActivated`、`AccessPolicyRetired`、`AccessDecisionIssued` |
| `Approval` | 提交人与审批人职责分离；四眼审批不得由同一 operator 完成；审批必须绑定操作摘要、Policy 版本、Evidence 和过期时间。 | `RequestApproval`、`ApproveRequest`、`RejectRequest`、`EscalateApproval`、`ExpireApproval`、`WithdrawApproval` | `ApprovalRequested`、`ApprovalGranted`、`ApprovalRejected`、`ApprovalEscalated`、`ApprovalExpired`、`ApprovalWithdrawn` |
| `ManualOverride` | 必须有 targetDomain、targetCommand、businessRef、reasonCode、operator、approvalRef、evidenceRef 和幂等键；不得直接写目标库；结果必须回填。 | `RequestManualOverride`、`AuthorizeManualOverride`、`DispatchControlledCommand`、`RecordOverrideResult`、`CancelManualOverride` | `ManualOverrideRequested`、`ManualOverrideAuthorized`、`ControlledCommandDispatched`、`ManualOverrideResultRecorded`、`ManualOverrideCancelled` |
| `ChangeRequest` | 配置或规则变更必须有 diff、影响范围、回滚方案、审批和生效窗口；发布结果必须可追溯到版本。 | `CreateChangeRequest`、`AttachChangeEvidence`、`SubmitChangeReview`、`ApproveChangeRequest`、`PublishChange`、`RollbackChange` | `ChangeRequestCreated`、`ChangeEvidenceAttached`、`ChangeReviewSubmitted`、`ChangeRequestApproved`、`ChangePublished`、`ChangeRolledBack` |
| `Evidence` | Evidence 只可追加和封存；必须有来源、摘要、hash、访问级别、保留期和业务引用；敏感原件不进入审计正文。 | `RegisterEvidence`、`AttachEvidenceToAction`、`SealEvidence`、`SetEvidenceRetention`、`RecordEvidenceAccess` | `EvidenceRegistered`、`EvidenceAttachedToAction`、`EvidenceSealed`、`EvidenceRetentionSet`、`EvidenceAccessRecorded` |
| `OperatorActionLog` | 每个后台动作必须追加记录；记录不可静默修改；必须包含 actor、resource、purpose、decision、commandRef、result 和时间戳。 | `RecordOperatorAction`、`LinkActionResult`、`SealActionChain`、`MarkActionAnomaly` | `OperatorActionRecorded`、`OperatorActionResultLinked`、`OperatorActionChainSealed`、`OperatorActionAnomalyMarked` |
| `SensitiveAccessSession` | 访问敏感数据必须有最小数据范围、原因、时限和审批策略；批量导出必须额外记录接收方和用途。 | `RequestSensitiveAccess`、`GrantSensitiveAccess`、`DenySensitiveAccess`、`RecordSensitiveFieldViewed`、`CloseSensitiveAccessSession` | `SensitiveAccessRequested`、`SensitiveAccessGranted`、`SensitiveAccessDenied`、`SensitiveFieldViewed`、`SensitiveAccessSessionClosed` |

## 6. 状态机

### `Approval` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Requested` | 已提交审批请求，等待策略匹配和审批人分配。 | `PendingReview`、`Rejected`、`Withdrawn`、`Expired` |
| `PendingReview` | 审批人可查看证据并提交意见。 | `Approved`、`Rejected`、`Escalated`、`Expired` |
| `Escalated` | 超时、冲突或高风险导致升级审批。 | `Approved`、`Rejected`、`Expired` |
| `Approved` | 满足 Policy 和四眼规则，可授权后续动作。 | 终态 |
| `Rejected` | 审批人或 Policy 拒绝。 | 终态 |
| `Withdrawn` | 提交人撤回且未派发命令。 | 终态 |
| `Expired` | 超过有效期，必须重新申请。 | 终态 |

### `ManualOverride` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Draft` | 操作者准备人工覆盖请求。 | `Requested`、`Cancelled` |
| `Requested` | 已进入权限、Policy 和 Approval 检查。 | `Authorized`、`Denied`、`Cancelled` |
| `Authorized` | 已满足授权，可派发目标 domain 命令。 | `Dispatched`、`Cancelled` |
| `Dispatched` | 受控命令已发出，等待目标 domain 结果。 | `Succeeded`、`Failed`、`RejectedByTarget` |
| `Succeeded` | 目标 domain 接受并完成动作。 | 终态 |
| `Failed` | 技术失败、超时或结果未知。 | 终态，可基于新 Evidence 重试 |
| `RejectedByTarget` | 目标 domain 因不变量或业务规则拒绝。 | 终态 |
| `Denied` | 权限、Policy、Risk 或 Approval 拒绝。 | 终态 |
| `Cancelled` | 未产生外部副作用前取消。 | 终态 |

### `ChangeRequest` 与 `SensitiveAccessSession` 状态

- `ChangeRequest`: `Draft`、`Reviewing`、`Approved`、`Scheduled`、`Published`、`RollbackRequested`、`RolledBack`、`Rejected`、`Cancelled`。发布只能调用目标 domain 的配置命令，Admin & Audit 记录版本和结果。
- `SensitiveAccessSession`: `Requested`、`Granted`、`Denied`、`Active`、`Closed`、`Expired`、`Revoked`。每次查看敏感字段都追加 `SensitiveFieldViewed`。
- `Evidence`: `Registered`、`Attached`、`Sealed`、`ExpiredRetention`。封存后只能追加访问记录，不能改摘要和 hash。

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `EvaluateAccess` | `AccessPolicy` | `AccessDecisionIssued` | operatorId + resource + action + contextHash |
| `RequestApproval` | `Approval` | `ApprovalRequested` | requesterId + targetAction + businessRef + requestDigest |
| `ApproveRequest` | `Approval` | `ApprovalGranted` | approvalId + approverId + decisionVersion |
| `RejectRequest` | `Approval` | `ApprovalRejected` | approvalId + approverId + reasonCode |
| `RequestManualOverride` | `ManualOverride` | `ManualOverrideRequested` | targetDomain + businessRef + commandType + requestDigest |
| `AuthorizeManualOverride` | `ManualOverride` | `ManualOverrideAuthorized` | manualOverrideId + approvalRef + policyVersion |
| `DispatchControlledCommand` | `ManualOverride` | `ControlledCommandDispatched` | manualOverrideId + targetDomain + commandId |
| `RecordOverrideResult` | `ManualOverride` | `ManualOverrideResultRecorded` | manualOverrideId + targetEventId/resultDigest |
| `CreateChangeRequest` | `ChangeRequest` | `ChangeRequestCreated` | changeType + resourceRef + diffHash + requesterId |
| `PublishChange` | `ChangeRequest` | `ChangePublished` | changeRequestId + targetDomain + version |
| `RollbackChange` | `ChangeRequest` | `ChangeRolledBack` | changeRequestId + rollbackVersion + approverId |
| `RegisterEvidence` | `Evidence` | `EvidenceRegistered` | sourceSystem + sourceRef + evidenceHash |
| `AttachEvidenceToAction` | `Evidence` | `EvidenceAttachedToAction` | evidenceId + actionRef |
| `RequestSensitiveAccess` | `SensitiveAccessSession` | `SensitiveAccessRequested` | operatorId + dataClass + resourceRef + purpose |
| `GrantSensitiveAccess` | `SensitiveAccessSession` | `SensitiveAccessGranted` | accessSessionId + policyVersion + approverRef |
| `RecordSensitiveFieldViewed` | `SensitiveAccessSession` | `SensitiveFieldViewed` | accessSessionId + fieldClass + viewTimeBucket |
| `RecordOperatorAction` | `OperatorActionLog` | `OperatorActionRecorded` | operatorId + actionType + resourceRef + actionDigest |
| `MarkActionAnomaly` | `OperatorActionLog` | `OperatorActionAnomalyMarked` | actionId + anomalyType + detectorVersion |

所有事件必须包含 `eventId`、`occurredAt`、`operatorRef`、`actorType`、`resourceRef`、`resourceDomain`、`reasonCode`、`policyVersion`、`approvalRef`、`evidenceRef`、`correlationId`、`causationId`、`schemaVersion`。涉及敏感数据的事件只发布字段类别、掩码摘要和 Evidence 引用。

## 8. 策略和 Saga 参与点

- 权限策略：每次后台动作先执行 RBAC 粗筛，再执行 ABAC 精细判断；ABAC 输入包括资源归属、数据范围、Case、风险等级、MFA、设备、时间窗口和值班状态。
- 四眼审批策略：价格规则发布、退款人工修正、库存调整、订单强制取消、供应商原文访问、批量导出和风控放行必须由提交人之外的审批人确认。
- ManualOverride 策略：Admin & Audit 只派发受控命令；目标 domain 可因不变量拒绝，拒绝结果进入 `ManualOverrideResultRecorded` 和 `OperatorActionRecorded`。
- 敏感访问策略：默认展示脱敏摘要；查看完整证据或原始报文需要短时 `SensitiveAccessSession`，并记录每个字段类别的访问。
- 配置发布策略：`ChangeRequest` 完成评审后，按生效窗口向 Service Plan、Fare & Pricing、Risk & Compliance 或其他目标 domain 发布版本化命令；发布失败触发回滚或人工处置。
- 价格/规则治理策略：Fare & Pricing 拥有价格和规则语义；Admin & Audit 管理变更权限、Approval、发布证据和回滚轨迹。
- 库存人工动作策略：库存调整、释放锁定、配额变更只能请求 Capacity & Availability 执行；Admin & Audit 记录操作者、审批、原因和目标结果。
- 订单/退款人工动作策略：订单取消、售后豁免、退款重试、资金修正分别路由到 Journey Order、Post Sales、Payment；Admin & Audit 不写这些聚合状态。
- 内部滥用侦测策略：异常批量查询、非工作时间高敏访问、审批自批、频繁失败 ManualOverride 会发布信号给 Risk & Compliance。
- 监管审计策略：按业务对象串联 Evidence、Approval、OperatorAction、目标 domain 结果事件，形成可导出的 `AuditTrail`。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `OperatorPermissionView` | `OperatorRegistered`、`OperatorAttributesUpdated`、`AccessPolicyActivated`、`OperatorDelegationGranted/Revoked` | 后台网关、客服工作台、运营管理台。 |
| `AccessDecisionView` | `AccessDecisionIssued`、Policy events | 权限排障、审计、后台 API。 |
| `ApprovalQueueView` | `ApprovalRequested`、`ApprovalGranted`、`ApprovalRejected`、`ApprovalEscalated`、`ApprovalExpired` | 审批人、主管、客服、运营。 |
| `ManualOverrideConsole` | `ManualOverrideRequested`、`ManualOverrideAuthorized`、`ControlledCommandDispatched`、`ManualOverrideResultRecorded` | 后台操作台、Customer Service、审计员。 |
| `ChangeRequestReleaseView` | `ChangeRequestCreated`、`ChangeReviewSubmitted`、`ChangePublished`、`ChangeRolledBack` | 配置管理员、Service Plan、Fare & Pricing、Risk & Compliance。 |
| `SensitiveAccessAuditView` | `SensitiveAccessRequested`、`SensitiveAccessGranted`、`SensitiveFieldViewed`、`SensitiveAccessSessionClosed` | 安全、合规、审计和主管。 |
| `EvidenceRegistryView` | `EvidenceRegistered`、`EvidenceAttachedToAction`、`EvidenceSealed`、`EvidenceAccessRecorded` | 审计导出、客服、风险审核、法务。 |
| `OperatorActionTimeline` | `OperatorActionRecorded`、`OperatorActionResultLinked`、目标 domain 结果事件 | 追责、事故复盘、监管查询。 |
| `BusinessObjectAuditTrail` | Approval、ManualOverride、ChangeRequest、Evidence、OperatorAction、目标结果事件 | 按 orderId、paymentId、refundId、serviceId、ruleId 追溯。 |
| `PolicyComplianceDashboard` | Policy events、access decisions、approval results、anomaly marks | 内控、权限治理、管理层。 |

读模型可以冗余业务对象标题、脱敏用户信息、订单号、金额摘要、规则版本和客服 Case 编号，但不得成为业务域写侧事实来源。

## 10. 外部系统和防腐层

Admin & Audit 需要对身份、审批、证据、旧后台和安全工具建立 ACL，避免外部语言污染领域模型。

| External / Legacy Boundary | ACL Need | Admin & Audit Design |
|---|---|---|
| 企业 IAM / HR / SSO | 员工、外包、机器人、组织、岗位、离职、MFA 和设备状态 | 映射为 `OperatorIdentity` 属性和会话上下文。 |
| 审批工具 | 审批实例、审批人、意见、附件、超时和撤回 | 映射为 `Approval` 状态和 Evidence 引用；不让外部审批直接执行业务命令。 |
| 证据存储 / Drive / 录音系统 | 文件权限、保留期、水印、下载、hash 和病毒扫描 | Admin & Audit 保存 `Evidence` 引用、摘要、hash 和访问记录。 |
| 后台网关 | 菜单、API、资源、按钮、数据范围 | 统一调用 `EvaluateAccess` 并追加 `OperatorAction`。 |
| SIEM / 安全审计工具 | 异常检测、告警、日志查询 | 接收审计事件和异常标记，返回告警只作为 Evidence 或 anomaly。 |
| 遗留 `ts-admin-order-service` | 直接改订单、退款、库存、票证和备注 | LegacyAdminACL 将旧操作拆为 ManualOverride、Approval 和目标 domain 受控命令。 |
| 遗留配置表和后台脚本 | 无版本、无审批、直接生效 | 迁移为 `ChangeRequest`、版本化发布命令和回滚记录。 |

防腐规则：外部审批状态、IAM 角色名、旧后台按钮名和安全工具告警码不得直接成为业务权限语义；必须映射到 Admin & Audit 的 Policy、Approval、Evidence 和 OperatorAction 语言。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-admin-order-service` | 从直接修改订单、退款、库存、售后状态迁移为 ManualOverride + Approval + 目标 domain 受控命令；保留旧入口时必须通过 LegacyAdminACL。 |
| `ts-admin-basic-info-service` | 班次、站点、运营日历、基础配置的后台变更迁移为 ChangeRequest，执行路由到 Service Plan 或 Place & Network。 |
| `ts-price-service` / fare 相关配置 | 价格、折扣、手续费、退改规则后台发布迁移为 Fare & Pricing 的版本化命令，Admin & Audit 负责审批和发布审计。 |
| `ts-travel-service` / route 相关后台 | 运营规则、线路和服务展示配置进入 ChangeRequest；目标 domain 保留语义校验。 |
| `ts-security-service` | 后台异常访问、审批绕行、批量导出信号进入 Risk & Compliance；权限和审计治理收敛到 Admin & Audit。 |
| `ts-inside-payment-service` / `ts-payment-service` | 退款重试、资金修正、渠道查询等后台动作需走 Payment 受控命令和 Approval，不允许人工写资金状态。 |
| `ts-cancel-service` / `ts-rebook-service` | 人工退改、豁免、售后重试迁移为 Post Sales 受控命令；Admin & Audit 保存审批和 Evidence。 |
| `ts-preserve-service` / `ts-ticket-office-service` | 占座、出票、供应商处理异常的后台动作改为 Booking/Entitlement/Provider 受控命令。 |
| `ts-notification-service` | 审批提醒、发布通知、审计告警由 Notification 发送；Admin & Audit 只发布通知请求。 |
| 旧权限表、角色表、操作日志 | 迁移为 `OperatorIdentity`、`AccessPolicy`、`OperatorActionLog` 和 `SensitiveAccessSession` 事件流。 |
| 旧配置发布脚本 | 纳入 `ChangeRequest` 和 `OperatorAction`，补齐 diff、审批、发布窗口、回滚和结果事件。 |

迁移切片建议：先接入后台网关权限决策和 OperatorAction 追加日志；再封装高风险订单、退款、库存和价格操作为 ManualOverride；随后把规则与配置发布迁移到 ChangeRequest；最后治理敏感访问、证据保全、审计导出和旧脚本关闭。

## 12. 验收标准

- [x] Admin & Audit 聚合所有权明确：`OperatorIdentity`、`AccessPolicy`、`Approval`、`ManualOverride`、`ChangeRequest`、`Evidence`、`OperatorActionLog`、`SensitiveAccessSession` 属于本 domain。
- [x] 明确本 domain 不直接修改业务聚合内部状态，后台动作必须转换为目标 domain 的受控命令。
- [x] 明确 Customer Service 是工单协作域，Risk & Compliance 是风险决策域，二者不替代审计治理。
- [x] 覆盖 RBAC/ABAC、四眼审批、敏感数据访问审计、Evidence 保全和 OperatorAction 不可抵赖记录。
- [x] 覆盖价格、规则、库存、订单、退款、配置发布和风控放行等 ManualOverride 治理场景。
- [x] Approval、ManualOverride、ChangeRequest、SensitiveAccessSession 状态机只定义本 domain 自有状态。
- [x] 所有高风险动作都要求 operator、reasonCode、policyVersion、approvalRef、evidenceRef、correlationId 和目标结果回填。
- [x] 审计读模型明确为追责和治理视图，不成为订单、支付、库存、票证、价格或售后状态权威来源。
- [x] 当前服务迁移影响覆盖后台订单、基础信息、价格、支付、退改、出票、安全、通知、旧权限和脚本链路。
