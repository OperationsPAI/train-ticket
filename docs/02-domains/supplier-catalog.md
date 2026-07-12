# Supplier Catalog Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Supplier Catalog |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-supplier-catalog |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/general-travel-ddd.md`, `docs/02-domains/fare-pricing.md`, `docs/02-domains/service-plan.md` |

## 1. 领域目标

Supplier Catalog 负责把平台可合作的供应侧主数据整理成稳定、可版本化、可审计的目录。它回答：谁是 Supplier，能提供哪些 ProductCatalog、在哪些地点和交通方式服务、合同和能力如何生效、哪些 FareFamily、ServiceClass、AncillaryProduct 与 RuleSource 可被下游引用。

本领域是独立边界，因为供应商主数据的变化节奏不同于运行计划、库存、价格和订单：供应商接入、合同签署、能力开通、产品上下架和规则来源确认通常先于售卖发生，并需要审批、版本、审计和跨域一致引用。Supplier Catalog 不执行实时交易，只发布被 Service Plan、Fare & Pricing、Provider Integration、Ancillary Service 和 Finance Settlement 消费的供应侧事实。

核心目标：

1. 管理 Supplier、Carrier、Agency、FleetOperator、StationOperator、MarketplacePartner 等供应侧主体及其法律实体、结算主体、品牌和运营身份。
2. 管理 ProductCatalog：主票产品、交通方式、服务等级、票价族、附加商品底稿、适用市场、销售渠道和上下架状态。
3. 管理 Contract 及合同版本：合作范围、佣金/结算条款摘要、价格和退改规则来源、API 权限、SLA、责任边界和生效窗口。
4. 管理 Capability 矩阵：查询、报价、预留、确认、出票、取消、改签、退票、值机、选座、行李、异常同步、结算文件等能力是否支持。
5. 管理供应商地点、服务等级、票价族和外部编码到平台统一语言的映射。
6. 管理 RuleSource：来自合同、供应商接口、人工录入、监管公告或历史迁移的规则底稿来源和可信级别。
7. 通过审计发布向下游提供版本化 Supplier Master Data，避免交易域直接读取不稳定后台草稿。

## 2. 边界

### In Scope

- Supplier、Carrier、品牌、法律实体、结算主体、联系人、认证资质、服务市场和交通方式范围。
- ProductCatalog、Product、FareFamily、ServiceClass、AncillaryProduct、销售渠道、适用旅客类型和上下架窗口。
- Contract、ContractVersion、合作范围、API 权限、SLA、佣金/结算条款摘要、责任边界和生效/失效日期。
- Capability 矩阵：按 Supplier、Contract、Product、mode、market、channel 记录可支持业务动作和限制。
- 外部编码映射：供应商站点/机场/港口/上车点、舱等/席别、票价族、产品码、附加服务码、规则码。
- RuleSource 和规则底稿：票价、退改、服务费、附加商品、证件和特殊旅客规则的来源、版本、可信级别和下游归属。
- 多交通方式供应商差异：铁路局、航司/GDS/NDC、大巴公司、船司、网约车平台、接驳运营商和代理商的目录表达。
- 供应商主数据版本、草稿校验、审批发布、撤回、替换、审计记录和变更事件。

### Out of Scope

