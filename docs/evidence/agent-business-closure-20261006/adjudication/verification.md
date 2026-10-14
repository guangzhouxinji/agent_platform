# 裁决结果交接切片：工程验证与本域只读准入原件

日期：2026-10-06（Asia/Shanghai）。任务：IMP-ADJ-AGENT-WORKSPACE-HANDOFF-01、VER-AGENT-THREE-DOMAIN-ADMISSION-01 的裁决范围。本文是具体执行证据，不是并行任务/准入状态台账；主计划不由本子会话修改。

## 本次实际代码与边界

- 新增 WorkArea.tsx、reportHandoff.ts 及两份专属测试；经用户明确扩大范围后修改 AdjudicationWorkspace.tsx、workspace.test.tsx。
- 原正式入口内嵌 panel 已被 WorkArea 替换，不是孤立组件，没有新路由。消费共享 panel 的实际 onView；请求开始 null、手动换 Run、身份/Case/工作版本/输入版本/历史/disabled 变化立即清空绑定。领域请求在交给 panel 前拒绝跨 Case/错误 Run 响应；对账失权清空父案件和 panel。
- 同 Case/Run/Checkpoint version 重新读取当前案件与服务端版本头。case-report 引用必须精确；Outcome 如果存在必须指向同一 observation（允许已有 #critical-gaps）。匹配唯一当前、非 stale、非 policyStale、同 inputVersion 的报告；生成审计与服务端工作版本一并核实。
- observation.sha256 为 ObjectMapper 序列化完整 Report 的 SHA，不是 report.contentSha256。本切片以保留服务端字段/列表顺序的 JSON 字节重新计算 object SHA；不排序、不用 content SHA 兜底。编码顺序/转义/提交复核状态变化造成不一致时失败关闭，不宣称能跨序列化变体保证匹配。真实新版 Runtime Run 尚未部署，端到端该编码稳定性仍需实测。
- 点击定位再次读取 Run/案件/版本并校验。经原 navigate 未保存保护后将 fresh CaseView 与 versions 更新进正式工作区，切到原“报告”tab，并聚焦已有报告编辑/独立复核区。没有创建报告副本、采信、提交或复核命令；保留现有 Reports/ReportRevisionEditor 和后端权限裁决。
- 原 onAdvanced 的案件刷新需要由此 fresh-onOpenReport 更新满足，执行结果不复用旧案件快照。读取/执行后的交接按钮指向经核验的服务端当前报告，点击后更新正式摘要与报告区。
- modelMaterialIds 始终显式 []，没有模型自动调用或自动批准。读取登记争点/引用/人工对应及发现官方状态缺口不构成自主专业分析；页面明确该限制。
- 未修改共享 AgentTaskPanel、runtime、三个 Controller、共享配置、主计划或任何 backend；未运行 Maven或全量后端构建；初轮未运行统一 frontend build，后按负责人明确指令实际运行 npm run build（见下方最终修正）；未创建工作树。

## 实际验证

1. focused-tests.log：5 文件、66 项通过（其中新增两个专属测试共43项，原 workspace 新增2项真实入口集成；其余为既有裁决 API/报告编辑/阅读回归）。替身数据只证明工程合同，不是正式案件/专业验收。
2. focused-typecheck.log：退出0。仅显式列出本次源与测试，通过传递依赖检查相关裁决/共享渲染类型，没有全量构建。测试显式 import '@testing-library/jest-dom/vitest'，没有共享 vitest 配置变更；getByRole 使用精确 name 字符串。
3. 原失败 focused-tests-initial-failure.log、focused-typecheck-initial-failure.log、entry-integration-initial-failure.log 保留。错误分别为重挂 panel 后测试误期待空 Run 的读取按钮启用、显式测试声明/夹具类型不足、原报告正文有多个展示点及错误的编辑按钮名称；均已局部修正。
4. verify-before.log、verify-after.log：均退出1。47 baseline files/41 ledger rows一致；唯一失败阶段为 TEST_EVIDENCE_STALE:project。不得把 focused tests 或结构检查当全仓绿灯、专业正确性或业务验收。

命令：

```powershell
# cwd: D:\daima\xinji\agent_platform\frontend
npm test -- --run src/features/adjudication/reportHandoff.test.ts src/features/adjudication/WorkArea.test.tsx src/features/adjudication/workspace.test.tsx src/features/adjudication/report-editor.test.tsx src/features/adjudication/api.test.ts
./node_modules/.bin/tsc --ignoreConfig --noEmit --jsx react-jsx --module esnext --moduleResolution bundler --target ES2022 --lib 'ES2022,DOM' --skipLibCheck --types 'vitest/globals,node,vite/client' src/features/adjudication/AdjudicationWorkspace.tsx src/features/adjudication/WorkArea.tsx src/features/adjudication/reportHandoff.ts src/features/adjudication/WorkArea.test.tsx src/features/adjudication/reportHandoff.test.ts src/features/adjudication/workspace.test.tsx
# cwd: D:\daima\xinji\agent_platform
node scripts/verify-all.mjs
```

