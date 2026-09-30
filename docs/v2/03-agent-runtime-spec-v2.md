# Task、Goal、Agent Runtime 规格

逻辑契约，非现有代码或物理表设计。依据：咨询总目录第 3.3/8 节、CON-04/11/12，执法/裁决总览异步任务与人工门禁。`ADR-V2-01/02/07`。与 [产品契约C2](01-product-requirements-v2.md) 的可观察行为一致后，具体对象/字段/原子提交方案仍须当前Task的D门。P0 只实现首条任务需要的子集；通用调度器、事件溯源框架和跨域统一状态机不预先冻结。

四项共同产品约束已确认，见 docs/02 第23节；这不批准本文件所列全部字段、命令、状态、存储方案或三域通用运行引擎。

## Task 与 Goal

```text
Task {taskId, domain, ownerRef, actorRef, goalVersion, purpose, scope,
      inputRefs[], childTaskRefs[], completionContract, artifactRefs[],
      taskState, stateReason, revision, createdAt, updatedAt}
Goal {goalId, version, userStatement, objectRefs[], desiredOutcome,
      constraints, confirmedFacts[], unresolvedFacts[], acceptedBy?, status}
CompletionContract {requiredOutputs[], evidenceQuestions[], blockingGaps[],
                    disclosureItems[], humanGate?, domainEvaluatorVersion}
```

- `ownerRef` 是领域工作对象（咨询主题、执法线索/案件、裁决案件）的引用，不能由 Task 复制其权威状态；`actorRef` 仅审计创建者，读取仍按当前授权。
- 目标版本区分用户原文、机器理解、用户确认、变更与作废；自动解释只能产生 `proposed`，对范围/对象/用途有实质影响时需 CONFIRM。用户无须确认每个查询改写。
- 复合任务保存实际存在的子目标依赖；仅在任务确有依赖时用 DAG 表达，不要求先建设通用执行引擎。子任务独立成果与失败；完成定义按目标关键项聚合，不能用全部工具成功或简单子任务数量判断。子任务失败可以形成部分成果和明确下一步。
- `completionContract` 是逐任务的领域规则和专业评审产物，不以跨域通用布尔公式冒充法律充分性。没有经责任方批准的专业标准时只报告候选、缺口及待复核，不能标正式业务完成。

## 状态维度

| 维度 | 建议状态/判据 | 不得混淆 |
|---|---|---|
| Task 用户展示 | `进行中、等待用户补充、等待确认、部分完成、已完成、证据不足、需要人工复核、技术失败、用户取消`；按咨询确认的集合展示，执法/裁决复用前逐域评审 | 子任务/Run、业务案件、审核、正式状态 |
| Run attempt | `排队、执行中、部分成功、成功、失败、取消、超时/中断待恢复`；有 attempt ID | 成功仅表示约定技术步骤完成 |
| Artifact | 内容版本/缺项、审阅进度、来源有效性、当前可访问性分别表达；旧版已复核与新版待复核可同时成立 | 不把已复核等同永远可读，不压成一个跨域万能枚举；审核不等于签发/处罚/裁决 |
| Human request | `待发送、待受理、处理中、已答复、拒绝、过期、撤回`；真实回执决定受理 | 请求发出不等于已受理 |
| Official | 只由权威系统事件或授权人员经验证的线下事实推进，具体枚举[待确认] | 不由 Agent/Task 聚合状态推算 |

状态含结构化 `reasonCode`、影响范围、当前阻断方、可恢复动作和版本；例如证据不足与来源失败不同，取消与技术失败不同。迟到结果只能入运行审计，不能复活取消任务或覆盖新版本。纠正已有目标、改变范围和替换目标按P0-20及领域契约分别处理；不因当前是终态就一律新建无关任务，不静默改写旧历史。

## Agent 行为策略

