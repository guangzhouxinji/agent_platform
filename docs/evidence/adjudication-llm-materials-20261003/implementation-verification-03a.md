# 裁决材料整理03A实施与验证证据（2026-10-03）

范围：IMP-ADJ-V2-TASK-03A。本文是实施证据，不是第二套任务状态台账；任务状态仅见08总计划。用户首轮范围及供应商例外已批准，当前限定D通过，不重复等待同一授权。

## 1. 已实施的正式能力

- 领域端口 `MaterialOrganizationModel` 与 `MaterialCandidateValidator`：区分当事人主张、材料明确记载的陈述、争议线索、缺项，不输出“已确认事实”。每条候选绑定来源、版本、SHA、逐字摘录和UTF16定位；拒绝未知/重复JSON字段、重复候选、未知类型、错方别、源漂移及非唯一定位。机械一致性不证明归纳语义正确。
- DeepSeek独立裁决适配器固定 `deepseek-flash`，官方chat/completions、非思考、JSON、temperature 0、最大输出2048；无工具、重试、重定向或模型回退。仅提交sourceTag/side/text，不发送身份、组织、案件编号或内部来源ID。供应商条款证据和两份用户批准保持原样。
- 正式POST/GET `/v2/adjudication/case-works/{caseId}/material-organization/runs`，客户端只能选择材料及确切版本，不能指定模型/actor或注入候选。现时逐案MODEL_EXECUTE及来源MODEL用途为裁决专用增量；READ/WRITE/ANALYZE不自动获得MODEL。
- 复用既有案件CAS与审计，模型外呼位于事务外；返回后的授权、来源generation和确切版本再次检查，成功记录与案件窄审计共用现有数据源和事务管理器。模型输出不能直接变更事实、专业对应、审核或正式状态。
- V090专属预算/回合旁表，AES-GCM逐回合密钥、加密正文CLOB；幂等重放不再次外呼。已付费但不确定的失败不释放预留额度，不覆盖旧成功产物。
- V091绑定同一批准的唯一approval_key；换案件/证据SHA不能生成第二份额度。既有已知policy_key受控回填，未知未对账旧预算禁止新建。恢复后预算不清零。
- 本地新增回合期限不超过30天及许可截止时间；密钥不进入DB或DB备份。到期拒读、定时清理，清理失败关闭模型分支。未删除既有01/02案件历史。

代码位置：`backend/adjudication/.../materials/`、`backend/adapters/model-deepseek/.../adjudication/materials/adapter/`、`backend/apps/platform-api/.../adjudication/casework/Material*`、`JdbcMaterialRunStore`；DDL见V090/V091。旧领域State/Codec、旧报告接口及页面不重做，不新建通用Agent平台。

## 2. 真实模型与DM8组件实证

[真实组件摘要](real-model-dm8-01/real-model-dm8-summary.json)：固定专用schema ADJLLM20261003，保留既有额度，不换库重置。

- 实际模型：deepseek-flash；16条候选；输入687、输出1149 tokens；累计1次。
- 保守成本记账10566微元（0.010566元），不是供应商实际账单确认。上限10元/20次/输入100000/输出20000保持不变，预留策略提前拒绝无法保证上限的调用。
- 原始请求、输出、输入快照和候选加密写入实际DM8，重新建立存储对象后逐字一致。证据只含计数、SHA、回合标识，不含密钥、密码、JWT或模型正文。
- [最新代码恢复](current-code-dm8-reload-09/current-code-reload-summary.json)及[实际测试](current-code-dm8-reload-09/tests.log)：已实际应用V091、回填批准绑定并重载此前同一回合，16候选引用机械重核一致，累计仍1次/687/1149/10566，未调用供应商。
- 该证据明确是用户获准输入的实际组件验证；没有把旧测试身份包装成当前业务身份，也没有把替代材料称为真实案件价值验收。

## 3. 实际测试和回归范围

| 运行 | 实际结果 | 边界 |
|---|---|---|
| guards-03 / regression-04 | 曾有3项新测试失败，原日志保留 | 错误映射、测试事务数据源和并发案例已修正，不抹掉失败证据 |
| guards-05 | 54项通过 | 较早实现快照，不冒最新V091证据 |
| regression-07 | 334套/1133项，0失败0错误，39条件跳过 | 后端`*Test`隔离源码快照；不是所有IT、前端或全仓验收 |
| guards-09 | 8套/57项，0失败0错误0跳过 | 最新V091、JSON重复字段、HTTPS防御及旧01 API补测；不是新一次全仓回归 |
| current-code-dm8-reload-09 | 1项实际DM8恢复通过 | 无新增模型调用；不是物理备份导入 |
| legacy-browser-08 | 真实Chromium/PKCE/138次HTTP、服务重启及DM8一致通过 | 旧01/02页面回归，不是03候选页面验证 |

[完整后端报告索引](regression-07-report-index.json)及归档XML保留SHA与原mtime。按suite名称统计CON为83套/335项、0跳过，ENF为105套/312项、30条件跳过；名称统计不是所有跨域共享测试归属。整个07运行0失败/0错误。其后ADJ专属改动由09定向补测，不把07称为最新全仓通过。

[最新专项索引](guards-09-report-index.json)、[日志](guards-09.log)。57项包含领域引用12项、预算/恢复15项、HTTPS模型防御9项、既有01 API21项。HTTPS测试使用真正生产控制器、签名HTTPS和当前许可维护流程，但模型port仅在测试目录替代、DB为H2；不能与真实模型DM8组件拼接冒称正式业务API整段实证。

[旧页面浏览器结果](../adjudication-workspace-20261002/llm03a-legacy-browser-08/browser-result.json)：正常生命周期、退回再提交、历史只读、版本冲突/旧报告失效、来源撤回清空内容、安全编辑草稿和移动端检查通过，无页面错误；服务重启后同案可读，非网络mock。

## 4. 尚未达到03A退出的内容

- 当前产品主体经受控许可维护，在实际单案/来源范围取得现时MODEL_EXECUTE/MODEL；随后完成真实模型 + 正式可信HTTP + DM8同案串联、服务重启及适用撤权/撤源/版本/失败负例。
- 新模型旁表的独立备份导入、密钥排除、到期不可恢复及部署备份策略实证。当前只有数据库重载和组件到期/损坏防御，不冒真实物理备份恢复已过。
- 03B人工采用版本、03C页面、03D人工基线与综合验证未实施；不以旧工作台回归替代新候选操作。
- 真实案件资格、人工遗漏/虚构/修改量/耗时和专业业务验收未验证，不升级ADJ十二项完成度。

以上是剩余实施/实证工作，不是再次要求用户批准相同用途/预算。01/02完成状态、CON/ENF状态及依赖不改变。

## 5. 治理验证

最新修改前 `verify-before-progress-10.log` 实际退出0；前置 `verify-scope-guard-before.log`及旧浏览器轮前后verify-all也为0。47文件/41契约基线一致。文稿与总计划更新后继续执行verify-all，后置结果见 `verify-after-progress-10.log`，以日志实际结果为准。结构验证不证明专业业务正确或业务验收。