- 不执行 Provider API，不做重试、熔断、报文解析、供应商状态码映射或实时外部调用；这些属于 Provider Integration。
- 不计算最终价格、税费、优惠、退改费或差价；Fare & Pricing 消费本域底稿后发布可执行规则。
- 不拥有 Service Plan 的车次、航班、船班、班线、Calendar、Timetable 或 ScheduledService。
- 不拥有实时 Capacity、余票、座席、舱位、司机供给、Hold、Quota 或可售性判断。
- 不创建 Offer、Journey Order、Segment Booking、Payment、Entitlement、Fulfillment 或 PostSalesCase。
- 不管理 Place & Network 的地点拓扑和官方节点主数据；本域只保存供应商外部编码到 PlaceId/TransportNodeId 的映射。
- 不负责 Finance Settlement 的对账、清算、收入确认和发票，只提供合同与结算主体引用。
- 不决定客服人工赔付、异常恢复方案或用户通知内容。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| Supplier | 与平台合作提供运输、票务、调度或附加服务的供应侧主体。 | 可是承运商、代理、平台、车队或运营方。 |
| SupplierProfile | Supplier 的法律实体、品牌、市场、联系方式、资质和风险属性。 | 发布后作为下游展示和审计引用。 |
| ProductCatalog | 某 Supplier 可售或可服务产品的版本化目录。 | 包含主票和 AncillaryProduct 底稿。 |
| Contract | 平台与 Supplier 的合作契约。 | 只保存业务摘要和可引用条款，不替代法务系统原件。 |
| ContractVersion | Contract 的可发布版本。 | 生效窗口、范围和能力不可与同 scope 版本冲突。 |
| Capability | Supplier 在特定范围内支持的业务动作。 | 例如 search、quote、reserve、issue、refund、change、check-in。 |
| CapabilityMatrix | 按 Supplier、Product、mode、market、channel 组织的能力表。 | Provider Integration 和 Booking Orchestration 消费。 |
| FareFamily | 供应商定义或平台归一的票价族。 | Fare & Pricing 将其转成可执行 FareRuleSet 输入。 |
| ServiceClass | 服务等级或舱等/席别归一分类。 | 与 Capacity 的库存分类相关，但不表示余量。 |
| AncillaryProduct | 行李、保险、餐食、选座、接送、优先登乘等附加商品底稿。 | Ancillary Service 负责售卖和履约。 |
| RuleSource | 价格、退改、资格或附加服务规则的来源记录。 | 来源可以是合同、供应商接口、监管公告、人工录入。 |
| SupplierLocationMapping | 供应商地点编码到平台 TransportNodeId 或 PlaceId 的映射。 | 不创建地点权威。 |
| ExternalCodeMapping | 外部产品码、舱等码、规则码和能力码到平台语言的映射。 | ACL 使用，发布后不可无痕覆盖。 |
| MasterDataRelease | 一次可被下游消费的供应商主数据发布版本。 | 下游只读 release，不读草稿。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| Admin & Audit | operator identity、approval result、audit reason、release approval | 供应商接入、合同发布、能力开通和产品上下架需要审批与审计。 |
| Place & Network | PlaceId、TransportNodeId、node snapshot、timezone、node type | 映射供应商地点编码，避免本域自造站点、机场、港口或上车点。 |
| Legal / Contract Repository | contract document ref、legal party、effective terms summary | ContractVersion 需要引用法务原件和条款摘要。 |
| Provider Integration | provider descriptor、external capability discovery、data quality report | 外部接口可发现能力和编码，但需经本域发布为主数据。 |
| Finance Settlement | settlement account validation、tax profile validation | 结算主体和账期摘要需要可被清结算识别。 |
| Risk & Compliance | supplier risk flag、license validation、market restriction | 合规风险影响 Supplier 是否可发布或可售。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Service Plan | Supplier、Carrier、ServiceMode、ContractCapability、SupplierLocationMapping | 固定班次计划需要承运主体、服务方式和地点编码映射。 |
| Fare & Pricing | FareFamily draft、tariff source、refund/change policy draft、provider fee policy、RuleSource | 价格和退改规则底稿来自供应商与合同，但执行规则归 Fare & Pricing。 |
| Provider Integration | SupplierConnectionProfile、CapabilityMatrix、ExternalCodeMapping、contract API scope | 防腐层据此选择接口、鉴权、能力和错误映射。 |
| Capacity & Availability | capacity capability flags、inventory class mapping、quota source hint | 库存上下文需要知道供应商支持的库存查询、锁定和分类语言。 |
| Ancillary Service | AncillaryProduct draft、eligibility hint、fulfillment responsibility | 附加商品域负责商品化、售卖、履约和售后联动。 |
| Booking Orchestration | booking capability summary、reservation policy hint、supplier responsibility boundary | 编排域判断某段是否可预留、是否需供应商确认和失败补偿方式。 |
| Finance Settlement | settlement party ref、commission term summary、contract version ref | 清结算按合同版本和主体计算应收应付。 |
| Reporting / Customer Service | supplier display profile、release history、capability audit view | 运营、客服和报表需要解释供应商状态和变更历史。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| Supplier | supplierId 全局唯一；法律实体、品牌、运营主体和风险状态可追溯；Inactive 或 Suspended Supplier 不能发布新 ProductCatalog；发布版本不可原地覆盖。 | RegisterSupplier、UpdateSupplierProfile、SuspendSupplier、ReactivateSupplier、PublishSupplierProfile | SupplierRegistered、SupplierProfileUpdated、SupplierSuspended、SupplierReactivated、SupplierProfilePublished |
| ProductCatalog | 必须绑定 Supplier 和 catalog version；Product、FareFamily、ServiceClass、AncillaryProduct 的外部码在同版本内唯一；上线窗口不能早于 Supplier 和 Contract 有效期；下架不删除历史引用。 | CreateProductCatalog、AddProduct、MapServiceClass、MapFareFamily、AddAncillaryProduct、PublishProductCatalog、RetireProduct | ProductCatalogCreated、ProductAdded、ServiceClassMapped、FareFamilyMapped、AncillaryProductAdded、ProductCatalogPublished、ProductRetired |
| Contract | ContractVersion 必须绑定 legal document ref、Supplier、scope、effective window 和 approval ref；同 scope 有效窗口不能冲突；Published 后只能新版本替换、暂停或终止。 | RegisterContract、DraftContractVersion、ValidateContractVersion、PublishContractVersion、SupersedeContractVersion、TerminateContract | ContractRegistered、ContractVersionDrafted、ContractVersionValidated、ContractVersionPublished、ContractVersionSuperseded、ContractTerminated |
| CapabilityMatrix | 每个 Capability 必须有 scope、source、support level、constraint 和 owning ContractVersion；能力变更必须生成新 release；不允许宣称超出合同和供应商资质的能力。 | DefineCapability、UpdateCapabilityConstraint、ValidateCapabilityMatrix、PublishCapabilityMatrix、SuspendCapability | CapabilityDefined、CapabilityConstraintUpdated、CapabilityMatrixValidated、CapabilityMatrixPublished、CapabilitySuspended |
| RuleSource | 每个规则底稿必须有来源类型、source ref、可信级别、适用范围、版本和下游归属；冲突来源必须标记优先级；发布后保留原文摘要和解析结果。 | RegisterRuleSource、AttachRuleDraft、ClassifyRuleSource、ResolveRuleSourceConflict、PublishRuleSourceSet | RuleSourceRegistered、RuleDraftAttached、RuleSourceClassified、RuleSourceConflictResolved、RuleSourceSetPublished |
| MasterDataRelease | 一次 release 必须引用 SupplierProfile、ProductCatalog、ContractVersion、CapabilityMatrix 和 RuleSourceSet 的一致版本；发布需审批；撤回必须发布影响范围。 | PrepareMasterDataRelease、ValidateMasterDataRelease、PublishMasterDataRelease、WithdrawMasterDataRelease | MasterDataReleasePrepared、MasterDataReleaseValidated、MasterDataReleasePublished、MasterDataReleaseWithdrawn |

