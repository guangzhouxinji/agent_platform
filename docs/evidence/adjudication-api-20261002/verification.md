# 裁决 01B API、持久化与恢复实测证据（2026-10-02）

关联唯一主计划任务：IMP-ADJ-V2-TASK-01B；限定设计：[D1 ADR](../../adr/adjudication-api-persistence-20261002.md)，决定来源见 docs/02 第71节。本文件记录实现/测试事实，不是第二份任务状态账本。

## 实现与边界

- 复用01A `AdjudicationCaseWork`、`CaseWorkContract`、`CaseWorkIntegrity`，没有重做领域算法。领域只新增 `casework/persistence/CaseWorkRepository.java` 端口。
- 应用新增 `backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/adjudication/casework/`：独立运行入口、真实JWT/session/当前逐案Grant、mTLS维护、独立来源用途与撤回水位、HTTP合同、严格命令、JDBC事务、完整性恢复、响应投影与失败关闭。
- 默认关闭 `adjudication.v2.enabled`；不注册咨询Task控制器，不新增CON/ENF阻塞依赖。现有共享授权实现只引用不修改；裁决表/来源用途隔离。
- 原文与摘要、不可变工作快照、审计、幂等回执、head CAS均落数据库；同案提交与恢复读取锁定head。重读校验全版本链、引用/权项/材料/报告摘要、独立审计与回执一致性，损坏503，不回退内存。
- 来源权限不从PROJECT_AUTHORED或客户端声明自动推断。独立来源数据库保留用途批准/撤回事件；历史数据读取仍按当前权限和来源核验。辅助报告、人工修订、审核状态独立于法定裁决。

## 页面可复用的合同

前缀 `/v2/adjudication/case-works`：

| 操作 | 合同 |
| --- | --- |
| POST 创建 | `input` + `originalText` + `Idempotency-Key`；逐案CREATE权限 |
| GET `/{id}`、`/versions`、`/versions/{version}`、`/originals`、`/audits`、`/reports/{revision}` | 当前/历史、原文、审计与确切报告；当前READ权限与独立来源用途 |
| POST `/{id}/context-revisions`、`/materials`、`/claims`、`/citations`、`/findings`、`/reports` | 每次携`expectedVersion`与`Idempotency-Key`；WRITE权限；上下文补正显式新原文版本，不猜法律时点 |
| POST `/{id}/reports/{revision}/revisions`、`/submit` | 再携`reportSha256`，修订另携章节/内容/原因；不允许旧稿或错误摘要 |
| POST `/{id}/reports/{revision}/reviews` | 当前HUMAN_REVIEW权限；复核人不在作者/贡献者集合；只允许RETURNED/REVIEWED_NOT_OFFICIAL |

所有命令不接受客户端actor、权限或报告Policy。400非法输入/缺原文；401无有效身份；403无精确权限/来源；404不可得对象；409版本/状态/幂等/旧稿冲突；503授权水位不可核或持久损坏。返回工作版本、输入版本、确切报告版/摘要与快照摘要。相同键相同命令返回原回执；不同键重复提交及相同键不同actor/body均拒绝。

## 单案真实HTTP链路

`CaseApiLifecycle`在真实嵌入HTTPS服务器上调用上述正式接口：创建→申请方/答辩方材料→权项v1→缺上下文报告及拒提交→人工上下文原文v2→权项v2→两项人工特征对应→工程引用原文→辅助报告v2→人工稿v3→确切SHA提交/同键重放→作者自审拒绝→独立人退回→旧版再提交拒绝→人工稿v4→再提交→独立人复核→重复复核拒绝。工作版本17、报告4版、审计17条，报告v3保留RETURNED，v4为REVIEWED_NOT_OFFICIAL。读取版本1、全历史、原文与审计均走当前权限。

测试身份/批准是明确的工程身份和输入用途；签名、TLS、mTLS、数据库session和逐案Grant是真的技术机制，不是mock成功权限。不是具名生产人员身份或专业/正式业务验收。

## DM8物理恢复

最终实测原件见本目录实际DM8执行子目录：两个独立DM8实例5301/5302，随机且只属于本次测试的ADJ schema。业务库用V086，独立来源库用`adjudication_source_authority.sql`；不是把独立撤回权威纳入业务恢复。

停止并重启API，重载工作版本17全JSON及head摘要完全相同；再通过HTTP写入45,000字符Unicode材料，重启重读正文完全一致，旧报告明确stale且不可复用；版本18时快照/回执/审计各18条。来源通过真实mTLS撤回后，再重启仍拒历史读取。最终`dm8-api-verification.json`的`passed=true`仅在两个隔离schema清理成功后写入。

