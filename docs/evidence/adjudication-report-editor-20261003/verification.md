# 裁决报告人工修订安全编辑：实施与实证

日期：2026-10-03（Asia/Shanghai）；任务IMP-ADJ-V2-TASK-02，状态只在主计划。来源：[限定D](../../adr/adjudication-report-editor-20261003.md)、docs02第82节；复用已完成01A～D，不重做领域/API/持久化，不升级ADJ完整成熟度或专业A。

## 实际代码

- `frontend/src/features/adjudication/ReportRevisionEditor.tsx`：原章节完整预填与对照阅读；每章节正文/原因分别保留在内存，切换不丢；保存仅发送所选章和原报告SHA。没有自动HTTP、浏览器持久存储或第二套报告/规则逻辑。
- `Reports.tsx`：绑定确切revision的既有修订API；选择旧报告后保存成功改看服务端最新稿，不继续停在旧选择；未保存时切换报告/生成/提交/复核先确认。
- `AdjudicationWorkspace.tsx`：现有回执+重读都成功才返回UI成功信号；409/403/网络未知/服务端异常false，编辑稿不因失败被当成已保存。继续原幂等键和结果不确定对账，不自动换版/换键/重试。当前工作区切换面板/案件/历史及刷新前确认；锁定/401/403/来源撤回优先清全部正文，包括未保存稿。
- `Forms.Command`返回boolean或void的兼容类型；原裁决表单继续忽略返回值，新建案件callback保持原void形式。不改HTTP响应、权限或状态机。
- 同时修改多个章节时，保存前明确确认只保存所选章、成功后放弃其他章；取消不发送请求，失败仍保留所有输入。所有正式保存仍生成可审计新版本，不回写原稿。

保护范围是裁决当前工作区内部导航及整页beforeunload；不冒称平台跨模块SPA路由、浏览器SPA前进/后退已全部接入，未为此改共享Router。未保存内容不是服务端业务状态或持久草稿。产品不含开发字段、模拟身份、默认案例或Demo提示。

## 测试与实际执行

| 验证 | 实际结果 | 原件 |
|---|---|---|
| 修改前统一校验 | 退出0；47基线/41需求账本一致 | [before](verify-before.log) |
| 登记限定D后校验 | 退出0 | [design-after](verify-design-after.log) |
| 全前端 | 20文件74项通过（原65+新增编辑9）；首轮73通过后补多章确认反例重跑 | [frontend-tests-second](frontend-tests-second.log) |
| 生产构建 | 实际通过 | [browser前置build](../adjudication-workspace-20261002/report-editor-first/frontend-build.log) |
| 真Chromium/PKCE/双DM8/同案重启与API兼容 | 7套23项，0失败/错误/跳过；同案22版5稿，138次HTTP，4次真实PKCE会话，无页面异常 | [实际结果](../adjudication-workspace-20261002/report-editor-first/verification.json)、[browser](../adjudication-workspace-20261002/report-editor-first/browser-result.json)、[tests](../adjudication-workspace-20261002/report-editor-first/tests.log) |
| 独立只读退出复核 | 23项XML/138HTTP/恢复摘要/截图和新增编辑拒绝证据通过；编辑退出4项+原复核兼容5项通过 | [editor audit](exit-audit-editor.json)、[4项](audit-editor-tests.log)、[兼容5项](audit-compatibility-tests.log) |
| 本轮隔离完整后端快照 | 330套1084项，0失败/错误，39条件跳过；CON106/ENF100/ADJ42无跳过 | [full log](full-backend-regression.log)、[确切窗口与330份XML](full-regression-summary.json) |
| 并行新增代码补核 | 当前新快照2项ENF机械兼容反例+3项ADJ规则/来源组件回归，无失败/错误/跳过；不混加到1084全量 | [supplement](supplement-tests.log)、[核实摘要](supplement-reviewed-summary.json) |
| 最新受影响代码/两域/裁决补回归 | 55套306项，0失败/错误/跳过；当前源码核对无漂移，不混称为新的完整后端回归 | [实际日志](latest-affected-regression.log)、[原件/摘要](latest-affected-summary.json) |
| 退出状态变更前统一校验 | 退出0；本轮最新受影响代码已补核 | [latest-before-record](verify-latest-before-record.log) |
| 状态记录后统一校验 | 实际退出0；47基线/41需求账本一致 | [final-after](verify-final-after.log) |