1. `Observe`：读取当前 Goal/事实/未决项、产物依赖、来源健康、预算和当前身份授权；历史授权不复用。
2. `Plan`：列下一小步的目的、预期信息增益、成本/风险和停止条件；只选择发布的领域 Skill。计划是可修订策略，不是固定大模型思维链或可由文献指令修改的命令。
3. `Act`：低风险只读检索、解析、核对在预授权范围内可连续；高影响目标/范围改变先 CONFIRM，专业判断先 REVIEW，不可逆正式动作禁止自动执行。
4. `Observe/Evaluate`：检查工具真实结果、来源、证据覆盖、冲突与预算。没有新信息时依据获准策略调整方法或停止，不虚构固定“一次”业务限制；不能无限同义词扩展来制造进展。
5. `Re-plan/Stop`：重大事实纠正、来源撤回、失败/权限变更时只重算依赖部分。达到领域完成标准、无增益、预算耗尽、证据不足、越界、人工接管或用户取消时停止并解释范围与下一步。

`ASK` 合并必要缺口并尽可能先做低风险准备；`CONFIRM` 只用于对象/时点/地域/数据源/目标实质变更；`REVIEW` 交明确版本的证据包；`TAKE_OVER` 停止 Agent 竞争写入并转人工。没有收件系统不可显示已转接。

## 检查点与恢复

- 每次工具调用前固化 task revision、goal version、输入与源引用、实际权限策略版本、工具/模型/规则版本、预算剩余、幂等键和意图中的下一动作。提交结果以预期 revision CAS 校验；结果与产物采用原子提交或可对账补偿。
- 幂等作用域 = 可信主体 + task + operation + input fingerprint；同键同载荷在当前读取权限复核通过后返回原尝试，同键不同载荷冲突。重试是新 attempt，引用原 attempt，不复写人工版本。
- 可重试只读且幂等的工具按短退避/限流恢复；来源状态不确定、外部写入或权限变更时先查询权威回执/重新授权，不盲重放。重启扫描中断检查点：一致则继续、冲突则等待确认或受控失败。
- 取消写入版本化命令，阻止后续步骤；活动工具无法中断时其迟到响应隔离并审计。人工修订和紧急撤销优先于旧自动尝试。
- 订阅源更新/撤回、权限撤回、规则紧急撤销；无事件能力时关键读取/复核/导出前按源核验。遍历依赖边生成影响集、待复核原因与重新执行建议；历史内容仅保留受控审计引用。

## 预算、停止与失败矩阵

| 条件 | Runtime/Agent 行为 | 用户可见结果 |
|---|---|---|
| 工具无结果且来源健康 | 可改变获准方法，披露实际检索范围 | 当前范围未找到，不推定不存在 |
| 来源超时/限流、模型失败 | 有上限重试或受控降级，保存已完成成果 | 技术失败/部分完成和恢复入口 |
| 关键全文/权利要求不可读 | 停深入判断，可产出线索级结果 | 证据不足和具体缺项 |
| 权限/数据许可不满足 | 立即拒绝该工具和内容发布，不用其他模型规避 | 无权限/不可用，不能泄漏存在性细节 |
| 无进展/预算上限 | 停止新调用，保留审阅和必要收尾预算 | 已做事项、覆盖范围、未决和下一步 |
| 重要冲突/人工接管 | 暂停相关分支，打包材料 | 等待复核/接管，其他无关分支可继续 |

预算维度为任务总时长、工具次数、模型 token/费用、检索扩展范围、并发和人工等待；数值按真实工作观察/安全责任方批准 **[待确认]**，不填拍脑袋固定轮数。硬上限和紧急停止由服务端执行。记录每步预期信息增益和实际新增证据，连续无进展停止；正式专业任务不因省成本自动给出有风险的完成结论。

## 最小 API/事件语义

`CreateTask`, `AmendGoal`, `SubmitFactCorrection`, `RequestAction`, `CancelTask`, `ResumeTask`, `GetTask`, `GetArtifact`, `SubmitHumanResponse`：命令携 expected revision/idempotency，服务端身份决定权限；响应有 task/attempt/revision、当前阻断与 trace ID。事件 `TaskCreated`, `GoalConfirmed`, `ToolAttemptFinished`, `ArtifactVersionProduced`, `DependencyInvalidated`, `HumanRequested/Answered`, `TaskOutcomeChanged` 均有 eventId、因果版本、发生/记录时间和敏感数据引用，不在事件总线上广播原文全文。详见 [边界](06-skill-tool-spec-v2.md#api与事件边界)。
