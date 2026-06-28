# Reporting Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Reporting |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-reporting |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/context-map.md`, `docs/02-domains/finance-settlement.md`, `docs/02-domains/customer-service.md` |

## 1. 领域目标

Reporting bounded context 负责把交易、履约、售后、客服、供应商、通知和财务事件转换为稳定、可追溯、可复算的分析资产。它面向运营、财务、客服主管、供应商运营、管理端和数据分析消费，提供 `Metric` 定义、`DataMart`、`Projection`、`KPI`、`Cohort`、`OperationalReport`、`FinancialReport` 输入、质量校验、数据延迟监控和重算能力。

Reporting 是读侧/分析域，不是业务事实源。它不定义 JourneyOrder、Payment、PostSales、Capacity、Entitlement、Provider、Customer Service 或 Finance Settlement 的业务状态，也不回写这些聚合。所有事实来自上游 Published Language、读模型快照、Finance Settlement 财务口径和受治理的数据导入；Reporting 只在本域内拥有指标定义、报表发布、投影作业、数据质量和分析消费权限。

本领域独立存在的原因：

1. 运营问题通常跨多个上下文，例如搜索转化、下单/支付/出票漏斗、退改原因、供应商失败率、客服 SLA、通知触达和异常恢复效果，需要统一分析语义。
2. 报表要支持批处理、流式 Projection、延迟修正、回补和历史重算，不能把这些逻辑散落在交易核心服务里。
3. 财务报表需要消费 Finance Settlement 的权威财务事实；Reporting 可以做展示、聚合和趋势分析，但不能替代财务口径。
4. 指标定义、版本、口径审批和数据质量需要可审计，否则同一 GMV、支付成功率、出票成功率、退款率会在不同团队产生多套口径。
5. 分析消费需要脱敏、权限、行列级访问控制和导出审计，与交易写模型的权限模型不同。

## 2. 边界

### In Scope

- `Metric`：指标定义、口径版本、计算粒度、过滤条件、归因窗口、口径审批和废弃策略。
- `DataMart`：面向主题的数据集市，包括 search、funnel、order、payment、ticketing、post-sales、recovery、provider quality、customer service、notification、finance、data freshness。
- `Projection`：从 `EventStream`、上游读模型和 Finance Settlement view 构建分析读模型，支持流式增量、批量回补和幂等重算。
- `KPI`：面向运营、财务、客服、供应商和管理层的目标、阈值、告警和周期对比。
- `Cohort`：按用户、渠道、线路、交通方式、供应商、活动、订单类型、售后原因、异常事件等维度分群分析。
- `OperationalReport`：搜索转化、下单漏斗、支付漏斗、出票漏斗、履约、退改、异常恢复、供应商质量、客服 SLA、通知触达。
- `FinancialReport` 输入：收入、退款、费用、结算、差异、补偿成本、发票和财务延迟的展示层与分析层；权威金额来自 Finance Settlement。
- 数据质量校验：完整性、重复、乱序、延迟、schema 演进、跨源对账、指标突变和异常值检测。
- 数据延迟、重算和补数：记录 watermark、backfill run、recompute reason、影响范围和报表版本。
- 分析消费治理：报表目录、权限、导出审计、脱敏策略、指标血缘和消费 API。

### Out of Scope

