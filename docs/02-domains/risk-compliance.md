# Risk & Compliance Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Risk & Compliance |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-risk-compliance |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/domain-glossary.md`, `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/general-travel-ddd.md`, `docs/02-domains/account.md`, `docs/02-domains/traveler-profile.md`, `docs/02-domains/payment.md` |

## 1. 领域目标

Risk & Compliance bounded context 负责在查询、报价、下单、支付、售后、账户与旅客资料变更等关键节点给出可解释、可审计、可复算的风险与合规决策。它把账号、旅客、设备、行程、订单、支付方式、售后行为、外部名单和政策规则转化为 `RiskAssessment`、`ComplianceCheck`、`FraudSignal`、`PolicyRule`、`SanctionScreening`、`AbuseControl`、`Challenge`、`RiskHold` 等统一语言。

本领域独立的原因是：风险和合规规则变化频率高、证据来源多、解释与审计要求强，且必须横跨 Account、Traveler Profile、Journey Order、Payment、Post Sales、Offer Management 等上下文，但又不能拥有这些上下文的核心状态。Risk & Compliance 只发布 `allow`、`deny`、`challenge`、`hold`、`release` 等决策和证据，不直接创建订单、扣款、出票、退款或修改旅客资料。

核心目标：

1. 在下单前识别黄牛、刷票、撞库、重复行程、证件异常、跨境限制、特殊旅客资格风险。
2. 在支付前识别盗刷、异常支付方式、账户接管、黑卡、重复扣款诱导、渠道风险。
3. 在售后阶段识别重复退款、恶意退改、批量薅补偿、异常客服申诉。
4. 对高风险场景生成 `Challenge` 或 `RiskHold`，让业务域暂停或要求补充验证。
5. 管理 `PolicyRule` 版本、命中解释、人工审核记录和审计证据链。
6. 通过防腐层接入实名、制裁、黑名单、设备指纹、行为风控和监管合规服务。

## 2. 边界

### In Scope

- `RiskAssessment`：对账号、旅客、设备、行程、订单、支付、售后请求进行风险评分、分层和决策。
- `ComplianceCheck`：实名、证件、年龄、跨境、地区政策、特殊旅客、限购、监管限制检查。
- `FraudSignal`：归一化来自登录、搜索、下单、支付、退款、客服、外部名单的风险信号。
- `PolicyRule`：规则版本、适用范围、阈值、灰度、解释模板、启停与审批轨迹。
- `SanctionScreening`：制裁名单、黑名单、失信名单、监管限制名单命中与复核。
- `AbuseControl`：限流、限购、频控、设备/证件/支付方式/行程维度滥用控制。
- `Challenge`：短信、实名再验证、人脸、支付二次验证、人工补件等挑战生命周期。
- `RiskHold`：对订单、支付、售后或账户动作给出暂停、释放、拒绝的风险保持决策。
- 人工审核、误杀申诉、规则解释、证据保全和审计导出。
- Outbox/Inbox 幂等消费与发布风险决策事件。

### Out of Scope

