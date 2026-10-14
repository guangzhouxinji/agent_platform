# 独立真实安全边界实测说明

任务上下文：VER-CON-USER-START-MTLS-01 / V10.18。本文仅为本次专项原件与范围说明，不建立任务/门禁状态台账，不修改主计划或ADR。

## 实际阶段与许可核对

已交付本轮限定的测试源码与机械专项实证；父任务的真实prepare成功、候选交接、正常模型执行、完整启动与结果读取整链仍未证明。

已读取AGENTS、主计划当前任务、总体架构/治理规范及actual-security-receipt-18。追加核对comparison工程输入后确认：data/consultation/dev-comparison-input-v1/manifest.json确有项目原创claimCandidates的QUERY/SAVE/DISPLAY/COMPARE_INPUT许可，窗口为2026-09-25T00:00:00+08:00至2027-03-24T00:00:00+08:00。载荷SHA及三个来源manifest SHA均吻合，不能称该工程输入没有许可或已经过期。详existing-comparison-license-audit.json。

确切差额：现有记录的环境/组织/主体为isolated-development-only、dev-con-isolated及dev-con-a/dev-con-b；未找到将该包claimCandidates/COMPARE_INPUT映射为当前产品Source的CLAIMS DISPLAY与COMPARISON，以及绑定该包SHA/版本/受控原件定位的适用native Source准入Decision。docs/02第32节要求逐版本激活、不得令旧回执自动扩权；最终prepare按docs/adr/consultation-prepare-feature-20261005.md及ProductComparisonDocuments执行现时逐字段/用途/Task源范围，不从工程身份许可推定Source批准。本轮没有生成新的CLAIMS声明、决定、许可或转换后的CLAIMS包。

其他现有资料：source-engineering-01/owned-input/license.json只准QUERY/SAVE/DISPLAY，明示排除COMPARISON/MODEL，并截至2026-10-07T10:07:02Z；本次回执观察时间2026-10-07T16:24:22.822039700Z已在其后。agent-window03的Source决定截至2026-10-06T23:18:28.2039339+08:00，CLAIMS不含COMPARISON。两者不复制或复活。现有PatentsView采集manifest不等于当前Task的产品准入；ADJ工程材料限定机械域测试且禁止PRODUCTION_SOURCE_ADMISSION，不能移作CON来源批准。

## 本轮新增实证，不把继承断言计成新增场景

| 新边界 | 实际观察 |
|---|---|
| 两个真实Task、两份确认Goal/Input均有当前精确READ/WRITE/COMPARE许可；自己的Goal配另一Task的Input | prepare 409 / PREPARATION_VERSION_CHANGED |
| 另一Task的Goal与Input一起塞入原Task | prepare 409 / PREPARATION_VERSION_CHANGED |
| 原Goal/Input配另一Task的真实目标message | prepare 404 / COMPARISON_REQUEST_REJECTED，不泄目标 |
| 已确认Task version2使用version1 | prepare 409 / PREPARATION_VERSION_CHANGED |
| 自有TITLE真实候选→核验→具名工程用途→产品启用→scope后消费 | 当前Source guard和既有capture projection reader读到恰好1条、仅TITLE；不冒HTTP搜索结果或Worker交接 |
| 经真实HTTPS/mTLS产品维护端撤回上述来源 | source epoch 1→2，QUERY/DISPLAY/COMPARISON当前用途均拒绝；核验历史与原件SHA不变 |
| 两个真实Issuer会话中注销一个 | 原token的原键Goal写入、Task/Comparison读取均401；另一会话Task读取200，原键Goal重放回原结果，不增加Goal版本 |

所有拒绝后con5_preparation、con5_comparison_input、con3_worker_delegation均为0。没有插入假ComparisonInput、委托、attempt、COMMITTED、模型批准或许可；modelRequests=0。TITLE输入是本次鲜生成的自有工程metadata，许可只延续已实证的自有TITLE范围，不冒真实专利。

## 源码写入范围与运行方式

仅写入以下允许源码：
- ProductComparisonRealSecurityChainTest.java：接入新边界与回执。
- ProductComparisonRealSecuritySource.java：完善鲜生成TITLE capture的既有DETAIL原件绑定，验证真实current-source guard/投影消费与撤回，保留本轮七份无凭据原件。
- ProductComparisonRealSecurityBoundaries.java：新增真实后继Task/精确绑定及独立会话拒绝断言。
- ProductComparisonRealSecurityEnvironment.java：只将既有V058纳入独立临时H2环境建表清单，支持实际普通用户Task创建回执。没有修改migration文件或运行现行/部署迁移。

Configuration与其他测试未改。证据/编译classes仅写本独立目录。使用JDK21独立编译当前774个backend主源码以及上述实际安全测试和只读TLS helper；最终javac-04为完整源码编译退出0，javac-07为允许测试最终重编译退出0。共享target只作为已有classpath读取，不写入。production-source-drift-after.json显示774个生产源码无摘要漂移。

BoundaryJupiterRunner只选择ProductComparisonRealSecurityChainTest，不扫描/运行其他测试。junit-04：1 successful / 0 failed / 0 skipped，退出0。随机HTTPS端口、工程自有CA/mTLS维护客户端、真实Issuer login/PKCE/JWT、独立临时H2 primary/authority；退出已关闭进程内服务/连接池并由既有helper清理临时库及TLS材料。无5492连接、现行DB访问、模型调用、全量套件或共享target输出。

先前失败原件保留：javac-01/02/03为参数 quoting、反斜杠classpath或J参数位置错误；junit-01为错误重复initial-task 409；junit-02为临时H2缺V058回执表503；junit-03为鲜生成TITLE manifest缺既有DETAIL类型绑定。仅修正测试输入/临时环境，不放宽生产拒绝或调整其他测试。

## 原件与复核限制

- actual-security-receipt-04.json、junit-04.log/exit：本轮成功专项。
- actual-security-receipt-04.json-source-originals/：仅七份本轮鲜生成自有Source/rights/capability/decision/retention原件；不含库导出、token、密码或私钥。SHA见owned-title-originals-sha256.json。
- compile-inputs-before.json、final-test-sources.json、production-source-drift-after.json：源码输入/冻结SHA。
- existing-comparison-license-audit.json：既有工程输入许可及摘要只读核对，明确已有许可与差额，不签新批准。
- baseline-before.log及baseline-after.log：仅read-only verify-governance，均0，47基线文件/41项一致。依用户禁止全量/共享写入，本轮不运行verify-all，不升级需求/门禁/验收状态。

full02的2102项是历史证据，不作变化后回归。最终统一RBC09由主会话执行。源码交付冻结，不再续改。H2机械专项不等于DM8正式部署、业务主体授权、专业效果、浏览器验收或整个启动整链已验证。