- 不回写或修正 JourneyOrder、Payment、PostSales、Capacity、Entitlement、Fulfillment、Provider Integration、Customer Service 或 Finance Settlement 聚合。
- 不定义订单、支付、出票、库存、售后、履约、供应商和客服工单的业务状态；只消费这些状态的事件或快照。
- 不重新定价，不计算退改费、差价、税费、补偿规则或供应商罚金；这些来自 Fare & Pricing、Post Sales、Finance Settlement。
- 不执行支付、退款、出票、取消、改签、库存释放、通知发送或客服人工动作。
- 不替代 Finance Settlement 的财务权威口径；`FinancialReport` 只能基于 Finance 的 Ledger、RevenueRecognition、Settlement、Invoice 和 Payout 读模型。
- 不作为人工修数后台；发现数据或业务异常时发布质量事件、告警或分析结论，修正必须进入对应业务域或 Admin & Audit 受控流程。
- 不保存超出分析最小必要的敏感明文；旅客、证件、联系方式和支付凭据必须脱敏或聚合。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| Reporting | 报表与分析上下文，负责指标、DataMart、Projection、KPI、质量校验和消费治理。 | 只读分析域。 |
| Metric | 带版本、粒度、过滤条件和口径说明的指标定义。 | 例如 search conversion、payment success rate、refund amount。 |
| MetricDefinition | 指标元数据聚合，描述计算逻辑、依赖数据、owner、审批和生效窗口。 | 不直接保存业务事实。 |
| DataMart | 面向主题和消费场景组织的分析数据集。 | 可由流式 Projection 和批处理共同维护。 |
| Projection | 将 EventStream 或读模型转换为 Reporting 内部分析模型的投影。 | 支持幂等、watermark 和重算。 |
| KPI | 带目标值、阈值、周期和责任人的管理指标。 | 可触发告警，不触发交易动作。 |
| Cohort | 按渠道、用户、线路、供应商、时间、异常类型等条件定义的分析群组。 | 用于留存、转化和质量分析。 |
| OperationalReport | 运营报表，如漏斗、供应商质量、客服 SLA、通知触达和异常恢复效果。 | 可服务运营决策。 |
| FinancialReport | 财务展示报表，基于 Finance Settlement 权威事实聚合。 | 不重新确认收入。 |
| EventStream | 上游领域事件、变更日志或读模型变更的输入流。 | 需要 schema registry 和消费位点。 |
| DataQualityCheck | 对完整性、唯一性、延迟、数值范围和跨源一致性的校验。 | 失败产生质量事件。 |
| BackfillRun | 因历史修复、schema 升级或口径变更而执行的补数/重算作业。 | 必须记录影响报表和版本。 |
| FreshnessWatermark | 每个数据源、Projection 和 DataMart 的最新可用业务时间与处理时间。 | 用于延迟提示和 SLA。 |
| ReportCatalog | 报表、指标、血缘、权限和订阅的目录。 | 分析消费入口。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Trip Planning | search request/result events、zero-result、ranking exposure、itinerary selected | 搜索转化、无结果率、线路热度和方案质量分析。 |
| Offer Management | `OfferQuoted`、`OfferExpired`、price snapshot、offer validation result | 报价点击、Offer 过期、价格变动和下单前转化分析。 |
| Journey Order | order created/confirmed/cancelled/failed/disrupted events、OrderSummary read model | 下单漏斗、订单结构、用户商业订单趋势和异常订单分析。 |
| Booking Orchestration | segment booking started/confirmed/failed/compensated events | 预订确认率、供应商确认耗时、多段补偿和出票前失败分析。 |
| Payment | payment authorized/captured/failed/refund events、payment status view、late payment view | 支付漏斗、支付成功率、退款时效和渠道质量分析。 |
| Entitlement & Ticketing | entitlement issued/voided/failed events、ticketing timeline | 出票成功率、出票耗时、票证失败原因和权益交付分析。 |
| Fulfillment | boarding/check-in/segment completed/no-show/delay facts | 履约完成、使用率、No-show、到达延迟和联程实际表现分析。 |
| Post Sales | refund/change/cancel case events、reason、fee summary、manual review markers | 退改率、售后原因、退款时效、人工介入和费用保留分析。 |
| Disruption Recovery | incident、recovery option、recovery accepted/failed、batch impact events | 异常影响范围、恢复方案采纳率、恢复时效和补偿效果分析。 |
| Capacity & Availability | availability snapshot、hold/release summary、sell-out markers | 库存转化、售罄、锁票释放和可售性质量分析。 |
| Provider Integration | provider request/result events、normalized error、supplier latency、statement summaries | 供应商质量、接口稳定性、失败原因和结算输入质量分析。 |
| Customer Service | support case events、classification、SLA events、compensation outcome | 客服 SLA、投诉原因、人工动作效果和补偿成本分析。 |
| Notification | message requested/sent/delivered/failed/read events、channel summary | 通知触达、失败率、重试效果和关键消息覆盖分析。 |
| Finance Settlement | LedgerView、RevenueRecognitionView、SettlementBatchView、ReconciliationDashboard、Invoice/Payout views | 财务指标、收入、退款、结算、差异和财务月结报表输入。 |
| Admin & Audit | report permission decisions、metric approval、data export audit events | 指标口径审批、访问控制、导出审计和治理。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| 运营团队 / 管理端 | `OperationalReport`、KPI dashboard、trend、alert summary | 运营决策、异常监控和目标管理。 |
| 财务团队 | `FinancialReport` display、finance trend、settlement difference analytics | 基于 Finance Settlement 的财务分析与展示。 |
| Customer Service | support KPI、complaint trend、SLA breach analytics、case reason dashboard | 客服排班、质检和问题定位。 |
| Provider Operations | supplier quality score、latency/error report、settlement data quality hint | 供应商治理、合同复盘和接口质量改进。 |
| Admin & Audit | metric approval log、report access log、export audit、quality incident | 口径治理、权限审计和数据风险处理。 |
| Finance Settlement | data quality observation on finance inputs、report consumption feedback | 仅反馈质量观察，不要求 Finance 改账。 |
| Data Science / Experiment | curated DataMart、Cohort、feature-ready aggregates | 分析、实验、预测和增长建模。 |
| Alerting / Observability | KPI threshold breached、freshness delayed、quality check failed | 告警和运维响应。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `MetricDefinition` | 指标必须有 owner、业务定义、计算表达、粒度、时间口径、依赖 DataMart、版本和生效窗口；已发布版本不可静默改写；财务指标必须引用 Finance Settlement 来源。 | `DraftMetricDefinition`、`SubmitMetricForReview`、`ApproveMetricDefinition`、`PublishMetricVersion`、`DeprecateMetricVersion` | `MetricDefinitionDrafted`、`MetricDefinitionSubmitted`、`MetricDefinitionApproved`、`MetricVersionPublished`、`MetricVersionDeprecated` |
| `ProjectionJob` | 每个 Projection 必须绑定 source、schema version、watermark、幂等键和目标 DataMart；重放不得重复计数；乱序事件按策略处理并记录。 | `RegisterProjection`、`StartProjectionRun`、`AdvanceWatermark`、`RecordProjectionLag`、`ReplayProjectionWindow`、`FailProjectionRun` | `ProjectionRegistered`、`ProjectionRunStarted`、`ProjectionWatermarkAdvanced`、`ProjectionLagRecorded`、`ProjectionWindowReplayed`、`ProjectionRunFailed` |
| `DataMartDataset` | Dataset 必须有 schema、partition、retention、quality rules、lineage 和访问级别；schema 破坏性变更必须新版本；敏感字段必须标记。 | `CreateDataMartDataset`、`PublishDatasetVersion`、`AttachQualityRule`、`RetireDatasetVersion`、`GrantDatasetAccess` | `DataMartDatasetCreated`、`DatasetVersionPublished`、`QualityRuleAttached`、`DatasetVersionRetired`、`DatasetAccessGranted` |
| `Report` | Report 必须引用 Metric 或 Dataset 版本；发布前必须通过权限和质量检查；导出必须记录；财务报表必须显示 Finance 数据时点。 | `CreateReport`、`PublishReport`、`ScheduleReportRefresh`、`RecordReportExport`、`ArchiveReport` | `ReportCreated`、`ReportPublished`、`ReportRefreshScheduled`、`ReportExportRecorded`、`ReportArchived` |
| `KPIProgram` | KPI 必须有指标版本、目标值、周期、责任人、阈值和解释口径；告警不允许触发交易写命令。 | `DefineKPI`、`ActivateKPI`、`EvaluateKPI`、`RecordKPIBreach`、`CloseKPIProgram` | `KPIDefined`、`KPIActivated`、`KPIEvaluated`、`KPIBreachRecorded`、`KPIProgramClosed` |
| `DataQualityRun` | 校验规则必须绑定数据源、时间窗口、阈值和 owner；失败必须有严重级别、影响范围和处理记录；自动修复只限 Reporting 投影。 | `StartDataQualityRun`、`RecordQualityCheckResult`、`OpenQualityIncident`、`AcknowledgeQualityIncident`、`CloseQualityIncident` | `DataQualityRunStarted`、`QualityCheckResultRecorded`、`QualityIncidentOpened`、`QualityIncidentAcknowledged`、`QualityIncidentClosed` |
| `BackfillRun` | Backfill 必须有原因、范围、source version、target dataset、审批和回滚策略；执行结果必须可比较；不得修改上游事实。 | `PlanBackfillRun`、`ApproveBackfillRun`、`ExecuteBackfillRun`、`PublishBackfillResult`、`CancelBackfillRun` | `BackfillRunPlanned`、`BackfillRunApproved`、`BackfillRunExecuted`、`BackfillResultPublished`、`BackfillRunCancelled` |

