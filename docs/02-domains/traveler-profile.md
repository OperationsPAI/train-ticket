# Traveler Profile 领域说明

## 实现与发布状态

Traveler Profile 位于 `services/traveler-profile`，使用 Java 25、Spring Boot、PostgreSQL 和 Redis Streams。
服务提供旅客创建、读取、更新、资格判断及外部身份核验事实接收。
HTTP virtual threads 和已发布 outbox 清理索引已经部署到 revision 279。

## 领域边界

本域拥有旅客资料、证件摘要、资格摘要、Preference 及接收到的身份核验事实。
Account 负责账户主体和登录，Identity Verification 提供核验结果，Risk & Compliance 决定风险处置。
Traveler Profile 不决定订单、支付、票证、供应商预订或售后状态。
外部身份核验结果只更新本域资料及证件可用性，不直接改变订单。

当前实现没有独立的 ConsentGrant、VerificationCase 或资料合并聚合。
服务只订阅 Identity Verification 事件，没有 Account 生命周期事件订阅。
HTTP 当前只开放下表中的业务操作。

| 方法与路径 | 当前行为 |
|---|---|
| `POST /api/v1/travelers` | 校验账户、旅客类型及姓名，创建资料，返回 201。 |
| `GET /api/v1/travelers/{travelerId}` | 返回资料视图、主证件脱敏引用和外部核验事实。 |
| `PATCH /api/v1/travelers/{travelerId}` | 更新提供的资料字段，可追加证件，并推进 `snapshotVersion`。 |
| `POST /api/v1/travelers/{travelerId}/eligibility` | 按旅客类型生成资格结果，并保存幂等响应。 |

写请求要求 `Idempotency-Key`。
创建及更新中，`documentType` 与 `documentNumber` 必须成对提供。
API 的证件类型为 `ID_CARD`、`PASSPORT`、`OTHER`。
旅客类型为 `ADULT`、`CHILD`、`INFANT`、`STUDENT`、`SENIOR` 和 `MILITARY`。
当前资格接口对除 ADULT 外的这些类型返回 eligible，资格有效期为 365 天；此结果不能解释为外部资格材料已经核验。

## 模型与标识

| 模型 | 当前内容与约束 |
|---|---|
| `TravelerProfile` | 管理内部 profile ID、状态、Document 集合、主证件、资格、Preference、外部事实和持久化 version。 |
| `TravelerState` | 组合聚合及 API 使用的姓名、账户、旅客类型、联系方式、`travelerId`、`snapshotVersion` 与时间。 |
| `Document` | 保存类型、脱敏号码、SHA256、签发国家、有效期、核验及撤销状态，同一类型和 hash 不得重复添加。 |
| `EligibilitySummary` | 保存资格类型、来源、证据 hash、有效期及撤销原因。 |
| `PreferenceSnapshot` | 保存键值信息、辅助需求映射及版本，更新返回新的 snapshot。 |
| `ExternalVerificationFact` | 保存 credential、case、状态、证件摘要、政策版本、原因、有效期、记录时间和来源事件。 |

HTTP 对外引用为 `TravelerState.travelerId`，格式是 `tvl-<profileId>`。
聚合内部另有 `TRV-` 开头的 `travelerRef`，API 路由使用 `travelerId`。
数据库快照的 bigint `version` 用于并发保存，API `snapshotVersion` 使用 `sv-N` 格式。
资料更新和外部身份事实写入推进 API snapshot version。

## 聚合内部状态

| 对象 | 状态与规则 |
|---|---|
| `TravelerProfile` | `DRAFT`、`ACTIVE`、`SUSPENDED`、`DEACTIVATED`。 |
| `Document` | `PENDING_VERIFICATION`、`VERIFIED`、`EXPIRED`、`REVOKED`。 |
| `ExternalVerificationFact` | `REGISTERED`、`PASSED`、`FAILED`、`MANUAL_REVIEW_REQUIRED`。 |
| `EligibilitySummary` | 根据有效期和 revoked 标志计算资格是否有效。 |

聚合内部支持激活、暂停、恢复、停用、证件核验、主证件选择及资格撤销等操作。
首次激活要求已有主证件且该证件可用于新预订。
`requireCanReferenceForNewBooking` 拒绝 DRAFT、DEACTIVATED，以及主证件未核验、过期或撤销的情况。
这些领域方法没有对应 HTTP 生命周期操作入口，当前创建接口也不自动调用激活方法。
当前资料 API 没有出生日期及证件有效期输入，创建时使用代码中的默认日期。
因此不能把领域内部校验描述为全部 HTTP 创建流程已经执行的下单资格检查。

## 身份核验事件

唯一订阅 stream 为 `events:identity-verification`，consumer group 为 `traveler-profile`。
处理器先检查 producer 和 eventType，无关事件在取得数据库连接及写入消费记录前直接完成处理。

