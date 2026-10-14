# 裁决首个正式可运行 V2 单案闭环：01D 实施与验证

日期：2026-10-03（Asia/Shanghai）。权威任务状态仅在 `docs/08-总开发实现计划.md`；本文为不可替代代码/测试的实施证据，不另建状态台账。

## 1. 实际产品修正

依据[最终产品D2](../../adr/adjudication-final-product-20261003.md)和docs/02第81节，沿用01A/01B/01C已有领域、HTTP、数据库、TLS/JWT/PKCE及工作台，不重新搭建内核或另建Demo接口。

- 正式工作台删除工程包/Demo性质提示与新录入选项；历史来源枚举保持存储兼容，不以来源种类给产品贴模拟标签。案件和原文不会通过过滤或改写来掩盖性质。正式界面保留辅助报告与机关签发职责分工。
- 第一版方法修正：每个必要特征有确切支持或反向记录，均可形成可复核分析；反向不自动变成不侵权结论。冲突、不确定、无确切对应、缺原文/方别/上下文/法条仍阻断。原“三参数Policy”调用保持兼容，不重做01A。
- 正式规则包 `backend/apps/platform-api/src/main/resources/adjudication/report-policy-v2.json`：方法、责任角色、法源、10个不可删mandatory规则、章节、字数预算、反向处理配置。配置字节SHA+传输预算绑定报告版本。预算是工程资源限制，不是法律阈值。
- 规则版本变化：历史报告/快照/审计不回写；投影显示policyStale，不再冒当前有效版本。旧稿修订、提交、复核受后端规则约束，409 REPORT_POLICY_CONFLICT；必须重新生成当前规则新稿。
- 新PUBLIC批准必须具备提供方、HTTPS原件URL、采集时间、出版版本、权利依据、目的、原件字节SHA/长度、正文SHA和manifestSHA。原文SHA与批准证据必须对应。继续真实mTLS独立维护、逐案scope、独立水位、到期和撤回；采集器不能自动批准来源。
- 全部变更限裁决目录、裁决测试、规则资源和专项脚本；无新共享迁移/共享身份绕过，无CON/ENF业务或任务状态修改。

## 2. 真实公开数据与正式数据治理

实际捕获国家知识产权局2024-12-30发布的第81号《专利纠纷行政裁决和调解办法》官方页面及官方PDF附件：33页完整原件、逐页提取正文、原件/响应/正文SHA和长度、URL/最终URL/HTTP响应/UTC采集时间。保存于 `data/adjudication/public-legal-sources-v1/`，实际第17页第38/39条作为本案法条引用输入。

原件manifest SHA：`2f2c40727172401007bcdade238fc3e951574e9615e53a36f76dad4ec9ec8656`。详manifest/citation记录、[采集日志](capture.log)、[原件验证](source-verify.log)、[9项完整性正反例](source-negatives.log)。保留2026-05-25指南征求意见通知及其性质，**不当成生效法律**；不据此宣称所有法源最新性或全部个案适用性已获专业确认。

采集和验证可重复运行：bundled Python执行 `scripts/capture-adjudication-public-law.py capture <不存在的新版本目录>` 或 `verify data/adjudication/public-legal-sources-v1`。拒绝覆盖原版本；测试9项含原件/正文/manifest/引用漂移、缺文件、越界路径。采集不代替来源用途批准，也不自动创造真实案件事实。

本次浏览器案件/双方材料/权项是用户允许继续使用的测试替代输入，位于测试资源 `backend/apps/platform-api/src/test/resources/adjudication/browser-case-input.json`，用途仅完整操作验证；不是现实案件/真实专利/正式事实结论。真实法条与替代案件在证据中分别标识，不污染产品界面、不成为默认业务资料。原法院100条内部开发用途批准未扩大。

## 3. 实际执行与原件