## 6. 状态机

### `MetricDefinition` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Draft` | 指标正在设计，尚不可被正式报表引用。 | `InReview`, `Discarded` |
| `InReview` | 指标 owner、数据 owner 或财务 owner 正在审核口径。 | `Approved`, `Rejected`, `Draft` |
| `Approved` | 口径已批准但未发布。 | `Published`, `Deprecated` |
| `Published` | 指标版本生效，可被 Report、KPI 和 API 引用。 | `Superseded`, `Deprecated` |
| `Superseded` | 被新版本替代，历史报表仍可追溯。 | `Deprecated` |
| `Deprecated` | 不再建议新消费，保留历史可解释性。 | 终态 |
| `Rejected` | 审核拒绝，需要重新设计。 | `Draft`, `Discarded` |
| `Discarded` | 草案废弃。 | 终态 |

### `ProjectionJob` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Registered` | Projection 已登记 source、schema 和目标 Dataset。 | `Running`, `Disabled` |
| `Running` | 正在消费 EventStream 或批处理窗口。 | `Lagging`, `Replaying`, `Failed`, `Paused` |
| `Lagging` | 延迟超过阈值但仍在推进。 | `Running`, `Failed`, `Paused` |
| `Replaying` | 正在重放窗口或回补历史。 | `Running`, `Failed`, `Paused` |
| `Paused` | 人工暂停，通常因质量问题或上游变更。 | `Running`, `Disabled` |
| `Failed` | 作业失败，需要处理后恢复或重建。 | `Running`, `Replaying`, `Disabled` |
| `Disabled` | 不再运行。 | 终态 |

