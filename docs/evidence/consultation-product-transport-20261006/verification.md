# 咨询正式传输与签名认证链执行原件（2026-10-06）

本文仅为原件索引，不维护任务状态。唯一状态见主计划7.8.3的FIX-CON-PRODUCT-TRANSPORT-01及IMP-AGENT-CONSULTATION-01。

## 实际问题与修复

现有ProductIdentityRouteSecurityConfiguration已把Agent Run、工作台、检索执行、模型对话放入JWT安全链，但ProductIdentityTransportFilter的旧逐端点名单漏掉这些入口。旧实现的8路由×3传输场景24项全部失败：HTTP没有先拒绝、HTTPS未执行该层水位检查、未决水位未在Controller前拒绝。原件见[旧代码日志](transport-red-tests.log)、[退出](transport-red-tests-exit.json)、[原始XML](red-reports/TEST-cn.xinji.agentplatform.security.product.ProductConsultationTransportTest.xml)。首次命令PowerShell未引用带点的Maven参数造成生命周期错误，保留[命令错误](transport-red.log)，它不是产品反例。

修复只将传输匹配收敛为现有/v2/consultation/tasks根及其完整子树，不继续维护易漏的逐端点传输白名单。所有方法、未来子路由、编码/反斜线Task URI均按既有传输合同处理；不开放未知路由、不扩授权或Grant、不改其他Agent域的过滤器。认证和授权仍由原安全链、现时session/subject/key及领域服务负责。

## 专项验证原件与真实边界

[六套专项命令日志](security-green.log)、[实际退出](security-green-exit.json)、[汇总](focused-result.json)、focused-reports目录6份原始XML：59项、0失败、0错误、0跳过。

- ProductConsultationTransportTest：26项。8种实际路由的HTTP403、HTTPS现时水位、未决水位503，加编码/反斜线及未来Task路由不漏过滤；其他业务域仍由自身合同控制。
- ProductConsultationAgentSecurityTest：8项。实际Spring SecurityFilterChain、ProductIssuerConfiguration.jwtDecoder、ProductSigningKeys及其SQL、两独立H2数据库授权水位、ProductConsultationAccess和实际Controller/Runtime/加密Checkpoint/Dialogue SQL；不通过自定义JWT参数解析器注入主体。覆盖匿名/畸形/坏签名、错误issuer/audience/过期、HTTP/未决水位、未获Grant成员、会话撤销、写Grant撤销、主体停用/Key撤销，以及有效令牌同键启动/逐步完成/终态重放/读取仅一个Run和一个模型回合。
- 其余既有Issuer HTTP、Run绑定、Selection传输和Search观察套件25项保持通过。

新安全测试首次编译引用private notFound失败，保留[首次编译日志](security-first.log)。第二轮7项通过、1项失败来自测试Grant scope错写成scope而非领域Task.dataScope()的scope:1，保留[日志](security-second.log)及security-second-reports；只修测试输入，未松绑实际授权规则。

这些token使用测试密钥签发、会话/Grant是隔离工程夹具；工作台目标/检索/Worker/供应商仍有标明的边界替身。secure=true验证Servlet传输合同，不证明真实TLS握手。不得称正式OAuth发行、真实mTLS Worker、供应商、浏览器任务链、来源权益或专业Golden验收。

## 未筛选回归环境

full目录沿用已验证的隔离回归方式，新5386自有容器/卷/空schema；5381/5382仅复启已核对完整ID、run/role标签、loopback端口及单volume owner的执行自有实例，所有新授权夹具写到新的随机schema。原5237/5311/5312业务服务不变；5311仅检查旧设施表存在及读取原证据，不续期/签发原Grant。

第一次隔离夹具准备的供应商公共KB导入工具仍硬编码旧5385，安全检查主动拒绝。保留[首次夹具失败](full/first-full-round02-fixture-prepare.log)。核对新资源manifest后，只把复制到本轮证据目录的工具限定端口改为5386，不删除防护、不改原工具或产品数据；第二轮使用新的随机Grant/schema，不覆盖第一次数据或失败。

未筛选结果与资源停止记录在实际完成后附于下文，不将专项原件冒充全量。

## 全量暴露的新测试隔离缺陷及纠正

