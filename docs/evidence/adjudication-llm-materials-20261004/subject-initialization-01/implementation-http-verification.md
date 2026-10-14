# 03A 正式主体、真实 HTTP 模型链与异常验证实施证据

日期：2026-10-04。任务状态只以总计划为准；本文件不是独立状态台账。

## 授权、设计与可复用边界

承接原03A限定D与负责人主体选择授权，复用既有ProductIdentityBootstrap、维护证书校验、ProductSubjects、OAuth2/PKCE、精确逐案Grant、来源许可、材料版本及模型适配器。补充D见[ADR §10](../../../adr/adjudication-llm-materials-20261003.md)，新增仅裁决本地默认关闭的注册/激活/签名Key维护适配和材料接口404映射，不新建通用Agent平台、不修改01/02或共享身份内核。

[授权原件](user-approval.json)限定委托产品运行账户，不认证现实人员身份/专业资质，不授HUMAN_REVIEW，不代签审核。组织project-adjudication，维护主体adj-deployment-maintainer-20261004，登录账户adj-case-operator-20261004。初始化证据：[选定信任](selected-trust.json)、[部署初始化](deployment-initialization.json)、[正常维护HTTP](subject-maintenance-http.json)。正常mTLS维护和密码/PKCE登录，无手签JWT、数据库写Grant、开发身份或TLS校验旁路。秘密只在受限私人目录，不进本文。

现有5301/ADJLLM20261003保留预算与历史；独立权威库5370/ADJAUTH20261004，不替换咨询singleton、不连接执法事故库。DM8 REFERENCES缺权限的真实503/回滚保留，仅独立ADJAUTH服务角色补必要DCL；并未先造业务许可。

## 一次新增真实调用及持久化

[正式模型HTTP](actual-http-model-run.json)、[HTTP响应元数据](material-model-http.json)、[调用前账本](actual-runtime-before-model.json)、[调用后账本](actual-runtime-after-model.json)：

- 固定DeepSeek官方接口、实际模型deepseek-flash，输入只为获准项目自有去敏单案替代材料，用途不扩张。
- 案件adjudication-materials-case-01；run d3c5699f-18ed-453a-888b-3661c98214b3；request key adj03a-http-20261004-01。
- 真实GET所得案件版3提交；结果案件版4/输入版3。16条候选由既有生产校验器校验材料版本、原文摘录及定位；不是固定候选或人工伪装模型。
- 本轮新增1次，输入687/输出1149 tokens；累计2次、输入1374/输出2298、保守成本0.021132元。该值为账本保守计价，不是供应商实际账单确认。
- 原approval_key/policy_key不变，额度未清零；截止2026-11-01T16:00:00Z（北京时间11月2日00时），不由新账号或重启延长。保留30天约束不授权删除既有案件历史。
- 模型输出以既有加密记录保存DM8；机器候选没有写成确认事实、专业对应或审核结论。

部署包SHA 83d2df8a531010f057a7f950a14e81d58e132015452cfcee6527adf62f6052db，正式独立入口ProductAdjudicationApplication，见[current-release.json](current-release.json)。

## 真实顺序异常矩阵：零新增供应商调用

[逐项实际HTTP](actual-http-negatives.json)、[断言汇总](actual-http-negatives-summary.json)、[最终只读DM8账本/权限/来源](actual-runtime-after-negatives.json)均保留原件。

| 项目 | 实际结果 |
| --- | --- |
| 服务重启 | 原run与完整候选view SHA一致；身份/预算不重建 |
| 无JWT | 401；已登录Web cookie但无Bearer为403，二者不混淆 |
| 客户端覆盖固定模型 | 400 |
| 跨案读取 | 404 |
| 旧版/CAS冲突 | 409 |
| 同request key重放 | 同run，无第二次外呼 |
| 正常mTLS撤销MODEL_EXECUTE | 查询/执行403；案件READ仍有效 |
| 正常精确重签MODEL许可 | 查询恢复，旧许可历史保留 |
| 来源v2及材料追加 | 正常来源登记仅READ/SAVE/ANALYZE，无MODEL；案件head5/input4，原run明确stale，候选不覆盖 |
| 新来源版本无MODEL | 新生成403 |
| 正常撤回material-2@v1 | 案件/原模型回合读403，不能拿历史许可恢复读 |
| 失败不覆盖 | 原模型密文SHA不变；run数/账本calls仍2 |