第一轮API与恢复虽通过，但清理误用独立实例密码，整轮不算通过；修正测试清理凭据后再跑。遗留唯一ADJ12F1AA350C3645已明确从两个实例清理，原件见`dm8-api-first/owned-schema-cleanup-second.log`。更早profile/TLS信任库问题及修正前编译/测试失败日志均保留，不抹除失败历史。

## 复现与部署前置

- API入口是`cn.xinji.agentplatform.adjudication.casework.ProductAdjudicationApplication`；用正式TLS及非开发profile独立运行，不要求改CON/ENF现有入口。打包仍保留原平台默认启动入口。
- 独立部署必须明确配置`adjudication.v2.enabled=true`、`product.identity.enabled=true`、固定HTTPS issuer/audience、加密签名master-key/验证窗口、密码策略、主库和`product.identity.authority.*`独立库、TLS keystore/truststore/client-auth，并按现有身份维护合同准备可信主体/session/维护证书/签名密钥。缺权限或来源用途不自动开放。
- 主库身份前置V032/V033/V046/V057及裁决V086；独立库身份V001及裁决来源authority SQL。不得把SQL直接应用到CON/ENF现用schema。01B验证的是隔离部署与重启；不宣称已部署生产。
- 针对性测试：`mvn -f backend/pom.xml -pl apps/platform-api -am -Dtest=CaseWorkHttpsLifecycleTest,CaseWorkHttpsNegativesTest,CaseWorkRecoveryFailuresTest,CaseWorkConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- 实际双DM8：`scripts/verify-adjudication-api-dm8.ps1`接受主/独立实例DPAPI凭据文件及唯一RunId，创建随机schema、执行真实IT、清理并清除进程凭据环境变量；不打印密码、不覆盖证据目录。没有实际DM8配置会失败，不能以条件跳过冒通过。

## 数据与验收限制

输入严格使用01A最小包`data/adjudication/task-first-loop-input-v1/records.json`，明确PROJECT_AUTHORED、notRealCase；补正上下文、引用原文与大文本仍是工程输入。不是现实案件、真实专利、真实法规、正式法律依据或专业金标。特征分析为现有01A人工对应/确定性完整性处理及辅助报告，不冒模型自动法律判断。保留专业方法、数据权利、具名生产身份、生产保留/恢复和正式裁决岗位的独立门槛。

01C页面本轮不实施，不将API/工程机制验证写成ADJ-01～12全项验收；父任务仍持续进行。
## 需求合同到限定切片的对应

- ADJ-04：逐案创建/当前与历史/工作版本/原文审计；不扩法定受理与官方状态。
- ADJ-06：人工选择原文、摘要/法域/程序/时点绑定及漂移拒绝；不冒自动法律适用。
- ADJ-07：人工确认权项及必要要素的版本/原文关系；不是OCR、自动解析或真实专利专业验收。
- ADJ-09：既有人工特征对应、支持/反向/资料缺口的确定性处理及辅助报告；不输出侵权结论。
- ADJ-11：辅助报告、缺项阻断、人工修订新版本、旧稿失效及持久恢复。
- ADJ-12：确切版+SHA提交、独立人退回/复核、退回后新版本再提交及不可覆盖审计。

这些是任务型01B在已有01A上的有限实现证据，不把整个ADJ需求行的完整D/V/A改成通过。ADJ-05自动匹配、ADJ-08现实有效性分析及未覆盖部分不计实现。
## 最终执行原件与准确计数

| 执行 | 实测结果 | 原件 |
| --- | --- | --- |
| 最终全后端正式输入回归，2026-10-02 22:12:37～22:20:36 +08 | 319套、1,033项，0失败/0错误/39条件跳过；退出0 | [运行窗口](backend-product-final-run.json)、[逐suite摘要及SHA](backend-product-final-summary.json)、[同窗口canonical XML归档](backend-product-final-xml.zip)、[原始日志](backend-product-final.log) |
| 本次正式API专项 | 18项：生命周期1、HTTPS反例14、损坏/撤回恢复2、并发1；0失败/错误/跳过 | 上述XML归档内CaseWork四套，采用与真实DM8一致的API生命周期 |
| 三域模块回归 | 咨询106、执法100、裁决41；均0失败/错误/跳过 | 同窗口XML及摘要modules；执法数量变化来自并行轨道，不回退为旧92项 |
| 最终真实双DM8 HTTPS/重启恢复 | 1项，0失败/错误/跳过；API和清理都通过 | [最终JSON](dm8-api-final/dm8-api-verification.json)、[XML](dm8-api-final/TEST-CaseWorkDm8ApiIT.xml)、[日志](dm8-api-final/dm8-api-tests.log) |
| 可运行包 | `mvn -f backend/pom.xml -pl apps/platform-api -am -DskipTests package`退出0 | [打包日志](package-final.log)、[默认入口/独立入口验证](packaged-entry-evidence.json) |
| 实施后治理 | `node scripts/verify-all.mjs`退出0，47份需求基线和41项合同未漂移 | [最终实施校验](verify-implementation-final.log)、[最终DM8后校验](dm8-api-final/verify-after.log) |

39个条件跳过不计通过；本次裁决18项及真实DM8的1项没有跳过。全量结果限定于上述实际编译/执行窗口，不能替后续并行轨道的新变更背书。归档排除陈旧/带suffix报告，并保留每份原件SHA。前一轮正式配置全量1,032项也已通过，随后增加严格嵌套版本反例，因此最终采用1,033项，不重复合计两轮。

收尾不隐去失败：`backend-final-regression.log`是未注入正式测试配置的首次全量，含裁决授权正例重复已有Grant的1个失败及其他域缺实际环境的20个错误，不算绿色；修正测试输入、不降低当前Grant保护，并复用既有正式输入脚本才得到后两轮通过。最终DM8执行前verify曾因并行测试的TESTS_FAILING退出1，原件保留；最终DM8后与实施后verify均实际退出0，不将先前失败改写为成功。

严格JSON检查使用裁决专用mapper副本，不改共享mapper；权项revision小数、字符串、null拒400，expectedVersion非法/陈旧、缺原文、引用漂移、已采用引用后的合法上下文替换409、旧产物/错SHA、重复提交/非法官方状态、伪actor/错幂等体/无键、无Grant/跨案/无或错JWT、无mTLS维护以及外issuer签发均有真实HTTPS反例。快照/审计/回执/父链损坏503；源在业务事务中撤回导致业务行全部回滚；并发CAS一成功一冲突，同键并发只产生一版。

## 兼容性与独立性原件

[兼容性摘要及本次写集SHA](compatibility-evidence.json)以[修改前边界](before-boundaries.json)为基准。01A三个核心文件摘要完全相同。本任务没有修改CON/ENF业务或共享security/product文件，也没有为其添加ADJ前置条件。期间独立轨道修改了执法文稿的五个源/测试文件，以及咨询来源的多个`security/product`文件（包括证据/原文/字段/引用/恢复适配，逐个路径与SHA见兼容性摘要）；另有咨询对照D与两个执法任务行的独立更新，全部保留，没有覆盖/回退。故不声称“整个共享目录在本轮绝对未变”；边界原件逐文件区分并行变化与本任务新增文件。

打包默认Start-Class仍为`AgentPlatformApplication`。需要独立启动裁决时，可使用已实证存在的PropertiesLauncher选择本次独立入口（用部署配置/密钥，不用测试固定凭据）：

```powershell
java -Dloader.main=cn.xinji.agentplatform.adjudication.casework.ProductAdjudicationApplication -cp backend/apps/platform-api/target/agent-platform-api-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher --spring.config.additional-location=file:D:/deploy/adjudication/application.properties
```

这里只核包中入口和实际API启动/恢复，不声称部署配置已审批或生产服务已常驻运行。不会为读取旧数据而绕过当前权限/来源。

## 记录后的并行变化与补复核

第一次登记后`verify-record-final.log`因其他轨道新源码晚于测试而TEST_EVIDENCE_STALE退出1，没有改checker、删除报告或回退外域代码。随后按当前源码重新执行三域模块测试与本次四套API专项：两条Maven命令均退出0，CON106/ENF100/01A41和API18均0失败/错误/跳过；详[补复核运行窗口](post-record-run.json)、[精确范围摘要](post-record-summary.json)、[XML原件](post-record-xml.zip)、[领域日志](domains-post-record.log)、[API日志](api-post-record.log)。只归档这两条命令实际执行的三域模块和四套API，不将同时运行的其他任务测试混入。随后[verify-post-record-recheck.log](verify-post-record-recheck.log)实际退出0。

全量1,033项仍只描述其已记录编译窗口；后续其他轨道新来源组件的业务验证由其自己的证据负责，不用本次API机械测试冒替。裁决本次源代码在最终全量、DM8、打包与补复核间没有变化，兼容性原件保留当前并行变化。父任务仍进行中，01C未开始。
最后记录稿校验`verify-final-recorded.log`仍遇到独立咨询新增测试源晚于报告的TEST_EVIDENCE_STALE；不是本次裁决源码变更，没有回退外域状态。该轨道测试随后实际完成，再执行[收尾校验](verify-closeout.log)退出0。以上所有失败日志保留；全量回归、当前265项补复核、真实DM8与结构治理各自记录，不将结构校验当成法律/业务正确性证明。