- Account 的注册、登录、会话、账号冻结最终状态；Risk & Compliance 只建议挑战、限制或冻结理由。
- Traveler Profile 的旅客资料、证件主数据、优惠资质主数据；本域只消费快照并发布合规结果。
- Journey Order 的订单创建、取消、确认、完成、售后入口状态。
- Payment 的 `PaymentIntent`、扣款、退款、渠道回调和资金状态机。
- Booking Orchestration 的库存保留、供应商确认、出票编排和补偿 Saga。
- Post Sales 的退改资格、手续费和退款金额计算；本域只识别滥用和合规风险。
- Customer Service 的工单状态；本域提供审核结论和证据，不替代客服流程。
- Admin & Audit 的通用权限审批；本域拥有风险规则和审核证据，仍需接受后台权限约束。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| `RiskAssessment` | 对一次业务动作的风险评估结果，包含 subject、scenario、score、level、decision、evidence、policyVersion。 | 聚合根，按场景幂等生成。 |
| `ComplianceCheck` | 对实名、证件、跨境、年龄、地区、特殊旅客和监管规则的检查结果。 | 可被 `RiskAssessment` 引用。 |
| `FraudSignal` | 一个可用于风险判断的归一化信号，如设备异常、短时高频搜索、支付失败聚集。 | 来自多上下文或外部服务。 |
| `PolicyRule` | 风险或合规规则定义，包含条件、动作、阈值、优先级、版本和解释。 | 规则版本不可变发布。 |
| `SanctionScreening` | 对人、证件、手机号、支付工具、地区或供应商名单的筛查。 | 命中后通常进入 `hold` 或人工复核。 |
| `AbuseControl` | 针对黄牛、刷票、限购绕过、重复退款、补偿滥用的控制策略。 | 包含频控和配额。 |
| `Challenge` | 要求用户、旅客或客服补充验证的过程。 | 通过后可 `release`。 |
| `RiskHold` | 风险域对外部业务动作施加的暂停决策。 | 不等于订单状态，只是协作约束。 |
| `Decision` | `allow`、`deny`、`challenge`、`hold`、`release`。 | 对外发布的稳定枚举。 |
| `EvidenceBundle` | 决策依赖的信号、规则、外部返回摘要和人工说明。 | 支撑审计和申诉。 |
| `ReviewCase` | 人工审核风险或合规决策的案例。 | 与 Customer Service 工单可互链。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Account | `AccountRegistered`、`LoginSucceeded`、`LoginFailed`、`SessionChanged`、账号安全摘要 | 识别账户接管、撞库、异常登录和账号信誉。 |
| Traveler Profile | 旅客证件快照、年龄、国籍/地区、特殊旅客标记、实名验证结果 | 执行实名、跨境、年龄、特殊旅客和黑名单检查。 |
| Offer Management | `OfferQuoted`、价格、库存紧张度、风险提示、有效期 | 判断刷票、套利、异常报价组合和下单前合规。 |
| Journey Order | `JourneyOrderCreated`、旅客列表、行程、订单金额、渠道、历史订单摘要 | 下单风险、重复行程、限购、黄牛和冲突行程判断。 |
| Payment | `PaymentIntentCreated`、`PaymentCaptured`、支付方式摘要、失败原因、退款状态 | 支付风险、盗刷、黑卡、异常支付失败聚集和退款滥用判断。 |
| Post Sales | 退改请求、原因、金额、频次、历史售后摘要 | 重复退款、恶意退改和补偿滥用识别。 |
| Fulfillment / Entitlement | 登乘、验票、未乘、票证作废事实 | 识别 No-show 滥用、票证异常使用和售后证据。 |
| Customer Service | 申诉、人工补件、客服备注、误杀反馈 | 支撑人工审核、规则调优和解释。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Account | `AccountRiskDecisionIssued`、`ChallengeRequired`、账号限制建议 | 账号域按自身状态机执行挑战、冻结或解冻。 |
| Traveler Profile | `TravelerComplianceChecked`、`SanctionScreeningMatched`、资料复核建议 | 旅客域处理资料修正或重新实名。 |
| Offer Management | 查询/报价阶段 `allow`、`deny`、`challenge`、风险提示 | 防止明显不可售或高风险方案进入报价。 |
| Journey Order | `OrderRiskDecisionIssued`、`RiskHoldPlaced`、`RiskHoldReleased`、`RiskDenied` | 订单域决定是否继续创建、确认或进入异常。 |
| Booking Orchestration | Saga 参与点的风险阻断或释放事件 | 编排域暂停占座、出票或继续流程。 |
| Payment | `PaymentRiskDecisionIssued`、支付方式限制、二次验证要求 | Payment 根据风险决策决定是否提交渠道。 |
| Post Sales | `RefundRiskDecisionIssued`、售后 `RiskHold`、人工审核要求 | 售后域决定是否执行退款或等待审核。 |
| Customer Service / Admin & Audit | `ReviewCaseOpened`、`EvidenceBundleReady`、审核结论 | 支撑人工处理、审计和监管留痕。 |
| Reporting | 风险指标、命中率、误杀率、审核积压、规则效果 | 运营分析，不反向修改交易状态。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `RiskAssessment` | 同一 subject + scenario + businessRef + policyVersion + idempotencyKey 只能产生一个语义等价评估；决策必须保留命中规则和证据摘要；终态不可被静默覆盖。 | `AssessRisk`、`AppendFraudSignal`、`IssueRiskDecision`、`SupersedeAssessment` | `RiskAssessmentCreated`、`RiskDecisionIssued`、`RiskAssessmentSuperseded` |
| `ComplianceCheck` | 检查输入快照、外部名单版本、规则版本必须可追溯；检查结果不得修改旅客主数据；命中高敏名单必须进入审核或拒绝。 | `RunComplianceCheck`、`RecordSanctionScreening`、`ConfirmComplianceResult` | `ComplianceCheckCompleted`、`SanctionScreeningMatched`、`ComplianceResultConfirmed` |
| `PolicyRuleSet` | 已发布版本不可变；同一场景只能有一个 active baseline；高风险规则变更必须有审批和回滚版本。 | `DraftPolicyRule`、`ApprovePolicyRuleSet`、`ActivatePolicyRuleSet`、`RetirePolicyRuleSet` | `PolicyRuleSetApproved`、`PolicyRuleSetActivated`、`PolicyRuleSetRetired` |
| `Challenge` | 一个业务动作同一挑战类型在有效期内只能有一个 active challenge；通过、失败、过期必须有证据；挑战结果只能释放对应 `RiskHold`。 | `CreateChallenge`、`PassChallenge`、`FailChallenge`、`ExpireChallenge` | `ChallengeCreated`、`ChallengePassed`、`ChallengeFailed`、`ChallengeExpired` |
| `RiskHold` | hold 必须绑定业务引用、原因、释放条件和过期策略；不能直接改变外部业务状态；释放必须满足规则、挑战或人工审核结论。 | `PlaceRiskHold`、`ReleaseRiskHold`、`DenyHeldAction`、`ExpireRiskHold` | `RiskHoldPlaced`、`RiskHoldReleased`、`RiskHoldDenied`、`RiskHoldExpired` |
| `ReviewCase` | 人工审核必须记录 operator、reason、evidence、前后决策；审核不能绕过强制合规拒绝规则。 | `OpenReviewCase`、`AssignReviewer`、`SubmitReviewDecision`、`CloseReviewCase` | `ReviewCaseOpened`、`ReviewDecisionSubmitted`、`ReviewCaseClosed` |