第一轮[未筛选原件](full/full-round02-result.json)/[实际退出](full/full-round02-exit.json)为1556项、0失败、160错误、40条件跳过、退出1（375份当次XML）。不能与旧1543绿色或59专项拼成通过。实际应用扫描发现新安全测试内嵌SecurityNode使用普通Configuration，被全应用上下文发现并误导入测试JWT配置，多个dm8-integration/test-synthetic等上下文触发正式Issuer禁止共享开发profile的既有保护；这属于本轮测试隔离缺陷，不归为外部硬阻塞。原保护与旧业务配置不改。

原测试源码保留在security-before-isolation-fix.java，首次纠正将内嵌配置改为TestConfiguration；最终同时加入仅本测试显式启用的隔离profile，过程及第二次纠正见下文。第一轮源码输入2461文件在命令前后SHA一致；原失败报告、日志及160错误均保留。随后定向检查安全/传输及原HTTP应用集成，并在新的5387空schema/fileRoot上重新执行全量，不复用已启动的RBC schema/fileRoot。5386首轮自有容器已停、卷与原件保留。最终结果以full-final原件为准。

改实现后的第一次verify-all退出1来自TEST_EVIDENCE_STALE：源码晚于主计划当时指定的旧回归；47文件/41契约基线与其他独立检查仍通过。保留after-implementation-verify.log，不用专项更改全量引用规避检查。

前端原回归21套/81项通过且build退出0，见frontend-test.log、frontend-build.log及frontend-exit.json；没有前端源码或产品体验改动，不冒浏览器实证。

隔离纠正的第一次专项仍退出1（53项、0失败、19错误）：只用TestConfiguration不足以阻止正式ProductConsultationApplication的裸ComponentScan。保留isolation-green.log、isolation-green-exit.json及isolation-first-reports，不把其文件名green当通过。最终新配置同时受仅本测试AnnotationConfigWebApplicationContext显式设置的consultation-agent-security-isolated profile约束；其TestConfiguration不会进入其他profile的应用扫描。此profile仅存在于src/test，不增加产品profile、入口、身份或绕过Issuer禁开发profile的规则。以isolation-final及最终未筛选原件验证实际结果。

最终隔离专项isolation-final.log实际执行4个套件、53项、0失败/0错误/0跳过、退出0。isolation-final-result.json仅按该命令Running名单和精确默认XML计数；目录中复制的其他旧XML不在该命令范围。第一次库存汇总错误包含15项旧错误，保留isolation-final-result.json.inventory-error-original，纠正结果不改原XML，也不借此隐藏未筛选首轮160错误。

## 最终未筛选结果

2026-10-06 12:05:35 +08:00，[最终未筛选汇总](full-final/full-round02-result.json)、[实际退出](full-final/full-round02-exit.json)、[全命令日志](full-final/full-round02-unfiltered.log)：全部后端Reactor模块成功，1577项、0失败、0错误、40条件跳过，379份当次XML；platform-api为1215/0/0/40。2461真实源码/脚本/数据输入在命令前后逐字节SHA核对一致，见full-final/source-inputs.json及full-round02-input-check.json。40条件跳过逐项留存，不通过或验收。旧11:25的1543项不代替当前版本证据，首轮1556/0/160/40不删除。

本轮只关闭已满足限定退出条件的传输子任务；咨询父任务及真实OAuth/TLS Worker/模型/浏览器链、后台执行、动态决策、业务Golden和实际生产部署各自仍需证据。新测试源和完整原件不证明这些已完成。状态仅在主计划维护。

## 收尾检查与资源

主计划写回实际最终原件后，codex-turn closeout退出0（closeout.log），47需求文件/41契约一致，全部独立治理检查通过。进度看板桌面/移动验证退出0（dashboard-verify.log）。resources-stopped.json记录5381/5382/5387执行自有容器已核对ID/角色/端口/单卷归属后停止；5386首轮已按full/resource-stopped.json停止。卷、schema及原失败/成功证据全部保留；原agent-platform-dm8:5237、codex-enf-i-business:5311与codex-enf-i-authority:5312仍运行，未改其Grant或执行V096/V097迁移。没有真实产品API/浏览器/Worker验证服务保持后台运行。