完整后端在独立源码快照运行，JVM user.dir指向工作区定位既有真实CON输入，不修改CON测试或放宽断言；使用既有正式输入编排准备并清理TST5DE2350B。裁决浏览器另用随机独立schema，实际双库清理通过，不对CON/ENF业务schema做DDL。

全量运行期间ENF新增专用法律准备IT、机械兼容测试及未激活测试profile改动，保留其源码/状态，不回退。已对本轮实际XML与对应源码核对，按真实mtime/SHA归档并更新统一校验读取目录；新增IT未执行，不声明其业务验证通过。随后新快照编译及5项组件补测实际通过，补核摘要记录sourceDrift空。原补核摘要文字按初读写“1项新反例”，XML实际为2项；保留原描述，新增reviewed摘要据XML更正，没有修改测试原件。

code-after/browser前后/regression-after统一校验的TEST_EVIDENCE_STALE退出1全部保留；原因是并行后端文件更新晚于报告。未降低checker、伪造时间或用旧绿冒当前，通过真实补核后exit-before退出0。

## 新能力正反矩阵

| 操作/风险 | 实际证据 |
|---|---|
| 章节预填与长文本完整阅读 | 组件精确比对含换行原文；真实浏览器从服务端报告加载原正文，原章详情可展开 |
| 两章节正文/原因分别保留 | 组件测试及两次真实人工修订的章节来回切换，内容/原因逐项assert；report-editor-draft.png |
| 单章保存避免静默丢其他章 | 多章保存确认正反组件测试，取消不发API；确认后拒绝仍保留各章节 |
| 取消离开/刷新/切换报告 | 组件不发生成请求且保留稿；浏览器取消切换“材料”和取消刷新保留确切内容 |
| 确切报告/章节绑定 | 组件核revision路径、原SHA/sectionId/content/reason；真实流程仍生成报告v3/v4并独立退回/再提交/复核 |
| 并发409不清修订 | 浏览器实际POST /reports/4/revisions→409 WORK_VERSION_CONFLICT，正文与原因仍在；report-editor-rejection.png |
| 未知/异常不能冒成功 | 组件false、undefined和异常均不清稿；保存中重复submit只一次调用；工作台原同键对账仍保留 |
| 来源撤回敏感稿清理 | 真实mTLS撤回后，带未保存人工正文POST /reports/5/revisions→403 CASE_ACCESS_DENIED/SOURCE_REVOKED；表单、正文、工作区导航全部清空，没有草稿阻挡清理 |
| 成功跳到新稿/历史不写 | 组件核先选v1保存后跟随新v3；浏览器旧稿失效、历史只读、退回后新稿提交/独立复核/重启和移动历史回归 |
| 退出复核不能旧证据代替新能力 | 新编辑退出检查强制新增五个编辑结果、实际409/403修订响应和两张截图；旧01D原件缺编辑字段会拒绝 |

独立退出是只读工程工序，不是第二具名专家或专业业务验收。原文、报告/审计及来源历史保持不可变；本轮不改变任何专业阈值、法律结论、签发/官方状态。

## 边界与结论

本轮业务代码仅裁决前端目录；测试补充仅裁决浏览器、组件与裁决退出脚本，没有业务后端/共享安全/数据库修改。计划状态只推进本任务，01A～D历史完成不重做，CON/ENF行逐字保留，编辑边界摘要另附。已实现并完成组件、真实操作、回归及退出验证；ADJ-01～12全能力、专业A和生产组织验收不自动升级。

记录期间再次发生并行CON页内引用/transport/授权历史批量读取更新，record-before的TEST_EVIDENCE_STALE退出1保留。未覆盖或恢复外域代码；新隔离snapshot实际重跑全部CON/ENF/ADJ领域、ADJ API21和受影响CON组件（55套306项零失败/错误/跳过），源码SHA无漂移后归档，再执行latest-before-record退出0。完整回归仍只声称首轮330套1084快照，后续306是受影响补回归，不能相加或冒新全仓窗口。

最终记录后统一校验 `verify-final-after.log` 实际退出0。主计划只将IMP-ADJ-V2-TASK-02推进为已完成；[编辑前后边界](plan-edit-boundary.json)核507条CON/ENF行逐字相同（含状态和依赖）。没有把并行外域变动归为本任务，也没有将补回归冒充全仓新窗口。