## 6. 状态机

### `RiskAssessment` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Requested` | 已收到评估请求，等待信号和规则执行。 | `Evaluating`、`Failed` |
| `Evaluating` | 正在执行规则、模型、名单和聚合信号。 | `Allowed`、`Denied`、`Challenged`、`Held`、`ManualReview`、`Failed` |
| `Allowed` | 风险允许继续。 | `Superseded` |
| `Denied` | 风险或合规拒绝。 | `ManualReview`、`Superseded` |
| `Challenged` | 需要用户或旅客完成 `Challenge`。 | `Allowed`、`Denied`、`Held`、`Superseded` |
| `Held` | 已生成 `RiskHold`，外部动作应暂停。 | `Allowed`、`Denied`、`ManualReview`、`Superseded` |
| `ManualReview` | 等待人工审核。 | `Allowed`、`Denied`、`Held` |
| `Failed` | 评估执行失败且不能给出可信决策。 | `ManualReview`、`Superseded` |
| `Superseded` | 被新版本评估替代。 | 终态 |

### `Challenge` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Created` | 挑战已创建，尚未发送或展示。 | `PendingUserAction`、`Expired`、`Cancelled` |
| `PendingUserAction` | 等待用户、旅客或客服完成验证。 | `Passed`、`Failed`、`Expired`、`Cancelled` |
| `Passed` | 验证通过，可触发 release 决策。 | 终态 |
| `Failed` | 验证失败，可触发 deny 或继续 hold。 | 终态 |
| `Expired` | 超过有效期。 | 终态 |
| `Cancelled` | 业务动作取消或评估被替代。 | 终态 |

