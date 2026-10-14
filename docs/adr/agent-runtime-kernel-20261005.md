# ADR-AGENT-RUNTIME-20261005：共享内核与三独立 Agent 的增量重构

日期：2026-10-05。来源：本轮用户明确的技术重构指令、docs/01、docs/09、docs/54。技术决定，不新增法律、来源许可、权限或业务验收规则。状态唯一见 docs/08。

## 1. 扫描与现状 → 目标 → 任务矩阵

扫描源码文件清单（排除 target/node_modules）：backend 1247 文件（1209 Java）、frontend/src 133、E2E 50、scripts 151、requirements 47。阅读主计划/架构/治理/部署边界、构建与验证配置，并检查以下关键实现；这不是对全部 1209 个类逐行语义审计的声明。

| 模块 | 当前实现 | Runtime归属/复用 | 保留/抽象 | 目标归属 | 优先级/验证 |
|---|---|---|---|---|---|
| kernel | 只有pom，无运行代码 | 新建最小机械内核 | 不冻结业务Runtime模型 | 共享平台 | 1；内核状态/恢复/预算/越权反例 |
| consultation/platform-api | ProductTaskWorkerExecution、Fence、Task/Goal/Input与事务、来源SHA | 可复用执行围栏；不能直接泛化为全域权限 | 保留领域事务，独立Agent接线 | Consultation Agent | 3；既有检索/对照/恢复回归+任务评测 |
| enforcement | 证据/程序/处罚/文书/人工流程；Service最多289行 | 专业能力作为独立Skill调用 | 保留规则与正式状态所有权 | Enforcement Agent | 4；领域回归+人工边界反例 |
| adjudication | MaterialOrganizationModel及版本/源绑定 | 可复用材料整理；模型适配器固定deepseek-flash/endpoint | 保留严格模型批准与SHA/stale校验 | Adjudication Agent | 5；材料模型/候选采用回归 |
| modelconfig/model-deepseek | ManagedModelConfigurationService 284行；咨询注册表；裁决分叉HTTP | 需要共享Gateway，不继承咨询权限 | 先提取传输/配置端口，批准目的不互借 | 共享Gateway+独立Prompt/Policy | 2；协议/失败/批准/真实HTTP |
| identity | Product JWT/Grant/Worker与历史v0同时存在；配置有v0默认 | 可复用当前授权，不将历史证据作当前授权 | 按正式路由迁移，不全局替换字符串 | 平台身份+领域范围 | 6；撤权/过期/跨域拒绝 |
| frontend | 正式咨询与裁决页面、执法工作区；保留开发入口 | 共享任务展示，不共享业务决策 | 有实际Run读取API后接线，禁止静态伪进度 | 独立Agent工作区共享组件 | 7；组件/E2E/构建 |
| tests | API/组件/DM8/E2E证据丰富；未收口跨域Task评测 | 保留原测试并增加各Agent独立评测 | 不把内核测试当业务Golden Tasks | 三独立Evaluation Set | 8；任务完成/证据/错误动作/预算 |

基线：verify-all退出1，仅TEST_EVIDENCE_STALE；47文件/41契约一致。没有基线变更。最大Service不因行数机械拆分。当前运行库和在用服务不改动。

## 2. 当前限定R/D与实施边界

本轮用户批准技术目标为统一平台+三个独立Agent；对应docs/54既有边界。当前内核D只覆盖机械执行协议，不扩大41个业务需求的R/D：
- AgentDefinition版本绑定task类型、Skill→Tool白名单、Prompt/Policy/Evaluation版本；定义由各领域持有，kernel不得包含三个业务实现。
- Task/Goal/Run精确绑定；不可变Plan/Step/Observation/Outcome；Observe→Plan→Act→Evaluate→Re-plan→Stop。
- Checkpoint保存通过端口交给业务数据服务；每次advance只推进一个持久化状态。CAS先预留Invocation再调用Tool；恢复复用同一Invocation键。Tool适配器必须对该键幂等，业务提交仍需现有Fence和同事务回执，不承诺通用exactly-once。
- 每次执行、恢复与结果发布重新检查当前授权。历史检查点不是授权；许可/正式状态不由内核写入。
- CompletionEvaluator独立于Planner；只有机器合同SATISFIED可COMPLETED；人工门必须NEED_HUMAN。没有通用人工批准旁路。
- 有限步数/重规划/耗时预算；预留调用占预算，重试同键不额外消耗步数。StopReason固定枚举；预期工具故障显式分类，未知异常ERROR，不保存异常消息/机密。
- 运行恢复绑定Definition精确版本。失败持久化抛出，不吞掉或伪装成功。

当前允许纯Java内核代码及其组件/真实文件工具与磁盘检查点恢复测试。生产DM8端口、三个业务接线、Gateway、身份迁移、前端均后续任务，未经过相应设计/验证前不得称完成。无新增业务未决项；原docs/02专业/许可门禁保持。公开规则可以作为可替换候选，但不得自行批准第三方数据或最终法律规则。

## 3. 拆分与部署约束

三个领域各自持有AgentDefinition/Skills/Tools/Prompt/Policy/Task类型/权限/Evaluation/Lifecycle。kernel无Spring/领域/数据库/厂商依赖。生产Checkpoint端口由业务数据服务实现；跨域只通过API/Event/Contract。同仓同进程不构成合并。独立发布/扩容部署验证在后续接线任务取证。
