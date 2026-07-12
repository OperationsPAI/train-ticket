# Account Domain Design

## Metadata

| Field | Value |
|---|---|
| Domain | Account |
| Status | accepted-ddd-baseline |
| Owner Agent | agentm-account |
| Last Updated | 2026-06-28 |
| High-Level Inputs | `docs/01-ddd-high-level/domain-glossary.md`, `docs/01-ddd-high-level/context-map.md`, `docs/01-ddd-high-level/general-travel-ddd.md`, `docs/02-domains/payment.md` |

## 1. 领域目标

Account bounded context 负责平台用户账户、登录身份、会话、安全摘要、会员关系、企业与代理人账户关系、偏好、同意授权，以及与钱包、积分、券相关的引用边界。它为 Trip Planning、Traveler Profile、Journey Order、Payment、Risk & Compliance、Notification、Customer Service 等上下文提供稳定的账户引用和授权事实，但不保存实际旅客证件、资金余额、票证状态或风控判定。

Account 的目标是把“谁在使用平台、以什么身份使用、拥有哪些账户级关系和授权”建模清楚：个人用户可以注册登录并维护联系方式；企业、代理、团队场景可以表达组织账户、成员权限和代下单委托；常旅客绑定可以作为账户到 Traveler Profile 的关系引用；隐私同意和偏好可以支撑通知、营销、个性化和合规审计。所有跨域协作必须通过 `accountRef`、`userAccountId`、`corporateAccountId`、`delegationRef`、`consentRef` 等稳定引用完成，避免其他上下文读取 Account 内部表结构。

## 2. 边界

### In Scope

- `UserAccount` 生命周期：注册、激活、冻结、解冻、注销、恢复窗口和账户安全摘要。
- `LoginIdentity` 管理：手机号、邮箱、第三方登录、渠道账号、企业 SSO、代理人登录身份绑定与解绑。
- `Session` 管理：登录会话、刷新令牌、设备摘要、退出登录、强制失效和风险挑战后的会话降级。
- 联系方式归属：账户级手机号、邮箱、紧急联系摘要，以及联系方式验证状态。
- `Membership`：平台会员等级、成长值、权益输入、有效期、企业会员关系引用。
- `CorporateAccount`：企业账户、部门或项目维度、员工成员、企业支付/报销引用、企业权限策略。
- 代理人与团队账户：代理机构、代下单人、被代理账户、委托范围、有效期和撤销。
- 常旅客绑定关系：账户与 Traveler Profile 中实际旅客的绑定、默认旅客排序、本人关系声明。
- `Consent`：隐私政策、实名授权、营销授权、儿童/未成年人监护授权、跨境数据处理授权的版本化记录。
- `Preference`：通知偏好、语言、常用出发地、展示偏好、无障碍偏好等账户级偏好。
- `WalletRef`、积分、券引用：保存账户到钱包、积分账户、券包的引用和可见摘要，不维护资金或优惠券余额。
- Account events、read models、Outbox/Inbox 和跨域 ACL。

### Out of Scope

- Traveler Profile 负责实际旅客、姓名、证件、出生日期、资质、优惠资格和出行偏好明细。
- Payment 负责资金、支付方式、预授权、扣款、退款、钱包资金流水和渠道回调。
- Risk & Compliance 负责反刷、黑名单、设备风险、支付风险、重复行程、实名合规判定。
- Journey Order 只保存 `accountRef`、购买时账户快照和下单授权快照，不读取 Account 内部状态推进订单。
- Notification 负责模板、发送、重试和触达渠道；Account 只提供通知偏好和可联系性事实。
- Admin & Audit 负责后台权限审批和审计平台；Account 只发布账户域内的可审计事实。
- Customer Service 负责工单和人工处理流程；Account 只提供受控账户操作命令和查询视图。
- Finance Settlement 负责发票、清算、企业账单和收入确认；CorporateAccount 只保存账务引用。

## 3. 统一语言补充