### `RiskHold` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Placed` | hold 已对外发布。 | `Released`、`Denied`、`Expired`、`ManualReview` |
| `ManualReview` | 需要人工确认释放或拒绝。 | `Released`、`Denied`、`Expired` |
| `Released` | 释放外部动作继续执行。 | 终态 |
| `Denied` | 拒绝外部动作继续。 | 终态 |
| `Expired` | hold 超时，按策略拒绝或要求重新评估。 | 终态 |

### `ReviewCase` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Opened` | 审核案例已创建。 | `Assigned`、`Closed` |
| `Assigned` | 已分配审核人或队列。 | `EvidenceRequested`、`DecisionSubmitted`、`Closed` |
| `EvidenceRequested` | 需要用户、旅客或外部系统补充证据。 | `Assigned`、`DecisionSubmitted`、`Closed` |
| `DecisionSubmitted` | 审核结论已提交并触发决策事件。 | `Closed` |
| `Closed` | 审核结束。 | 终态 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `AssessOrderRisk` | `RiskAssessment` | `OrderRiskDecisionIssued` | orderId + scenario + policyVersion + requestId |
| `AssessPaymentRisk` | `RiskAssessment` | `PaymentRiskDecisionIssued` | paymentIntentId + paymentAttemptId + policyVersion |
| `AssessPostSalesRisk` | `RiskAssessment` | `RefundRiskDecisionIssued` | postSalesCaseId + refundPurpose + policyVersion |
| `AssessAccountRisk` | `RiskAssessment` | `AccountRiskDecisionIssued` | accountId + eventId + scenario |
| `RunTravelerComplianceCheck` | `ComplianceCheck` | `TravelerComplianceChecked` | travelerSnapshotId + itineraryRef + policyVersion |
| `RunSanctionScreening` | `ComplianceCheck` | `SanctionScreeningMatched` / `SanctionScreeningCleared` | subjectHash + listVersion + checkPurpose |
| `RecordFraudSignal` | `RiskAssessment` | `FraudSignalRecorded` | sourceEventId + signalType + subjectRef |
| `CreateChallenge` | `Challenge` | `ChallengeCreated` | businessRef + challengeType + assessmentId |
| `SubmitChallengeResult` | `Challenge` | `ChallengePassed` / `ChallengeFailed` | challengeId + resultAttemptId |
| `PlaceRiskHold` | `RiskHold` | `RiskHoldPlaced` | businessRef + holdReason + assessmentId |
| `ReleaseRiskHold` | `RiskHold` | `RiskHoldReleased` | holdId + releaseReason + reviewerOrChallengeId |
| `DenyHeldAction` | `RiskHold` | `RiskHoldDenied` | holdId + denyReason + decisionId |
| `OpenReviewCase` | `ReviewCase` | `ReviewCaseOpened` | assessmentId + reviewReason |
| `SubmitReviewDecision` | `ReviewCase` | `ReviewDecisionSubmitted` | reviewCaseId + decisionVersion |
| `ActivatePolicyRuleSet` | `PolicyRuleSet` | `PolicyRuleSetActivated` | ruleSetId + version + approvalId |

所有命令通过 `Inbox` 记录 producer event id 或 caller idempotency key；所有事件通过 `Outbox` 原子发布，事件载荷必须包含 policyVersion、evidenceRef 和 decision reason code。

## 8. 策略和 Saga 参与点