| 事件 | 本域处理 |
|---|---|
| `CredentialRegistered` | 保存 `REGISTERED` 事实，包括 document type、masked number 和 document hash。 |
| `VerificationPassed` | 校验 `verificationStatus=PASSED`，保存政策版本及有效期。 |
| `VerificationFailed` | 保存 `FAILED` 或 `MANUAL_REVIEW_REQUIRED`，按证件 hash 将匹配 Document 标为不可用。 |

已支持事件通过 `processed_events` 的事件 ID 去重，消费记录与资料写入在同一个事务中完成。
处理暂时失败时回滚事务，保留重试条件。
旅客不存在或领域状态不允许写入时，处理器记录原因并确认事件。

外部事实保留来源事件 ID，并按 credential、case 和来源事件组成的键保存。
核验结果缺少证件摘要时，可用同一 credential 已记录的注册事实补充。
注册事件晚于失败结果到达时，也会检查既有失败事实并更新匹配证件。
外部 PASSED 事实本身不调用本地 Document 的 `verify` 方法。
`verificationFacts` 查询输出包括结果状态、政策、原因、有效期及脱敏证件信息，不输出 document hash。

## PostgreSQL 事务与幂等

配置 `DATABASE_URL` 后，Spring 明确注册 `PostgresTravelerProfileStore`、`DbIdempotencyStore`、`PostgresConsumedEventLog` 及事务 outbox publisher。
`traveler_profile_snapshots` 保存 JSONB 快照及 version。
创建、更新和资格操作通过 Spring 事务提交快照、outbox 与幂等响应。
更新、资格判断和身份事实写入先通过 `SELECT ... FOR UPDATE` 锁定目标旅客，再读取当前状态。
快照保存同时使用 version 检查并发更新。

HTTP 幂等键包含方法及资源路径作用域。
fingerprint 根据请求方法、路径和请求体生成 SHA256。
已有记录且 fingerprint 一致时回放原响应，不重复创建旅客或发布事件。
fingerprint 不同会产生幂等键复用冲突。
这项机制使用 PostgreSQL 幂等表，已验证的 220 并发场景是已完成创建请求的回放。

发布事件为 `TravelerSnapshotUpdated`、`TravelerEligibilityChanged` 和 `TravelerDocumentVerified`，目标 stream 为 `events:traveler-profile`。
内部创建、资料变化和证件添加等事件映射为 `TravelerSnapshotUpdated`。
资格授予、到期及撤销映射为 `TravelerEligibilityChanged`，本地证件核验映射为 `TravelerDocumentVerified`。
外部身份事实接收直接保存资料，当前不额外发布这三类事件。

`processed_events`、`idempotency_records` 和已发布 outbox 都有对应的时间索引。
`005_outbox_retention_index.sql` 中的 `idx_outbox_published_at` 支持已发布事件清理，条件为早于 30 秒，每批最多 1000 条。
清理不改变未发布事件或旅客业务状态。

## 资料保护与读取范围

完整证件号只作为输入参与脱敏和 SHA256 计算。
`Document` 及持久化 JSONB 保存 `maskedDocumentRef`、`documentNumberHash`，不保存完整 document number。
HTTP 视图返回主证件类型和脱敏引用，不返回完整号码或 document hash。
姓名、联系方式等资料仍在当前快照和资料视图中，当前实现不提供这些字段的字段级加密证明。
当前资料读接口不等于完整的隐私授权或访问审计系统。

## HTTP 并发与已有验证

`spring.threads.virtual.enabled=true` 启用 Java virtual threads。
等待请求体或 JDBC 连接时，HTTP 请求可以释放 carrier thread，其他 HTTP 请求继续执行。
数据库并发仍受 Hikari 连接池约束。
readiness 保持数据库、outbox relay 和 Redis 依赖检查。

已有完整本地报告为 59 PASS，0 failure、0 error、0 skip。
`HttpRequestConcurrencyLiveTest` 耗时 2.930 秒。
该项目导入录制资料，经真实 HTTP 创建旅客，保持 220 个请求体未完成的幂等回放请求。
全部请求进入 servlet filter chain 后，health、readiness 及两个旅客资料读取分别在 2 秒内成功。
请求体补全后全部返回原 201 响应，录制快照不变，没有增加额外旅客或 outbox 事件。
另外两个 PostgreSQL 录制验证确认并发身份事实不会互相覆盖，以及无关事件过滤不依赖数据库连接可用性。

revision 279 已部署 HTTP virtual threads 和 outbox 清理索引。
部署后的 `TestLiveInitialTicketsMatchPurchasedSeatClass` six classes 全部 PASS，耗时 86.305 秒，包含旅客创建和风险检查。
类别为 `SECOND_CLASS`、`FIRST_CLASS`、`BUSINESS_CLASS`、`SLEEPER_HARD`、`SLEEPER_SOFT`、`STANDING`。
验证环境和重现命令见[服务说明](../../services/traveler-profile/README.md)。
待办统一见 [docs/TODO.md](../TODO.md)。