## 6. 状态机

### Supplier 状态

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Draft | 接入资料正在维护，不能被下游消费。 | UnderReview、Archived |
| UnderReview | 资质、合规、结算和合同资料审核中。 | Active、Rejected、Draft |
| Active | 可发布产品、合同和能力，供下游引用。 | Suspended、Inactive |
| Suspended | 因合规、接口、结算或业务风险暂停新增售卖。 | Active、Inactive |
| Inactive | 合作结束或长期停用，不允许新 release。 | Archived |
| Rejected | 接入审核未通过。 | Draft、Archived |
| Archived | 只保留历史订单、审计和报表解释。 | 终态 |

### ContractVersion 状态

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Draft | 合同条款摘要、范围和附件引用正在编辑。 | Validating、Withdrawn |
| Validating | 校验法务引用、生效窗口、scope 冲突、结算主体和能力范围。 | ReadyToPublish、Draft、Rejected |
| ReadyToPublish | 校验通过，等待审批发布。 | Published、Withdrawn |
| Published | 在有效期内作为下游规则和能力来源。 | Superseded、Suspended、Terminated、Expired |
| Suspended | 临时暂停使用，但保留恢复可能。 | Published、Terminated |
| Superseded | 被新版本替代，不再用于新发布。 | 终态 |
| Terminated | 合同终止，不允许新售卖；历史引用保留。 | 终态 |
| Expired | 超过有效期自然失效。 | 终态 |

