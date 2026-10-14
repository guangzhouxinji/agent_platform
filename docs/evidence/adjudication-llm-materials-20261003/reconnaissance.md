# 裁决真实模型材料整理核查证据

2026-10-03 +08；只记录实际核查，不代替docs08任务状态。

## 实际动作

读取docs08、docs01、docs09、docs02§11/31/83，反查requirements裁决00/04/07/09/12；原文件未修改。审查CaseWorkController/Commands/Access、CaseSourceAuthority、JdbcCaseWorkRepository及DeepSeek OpenAI适配器/ManagedModelConfigurationService和application.yml；对照docs71最新正式咨询模型设计。

CaseSourceAuthority用途仅READ/SAVE/ANALYZE；CaseGrantController动作仅CREATE/READ/WRITE/HUMAN_REVIEW；没有裁决材料MODEL许可。CaseWorkAccess检查当前可信JWT/session/subject、ADJUDICATION/逐案TaskScope/DataScope/grant epoch，不应复用旧测试身份。旧咨询适配器的业务候选输入及配置回退不等同裁决产品合同。现有配置deepseek:deepseek-chat；docs02§83记录本机凭据存在。本轮未读取/输出密钥、未实际模型请求、未证明裁决模型连接或授权。

## 校验原件

[修改前verify-all](verify-before.log)：实际退出1；47需求基线文件/41契约一致；项目计划DEPENDENCY_CYCLE:RUN-CON-FIRST-VERTICAL-01失败。未改咨询依赖或削弱检查器。非需求基线变化，无停止基线重排。

[编辑边界](plan-edit-boundary.json)：CON/ENF及ADJ01/02任务行逐字核对一致。新任务只登记在docs08，ADR只是设计证据；未新增业务代码/迁移/页面或测试完成声明。设计存在须责任方决定的用途/费用/保留，未标D通过。无新组件、浏览器、API或DM8实测，无新全仓/专业业务验收声明。

## 登记后实际校验

[登记后verify-all](verify-after-registration.log)：实际退出1；47/41基线和治理自测通过，670任务生成；本次错误为TEST_EVIDENCE_STALE:project。前次咨询依赖环在此次生成结果已不出现，本轨道没有编辑咨询行，不冒认其修复工作。当前生成任务中01/02仍DONE、03及ARC为RUNNING、03A为WAITING。没有新回归运行，不能沿历史通过宣称当前全仓通过；也不能篡改报告时间消除过期错误。