| 验证 | 实际结果 | 原件 |
|---|---|---|
| 本轮前置统一校验 | 退出0 | [verify-before](verify-before.log) |
| 裁决定向代码测试 | 领域42+API21，共63项，0失败/错误/跳过 | [second-targeted](second-targeted.log) |
| 正式前端回归 | 19文件65项通过 | [frontend-tests](frontend-tests.log) |
| 正式生产前端构建 | 实际通过，无测试专用产品页面 | [构建日志](../adjudication-workspace-20261002/formal-product-first/frontend-build.log) |
| 真Chromium+双DM8+API+同案重启 | 7套23项，0失败/错误/跳过；136次HTTP响应、无页面错误 | [browser实际原件](../adjudication-workspace-20261002/formal-product-first/verification.json)、[测试日志](../adjudication-workspace-20261002/formal-product-first/tests.log) |
| 真实公开原件完整性 | 9项通过 | [source-negatives](source-negatives.log) |
| 独立只读退出证据复核 | 23项测试XML、HTTP拒绝、恢复摘要、截图实证核对通过；反例5项通过 | [复核输出](independent-exit-audit-second.json)、[复核反例](exit-audit-tests.log) |
| 全后端及两域回归 | 329套1082项，0失败/错误，39条件跳过；CON106/ENF100/ADJ42无跳过 | [全量日志](full-backend-regression-second.log)、[确切窗口XML与摘要](full-regression-summary.json) |
| 退出状态变更前统一校验 | 退出0；47基线/41需求账本一致 | [verify-exit-before](verify-exit-before.log) |
| 状态/证据记录后统一校验 | 退出0；首轮project-plan.json瞬时文件锁失败保留，完整重跑通过 | [verify-final-after-retry](verify-final-after-retry.log) |

全量在同源隔离snapshot `formal-second-build.json`运行，防止并行共享target编译相互覆盖；测试运行时JVM user.dir指回原工作区供既有真实采集输入定位。仅将源码核对一致且本轮实际执行的329份XML同步统一校验读取目录，保留真实报告时间和SHA、旧报告备份；未修改checker、未touch假报告。并行只变更ENF公开输入对账IT测试（不在默认全量执行集），没有据旧snapshot声称该新IT通过，未复制其旧报告；详摘要sourceDrift。

浏览器全案22版、5报告，22份快照/审计/幂等回执；在来源撤回前真实停止/启动Spring服务，新PKCE登录继续读**同一case-A**的已复核历史与当前失效报告，原件/快照/审计/回执/案件头一致；公开法条provenance持久存在。随后独立维护撤回来源，历史读取403，页面清空正文。随机裁决schema实际删除，不触碰CON/ENF业务schema。

失败过程原件保留：first-targeted（旧文案断言和隔离根定位）；full-backend-regression（隔离目录找不到CON真实数据，非功能断言回退）；verify-design/second-before/browser前后/source-tests-after（TEST_EVIDENCE_STALE）；证据归档第一尝试Windows长路径错误，改短编号文件名，不删实际测试原件。退出checker第一次误把案件头摘要当成双摘要行，修正真实格式后通过，未改业务证据。

## 4. 七类异常证据矩阵

下列均为实际源码/已执行测试，不以计划或前端隐藏冒通过。23项原件与方法名见独立复核JSON；领域42项见全量摘要和归档XML。