### ProductCatalog / Capability 发布状态

| 状态 | 含义 | 允许转换 |
|---|---|---|
| Draft | 产品、编码或能力正在维护。 | Validated、Discarded |
| Validated | 编码、地点、合同和规则来源校验通过。 | Published、Draft |
| Published | 可被下游消费。 | Superseded、PartiallySuspended、Retired |
| PartiallySuspended | 部分产品、渠道、市场或能力暂停。 | Published、Retired |
| Superseded | 被新版本替代。 | 终态 |
| Retired | 产品或能力下架，不参与新售卖。 | 终态 |
| Discarded | 草稿废弃。 | 终态 |

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| RegisterSupplier | Supplier | SupplierRegistered | `sourceSystem + externalSupplierCode + onboardingBatchId` |
| UpdateSupplierProfile | Supplier | SupplierProfileUpdated | `supplierId + profileVersion + changeRequestId` |
| SuspendSupplier | Supplier | SupplierSuspended | `supplierId + reasonCode + approvalRef` |
| RegisterContract | Contract | ContractRegistered | `supplierId + legalDocumentRef + contractNo` |
| DraftContractVersion | Contract | ContractVersionDrafted | `contractId + versionNo + draftRequestId` |
| PublishContractVersion | Contract | ContractVersionPublished | `contractVersionId + approvalRef` |
| CreateProductCatalog | ProductCatalog | ProductCatalogCreated | `supplierId + catalogVersion + importBatchId` |
| MapServiceClass | ProductCatalog | ServiceClassMapped | `catalogId + externalClassCode + platformClass + requestId` |
| MapFareFamily | ProductCatalog | FareFamilyMapped | `catalogId + externalFareFamilyCode + requestId` |
| AddAncillaryProduct | ProductCatalog | AncillaryProductAdded | `catalogId + externalAncillaryCode + version` |
| PublishProductCatalog | ProductCatalog | ProductCatalogPublished | `catalogId + approvalRef` |
| DefineCapability | CapabilityMatrix | CapabilityDefined | `supplierId + capabilityScope + capabilityCode + sourceVersion` |
| PublishCapabilityMatrix | CapabilityMatrix | CapabilityMatrixPublished | `matrixId + approvalRef` |
| RegisterRuleSource | RuleSource | RuleSourceRegistered | `supplierId + sourceType + sourceRef + sourceVersion` |
| PublishRuleSourceSet | RuleSource | RuleSourceSetPublished | `ruleSourceSetId + approvalRef` |
| PublishMasterDataRelease | MasterDataRelease | MasterDataReleasePublished | `supplierId + releaseVersion + approvalRef` |
| WithdrawMasterDataRelease | MasterDataRelease | MasterDataReleaseWithdrawn | `releaseId + withdrawalRequestId` |

所有发布事件必须通过 Outbox；来自导入、Provider Integration 发现和后台审批的输入必须通过 Inbox 去重。同一外部编码的重复导入不得生成多个活跃映射。

## 8. 策略和 Saga 参与点