### `DataQualityIncident` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Opened` | 质量问题已识别并记录影响范围。 | `Investigating`, `Acknowledged`, `Suppressed` |
| `Investigating` | 正在定位源事件、Projection、口径或上游延迟。 | `Acknowledged`, `Escalated`, `Closed` |
| `Acknowledged` | owner 接受问题并给出处理计划。 | `Backfilling`, `Escalated`, `Closed` |
| `Backfilling` | Reporting 内部正在回补或重算。 | `Validating`, `Escalated` |
| `Validating` | 校验重算结果和指标影响。 | `Closed`, `Backfilling` |
| `Escalated` | 需要上游 domain、Admin & Audit 或数据平台协助。 | `Acknowledged`, `Closed` |
| `Suppressed` | 按规则临时抑制告警但保留审计。 | `Investigating`, `Closed` |
| `Closed` | 问题处理完成并记录原因。 | 终态 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `DraftMetricDefinition` | `MetricDefinition` | `MetricDefinitionDrafted` | metricKey + draftVersion |
| `ApproveMetricDefinition` | `MetricDefinition` | `MetricDefinitionApproved` | metricId + reviewer + reviewVersion |
| `PublishMetricVersion` | `MetricDefinition` | `MetricVersionPublished` | metricId + semanticVersion |
| `RegisterProjection` | `ProjectionJob` | `ProjectionRegistered` | projectionKey + sourceName + targetDataset |
| `StartProjectionRun` | `ProjectionJob` | `ProjectionRunStarted` | projectionId + runWindow + attempt |
| `AdvanceWatermark` | `ProjectionJob` | `ProjectionWatermarkAdvanced` | projectionId + sourcePartition + watermark |
| `ReplayProjectionWindow` | `ProjectionJob` | `ProjectionWindowReplayed` | projectionId + windowStart + windowEnd + reasonHash |
| `CreateDataMartDataset` | `DataMartDataset` | `DataMartDatasetCreated` | datasetKey + schemaVersion |
| `PublishDatasetVersion` | `DataMartDataset` | `DatasetVersionPublished` | datasetId + version + partitionSpec |
| `CreateReport` | `Report` | `ReportCreated` | reportKey + creator + draftVersion |
| `PublishReport` | `Report` | `ReportPublished` | reportId + metricVersionSetHash |
| `RecordReportExport` | `Report` | `ReportExportRecorded` | reportId + consumerId + exportTimeBucket |
| `DefineKPI` | `KPIProgram` | `KPIDefined` | kpiKey + period + metricVersion |
| `EvaluateKPI` | `KPIProgram` | `KPIEvaluated` | kpiId + evaluationWindow |
| `RecordKPIBreach` | `KPIProgram` | `KPIBreachRecorded` | kpiId + threshold + breachWindow |
| `StartDataQualityRun` | `DataQualityRun` | `DataQualityRunStarted` | ruleSetId + datasetId + checkWindow |
| `OpenQualityIncident` | `DataQualityRun` | `QualityIncidentOpened` | datasetId + ruleId + affectedWindow + severity |
| `PlanBackfillRun` | `BackfillRun` | `BackfillRunPlanned` | targetDataset + window + reasonHash |
| `PublishBackfillResult` | `BackfillRun` | `BackfillResultPublished` | backfillRunId + resultVersion |

