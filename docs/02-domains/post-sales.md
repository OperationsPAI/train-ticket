# Post Sales 当前设计

更新日期：2026年9月25日。
运行基线为 Helm revision 279，本文根据当前 Java 服务、README 和 HTTP 入口描述行为。
统一待办见 [TODO](../TODO.md)。

## 领域边界

Post Sales 拥有 `PostSalesCase`、冻结的 `PostSalesDecision`、`PostSalesExecutionPlan` 和执行记录。
Case 保存订单、旅客、行程段、票证和预订引用，以及申请原因、范围、审批结果和外部操作证据。
Fare Pricing 提供 adjustment quote，Post Sales 结合已接受订单的政策输入计算退票费用并保存决策。
Payment 执行收款和退款，Entitlement Ticketing 管理票证，Booking 与 Capacity 管理预订和库存，Wallet Promotion 发放补偿权益。
Post Sales 通过这些服务的 API 和事件推进 Case，各服务保存各自的业务状态。

## 申请与决策

| Case 类型 | 当前行为 |
|---|---|
| `REFUND`、`CANCELLATION` | 使用退款决策，审批事件提供资金和票证处理输入。 |
| `CHANGE` | 必须选择目标 Offer，冻结报价后通过执行 API 推进替代预订、差价和票证变更。 |
| `REBOOK` | 当前应用服务映射为退款决策；没有独立的自动重订执行器。 |
| `COMPENSATION` | 冻结补偿金额，通过 Wallet Promotion 向原订单账户发放等额额度。 |

创建 Case 使用 HTTP 幂等键。
退款、取消、改签和 `REBOOK` 共用订单级活动 Case 排他检查，活动申请冲突返回 `REFUND_ALREADY_IN_PROGRESS`。
已经应用的 Case 按票证或预订范围检查，重复覆盖已处理对象返回 `ORDER_NOT_REFUNDABLE`。
独立补偿申请不占用退款排他范围。

评估读取当前已接受订单、原 Offer、Fare Pricing 报价、旅客类型、出发时间和金额明细。
改签评估还核对目标 Offer 的账户、旅客集合、有效期和准确的目标报价引用。
外部读取期间不持有业务数据库事务，保存决策时重新取得订单锁并检查范围和目标未变。
并发评估复用已保存的决策。
具体费率和组成项见 [退改政策](../10-domain-enrichment/post-sales-refund-policy.md)。

## 状态与执行

普通 HTTP 流程依次创建、评估、审批和执行 Case。
评估相关内部状态统一显示为 `EVALUATING`，`EXECUTING`、`COMPENSATION_PENDING` 和 `MANUAL_REVIEW_REQUIRED` 显示为 `APPROVED`。
审批会启动内部执行，因此公开状态 `APPROVED` 同时覆盖执行期间。
调用方通过 `changeOperations`、`compensationOperations`、`releasedOrderItemRefs` 和终态判断结果。

历史 Case 如果仍处于内部 `EXECUTING`，可使用新的 HTTP 幂等键再次调用审批入口执行 redrive。
服务从冻结决策、当前 `approvedActions` 输入和持久化执行计划重建 `PostSalesApproved` 与 `PostSalesExecutionStarted`，而不重复状态迁移。
首次 redrive 将稳定的事件身份写入 Case 快照；重复或并发 redrive 复用同一事件对，由事务 Outbox 的事件唯一键收敛。
Payment 继续使用规范 Case ID（拆分退款使用 `caseId:paymentIntentId`）去重，票证作废携带相同业务 Case 引用且对已作废票证无操作，容量释放由其拥有方状态防重。
`APPLIED` Case 不重发任何执行事件。

redrive 消费追平后，内部 `reconcile-execution` 入口可以只读核对拥有方状态并补记已完成执行。
该入口仅支持退款形态的 `REFUND`、`CANCELLATION` 和当前 `REBOOK`，不处理 `CHANGE` 或 `COMPENSATION`。
每个范围内票证必须为 `VOIDED`，Booking 必须为 `CANCELLED`，并且订单、旅客、行程段、票证、Booking 和 `capacityHoldId` 的引用必须相互一致。
Capacity 返回匹配 Hold 的 `RELEASED` 可作为释放证据；只有在关联票证已作废且 Booking 已取消后，Hold 查询 404 才可作为历史释放的充分补充证据，孤立 404 不会被接受。
Payment 的 Case 退款必须与冻结审批金额和 `refundPayments` 分配一致，零金额审批则必须没有退款记录。
验证成功后按原执行计划幂等补记步骤、写入 `releasedOrderItemRefs`、收敛为 `APPLIED` 并发布一次 `PostSalesApplied`；整个过程不调用任何下游写接口。

普通退款和取消消费 `CapacityReleased`，记录每个范围内预订的释放确认。
范围全部释放后可发布 `PostSalesApplied` 并将 Case 置为 `APPLIED`。
该终态表示售后业务结果已应用，资金是否结算仍需查询 Payment 的 `Refund`。
重复释放事件跳过已经完成的记录。
Capacity 事件使用独立 Redis subscriber 和处理线程，业务数据库连接池仍与其他事件共享。