- 供应商接入策略：Supplier 只有在资质、结算主体、合同范围和风险标识通过后，才能从 UnderReview 进入 Active，并允许创建可发布目录。
- 合同版本策略：ContractVersion 生效窗口与 scope 冲突时阻止发布；新版本发布后，旧版本进入 Superseded，但历史 Offer、订单、结算仍按原 version ref 解释。
- 能力矩阵策略：Capability 不直接调用外部接口，只声明“平台与供应商约定且经验证可使用的能力”。Provider Integration 可消费能力并反馈实际健康状况，但健康熔断状态不写回为目录主状态。
- 产品上下架策略：ProductCatalogPublished 触发 Service Plan、Fare & Pricing、Ancillary Service 和 Provider Integration 重建读模型；ProductRetired 不删除历史，只禁止新售卖或新计划引用。
- 地点映射策略：供应商外部地点码必须映射到 Place & Network 的稳定节点；无法映射时目录校验失败，并形成数据质量事件。
- 规则底稿策略：RuleSourceSetPublished 后，Fare & Pricing 负责转换、校验和发布 FareRuleSet；本域不保证底稿已可直接执行。
- 变更影响策略：MasterDataReleaseWithdrawn 只撤回供应商主数据发布，不自动取消已售订单；Disruption Recovery、Post Sales、Customer Service 根据影响范围决定用户处理。
- 多交通方式策略：同一 Supplier 可拥有多个 mode 的 ProductCatalog 和 CapabilityMatrix，但铁路区间票额、航空 PNR、网约车派单、轮船舱房容量等专有状态仍在各自下游上下文或 Provider Integration 中处理。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| SupplierDirectoryView | SupplierRegistered、SupplierProfilePublished、SupplierSuspended、SupplierReactivated | Admin & Audit、Reporting、Customer Service、Provider Integration |
| SupplierReleaseView | MasterDataReleasePrepared、MasterDataReleasePublished、MasterDataReleaseWithdrawn | 所有下游上下文按 release 查询供应商主数据 |
| ContractVersionView | ContractRegistered、ContractVersionPublished、ContractVersionSuperseded、ContractTerminated | Fare & Pricing、Finance Settlement、Provider Integration、客服审计 |
| ProductCatalogView | ProductCatalogPublished、ProductAdded、ProductRetired | Service Plan、Offer Management、Ancillary Service、运营后台 |
| CapabilityMatrixView | CapabilityDefined、CapabilityMatrixPublished、CapabilitySuspended | Provider Integration、Booking Orchestration、Capacity & Availability |
| SupplierCodeMappingView | ServiceClassMapped、FareFamilyMapped、ProductCatalogPublished | Service Plan、Fare & Pricing、Provider Integration |
| RuleSourceLedger | RuleSourceRegistered、RuleDraftAttached、RuleSourceSetPublished | Fare & Pricing、Post Sales、审计、客服解释 |
| AncillaryDraftCatalogView | AncillaryProductAdded、ProductCatalogPublished、ProductRetired | Ancillary Service、Offer Management、Post Sales |
| SupplierChangeTimeline | SupplierProfileUpdated、ContractVersionPublished、CapabilityMatrixPublished、MasterDataReleasePublished | Reporting、Admin & Audit、Customer Service |

读模型允许冗余供应商展示名、品牌、外部码、合同摘要和能力说明；任何会改变下游可售范围的操作必须回到聚合命令生成新发布事件。

## 10. 外部系统和防腐层

Supplier Catalog 面向三类外部语言：供应商原始主数据、合同/法务语言和历史系统配置。防腐层的职责是把这些输入整理为平台 Published Language，而不是把外部状态直接暴露给核心交易域。

| External Source | ACL Mapping | Protection Rule |
|---|---|---|
| 铁路局/票务代理 | 车站码、席别、车次归属、退改公告 → SupplierLocationMapping、ServiceClass、RuleSource | 区间票额和实时余票不进入本域。 |
| 航司/GDS/NDC | airline code、booking class、fare brand、ancillary code → Supplier、FareFamily、ServiceClass、AncillaryProduct | PNR、实时舱位和出票状态留在 Provider Integration、Capacity、Entitlement。 |
| 大巴公司 | 班线商、上车点码、车型、座席等级 → Supplier、ProductCatalog、SupplierLocationMapping | 非标准上车点必须先由 Place & Network 确认。 |
| 船司 | 港口/码头码、舱房等级、车辆甲板产品 → ProductCatalog、ServiceClass、AncillaryProduct | 舱房和甲板容量不作为目录库存。 |
| 网约车平台 | 车型、服务城市、派单能力、取消费来源 → ProductCatalog、CapabilityMatrix、RuleSource | 司机、车辆、ETA 和派单状态属于 Dispatch / Provider Integration。 |
| 合同系统 | 合同编号、附件、条款摘要、生效范围 → ContractVersion、RuleSource | 本域保存可执行摘要和引用，不替代合同原件。 |
| 历史配置库 | supplier table、price config、seat class mapping、API flag → 草稿 ProductCatalog 和 CapabilityMatrix | 迁移输入需补齐版本、来源、审批和冲突校验后才能发布。 |