Reporting 消费外部 `EventStream` 时必须使用 Inbox 幂等处理；本域事件通过 Outbox 发布给报表目录、告警、治理和消费方。所有指标输出必须带 `metricVersion`、`dataAsOf`、`processingTime`、`sourceLineage` 和 `qualityStatus`。

## 8. 策略和 Saga 参与点

- 搜索转化策略：从 Trip Planning 的 search exposure、Offer 的 quote、Journey Order 的 order created 和 Payment 的 captured 事件构建 search-to-order-to-paid 漏斗；搜索无结果、Offer 过期和下单失败分别归因，不反推业务状态。
- 下单/支付/出票漏斗策略：按渠道、线路、交通方式、供应商、设备、活动和 Cohort 统计 order created、booking confirmed、payment captured、entitlement issued、segment completed；每一步的权威事实来自对应上下文。
- 售后分析策略：消费 Post Sales case、Payment refund、Finance refund accounting、Customer Service compensation outcome，区分用户主动退改、异常恢复退改、供应商原因、平台补偿和人工豁免。
- 异常恢复策略：把 Disruption Recovery incident、RecoveryOption、用户采纳、履约后结果和客服工单关联，分析影响人数、恢复成功率、补偿成本和通知覆盖。
- 供应商质量策略：基于 Provider Integration latency/error、Booking confirmation、Ticketing success、Finance settlement difference 和用户投诉形成 supplier quality score；评分只用于分析和治理，不作为供应商接口状态源。
- 客服 SLA 策略：消费 Customer Service 的 SupportCase、SLA 和 ManualAction 事件，统计首响、解决、升级、重开、满意度和补偿结果；SLA breach 只触发告警和管理报表。
- 通知触达策略：以 Notification 发送、送达、失败、阅读和用户后续动作构建触达漏斗，支持关键通知覆盖率和渠道质量分析。
- 财务指标策略：收入、退款、结算、差异、补偿成本、发票和 Payout 指标以 Finance Settlement view 为准；Reporting 只做维度聚合、趋势和展示。
- 数据延迟与重算策略：每个 Projection 维护 FreshnessWatermark；延迟超过阈值发布告警；口径变更、schema 升级或质量事件通过 BackfillRun 重算并保留版本。
- 分析消费策略：报表、导出和 API 访问必须经过 ReportCatalog 和权限检查；敏感维度默认聚合或脱敏。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `SearchConversionMart` | Trip Planning search events、Offer events、Journey Order events、Payment captured | 增长、运营、产品分析。 |
| `OrderPaymentTicketingFunnelMart` | Journey Order、Booking Orchestration、Payment、Entitlement events | 交易运营、管理层、异常排障。 |
| `PostSalesMart` | Post Sales events、Payment refund events、Finance RefundAccountingView、Customer Service compensation events | 售后运营、财务分析、客服主管。 |
| `DisruptionRecoveryMart` | Disruption Recovery incident/recovery events、Fulfillment delay facts、Notification delivery、SupportCase | 异常指挥、客服、运营复盘。 |
| `ProviderQualityMart` | Provider request/result、Booking result、Ticketing failure、Finance settlement difference、complaint events | 供应商运营、合同复盘、质量治理。 |
| `CustomerServiceSLAMart` | SupportCase、SLA、ManualAction、CompensationRequest、satisfaction feedback | 客服主管、排班、质检。 |
| `NotificationReachMart` | Notification requested/sent/delivered/failed/read events、user action events | 通知运营、产品增长、客服解释。 |
| `FinanceReportingMart` | LedgerView、RevenueRecognitionView、SettlementBatchView、ReconciliationDashboard、Invoice/Payout views | 财务团队、管理层、审计展示。 |
| `CapacityAnalyticsMart` | availability snapshot、hold/release summary、order conversion、sell-out markers | 库存运营、线路规划、供应商管理。 |
| `KPIDashboardView` | Metric output、KPI evaluation events、quality status、freshness watermark | 管理端、运营例会、告警。 |
| `MetricCatalogView` | MetricDefinition events、Dataset lineage、approval logs | 分析师、工程师、审计。 |
| `DataFreshnessView` | Projection watermarks、quality run events、backfill events | 数据运维、报表 owner、消费方。 |
| `ReportAccessAuditView` | Report publish/export/access events、Admin & Audit permission result | Admin & Audit、安全合规。 |