| 类别 | 反例/防线 | 实际测试/界面证据 |
|---|---|---|
| 输入异常 | 非法/非整权项版、缺原文、权项内容与上下文引用不一致、必要特征无对应、冲突/不确定/缺双方资料不可提交 | CaseWorkHttpsNegativesTest：nestedClaimRevisionCannotBeRoundedOrCoerced/missingOriginalCannotEnterPersistentClaim/caseContextCannotSilentlyInvalidateSelectedCitation/validOriginalCannotReplaceContextAfterCitationSelection；领域缺证测试；browser REPORT_INCOMPLETE，特征与缺口可读 |
| 来源异常 | 引用原文漂移、PUBLIC缺治理元数据/摘要不符、撤回后不可读历史；采集缺件/篡改/越界拒绝 | citationOriginalDriftIsRejected；publicOriginalMustBindCaptureRightsPurposeAndManifestBeforeIndependentApproval；9项采集完整性；browser SOURCE_REVOKED 403及source-withdrawal.png |
| 版本冲突 | 非法/过期CAS、旧输入稿/非当前稿/错误正文SHA、同键异体、规则配置变更旧稿不可复用 | invalidAndStaleVersionsAreRejected/oldReportAndWrongHashCannotBeReused/forgedActorAndWrongIdempotencyBodyAreRejected；CaseWorkConcurrencyTest真实双HTTP写入仅一胜者、同键只一版；规则重启测试；browser WORK_VERSION_CONFLICT和REPORT_INPUT_OR_HEAD_STALE |
| 非法状态转换 | 草稿复核、重复提交、作者自审、未完整报告提交、退回后不经正确版本重提 | duplicateSubmitAndIllegalReviewStateAreRejected；browser INDEPENDENT_REVIEW_REQUIRED/REPORT_NOT_DRAFT/REPORT_NOT_SUBMITTED；独立退回→人工修订→确切重提→复核完整走通 |
| 授权异常 | 无grant/跨案、伪actor/伪头/错issuer、普通成员无mTLS不可维护来源；现时撤回独立于历史证据 | absentGrantAndCrossCaseInputsAreRejected/signatureIsNotReplacedByUntrustedHeaders/grantMaintenanceRejectsForeignIssuerAndAcceptsExactScope/memberCannotMaintainSourceRightsWithoutTlsCertificate；browser真实PKCE作者与另一复核人，403不泄原文 |
| 恢复异常 | 快照正文损坏、重签摘要但改actor/回执、前链损坏503拒绝；事务中来源撤回全业务回滚，重启仍拒绝 | snapshotAuditReceiptAndChainCorruptionFailClosedWithoutRepairingHistory/authorityRevocationInsideCommandRollsBackEveryBusinessRowAndPersistsAcrossRestart；真实DM8 API重启与45000字Unicode CLOB；browser同案重启 |
| 数据一致性 | 每版快照/审计/回执同事务，双写CAS、幂等无多版，独立source库不能由业务备份复活；规则变更不改历史 | CaseWorkConcurrencyTest；CaseWorkRecoveryFailuresTest；CaseWorkDm8ApiIT；规则重启测试；verification.json的22/22/22与persistedDigests、sourceProvenance持久和schema清理 |

恢复反例中直接修改DB只发生在隔离测试用来制造损坏，正式产品没有修复历史/伪造授权接口。

### 并行边界核对

[本轮前后SHA](after-boundaries.json)不声称全工作区静止：01A的AdjudicationCaseWork/CaseWorkContract按D2修正专业方法和Policy，其余内核非重做。CON/ENF领域目录SHA未变；共享security目录下ProductCapturedPatentFields/ProductPatentsViewSourceDocuments由并行咨询工作在本轮开始后更新，已纳入formal-second snapshot和本轮全量回归，未回退它们，不把它们误写成裁决共享身份变更。其余已记录共享身份/安全文件未变。[计划编辑边界](plan-edit-boundary.json)证明本次编辑前后507条CON/ENF任务行逐字相同，包含状态和依赖；只改01D与父任务。

## 5. 独立退出复核结论与验收分界

按独立于实施操作的只读工序：先反查D2与后端控制（规则冲突、mTLS批准、逐案grant、确切原文、幂等/CAS、独立复核、失权清正文），再重新读取实际XML/HTTP/DB恢复摘要及截图，不复用测试代码的“通过”口号。`scripts/audit-adjudication-product-exit.py`可复核；`scripts/verify-adjudication-exit-audit.test.py`核缺XML、无异常响应、部分恢复和错误审计行数不能假通过。另人工视觉检查已复核历史、法条定位、版本冲突、来源撤回页面。

这是**独立工序的工程退出复核，不是第二位具名专业审查人或外部业务验收**。规则专业适用、权项稳定性/等同、真实案件事实、正式裁决签发仍由授权专业人员/机关确认。配置的负责角色是技术规则维护角色，不冒业务专家确认。

结论：代码C、本机真实服务/数据库/浏览器I及异常V均实证通过，裁决智能体首个正式可运行 V2 单案闭环完成；专业业务A未通过，不宣布ADJ-01～12全部完成，不改变CON/ENF状态或引入裁决前置依赖。产品仍是正式辅助工作台，不因本次替代案件验证而降级为Demo。