## 本域 native 实查：不是从咨询过期结论外推

原件与受管身份：
- primary：本机 consultation-product/container-deployment.json 的 databases[0]，名称 agent-platform-con-product-primary，完整ID 8d9d4abcb1646396716565fd35477a7a921f370cb9a4812efb96c28d28549d52，127.0.0.1:5301；以裁决已有 ADJLLM20261003 专属凭据读取，不使用 CONPROD 授权推断裁决。
- authority：docs/evidence/adjudication-llm-materials-20261004/subject-initialization-01/deployment-initialization.json，名称 agent-platform-adj-authority-20261004，完整ID 4ac68d0d910f37df36eba59ef35c8e0d74512bb7752ad74fb8883cbfc57ed271，127.0.0.1:5370，ADJAUTH20261004；以既有 ADJAUTH_SVC 凭据读取。
- 两次只读核验均先核完整 ID/名称/loopback 端口和状态。两库本轮前 exited，仅临时启动用于 SELECT，finally 恢复 exited；详见 first-native-service-state.json、native-service-state.json。未启动 API/模型服务/Worker，未运行 DDL/DML/迁移/授权签发或续期。Java JDBC setReadOnly 仅辅助；SQL源全部固定 SELECT，无 FOR UPDATE，无业务入口或第三方外呼。凭据仅从既有受保护文件读到内存并通过 stdin 交给 JDBC，没有复制/导出秘密。

实查原件 native-primary.log、native-authority.log：
- 本域 Task 原生父对象是唯一案件 adjudication-materials-case-01，organization project-adjudication，dataScope adjudication-materials-case-01-materials，head workVersion=5。尚无 Runtime 表，不能声称存在新的持久 Run/Goal 或已执行 Agent。
- Grant 与 current_epoch + active HUMAN 的现时库存连接匹配 CREATE/READ/WRITE/MODEL_EXECUTE 四项，均截止 2026-11-02 00:00:00 +08（2026-11-01T16:00:00Z），不是过期授权。历史另有 REVOKED 的 MODEL_EXECUTE 不冒当前项。
- live Session 数0：需要已有正式用户正常登录；不再要求重选主体或自动签发身份。该库存检查不等于一个已验证JWT的完整当前授权；正式请求仍必须核会话、精确scope、来源和独立水位/摘要。
- 独立授权水位 generation=6、READY，来源水位 generation=5、READY；只读未校正水位或代运行完整历史摘要核验。
- Source payload SHA 已校验：case-context/v1 ACTIVE，READ/SAVE/ANALYZE；material-1/v1 ACTIVE，READ/SAVE/ANALYZE/MODEL；material-2/v1 REVOKED；material-1/v2 ACTIVE，READ/SAVE/ANALYZE（无 MODEL）。截止均为 2026-11-01T16:00:00Z。不得因ID/文本SHA相同继承v1 MODEL到v2，也不得把时间未过当撤回解除。案件完整输入/源集合仍由正式CaseSourceAuthority逐项校验，不仅凭单条READ库存承诺可执行整个案件。
- 模型账本累计2 calls/1374 inputTokens/2298 outputTokens/21132 costMicros，frozen=0，保留2条 COMMITTED run，期限同上。没有重置预算/恢复旁表或新增模型调用。
- ID2_WORKLOAD 对本域 issuer 的行数0。请求内 execute 不依赖无人值守 Worker；不能据表存在声称有正式Worker。后台主体、调度、重启接续和授权准入是独立未交付范围。
- 主 schema 无 AGENT_RUN_HEAD/AGENT_RUN_REVISION；权威库同样没有，V096 应只在主 schema应用，不能用权威库代存Run。

## 已有批准与 A/B/C 分流（交主会话落实，不新建状态表）

批准依据：
- docs/adr/agent-runtime-product-binding-20261005.md §§1/2/6：既有三域能力接线R/D；V096/U096、正式32-byte Base64 checkpoint key、默认关闭/显式启用、受控迁移入口，保留期限沿当前父输入/来源最早截止。
- 同ADR“共享工作台限定增量技术D”及“三域任务结果交付限定技术D”：裁决无模型链与当前报告人工定位；正式请求内execute沿现时JWT和领域围栏，不是后台Worker。
- docs/08主任务行 IMP-ADJ-AGENT-WORKSPACE-HANDOFF-01/VER-AGENT-THREE-DOMAIN-ADMISSION-01 与本轮负责人将正式部署交主会话的明确分工。主计划不由本子会话更新。
- docs/02 §84与 subject-initialization-01 正常维护原件：03A限定案件正式主体选择/维护已落实，不重问同一主体批准。
- user-approval-v1.json、supplier-approval-v1.json：2026-10-03负责人限定单案自有去敏材料整理、DeepSeek、预算/期限及特定供应商条件处理；不是专业侵权/有效性/等同结论批准。native-config-audit.json 验证 policy 与两个批准原件摘要一致。