读模型可以保存业务引用、脱敏维度、汇总金额、统计粒度和质量状态；不得成为交易写入依据。若读模型与上游事实冲突，Reporting 打开 DataQualityIncident 或向 owner 发质量反馈，而不是直接修正上游数据。

## 10. 外部系统和防腐层

| External / Legacy Boundary | ACL Need | Reporting Design |
|---|---|---|
| Event bus / CDC / log pipeline | 事件格式、乱序、重复、schema 演进和消费位点 | Reporting EventStream ACL 将输入转换为版本化分析事件，记录 sourceLineage 和 watermark。 |
| Data warehouse / lakehouse | 分区、表格式、血缘、权限、批流一致性 | DataMart ACL 发布 dataset version、quality status、retention 和脱敏标签。 |
| BI 工具和看板平台 | 报表参数、权限、缓存、导出和订阅 | ReportCatalog 统一管理报表定义、Metric 版本和访问审计。 |
| Experiment / Data Science 平台 | Cohort 定义、特征快照、训练数据时间点 | 只提供 curated DataMart 和聚合特征，不暴露未治理敏感明文。 |
| 告警系统 | 阈值、降噪、升级路径、通知渠道 | KPI breach、freshness delay 和 quality incident 转为告警事件。 |
| 遗留统计 SQL / Excel 报表 | 隐含口径、手工修数、重复指标 | 迁移为 MetricDefinition、Report、DataQualityRun 和 BackfillRun。 |
| 外部财务分析工具 | 财务科目、期间、币种、收入确认状态 | 只消费 Finance Settlement 输出的权威财务 view，不直接读订单金额推导收入。 |