最终安全负例状态是：head5/input4、旧候选失效、material-2@v1撤回、当前案件读取403。这是预期安全拒绝，不是可采用的当前输入。未直改来源state、复活历史许可或掩盖状态；后继人工采用须先完成合法当前输入准入及对应详细D。

真实供应商故障、外呼中撤权竞争未另付费实测：已有机械防御测试与本次顺序撤权不能混称同一证据。

## 回归和验证的实际边界

- [当前定向测试](targeted-tests-02.log)：66项，0失败/错误，打包通过[package-02.log](package-02.log)。新本地身份控制器6项、404映射3项；01/02 HTTP生命周期、并发、恢复、异常和材料模块同轮回归。
- [当前完整后端尝试](full-backend-current.log)：退出1。[原始报告索引与实际统计](full-backend-current-summary.json)按日志Running套名匹配XML：339套/1152项，0断言失败、20错误、119跳过。归档XML保留真实SHA/mtime，没有把失败报告替换成定向通过。
- 按suite包名归属：ADJ 16套/130项，CON 71套/303项均0失败/错误/跳过；ENF 84套/271项含19错误/26跳过；其余共享/具名集成168套/448项含1错误/93跳过。名字归属不证明所有跨域覆盖。20错误均有索引记录，缺ENF运行输入及DM8/JOB_PACK环境；不启动5444、不连接事故恢复库、不改测试为跳过。因此不能宣称ENF无回归或全仓通过。
- 此次未改01/02页面，未另跑浏览器；历史浏览器证据原样保留，不冒03C已完成。
- [本轮修改前治理检查](verify-before-summary-02.log)真实退出1，47文件/41契约基线一致，失败是TEST_EVIDENCE_STALE。未削弱检查器、篡改报告时间或要求其他域退回状态。首次后置[verify-after-http-progress-01.log](verify-after-http-progress-01.log)仍真实退出1。随后在新隔离快照重跑新增9项：[targeted-current-freshness-03.log](targeted-current-freshness-03.log)通过，1185源文件逐项SHA与当前工作区一致；[同步记录](canonical-freshness-report-copy-03.json)仅复制这9项对应报告、保留旧原件及新报告真实mtime，没有覆盖或消除完整回归20错误。[后置治理检查](verify-after-freshness-03.log)真实退出0，结构通过不等于全仓/专业验收通过。

## 证据分段与退出复核

旧真实组件run、此前原生旁表备份导入、本次正式HTTP新run是三个不同范围。旧旁表备份包含当时1次组件调用，不声称本次第2次新run已做原生备份导入。本次新run有实际服务重启/DM8读回一致性证据，不等同重新执行原生灾备恢复。

复核：账号及正式入口待落实项已解决；正式调用、引用定位、保存/重启和适用顺序异常已实际完成。03A仍进行中，治理freshness已通过真实新测试及准确同步处置；剩余为当前完整回归20个环境错误的范围化处置和退出所需回归确认，不再等待同一用途、预算、供应商或主体选择批准。03B/C/D仍未放行；没有人工采用、材料整理页面闭环或真实案件价值验收结论，不升级ADJ十二项。

更高层原生恢复、专业规则/业务验收与真实案件资格继续独立取证，不用技术验证冒充专业验收。唯一状态记录：[总计划](../../../08-总开发实现计划.md)；[ADR](../../../adr/adjudication-llm-materials-20261003.md)；[确认依据§84](../../../02-待确认事项清单.md)。

本轮总计划和确认依据更新后，再运行verify-final-http-progress-03.log；以该原始日志为本轮最终治理结果，不把结构检查等同完整后端回归。
