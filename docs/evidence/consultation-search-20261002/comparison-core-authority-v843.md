# 专业技术对照原件、矩阵与现时用途C证据

日期：2026-10-04；任务：IMP-CON-PRODUCT-COMPARE-01。此为测试原件说明，不是任务/门禁状态台账。

## 执行及输入

- 基线冻结目录：`C:/Users/60329/AppData/Local/Temp/consultation-model-v836`。既有model-v836-build-inputs并不包含本次全部覆盖；额外12文件以[单独SHA清单](comparison-v843-build-inputs.json)核当前与冻结均相同。没有从其他域并发工作树重新建立基线或正式发行。
- v839旧版本日志BUILD SUCCESS，仅作旧版本记录；最新版本实际退出以v842、v843为准。
- [v841第一次迭代日志](comparison-v841-core-tests.log)实际退出1：显式栈展开的路径又被反转，导致合法继承链错误拒绝。删除错误反转后复跑，保留原失败样本，不修改断言或真实原件。
- [v842内核/兼容日志](comparison-v842-core-tests.log)实际退出0：24项，失败/错误/跳过均0。
- [v843权限/内核/兼容日志](comparison-v843-authority-tests.log)实际退出0：39项，失败/错误/跳过均0；8份选中suite XML另存本目录`comparison-v843-*Test.xml`，未覆盖规范全量证据或伪造freshness。

## 原件与权项

真实输入为既存PATENTSVIEW公开采集40篇`claimsText`原JSON。40篇均能重核原件SHA、RAW_SHA256文献版及decoded文本到原始UTF-8/JSON转义跨度；36篇完整权项图闭包通过。以下4篇继续保留缺口，不能悄悄按独立项放行：

| 原记录 | 实际拒绝 |
| --- | --- |
| 3933241.json | CLAIM_DEPENDENCY_CYCLE |
| 3952738.json | CLAIM_DEPENDENCY_CYCLE |
| 3955401.json | CLAIM_REFERENCE_CONFIRMATION_REQUIRED |
| 3989370.json | CLAIM_DEPENDENCY_MISSING |

展开改为显式栈；2500层依赖实际通过，不使用Java调用栈。择一分支仍独立保存，缺前项、循环、非前项、歧义及超分支预算拒绝。未实现的中文/繁体中文或中英混合引用表达明确要求确认，不猜成独立权项。

矩阵实现确认特征×候选分支完整唯一、目标/文献版、原字节证据及已分析范围、对应/部分对应/限定范围未找到/未知/未分析语义。机械测试验证缺行、重复、无差异、无未知原因、假原文SHA及跨择一证据拒绝。确定法律结论词拒绝测试通过；该词检查不等于全面语义安全或专业意见正确，结构校验亦不证明技术对应正确。

## 现时用途

Source候选/准入/读取认识COMPARISON用途码，不自动修改任何决议或签授权。固定包内`requireComparison`核SOURCE_SCOPE的COMPARE/COMPARISON，与旧DISPLAY/SOURCE_DISPLAY并存，QUERY及DISPLAY不能替代。机械SQL正反证据包括正确精确Grant、缺逐字段决议、错字段、到期、跨Task、Task版变化、错DataScope、撤Grant、旧Source epoch、旧Grant代数及独立水位中途变化。旧正式原文读取、既有来源准入及Query范围签发兼容通过。

这些为真实原件离线解析和机械H2/水位故障C，未签真实COMPARISON用途，未产生新正式成员/Worker HTTP请求，不是DM8 I、真实模型执行、业务/法律验收。

## 尚未交付边界

正式维护精确签发COMPARISON、prepare/用户明确特征确认、物理持久化、Worker原子提交、正式路由/工作面及模型专业质量仍需继续。原件存在或DISPLAY许可不等于COMPARE/MODEL_INPUT许可；不得把供应商此前最小结果元数据批准扩为整段CLAIMS/技术方案外送。未调用付费模型，未复活到期授权，未写运行库、恢复旧记录或修改其他域。

本次前置v840实际1，仅TEST_EVIDENCE_STALE；47需求文件/41台账一致。自身新增实现也产生freshness缺口，不能只归因其他域。后置检查另保存实际结果，不把39项定向C冒全量绿。

## 继续补强与最新版本

矩阵另增加继承闭包校验：首项不能仍有缺失父项，后项必须引用紧邻的实际前项并递增，拒绝遗漏依赖和混入无关独立权项。此为原矩阵完整性合同内补强，不改变业务关系枚举或专业目的。矩阵输入必须仍由可信准备链构建；该结构校验不从原字节独立证明调用方声明的依赖语义，不能代替原件权项解析。

[v845最新定向/兼容日志](comparison-v845-closure-tests.log)实际退出0，共40项，失败/错误/跳过均0；最新12源[封印](comparison-v845-build-inputs.json)及8套`comparison-v845-*Test.xml`另存，不使用v843旧SHA冒充最新版本。v846独立结构检查实际0。v844后置verify-all实际1仍仅TEST_EVIDENCE_STALE，47/41一致；规范全量freshness仍未通过。当前Docker运行I依旧受runtime socket不可连接限制，未发行或运行业务请求。