改签执行器为每个操作使用稳定的下游幂等键，并持久化 `changeOperations`。
执行顺序为记录目标与资金来源、提交全部旅客替代预订、等待确认、处理补差价、签发替代票、作废原票、等待原库存释放、结算应退差价、应用订单变更。
替代票阶段成功前保留原票。
目标过期且尚未创建替代预订时，核验原票后以 `CHANGE_OFFER_EXPIRED` 结束。
补差价支付未捕获且已终止时，取消替代预订并核验原票，以 `CHANGE_PAYMENT_TERMINATED` 结束。
已记录的容量拒绝会触发替代票作废、替代预订取消、Hold 释放及已捕获差价全额退款，核验完成后以 `CHANGE_CAPACITY_UNAVAILABLE` 结束。

## 钱包补偿

`COMPENSATION` 的公开决策字段为 `compensationAmount`，同币种的 `refundableAmount` 为零。
`PostSalesApproved` 携带 `decisionKind=COMPENSATION`、`compensation.method=WALLET_CREDIT`、金额和规范 `caseId`，其 `steps` 为空。
内部执行计划包含 `ISSUE_COMPENSATION_CREDIT` 和 `APPLY_RESULT`。
原票、座位预订、订单和已捕获支付在补偿过程中保留。

执行器检查原订单有效、范围内票证属于该订单且未作废、没有已记录的库存释放、Case 没有退款，以及原支付为 `CAPTURED` 且 `refundedAmount` 为零。
发放金额与冻结决策的币种和最小货币单位完全一致。
请求使用 `COMPENSATION_CREDIT`、`PROMOTION_CREDIT`、`POST_SALES_COMP` 和 `ANY_TRIP`，有效期为请求创建时间起30天。
请求、发放结果和验证分别保存在 `compensationOperations.request`、`issued`、`verified`，重试复用原请求时间。
正金额在完成前再次读取权益并核验身份和金额，零金额记录验证后完成，不发放权益。
Wallet Promotion 按 `caseId` 和完整请求去重，等价请求复用同一权益，内容冲突返回 HTTP 409。

补偿执行前还会通过 Wallet 的内部只读接口按规范 `caseId` 查询既有权益。
如果 Wallet 已经提交额度、但 Post Sales 在保存 `compensationOperations.issued` 前中断，执行器复用该权益并补齐本地证据，不再次调用发放接口。
如果权威 2xx 响应证明同一补偿同时存在退款、原票或原容量已经释放、原支付不再完整保留、Wallet 权益缺失或与批准账户/金额/类型不一致，服务把首份完整证据写入 `compensation_mismatch_checkpoints`。
该 checkpoint 不会被后续并发或重放覆盖；此后执行入口在任何下游调用前返回 HTTP 409 `COMPENSATION_MISMATCH`，并明确 `automaticReplayAllowed=false`。
依赖超时和非 2xx 响应仍是可重试故障，不会被误写成终态 mismatch。

Payment 跳过补偿审批的退款处理，Journey Order 和 Finance 跳过补偿完成事件的退款结果处理。
补偿发放成本由 Wallet 的 `BenefitIssued` 提供。

## API 与持久化

| 入口 | 用途 |
|---|---|
| `POST /api/v1/post-sales-cases` | 创建 Case。 |
| `POST /api/v1/post-sales-cases/{caseId}/evaluate` | 保存资格和金额决策。 |
| `POST /api/v1/post-sales-cases/{caseId}/approve` | 审批并启动执行；对历史 `EXECUTING` Case 使用新 HTTP 幂等键可安全 redrive。 |
| `POST /api/v1/post-sales-cases/{caseId}/target-offer` | 选择改签目标。 |
| `POST /api/v1/post-sales-cases/{caseId}/execute-change` | 推进改签操作。 |
| `POST /api/v1/post-sales-cases/{caseId}/execute-compensation` | 推进钱包补偿操作。 |
| `POST /api/v1/internal/post-sales-cases/{caseId}/reconcile-execution` | 只读核对 redrive 后的权威副作用并收敛历史退款 Case。 |
| `GET /api/v1/internal/compensation-mismatches/{caseId}` | 查询终态补偿不一致的完整冻结证据。 |
| `GET /api/v1/post-sales-cases/{caseId}` | 读取决策、状态和执行证据。 |
| `GET /api/v1/post-sales-cases/{caseId}/issue-authorization/{bookingId}` | 核验替代票签发条件。 |
| `GET /api/v1/post-sales-cases/{caseId}/settlement` | 核验改签资金归属和结算总额。 |

Case 快照和事件保存在 PostgreSQL，事件通过事务 Outbox 发布，消费通过事件标识去重。
执行 redrive 的稳定事件身份也保存在 Case 快照，因此不依赖已过期的 Redis 或 Outbox 历史。
改签和补偿共享执行锁，使用独立的16连接锁池和 PostgreSQL advisory lock，繁忙调用返回当前 Case。
每次推进需要新的 HTTP 幂等键，重复同一键返回已保存的 HTTP 响应。
操作证据支持重试和服务重启后的继续执行。
事件归档、消费游标、DLQ 证据与补偿 mismatch 的统一恢复边界见[持久事件恢复契约](../08-contracts/durable-event-recovery.md)。

实现入口见 [服务说明](../../services/post-sales/README.md)、[应用服务](../../services/post-sales/src/main/java/com/trainticket/postsales/application/PostSalesApplicationService.java)、[补偿执行器](../../services/post-sales/src/main/java/com/trainticket/postsales/application/CompensationExecutionService.java) 和 [HTTP API](../08-contracts/api/post-sales.md)。