- **报价前策略**：消费旅客证件快照、路线、地区和特殊旅客信息，返回合规风险提示或 `deny`，避免用户进入不可售报价。
- **下单策略**：Journey Order 创建前或创建后立即请求 `AssessOrderRisk`；若返回 `hold`，订单域暂停确认，Booking Orchestration 不应继续占座或出票。
- **支付策略**：Payment 在 `SubmitPayment` 或渠道提交前请求 `AssessPaymentRisk`；若返回 `challenge`，Payment 暂停渠道提交并等待挑战结果。
- **售后策略**：Post Sales 在退款执行前请求 `AssessPostSalesRisk`；重复退款、异常高频退改、补偿滥用进入 `RiskHold` 或 `ReviewCase`。
- **账户接管策略**：Account 发现异常登录后发布事件，本域可要求 `Challenge`、限制支付方式、限制下单或建议账号冻结。
- **跨境与特殊旅客策略**：对国籍、证件、年龄、签证/地区规则、儿童老人残障携宠等信息执行 `ComplianceCheck`，结果只约束是否继续交易，不修改 Traveler Profile。
- **人工审核策略**：`ReviewCase` 可由高风险规则、客服申诉、名单命中或规则冲突触发；审核结论必须回写为风险事件，不直接调用订单、支付或售后内部接口。
- **规则灰度策略**：新 `PolicyRuleSet` 可以 shadow mode 运行，仅生成观察事件；进入 enforce mode 后才能发布阻断类决策。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `RiskDecisionView` | `RiskDecisionIssued`、`RiskAssessmentSuperseded` | Journey Order、Payment、Post Sales、客服查询 |
| `ComplianceResultView` | `ComplianceCheckCompleted`、`SanctionScreeningMatched` | Traveler Profile、Offer Management、Journey Order |
| `FraudSignalTimeline` | `FraudSignalRecorded`、账号/订单/支付/售后事件摘要 | 风控运营、人工审核、排障 |
| `RiskHoldView` | `RiskHoldPlaced`、`RiskHoldReleased`、`RiskHoldDenied` | Journey Order、Booking Orchestration、Payment、Post Sales |
| `ChallengeView` | `ChallengeCreated`、`ChallengePassed`、`ChallengeFailed`、`ChallengeExpired` | Account、Payment、客服、客户端 API |
| `ReviewCaseQueueView` | `ReviewCaseOpened`、`ReviewDecisionSubmitted`、`ReviewCaseClosed` | Customer Service、Admin & Audit、风险审核团队 |
| `PolicyRuleVersionView` | `PolicyRuleSetApproved`、`PolicyRuleSetActivated`、`PolicyRuleSetRetired` | 风控运营、审计、Reporting |
| `EvidenceBundleView` | 评估、名单、挑战、人工审核事件 | 审计、监管导出、误杀申诉 |
| `AbuseControlMetricsView` | 风险决策、频控、限购、售后命中事件 | Reporting、运营治理 |

读模型可以冗余订单号、旅客展示名、支付方式摘要和客服工单号，但不得成为 Account、Traveler Profile、Payment 或 Journey Order 的写侧事实来源。

## 10. 外部系统和防腐层

Risk & Compliance 需要多个 ACL，所有外部返回都必须转换成平台统一语义并保留原始摘要：

- **实名与证件 ACL**：验证姓名、证件号、出生日期、证件有效期和地区规则；返回 `verified`、`mismatch`、`expired`、`manualReviewRequired` 等统一结果。
- **SanctionScreening ACL**：接入制裁名单、失信名单、黑名单、监管限制名单；返回 listVersion、matchStrength、matchedFields、reviewRequirement。
- **Device & Behavior ACL**：接入设备指纹、IP 信誉、代理/VPN、自动化行为、搜索/点击频率信号；返回 `FraudSignal`。
- **Payment Risk ACL**：接入卡 BIN、支付工具信誉、渠道风控、盗刷风险；不得直接改变 `PaymentIntent`。
- **Policy Engine ACL**：如使用外部规则引擎或模型服务，领域内仍以 `PolicyRuleSet` 和 `RiskAssessment` 解释最终决策。
- **Manual Review Tool ACL**：人工审核工具只提交审核事实，不能绕过聚合发布 `release` 或 `deny`。
- **Legacy Security ACL**：迁移期把 `ts-security-service` 等旧安全服务的结果映射为 `FraudSignal`、`Challenge` 或 `RiskDecision`。