防腐规则：外部 BI 或数据仓库表名不能进入领域语言；旧报表中的状态码、错误码和手工修正必须映射为 Reporting 内部 Projection 或质量事件；财务数据必须保留 Finance Settlement lineage；敏感字段必须以 token、hash、聚合或脱敏展示。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-travel-service`, `ts-route-plan-service` | 搜索曝光、无结果、路线选择和方案质量事件进入 `SearchConversionMart`；旧查询日志需要补 schema 和去重。 |
| `ts-order-service`, `ts-order-other-service` | 订单统计从直接扫订单表迁移为 Journey Order 事件与 OrderSummary 投影；Reporting 不再反向标记订单。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 预订确认、供应商失败、补偿和耗时事件接入漏斗与供应商质量 DataMart。 |
| `ts-payment-service`, `ts-inside-payment-service` | 支付、退款、late payment 和渠道质量事件进入 Payment 分析；收入、结算和差异指标改从 Finance Settlement 获取。 |
| `ts-ticket-office-service`, `ts-voucher-service` | 出票、取票、凭证和票证失败事件进入出票漏斗与履约分析，不由报表修票证状态。 |
| `ts-execute-service` | 检票、进站、履约完成、延误和 No-show 事件进入 Fulfillment 与 Recovery 报表。 |
| `ts-cancel-service`, `ts-rebook-service` | 退票、改签、手续费、原因和人工审核事件迁移到 `PostSalesMart`；退款金额展示使用 Finance/Payment 输入。 |
| `ts-admin-order-service` | 旧后台统计、手工导出和修数能力迁移为 ReportCatalog、DataQualityIncident、BackfillRun 和 Admin & Audit 审计。 |
| `ts-notification-service` | 发送、送达、失败、阅读和重试事件进入 `NotificationReachMart`。 |
| 旧客服系统 / 工单表 | 支持 Case、SLA、分类、补偿结果和满意度事件进入 `CustomerServiceSLAMart`。 |
| batch scripts / ad-hoc SQL / Excel reports | 迁移为版本化 Metric、DataMart、Report 和 BackfillRun；禁止以手工 Excel 作为新口径事实源。 |
| finance summary tables | 报表展示逐步切换到 Finance Settlement 的 Ledger、RevenueRecognition、SettlementBatch 和 Reconciliation 读模型。 |

迁移顺序建议：先建立 EventStream ACL、MetricCatalog 和核心漏斗 DataMart；再接入 Payment、Ticketing、Post Sales 和 Customer Service；随后切换财务报表到 Finance Settlement view；最后治理旧 SQL、Excel 报表、导出权限、质量告警和历史重算。

## 12. 验收标准

- [x] Reporting 的聚合所有权明确：`MetricDefinition`、`ProjectionJob`、`DataMartDataset`、`Report`、`KPIProgram`、`DataQualityRun`、`BackfillRun` 属于本 domain。
- [x] 明确 Reporting 是读侧/分析域，不回写 JourneyOrder、Payment、PostSales、Capacity、Entitlement、Provider、Customer Service 或 Finance Settlement 聚合。
- [x] 财务指标边界清楚：Finance Settlement 仍是 Ledger、RevenueRecognition、Settlement、Invoice、Payout 和财务差异的权威口径。
- [x] 覆盖搜索转化、下单/支付/出票漏斗、售后、异常恢复、供应商质量、客服 SLA、通知触达、财务指标和数据延迟/重算。
- [x] `MetricDefinition`、`ProjectionJob`、`DataQualityIncident` 的状态机只定义 Reporting 内部状态。
- [x] 每个指标输出要求携带 metricVersion、dataAsOf、processingTime、sourceLineage 和 qualityStatus，支持口径追溯与重算解释。
- [x] 数据质量问题只能形成质量事件、告警、BackfillRun 或跨域反馈，不得静默修改交易或财务事实。
- [x] 当前服务迁移影响覆盖搜索、订单、预订、支付、出票履约、售后、后台、通知、客服、批处理和财务汇总表。
- [x] 报表权限、敏感信息脱敏、导出审计、Cohort 小样本保护和 ReportCatalog 治理已纳入设计。