| Term | Definition | Notes |
|---|---|---|
| `Account` | 账户上下文的统称，包含用户、登录身份、会话、会员、企业关系、偏好和同意授权。 | 不等于实际旅客，也不等于资金账户。 |
| `UserAccount` | 平台个人账户聚合根，代表一个可登录或被授权使用平台的用户主体。 | 订单中引用为 `accountRef`。 |
| `LoginIdentity` | 可用于认证的身份因子，如手机号、邮箱、OAuth、企业 SSO、代理账号。 | 一个 UserAccount 可绑定多个 LoginIdentity。 |
| `Session` | 已认证访问上下文，包含设备摘要、认证强度、过期时间和失效原因。 | 不承载风控最终判定。 |
| `ContactPoint` | 账户级联系方式及验证状态。 | 旅客手机号属于 Traveler Profile。 |
| `Membership` | 会员等级、成长值、权益输入和有效期。 | 可影响报价展示，但优惠计算归 Fare & Pricing。 |
| `CorporateAccount` | 企业、机构或代理的组织账户。 | 不直接表达企业资金余额。 |
| `AccountMembership` | UserAccount 与 CorporateAccount 的成员关系、角色和权限范围。 | 用于企业订票、代理订票、报销场景。 |
| `Delegation` | 某账户授权另一账户或代理人为其执行指定操作的关系。 | 必须有范围、有效期和撤销记录。 |
| `FrequentTravelerBinding` | Account 到 Traveler Profile 中 travelerRef 的绑定关系。 | 不复制证件明细。 |
| `Consent` | 用户对隐私、实名、营销、数据共享等条款版本的同意或撤回事实。 | 需要版本化和可审计。 |
| `Preference` | 账户级偏好设置。 | 出行证件偏好归 Traveler Profile。 |
| `WalletRef` | 指向 Payment/Wallet/Promotion 等资金或权益账户的稳定引用。 | Account 只保存引用和展示摘要。 |
| `AccountSecuritySummary` | 账户安全摘要，如 MFA 状态、最近登录、冻结原因摘要。 | 风险评分来源于 Risk & Compliance。 |

## 4. 上下游契约

### Upstream

| Upstream Context | Consumed Contract | Reason |
|---|---|---|
| 用户、渠道、企业 SSO | 注册资料、登录凭证、设备摘要、SSO assertion | 创建或认证 UserAccount、LoginIdentity、Session。 |
| Risk & Compliance | `AccountRiskChallenged`, `AccountAccessBlocked`, risk challenge result | 根据明确风险结果冻结、降级 Session 或要求重新认证。 |
| Customer Service | `FreezeAccount`, `UnfreezeAccount`, `ResolveAccountRecovery`, `UpdateContactByCase` | 人工处理账户申诉、盗号、联系方式修正。 |
| Admin & Audit | 企业管理员任命、代理机构准入、角色审批结果 | 管理 CorporateAccount、代理人和高权限角色。 |
| Payment / Wallet | `WalletLinked`, `WalletUnlinked`, wallet capability summary | 维护 `WalletRef` 可见摘要，不解释资金状态。 |
| Promotion / Loyalty | 会员成长值变更、积分账户引用、券包引用 | 更新 Membership 输入和权益引用。 |
| Traveler Profile | `TravelerProfileCreated`, `TravelerProfileMerged`, `TravelerProfileDeleted` | 维护 FrequentTravelerBinding 的有效性。 |

### Downstream

| Downstream Context | Published Contract | Reason |
|---|---|---|
| Traveler Profile | `AccountRegistered`, `AccountClosed`, `FrequentTravelerBound`, accountRef | 旅客资料归属和默认旅客绑定。 |
| Journey Order | accountRef、购买时账户快照、`DelegationValidated`, `AccountFrozen` | 下单、企业代订、售后入口和冻结拦截。 |
| Payment | payerRef、CorporateAccount paymentRef、`WalletRef`, `AccountContactVerified` | 收银台展示、企业付款引用和联系方式校验。 |
| Risk & Compliance | 登录、冻结、解绑、委托、企业权限事件 | 风控建模和异常行为检测。 |
| Notification | `PreferenceChanged`, verified ContactPoint summary, consent status | 触达渠道选择和营销授权控制。 |
| Customer Service | Account read models、账户安全时间线、同意记录 | 客服解释用户身份、权限和账户操作历史。 |
| Reporting | 去标识化账户维度、会员等级、企业关系摘要 | 运营分析，不反向修改 Account。 |