A 工程可修：
- 原 scripts/start-adjudication-product-runtime.ps1 无 runtime.enabled/checkpoint-key，且启动前无条件 require provider。主会话可分开无模型报告切片和模型分支准入，配置正式Runtime与发布；不需要用模型批准阻止无模型分支。

B 已有R/D可受控实施的部署范围（不表示已实施）：
- 主会话按既有迁移/密钥合同在精确 ADJLLM20261003 主schema受控应用V096（只两Runtime表及已定义索引/约束），保留U096原件，不写独立权威库，不改旧授权/案件/预算/期限。
- 正式部署系统生成/保管新的32-byte Base64 Runtime key，禁止测试默认key/复用或复制模型和身份credentials；按既定保管/备份/轮换合同管理。
- 发行经主会话回归的最新独立裁决产品包，沿原TLS、JWT、authority、domain条件与当前来源/Grant；无需模型的报告整理/缺口/人工交接分支可独立部署。V097为咨询模型调用，不是本切片裁决前提。
- 本子会话的明确写入/只读边界不允许实施上述共享脚本/正式发布/DDL；操作证据由主会话补齐。这是已设计批准但尚未部署的工程，不整体归为外部授权阻塞。

C 不可从已有技术D推定的范围：
- material-2撤回解除、material-1/v2扩大MODEL、真实案件/第三方材料、专业侵权/有效性/等同方法与价值验收，均没有由本次读取或空modelMaterialIds获得新批准。应按各自负责人的具体证据处置。
- 无人值守Worker的正式主体、调度与重启恢复不能继承请求内execute批准。

现时 release 的准确缺项：
- current-release.json指向 application-api-20261004-02.jar；SHA 83d2df8a531010f057a7f950a14e81d58e132015452cfcee6527adf62f6052db 与实际一致。
- ZIP条目无 ProductAdjudicationAgentController、AgentRuntimeConfiguration、AuthorizedAgentExecution；嵌入YAML无新Runtime配置；原启动脚本无enabled/key；受管目录无命名为Runtime/checkpoint key的credential原件，不能据此猜秘密值或推定存在其他渠道配置。5445无listener。
- 因旧包/无V096/key，本次没有执行新版真实Run/浏览器业务闭环，也没有声明产品验收。原Native Grant/READ和有限模型批准仍有效；正常登录及主会话受控部署后应继续用精确案件/输入/来源运行并复验编码、交接和失败边界。

## 冻结与下一步

本次6个前端文件重新冻结于 code-freeze-manifest.json。统一frontend build已按负责人授权实际通过；主会话接续包装、既有批准范围的Runtime受控部署/正式登录及真实同Run交接；本证据不替代主计划状态维护。需要外部输入时只列确切未获准范围，不让用户重新选择方向。

## 正式构建修正与重新冻结

负责人指出先前 focused typecheck 的 --types node 不代表 app tsconfig 正式构建。仅在 WorkArea.test.tsx、reportHandoff.test.ts、workspace.test.tsx 顶部添加 /// <reference types="node" />，使用已安装的 @types/node；没有修改任何共享 tsconfig/vitest 配置或安装库。2026-10-06按负责人明确授权在frontend目录实际运行 npm run build（tsc -b && vite build），退出0；formal-frontend-build.log保存完整输出，包含正式AdjudicationWorkspace产物。此前focused类型检查仅作为窄检查，正式构建以本轮实际日志为准。没有继续增加测试，也没有新增backend或改变模型批准范围。
最终冻结后的verify-final-freeze.log退出1：47/41基线仍一致；当前主计划检查器报TESTS_FAILING:project及TEST_EVIDENCE_STALE:project。此为当时主计划引用工程证据的实际机器结果，不替代正在进行的主会话二轮full，也不归因为本轮已通过的正式frontend build。

## 最终代表性输入实查（2026-10-06 14:25:59 +08）

按负责人追加指令，仅对确切 caseId/organization/dataScope JOIN原生head与最新revision读取payload，未输出正文。native-primary.log的NATIVE_INPUT_METADATA：workVersion=5、inputVersion=4；payload SHA与revision及head均一致，scope/版本坐标匹配。材料历史版本3、active材料2、active readable持久标记2；争点1；权项版本0、要素0、引用0、要素对应0；报告0、complete报告0，latestReportComplete=null。readable持久标记不是现时来源READ权限的替代，仍不得使用已撤回source。

结论：当前原生案件可作为既有MATERIAL_ORGANIZATION_VALIDATION材料整理输入/缺项反例，不能作为代表性完整侵权分析Golden；它没有权项、引用、要素对应或完整报告，不能因有case head/预算/争点而标为完整专业任务验收。已批准的无模型报告切片可以在正式现时来源校验后生成真实缺项报告并人工接手，但不承诺机器得出完整侵权/有效性/等同结论。完整专业评测用途及专业责任人标准不在既有材料整理批准内。

该轮primary/authority SELECT均退出0；两库恢复本轮前exited，详见最新native-service-state.json。代码冻结未改变；正式npm run build仍以此前退出0原始日志为准，不继续增加测试/改backend/改共享配置。