ACL 必须记录 sourceSystem、sourceRef、sourceVersion、导入批次、解析规则版本和数据质量结果。供应商原始状态码、报文结构和临时字段不得成为本域聚合状态。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-basic-service` | 当前混合 station、train、route、price、config 的基础表需拆分；供应商、承运商、席别/舱等、外部码和产品目录迁入 Supplier Catalog。 |
| `ts-train-service` / 车次配置模块 | 车次归属的承运主体和服务等级映射由本域提供；具体时刻和经停仍迁入 Service Plan。 |
| `ts-price-service` | 供应商合同价、票价族、退改底稿和服务费来源迁为 RuleSource；可执行报价逻辑留在 Fare & Pricing。 |
| `ts-seat-service` | 席别分类和供应商库存类别映射来自 ProductCatalog；余票、区间库存和锁票逻辑仍归 Capacity & Availability。 |
| `ts-preserve-service`, `ts-preserve-other-service` | 下单前不再硬编码供应商能力；通过 CapabilityMatrix 判断是否可预留、确认、出票、退改。 |
| `ts-cancel-service`, `ts-rebook-service` | 退改能力、供应商罚金来源和合同版本引用来自本域；售后 Case 和费用计算不迁入本域。 |
| `ts-assurance-service`, `ts-food-service`, `ts-consign-service` | 附加服务底稿迁为 AncillaryProduct；商品化、售卖、履约和售后仍归 Ancillary Service。 |
| `ts-admin-*` | 供应商、合同、产品、能力和规则来源的后台配置改为命令式发布，并接入 Admin & Audit 审批。 |
| `ts-route-service` / `ts-station-service` | 地点权威迁入 Place & Network；本域只保留供应商外部地点编码映射。 |
| Provider 适配模块 | 接口地址、鉴权范围、能力开关和外部码映射从分散配置迁到 SupplierReleaseView；实时调用仍在 Provider Integration。 |
| 离线报表任务 | 供应商维度、合同版本、产品上下架和能力变更从 SupplierChangeTimeline 重建。 |

迁移优先级：先建立 Supplier 和 ContractVersion 主数据，再迁移 ProductCatalog 与编码映射，随后发布 CapabilityMatrix，最后把散落在价格、退改和附加服务中的规则底稿收敛为 RuleSource。

## 12. 验收标准

- Supplier Catalog 的聚合所有权明确：Supplier、ProductCatalog、Contract、CapabilityMatrix、RuleSource 和 MasterDataRelease 归本域。
- 本域不执行 Provider API、不计算最终价格、不拥有 Service Plan 时刻表、不拥有实时 Capacity，也不拥有 Booking、Payment、PostSales 流程。
- Supplier、ProductCatalog、ContractVersion、Capability、FareFamily、ServiceClass、AncillaryProduct、RuleSource 均有版本、来源、发布和审计语义。
- 多交通方式供应商差异已覆盖：铁路、飞机、大巴、轮船、网约车和接驳服务可以共享目录语言，同时保留专有边界。
- 合同版本、生效窗口、终止、替换和历史引用规则明确，已售订单和结算可追溯到旧版本。
- 能力矩阵能支持查询、报价、预留、确认、出票、取消、改签、退票、值机、选座、行李、异常同步和结算文件等能力表达。
- 供应商地点、服务等级、票价族、产品码、附加服务码和规则码映射有明确 ACL，不污染 Place、Service Plan、Fare 或 Capacity 的权威模型。
- 产品上下架和 MasterDataRelease 发布不会无痕覆盖历史，并能通过事件驱动下游重建读模型。
- RuleSource 与 Fare & Pricing 的边界清晰：本域发布底稿和来源，Fare & Pricing 发布可执行 FareRuleSet 和报价结果。