防腐规则：外部名单命中原文、模型分数、设备厂商状态码不得直接暴露给业务域；对外只发布决策、原因码、解释摘要、证据引用和可申诉路径。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-security-service` | 收敛为 Risk & Compliance 的信号采集、账号风险、Challenge 和 ACL 适配能力；拆出对订单/支付的直接写副作用。 |
| `ts-order-service` | 下单流程改为请求 `AssessOrderRisk` 并消费 `OrderRiskDecisionIssued`；不得内置黑名单和重复行程规则。 |
| `ts-preserve-service` | 在占座或出票前遵守 `RiskHold`；风险释放前不继续供应侧确认。 |
| `ts-payment-service` / `ts-inside-payment-service` | 支付前调用 `AssessPaymentRisk`，消费支付风险决策；支付渠道回调仍归 Payment，不归风控。 |
| `ts-cancel-service` / `ts-rebook-service` | 退改前请求售后风险评估；退款金额和资格仍归 Post Sales，风控只提供滥用判断。 |
| `ts-wait-order-service` | 候补、抢票、刷票和限购策略迁入 `AbuseControl`；候补业务状态仍归对应订单/编排域。 |
| `ts-admin-order-service` | 后台人工放行、驳回、规则命中解释改为 `ReviewCase` 和受控命令；操作进入审计证据链。 |
| `ts-assurance-service`、`ts-food-service`、`ts-consign-service` | 附加服务售后滥用信号可进入本域，但商品规则和退款执行仍由商品域与 Payment 负责。 |
| 旧黑名单/白名单表 | 迁移为 `PolicyRuleSet`、`SanctionScreening` 或 `AbuseControl` 数据，补齐版本、来源、审批和失效时间。 |
| 旧风控日志 | 迁移为 `EvidenceBundleView` 和 `FraudSignalTimeline`，支撑申诉与监管审计。 |

迁移顺序建议：先建立风险决策事件和只读命中解释；再把下单、支付、售后硬编码规则迁移为 `PolicyRuleSet`；随后引入 `RiskHold` 与 `Challenge`；最后完成名单、人工审核和规则版本审计闭环。

## 12. 验收标准

- [x] Risk & Compliance 聚合所有权明确：`RiskAssessment`、`ComplianceCheck`、`PolicyRuleSet`、`SanctionScreening`、`AbuseControl`、`Challenge`、`RiskHold`、`ReviewCase` 均在本域内建模。
- [x] 明确本域不拥有 Account、Traveler Profile、Payment、Journey Order、Booking Orchestration、Post Sales 的核心状态。
- [x] 对外决策限定为 `allow`、`deny`、`challenge`、`hold`、`release`，并携带 policyVersion、reason code 和 evidenceRef。
- [x] 下单、支付、售后、账户、旅客资料、跨境和特殊旅客合规场景均被覆盖。
- [x] 黄牛、刷票、重复退款、支付风险、账户接管、黑名单、制裁名单、实名和滥用控制均有模型落点。
- [x] `Challenge`、`RiskHold`、`ReviewCase` 状态机不依赖其他上下文内部状态。
- [x] 命令、领域事件、幂等键、Outbox/Inbox 和读模型明确。
- [x] 外部实名、制裁、设备、行为、支付风控、规则引擎和旧安全服务均通过 ACL 隔离。
- [x] 人工审核、规则版本、解释、证据保全和审计导出要求明确。
- [x] 当前服务迁移影响覆盖安全、订单、保留、支付、取消、改签、候补、后台和附加服务链路。
