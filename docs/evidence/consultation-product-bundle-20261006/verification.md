# 咨询正式发布包与现时部署准入核验原件索引（2026-10-06）

本文只索引原件、命令及证据范围；任务状态、依赖和下一步只在 `docs/08-总开发实现计划.md`。

## 当前源码正式发布包

- `preflight.log`：本轮改动前治理检查。
- `assets-tests.log` / `assets-exit.json`：12项资产暂存/路径及链接拒绝测试，独立咨询前端构建与实际暂存退出0。通用输出不能作为正式入口，拒绝原件为 `generic-bundle-rejected.json`。
- `frontend-tests.log` / `frontend-exits.json`：21套81项前端工程测试通过；通用构建兼容性退出0。jsdom样式能力警告不伪装成浏览器证据。
- `product-package-final.log` / `product-package-final-exit.json`：当前源码Maven正式产品构建成功。命令：`mvn -f backend/pom.xml -Dmaven.multiModuleProjectDirectory=WORKSPACE -pl apps/platform-api -am -Pbundle-consultation -DskipTests package`。此命令跳过测试，不计入回归。
- `frontend-current-source-inputs.json` / `frontend-current-source-check.json`：227项前端/构建输入前后SHA一致；通用构建后再次执行独立咨询构建与暂存，资源清单与实际JAR逐字节记录完全一致，未复用generic输出或陈旧咨询资源。此范围单独记录，不把后端回归输入清单称为覆盖全部前端。
- `actual-jar-verification.json`：直接读取实际Spring Boot JAR压缩条目，不解压执行。包内全部静态文件集合与资源清单严格一致，每文件字节数/SHA相等，无通用index或其他HTML；当前Controller、模型围栏及Runtime依赖在包内。JAR SHA与体积以该原件为准。该核验不等于部署、HTTPS、Worker、模型或业务验收。
- `mixed-profile-final.log` / `mixed-profile-final-exit.json`：实际Reactor命令同时选择 `bundle-frontend,bundle-consultation`，命中明确混包拒绝，退出1（预期负例）。不是把任意构建失败当门禁通过。
- 首次POM配置错误 `product-package.log`、`mixed-profile-red.log` 及其退出记录保留；随后validate的依赖解析先失败 `mixed-profile-guard.log` 明确 `guardObserved:false`，这些均不冒拒绝保护有效证据。

## 原部署现时准入（仅SELECT）

- `managed-readback-resources.json`：原受管两库完整容器ID、loopback5301/5302及本轮前停止状态。
- `ConsultationDeploymentReadback.java` / `readback-exit.json` / `managed-deployment-readback.json`：2026-10-06 12:45 +08:00实际SQL读取。59个Task现时有效0，相关Grant现时有效0，Source MODEL可用字段0；PATENTSVIEW准入及MODEL于2026-10-04 09:59:14过期；新Runtime/模型围栏3表未部署。
- 最近30条Worker记录到期只证明该查询范围，不声称全库Worker计数。独立水位READY/generation只是读取原记录，未验证完整历史摘要。不输出凭据，不写数据库，不签发或续权，不启动旧API/Worker。
- `managed-readback-restoration.json`：两原数据库已恢复本轮前停止状态，保留原卷，未停止本轮前运行的其他服务。
- 来源记录责任方为 `deployment-maintainer-20260928`，不据此推导其拥有新的法律/安全批准。现时用途和正式Identity/Task/Worker授权、隔离部署及迁移须按主计划解除条件补证。

## 新未筛选回归

- `intermediate-verify-all.log`：新POM输入已变化但仍指旧全量原件时，治理明确报 `TEST_EVIDENCE_STALE`；其他独立检查继续。此退出1不隐瞒、不算绿色，必须由真实新回归解决。
- `full-final/`：复制并适配既有全量夹具入口，generic库固定独立5388，独立卷/新schema/fileRoot；5381/5382复用精确核验的隔离实例但每轮创建随机新schema。保留原只读历史设施路径，禁止授权夹具与历史权威混用。
- `full-final/first-refused-*`：启动隔离实例后首次准备SQL code6001失败，未进入Maven全量。等待实例可用后以新随机schema再次准备；失败不覆盖。
- 新全量原件、实际总数及资源恢复以本目录最终记录为准，不以旧1577、打包成功或测试XML库存代替。

本轮不修改requirements，不补签专业结论，不恢复丢失的供应商信封，不代签业务验收。

## 最终工程结果原件

`full-final/full-round02-exit.json`与`full-final/full-round02-result.json`来自同次未筛选Maven：1577项 / 0失败 / 0错误 / 40条件跳过，379份实际XML，退出0。2466项后端/构建输入SHA前后一致；完整跳过/排除库存逐项保留。所有Reactor成功只证明工程范围，不能冒正式运行和三域业务任务完成。

## 派生看板补充复验

- `progress-verification.log`：首次1440px页面横溢失败，不覆盖。
- `dashboard-wrap/diagnosis-red.log`、`before-desktop.png`：实际viewport1440/document1488，summary-card/current-blocker长Token溢出；依赖链自身横向滚动不等于页面溢出，未盲目裁剪滚动区。
- `dashboard-wrap/progress-fixed.log`保留旧HTML超长Token检查失败；同次工具输出另记录登记新增行插入位置错误触发TASK_TABLE_INVALID_ROW，生成器未成功（该stderr在工具记录，不声称已保存在此log）。已恢复原任务行、按行首准确插入，未忽略严格解析失败或把旧HTML当新构建。
- `dashboard-wrap/progress-fixed-final.log`、`browser-final.log`、`final-1440.png`、`final-390.png`：改生成器summary-card换行，当前真实说明与额外超长来源SHA探针均不横溢、不截断；完整桌面/移动交互通过，图片已检查。探针仅存在测试DOM，不加入业务产品页面、正式数据、主计划或任务状态。
- `dashboard-wrap/verify-all-before-regression.log`：修改后独立治理退出0。
- `full-after-dashboard/`：为最新源码另建5389空schema/fileRoot，按既有隔离方式重新执行未筛选后端，不覆盖12:59原件。

状态仍只维护主计划，未把本证据索引当第二套执行台账。

最后当前源码复验：`full-after-dashboard/full-round02-exit.json`及`full-round02-result.json`，2026-10-06 13:11，1577/0/0/40，379份XML，2466输入SHA前后一致；没有覆盖之前成功或失败原件。

最终收口原件：`final-closeout.log`/`final-validation-exit.json`首轮写派生project-plan.json出现UNKNOWN文件写入错误，退出1，未覆盖；未推断具体锁定者或伪装成后端失败。`final-closeout-retry.log`/`final-progress-retry.log`/`final-validation-retry-exit.json`完整重跑治理及桌面/移动浏览器均退出0，主计划与派生输出一致。两轮测试各自隔离数据库恢复见`full-final/resource-restoration.json`、`full-after-dashboard/resource-restoration.json`，仅停止本轮启动的精确自有实例、保留卷/本地保护凭证；原三常驻库未动。原咨询两库恢复另见managed-readback-restoration.json。