## 5. 聚合设计

| Aggregate Root | Invariants | Commands | Events |
|---|---|---|---|
| `UserAccount` | 一个 active UserAccount 至少有一个可恢复联系方式或受控外部身份；冻结账户不能创建高风险 Session；注销进入冷却期后只允许恢复或最终关闭。 | `RegisterAccount`, `ActivateAccount`, `FreezeAccount`, `UnfreezeAccount`, `RequestAccountClosure`, `FinalizeAccountClosure` | `AccountRegistered`, `AccountActivated`, `AccountFrozen`, `AccountUnfrozen`, `AccountClosureRequested`, `AccountClosed` |
| `LoginIdentity` | 同一 identity provider + normalized identifier 在 active 状态下只能绑定一个 UserAccount；解绑不能让账户失去所有恢复路径。 | `BindLoginIdentity`, `VerifyLoginIdentity`, `UnbindLoginIdentity`, `RotateCredential` | `LoginIdentityBound`, `LoginIdentityVerified`, `LoginIdentityUnbound`, `CredentialRotated` |
| `Session` | Session 必须绑定 UserAccount 和认证强度；账户冻结、注销、凭证轮换可强制失效；过期 Session 不可刷新。 | `CreateSession`, `RefreshSession`, `RevokeSession`, `RevokeAllSessions`, `DowngradeSession` | `SessionCreated`, `SessionRefreshed`, `SessionRevoked`, `AllSessionsRevoked`, `SessionDowngraded` |
| `Membership` | 会员等级只能由有效成长值、购买资格或企业权益输入推导；权益快照需版本化；过期权益不再发布为 active。 | `EnrollMembership`, `ApplyMembershipInput`, `ExpireMembership`, `LinkCorporateBenefit` | `MembershipEnrolled`, `MembershipLevelChanged`, `MembershipExpired`, `CorporateBenefitLinked` |
| `CorporateAccount` | 企业账户必须有至少一个 active owner；高风险角色变更需要审批引用；企业冻结后成员不能新增企业代订授权。 | `CreateCorporateAccount`, `AddCorporateMember`, `ChangeCorporateRole`, `FreezeCorporateAccount`, `RemoveCorporateMember` | `CorporateAccountCreated`, `CorporateMemberAdded`, `CorporateRoleChanged`, `CorporateAccountFrozen`, `CorporateMemberRemoved` |
| `Delegation` | 委托必须有 grantor、grantee、scope、有效期和撤销路径；不能越过 CorporateAccount 或 Risk 的阻断结果。 | `GrantDelegation`, `ValidateDelegation`, `RevokeDelegation`, `ExpireDelegation` | `DelegationGranted`, `DelegationValidated`, `DelegationRevoked`, `DelegationExpired` |
| `Consent` | 同一 consent type 按版本记录同意和撤回；撤回后不得继续发布 active consent；未成年人授权必须绑定监护关系引用。 | `RecordConsent`, `WithdrawConsent`, `MigrateConsentVersion` | `ConsentRecorded`, `ConsentWithdrawn`, `ConsentVersionMigrated` |
| `Preference` | 偏好更新必须可追踪来源；通知偏好不能覆盖法律或交易必要通知；跨设备展示偏好按账户版本同步。 | `UpdatePreference`, `ResetPreference`, `ImportPreference` | `PreferenceUpdated`, `PreferenceReset`, `PreferenceImported` |

## 6. 状态机

### `UserAccount` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Registered` | 账户已创建，等待关键联系方式或身份验证。 | `Active`, `Frozen`, `ClosurePending` |
| `Active` | 可正常登录、下单和维护资料。 | `Frozen`, `ClosurePending` |
| `Frozen` | 因安全、客服、合规或企业策略被限制。 | `Active`, `ClosurePending` |
| `ClosurePending` | 用户请求注销，处于冷却与未完成业务检查期。 | `Active`, `Closed` |
| `Closed` | 账户最终注销，保留合规审计最小记录。 | 终态 |

