# traveler-profile

Traveler Profile 使用 Java 25、Spring Boot、PostgreSQL 和 Redis Streams。
服务管理旅客资料、证件摘要、资格摘要、Preference 和外部身份核验事实。
HTTP 使用 `tvl-<uuid>` 形式的 `travelerId` 作为旅客引用。

## 已部署状态

HTTP virtual threads 配置及已发布 outbox 清理索引已部署到 revision 279。
`spring.threads.virtual.enabled=true` 使等待请求体或 JDBC 连接的 HTTP 请求能够释放 carrier thread，其他 HTTP 请求可以继续处理。
数据库操作仍受 Hikari 连接池容量约束，readiness 仍检查数据库、outbox relay 和 Redis。
该配置不增加 JDBC 吞吐能力。

## HTTP 与数据模型

| 方法与路径 | 行为 |
|---|---|
| `POST /api/v1/travelers` | 创建旅客，返回 201 与 `travelerId`、`snapshotVersion`、`travelerType`、`createdAt`。 |
| `GET /api/v1/travelers/{travelerId}` | 返回资料、主证件脱敏引用及 `verificationFacts`。 |
| `PATCH /api/v1/travelers/{travelerId}` | 更新资料并返回当前视图。 |
| `POST /api/v1/travelers/{travelerId}/eligibility` | 根据当前旅客类型生成资格结果。 |

写接口要求 `Idempotency-Key`。
创建时若请求声明了 `dateOfBirth`，登记把它与 fare catalogue 所售的年龄区间对齐：区间之外的申报以 `VALIDATION_FAILED` 结束，profile、outbox 与幂等响应都不写入。
区间两端不属于本服务，它们随 catalogue 一起由 release 发布，因此移动区间不需要重新构建镜像。
`TravelerProfile` 聚合包含 `Document`、`EligibilitySummary`、`PreferenceSnapshot` 和 `ExternalVerificationFact`。
完整证件号在输入后转换为脱敏引用和 SHA256，快照不保存完整号码。
资料视图仍包含姓名和联系方式，不能据此宣称所有个人资料都经过字段级加密。

## PostgreSQL 幂等与消息

配置 `DATABASE_URL` 后，服务使用 `PostgresTravelerProfileStore` 和 `DbIdempotencyStore`。
创建、更新和资格操作通过 Spring 事务提交资料快照、outbox 与幂等响应。
幂等键带有 HTTP 方法及资源路径作用域，请求 fingerprint 覆盖方法、路径与请求体。
相同键和 fingerprint 回放原响应，复用键且请求不同会产生冲突。
更新资料、判断资格和写入核验事实前取得旅客行锁，快照保存同时检查 version。

服务只订阅 `events:identity-verification`。
`CredentialRegistered`、`VerificationPassed` 和 `VerificationFailed` 进入身份事实处理。
无关事件在数据库事务和消费去重前被过滤。
有效事件的消费记录与旅客事实写入使用同一事务，暂时失败回滚并保留重试。
发布到 `events:traveler-profile` 的事件为 `TravelerSnapshotUpdated`、`TravelerEligibilityChanged` 和 `TravelerDocumentVerified`。
身份事实处理更新 `verificationFacts`，该路径不额外发布上述事件。

已发布 outbox 清理使用 `005_outbox_retention_index.sql` 中的 `idx_outbox_published_at`。
清理条件为发布时间早于 30 秒，每批最多 1000 条，不改变事件发布及业务状态。

## 已有验证记录

本地完整测试报告为 59 PASS，0 failure、0 error、0 skip。
`HttpRequestConcurrencyLiveTest` 在该次运行中耗时 2.930 秒。
测试导入录制旅客快照，通过真实 HTTP 创建旅客，再同时保持 220 个请求体未完成的幂等回放请求。
全部请求进入 servlet filter chain 后，health、readiness 和两次旅客读取分别在 2 秒内成功。
补全请求体后，220 个请求均返回原来的 201 响应，录制快照不变，未增加额外旅客或 outbox 事件。
测试观察器只统计请求进入情况，HTTP、PostgreSQL 和 Redis outbox 使用应用实现。

已有 PostgreSQL 验证还覆盖两条身份事实并发更新，以及无关身份事件在连接池唯一连接被占用时仍可完成过滤。
revision 279 部署后，`TestLiveInitialTicketsMatchPurchasedSeatClass` 的 six classes 全部 PASS，总耗时 86.305 秒，包含旅客创建及风险检查。
对应类别为 `SECOND_CLASS`、`FIRST_CLASS`、`BUSINESS_CLASS`、`SLEEPER_HARD`、`SLEEPER_SOFT` 和 `STANDING`。

## 验证环境说明

录制验证使用 Java 25、本地 PostgreSQL、专用 Redis 测试数据库，以及版本控制之外保存的旅客快照和身份事件。
以下命令在服务目录中执行，用于重现已有验证。

```bash
export TEST_DATABASE_URL='jdbc:postgresql://localhost:15432/postgres?user=postgres'
export TEST_DATABASE_URI='postgresql://postgres@localhost:15432/postgres'
export TEST_REDIS_URL='redis://localhost:16379/14'
export TEST_TRAVELER_SNAPSHOT="$PWD/../../.scratch/runtime-fixes/goal-traveler-concurrency-two-facts.json"
export TEST_IDENTITY_FILTER_EVENTS="$PWD/../../.scratch/runtime-fixes/goal-traveler-filter-events.json"
mvn -Dtest=HttpRequestConcurrencyLiveTest,IdentityFactConcurrencyLiveTest,IdentityFilterDatabaseLiveTest test
```

完整本地测试入口为 `mvn test`，录制验证需要上述环境输入。
领域模型与当前边界见[领域说明](../../docs/02-domains/traveler-profile.md)。
待办统一见 [docs/TODO.md](../../docs/TODO.md)。
