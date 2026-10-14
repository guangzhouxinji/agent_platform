# 2026-10-06 文档收敛验证记录

本文件只记录本次迁移与校验原件，不维护项目任务状态。实施入口和任务状态唯一见`docs/08-总开发实现计划.md`。

## 变更范围

- 主计划原地重组：全程路线、41项归属包、阶段出口、最终交付标准与连续接续规则；既有任务和需求状态不升级。
- 13份重复路线/历史提案/评估散文件合并删除，高风险验证目录进入主计划；原始字节与SHA保存在`original-documents.zip`和`source-manifest.json`。
- 导航、治理及现有候选设计链接同步；原始需求、契约、业务代码、运行库与旧验证原件不改。
- 文档归属清单逐文件说明保留/迁移理由；只读历史不参与当前调度。

## 前置校验

`node scripts/verify-all.mjs`实际退出1：15项前置测试通过，47需求文件/41契约一致，治理自测通过；在项目健康检查处因`TESTS_FAILING:project`停止，后继脚本未执行。原件`verify-before.log`。此红灯为本轮前已存在，不以文档重排消除。

## 迁移完整性与修复

原件ZIP核对220项来源；13散文件原字节完整可恢复。原计划实际包含693行任务，但IMP-ENF-MATERIAL-01缺少末尾竖线，旧生成器只读取692项。本轮只补该行末尾分隔符，状态、依赖、退出条件和证据文字不变。新增本轮文档治理任务后机器视图为694项；没有制造一项新的执法业务完成。

首次迁移审计实际失败：3处百分号编码空格的旧Freeze链接未重定位；原件document-audit-initial-failed.json保留。修复这3处链接后重新完整审计，断链为0。原始需求文件/摘要、41项需求证据行及全部旧任务内容（只允许证据URL迁移）逐项对照原字节；所有原有docs/evidence文档SHA保持不变。

## 已执行的文档与结构验证

- `python scripts/verify-document-consolidation.py --self-test`：8项通过，原件document-audit-self-test.log。包含缺失表尾仍可被审计、重复ID拒绝、跨节隔离、百分号路径、外链排除、同文档锚点及相对链接反例。
- `python scripts/verify-document-consolidation.py`：通过；原件document-audit.json。检查可恢复原件、删除清单、693行任务保真、41需求保真、唯一主任务、全程路线、机器任务集合、全部本地文字链接及历史迁移锚点。
- `node scripts/verify-structure.mjs`：退出0；原件structure.log。既有软告警保留，不据此宣称专业正确。
- `node scripts/verify-project-progress.mjs`：退出0；原件dashboard.log。桌面/移动看板结构、交互、溢出及机读状态一致性通过；这不是业务工作台E2E。
- `node scripts/verify-all.mjs`：前置及中间/收口前均退出1，仅TESTS_FAILING:project；47文件/41契约及治理自测通过，未出现新增基线或计划结构错误。原件verify-before.log、verify-after-initial.log、verify-after-intermediate.log、verify-before-closeout.log。总脚本未执行的后继检查不得冒称通过。

## 收口边界

仅文档治理任务按限定退出条件完成，当前主任务恢复IMP-AGENT-RUNTIME-KERNEL-01；完整后继路线在docs/08第0节。未执行业务代码修改、数据库迁移、来源授权变更或新的业务全量测试，不改变既有专业/业务验收状态。2026-10-05最后一次隔离全量1128/0失败/4错误/40跳过仍是那次结果；当前健康生成器扫描多份历史/后缀XML的汇总不得当本轮新全量结果。

最终状态写回后的后置命令与退出码保存到final-validation.json、verify-after-final.log；文档审计与看板再次执行以核对最终主任务和生成物。失败原件不删除，不将总校验红灯改为绿色。