### `LoginIdentity` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `PendingVerification` | 已提交但未验证。 | `Verified`, `Rejected`, `Unbound` |
| `Verified` | 可用于认证或恢复。 | `Suspended`, `Unbound` |
| `Suspended` | 因风险或渠道异常暂不可用。 | `Verified`, `Unbound` |
| `Unbound` | 已解绑，仅保留审计摘要。 | 终态 |

### `Session` 状态机

| State | Meaning | Allowed Transitions |
|---|---|---|
| `Active` | 可访问平台能力。 | `Downgraded`, `Revoked`, `Expired` |
| `Downgraded` | 只能访问低风险能力或需重新认证。 | `Active`, `Revoked`, `Expired` |
| `Revoked` | 主动退出、风控或凭证变化导致失效。 | 终态 |
| `Expired` | 到达过期时间。 | 终态 |

### `CorporateAccount` 与 `Delegation` 状态

- `CorporateAccount`: `Draft`、`Active`、`Frozen`、`Closed`。冻结企业不能创建新委托，但不自动取消历史订单。
- `Delegation`: `Granted`、`Validated`、`Revoked`、`Expired`。Journey Order 下单时只使用购买时 `DelegationValidated` 快照。
- `Consent`: `Active`、`Withdrawn`、`Superseded`。新版本条款不能覆盖旧版本审计事实。

## 7. 命令和领域事件

| Command | Aggregate | Event | Idempotency Key |
|---|---|---|---|
| `RegisterAccount` | `UserAccount` | `AccountRegistered` | channel + normalized identity + request id |
| `ActivateAccount` | `UserAccount` | `AccountActivated` | accountId + verification event id |
| `FreezeAccount` | `UserAccount` | `AccountFrozen` | accountId + reason + operator/case id |
| `UnfreezeAccount` | `UserAccount` | `AccountUnfrozen` | accountId + case resolution id |
| `RequestAccountClosure` | `UserAccount` | `AccountClosureRequested` | accountId + closure request id |
| `FinalizeAccountClosure` | `UserAccount` | `AccountClosed` | accountId + closure due timestamp |
| `BindLoginIdentity` | `LoginIdentity` | `LoginIdentityBound` | provider + normalized identifier |
| `VerifyLoginIdentity` | `LoginIdentity` | `LoginIdentityVerified` | identityId + verification code/session id |
| `CreateSession` | `Session` | `SessionCreated` | accountId + device id + auth nonce |
| `RevokeAllSessions` | `Session` | `AllSessionsRevoked` | accountId + credential/security event id |
| `EnrollMembership` | `Membership` | `MembershipEnrolled` | accountId + membership program id |
| `ApplyMembershipInput` | `Membership` | `MembershipLevelChanged` | accountId + source event id |
| `CreateCorporateAccount` | `CorporateAccount` | `CorporateAccountCreated` | legal entity ref + channel request id |
| `AddCorporateMember` | `CorporateAccount` | `CorporateMemberAdded` | corporateId + userAccountId + invitation id |
| `ChangeCorporateRole` | `CorporateAccount` | `CorporateRoleChanged` | corporateId + userAccountId + approval id |
| `GrantDelegation` | `Delegation` | `DelegationGranted` | grantor + grantee + scope + request id |
| `ValidateDelegation` | `Delegation` | `DelegationValidated` | delegationId + businessRef + version |
| `RevokeDelegation` | `Delegation` | `DelegationRevoked` | delegationId + revocation request id |
| `BindFrequentTraveler` | `UserAccount` | `FrequentTravelerBound` | accountId + travelerRef |
| `UnbindFrequentTraveler` | `UserAccount` | `FrequentTravelerUnbound` | accountId + travelerRef + request id |
| `RecordConsent` | `Consent` | `ConsentRecorded` | accountId + consentType + version |
| `WithdrawConsent` | `Consent` | `ConsentWithdrawn` | accountId + consentType + withdrawal id |
| `UpdatePreference` | `Preference` | `PreferenceUpdated` | accountId + preference key + version |
| `LinkWalletRef` | `UserAccount` | `WalletRefLinked` | accountId + wallet provider + wallet ref |

## 8. 策略和 Saga 参与点

- 注册登录策略：渠道或 SSO 提供身份后，Account 创建 `UserAccount`、绑定 `LoginIdentity`、创建 `Session`，并向 Risk & Compliance 发布登录事实；若 Risk 返回挑战结果，Account 降级或撤销 Session。
- 下单授权策略：Journey Order 在创建订单前请求验证 `accountRef`、账户状态、联系方式验证、企业成员角色和 `Delegation` 范围；Account 返回版本化授权快照，不参与库存、价格或资金决策。
- 企业代订 Saga：CorporateAccount 成员以企业身份下单时，Account 校验成员角色、企业状态、代理权限和委托范围，Journey Order 保存 corporate snapshot，Payment 使用企业 paymentRef。
- 常旅客绑定策略：Account 只维护 travelerRef 绑定；Traveler Profile 负责实际旅客信息、证件合规和优惠资质。旅客合并或删除事件会让 Account 更新绑定状态。
- 账户冻结策略：冻结事件发布给 Journey Order、Payment、Notification、Risk、Customer Service；已有订单是否取消、退款或限制售后由各自上下文决定。
- 注销 Saga：Account 进入 `ClosurePending` 后发布事件，请 Journey Order、Payment、Customer Service 返回未完成业务摘要；冷却期结束且无阻断时 `AccountClosed`，但保留合规最小审计引用。
- 隐私同意策略：Consent 版本变化或撤回后，Notification、Reporting、Personalization 类消费者必须更新可用数据范围；交易必要通知不因营销同意撤回而停止。
- 会员权益策略：Membership 发布等级和权益输入给 Offer Management、Fare & Pricing 或 Promotion，但优惠价格计算、券核销、积分余额不在 Account 内完成。

## 9. 读模型

| Read Model | Source Events | Consumers |
|---|---|---|
| `AccountProfileView` | `AccountRegistered`, `AccountActivated`, `ContactPointVerified`, `PreferenceUpdated` | 客户端、Journey Order、Customer Service |
| `AccountSecurityView` | `SessionCreated`, `SessionRevoked`, `LoginIdentityBound`, `AccountFrozen` | Risk & Compliance、Customer Service、用户安全中心 |
| `LoginIdentityView` | `LoginIdentityBound`, `LoginIdentityVerified`, `LoginIdentityUnbound` | 登录网关、客服、风控 |
| `SessionView` | `SessionCreated`, `SessionRefreshed`, `SessionRevoked`, `SessionDowngraded` | API 网关、Risk & Compliance |
| `MembershipBenefitView` | `MembershipEnrolled`, `MembershipLevelChanged`, `CorporateBenefitLinked` | Offer Management、Fare & Pricing、Promotion、客户端 |
| `CorporateAccountView` | `CorporateAccountCreated`, `CorporateMemberAdded`, `CorporateRoleChanged`, `CorporateAccountFrozen` | Journey Order、Payment、企业后台、Customer Service |
| `DelegationAuthorizationView` | `DelegationGranted`, `DelegationValidated`, `DelegationRevoked`, `DelegationExpired` | Journey Order、Customer Service、Risk & Compliance |
| `ConsentLedgerView` | `ConsentRecorded`, `ConsentWithdrawn`, `ConsentVersionMigrated` | Notification、Reporting、Legal、Customer Service |
| `FrequentTravelerBindingView` | `FrequentTravelerBound`, `FrequentTravelerUnbound`, Traveler Profile lifecycle events | Traveler Profile、Journey Order、客户端 |
| `WalletRefView` | `WalletRefLinked`, `WalletRefUnlinked`, wallet summary events | Payment、客户端、Customer Service |

读模型可以冗余账户昵称、联系方式掩码、会员等级、企业名称、默认 travelerRef 和授权快照版本，但不能成为 Traveler Profile 证件、Payment 余额或 Risk 判定的事实来源。

## 10. 外部系统和防腐层

- 身份认证 ACL：隔离短信验证码、邮箱验证、OAuth、企业 SSO、设备指纹 SDK、密码库和 MFA 供应商。外部错误码统一映射为 `verificationFailed`、`identityAlreadyBound`、`providerUnavailable`、`challengeRequired`。
- 企业 SSO ACL：把企业 IdP 的组织、角色和 assertion 映射为 CorporateAccount member facts，不让 IdP 私有 role 名称污染内部权限模型。
- 钱包/积分/券 ACL：Account 只保存 `WalletRef`、points account ref、coupon wallet ref 和展示摘要；余额、冻结、核销、退款回钱包由 Payment、Wallet 或 Promotion 拥有。
- 客服与后台 ACL：所有人工改联系方式、冻结、解冻、解绑、恢复账户的动作都必须转为 Account 命令，带 operator、caseRef、reason 和证据引用。
- 隐私与合规 ACL：同意条款版本、地区、语言和采集来源必须标准化，便于 Legal、Reporting 和数据治理系统消费。
- Legacy ACL：旧账号表、登录表、会员表、企业用户表、常旅客关系表迁移期通过防腐层转换为 Account 聚合事件，禁止新域直接依赖旧表字段。

## 11. 当前服务迁移影响

| Current Service | Migration Impact |
|---|---|
| `ts-user-service` | 收敛为 UserAccount、LoginIdentity、ContactPoint、Preference 的主要来源；拆出旅客证件到 Traveler Profile。 |
| `ts-auth-service` 或登录网关 | 迁移为 LoginIdentity 与 Session 命令入口；认证供应商细节进入 ACL。 |
| `ts-security-service` | 风控策略继续归 Risk & Compliance；Account 只消费明确冻结、挑战和会话降级结果。 |
| `ts-order-service` | 只保存 accountRef、购买时账户快照、delegation snapshot，不再复制会员、证件或联系方式全量。 |
| `ts-preserve-service` | 下单前调用 Account 授权视图校验账户状态和代理权限，不直接读用户表。 |
| `ts-payment-service` / `ts-inside-payment-service` | 使用 payerRef、CorporateAccount paymentRef、WalletRef；资金状态仍归 Payment。 |
| `ts-notification-service` | 消费 Preference 和 Consent read model 决定触达渠道和营销授权，不维护独立偏好真相。 |
| `ts-member-service` | 会员等级、成长值和权益输入迁移为 Membership；优惠计算留给 Fare & Pricing/Promotion。 |
| `ts-consign-service`, `ts-food-service`, `ts-assurance-service` | 只引用 accountRef 和授权快照；商品资格、费用和履约不进入 Account。 |
| `ts-admin-order-service` | 后台账户操作改为受控 Account 命令，所有人工修改有审计和事件。 |
| 企业/代理后台模块 | 迁移到 CorporateAccount、AccountMembership、Delegation；代理下单权限变为版本化授权快照。 |
| 常旅客/乘车人模块 | 实际旅客资料迁移到 Traveler Profile；Account 保留 FrequentTravelerBinding 和默认排序。 |
| 旧积分/券包模块 | Account 只保留 points/coupon reference；余额、核销和过期规则由 Promotion/Wallet 类上下文负责。 |

## 12. 验收标准

- [x] Account 聚合所有权明确：`UserAccount`、`LoginIdentity`、`Session`、`Membership`、`CorporateAccount`、`Delegation`、`Consent`、`Preference` 由 Account 负责。
- [x] 明确 Traveler Profile 负责实际旅客和证件，Account 只维护 FrequentTravelerBinding 和 accountRef。
- [x] 明确 Payment 负责资金、退款、渠道和钱包资金流水，Account 只维护 `WalletRef`、积分和券引用摘要。
- [x] 明确 Risk & Compliance 负责风控判定，Account 只执行冻结、挑战后的会话处理和安全摘要更新。
- [x] 明确 Journey Order 只保存 accountRef、购买时账户快照和 delegation snapshot，不读取 Account 内部状态机。
- [x] 覆盖个人、企业、代理人、团队和常旅客绑定场景，包含权限委托、企业成员和代理授权。
- [x] 覆盖账户冻结、解冻、注销冷却、最终关闭和隐私同意撤回的状态与事件。
- [x] 命令、领域事件、幂等键、读模型和 ACL 清晰，跨域协作只通过 Published Language。
- [x] 会员权益输入、Preference、Consent、WalletRef 的边界与下游消费方明确。
- [x] 当前服务迁移影响覆盖用户、登录、安全、订单、支付、通知、会员、企业代理和常旅客模块。
