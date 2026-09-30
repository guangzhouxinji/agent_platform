# 执法 V2 ENF-12～17 后继产品 D 证据

- 日期：2026-09-29
- 责任：Codex 产品与技术复核
- 来源：ENF-12～17原始需求、执法总览、`docs/57` ENF-12～17、`docs/v2/15-requirement-traceability-v2.md`及项目负责人本轮功能后继指令。
- 边界：产品D与正式业务B分离；本文不批准中心模板、法律适用、处罚标准、真实岗位或正式业务验收。

## 1. 依赖复算

| 能力 | R | 产品D依赖 | 实现依赖 | 本轮判定 |
|---|---|---|---|---|
| ENF-12 模板 | 已通过 | 项目原创DOCX、冻结模板合同 | 自身D | D通过，允许IMP |
| ENF-13 映射 | 已通过 | ENF-12冻结模板合同、项目Schema输入 | ENF-12实现 | D通过；IMP等待ENF-12实现 |
| ENF-14 草稿 | 已通过 | ENF-12/13/17冻结合同、项目草稿输入 | 12/13/17实现，按需ENF-11/16 | D通过；IMP等待实现依赖 |
| ENF-15/16 处罚 | 已通过 | 项目公式输入、ENF-17冻结引用合同、现有Procedure/Conduct确切版 | ENF-17实现 | D通过，IMP等待ENF-17 |
| ENF-17 引用 | 已通过 | 可核来源快照、项目目标锚点、冻结引用合同 | 自身D | D通过，允许IMP |

`NEXT-V2-INT-ENF-01`只管理正式业务真实性验收，不是上述产品D或功能实现前置。旧`EXP-ENF-DOCUMENT`保留为汇总/业务验收历史任务，不用于绕过新窄D。

## 2. ENF-12 冻结合同

- 输入/输出：可信JWT和精确Grant；DOCX原字节/SHA、名称、环节、机构/地区/程序、章节/重复区块、稳定占位符、类型/必填/格式、来源与批准Ref。输出不可变模板修订、文件Ref/SHA、字段清单、校验诊断、预览artifact/rendererVersion和历史。
- API/UI：`/v2/enforcement/document-templates`创建/列表/详情；修订、字段、预览、发布、停用和版本文件下载。工作面提供上传、元数据、字段配置、样例预览、发布停用及历史下载，并覆盖loading/empty/403/409/503/request_id。
- 数据/状态：模板identity/current pointer、不可变revision、field、file binding、preview attempt/artifact、receipt/event；`DRAFT -> PUBLISHED -> DISABLED`。发布版不编辑，新改动建立新草稿。
- 权限：`DOCUMENT_TEMPLATE`的CREATE/EDIT/PREVIEW/PUBLISH/DISABLE/READ/DOWNLOAD按组织、资源、版本和用途校验；Agent不能发布。
- 失败/恢复：非DOCX、宏/脚本/外链、压缩炸弹、占位符/字段不一致、异常表格、缺字体/渲染失败、摘要漂移、版本/幂等冲突、DB/文件失败均零发布、零孤儿。DB+模板/预览文件共同恢复，备份外Grant/来源/删除水位不回灌；缺文件或SHA不符失败关闭。
- 禁止：不生成正式文号、签章、签发、送达；渲染成功不等正式模板批准。

## 3. ENF-17 冻结合同

- 输入/输出：事实/行为或程序事项、用途、地区、事件时间、案件Ref；目标报告/文书精确版本和锚点。输出候选稳定条文ID/版/正式名称/逐字原文/SHA/SourceRef、效力与过渡缺口、解释、引用修订、审核和复核状态；原文与解释分离。
- API/UI：`/v2/enforcement/legal-basis/searches`、`/legal-references`、绑定、审核、解绑、详情和历史；工作面在依据追溯区展示候选、逐字原文、解释、时间/地区/过渡提示、目标位置和人工动作。
- 数据/状态：共享Legal Knowledge端口拥有法规原文；执法域保存SearchAttempt/Member、LawReferenceRevision、BindingRevision、ReviewEvent、receipt/event和逐字快照。检索`COMPLETED/PARTIAL/FAILED`、审核`PENDING/CONFIRMED/RETURNED`、绑定`ACTIVE/NEEDS_REVIEW/UNBOUND`三轴分离。
- 权限：搜索、读原文、选择、绑定、解绑、确认和退回分别核组织、目标资源、动作、用途和现时Grant；正文读取和事务提交前再次核权。Agent/模型只能候选/解释。
- 失败/恢复：无匹配不造条号；源503与无匹配分开；部分成功列失败源；历史/过渡未知保持待核；目标/锚点/SHA漂移、跨组织、失权、CAS/幂等冲突均零绑定。业务DB、逐字快照/文件、回执共同恢复，Grant/source epoch不回灌；来源更新不覆盖旧正文，只标`NEEDS_REVIEW`。
- 禁止：V0 `PublicEnforcementReferenceCatalogPort`及公开裁决目录不得作为最终工作流来源；候选不等适用已确认。

## 4. ENF-15/16 冻结合同

- 规则输入：行为、职权/程序/地区/有效期、ENF-17依据版、法定范围、裁量基准、因素/证据、从轻/减轻/不罚条件、类型化公式、金额口径/币种/单位/精度/舍入/上下限和冲突优先关系。脚本执行禁止。
- 建议输入：案件确切版、具名确认事实、Evidence/Conduct/Procedure确切版、ACTIVE规则、ENF-17引用和逐项确认的金额输入。`SUSPECTED`、`UNREVIEWED/RETURNED`或gaps非空不算确认事实。
- 输出/状态：规则`DRAFT/ACTIVE/DISABLED`及无副作用trial轨迹；建议`WAITING_INPUT/PENDING_REVIEW/REVIEWED/RETURNED`、并列候选方案、法定范围、因素/证据、逐步计算、缺口/冲突、调整/复核历史。复核不产生处罚决定。
- API/UI：规则维护/试算/发布停用/历史；建议生成/详情/调整/复核/重跑。页面显示确切上游版本、输入来源、计算步骤、并列方案、缺项和历史，并覆盖403清屏、409刷新、503及部分失败。
- 权限/事务/恢复：规则READ/EDIT/TRIAL/PUBLISH/DISABLE和建议CREATE/READ/ADJUST/REVIEW/RERUN分离；同命令业务写、event和receipt单DM8事务。恢复核本域DB/回执/事件及上游Ref摘要，再核备份外现时Grant、来源和规则状态。
- 失败：缺依据/金额口径/舍入/边界样例、冲突无优先关系拒发布；缺值不作0，确认零值按公式；程序不适用不计算；多方案不暗选；越界调整不能复核；撤回/503/失权/重放/旧恢复均失败关闭。

## 4.1 ENF-13 冻结合同

- 输入/输出：输入固定模板ID/修订、项目Schema Registry版本、逐字段来源路径/类型、`DIRECT/DETERMINISTIC/AUXILIARY_DRAFT`方式、转换、缺失策略和用途；输出不可变规则修订、字段来源预览、缺失/无效路径、权限错误、模板漂移、未处理项、完整性和审计历史。
- API/UI：规则创建/列表/详情、修订、validate、无副作用trial、publish、disable和历史；工作面必须展示精确模板版、Schema版、字段来源、缺失策略、验证诊断、试生成来源和历史，并覆盖loading/empty/403/409/503/request_id。
- 数据/状态：规则identity、不可变revision、mapping、validation/trial attempt、receipt/event；`DRAFT -> PUBLISHED -> REVIEW_REQUIRED/DISABLED`。发布版不原地编辑；模板字段变化只把依赖规则标为待复核，不自动迁移。
- 权限：READ/EDIT/VALIDATE/TRIAL/PUBLISH/DISABLE分别核组织、模板、Schema、来源资源、动作和用途；正文或证据内容在读取与事务提交前再次核权。Agent/模型不得发布规则。
- 失败/恢复：未知路径、类型不符、未授权来源、无真实问答、未确认金额/法律引用、必填缺失、模板漂移、CAS/幂等冲突均失败关闭。业务DB、规则快照、trial artifact、回执/事件共同恢复；现时Grant、模板/Schema水位不从旧备份回灌。
- 禁止：不得用模型补造主体、金额、日期、法条、调查问答或签名；项目Schema和规则候选不冒中心字段字典或正式发布规则。

## 5. 输入与机器证据

- ENF-12：`data/enforcement/document-template-v2-d-input-v1`。项目原创DOCX SHA-256 `5656160c4173f2888ea79cece7b79786904ff55999108b5b1a0e252b7b40a2fd`；manifest SHA-256 `313c12443d3b7cfddfc4f3d2687737883df6ff983b790d56b4733c1360b43b48`。含唯一占位符、中文长文本、重复表格、页眉和禁止正式用途声明。
- ENF-17：`data/enforcement/legal-reference-v2-d-input-v1/manifest.json` SHA-256 `02e983fca17786074fc45cf46aca90f7988c929e0c132401ace3a07bb835491c`；逐SHA引用CNIPA三个`REVIEW_REQUIRED`官方快照，目标为项目原创Conduct报告锚点，法律适用保持`UNCONFIRMED`。
- ENF-13：`data/enforcement/document-rule-v2-d-input-v1/manifest.json` SHA-256 `963c613bc3ae1580b60fdcb04d9889d023936d9bd29e7ef81dd87e2321c7cf65`；项目Schema Registry、精确模板修订、三类生成方式、三类缺失策略及无问答/未确认金额和法律引用反例，全部为`INPUT_ONLY_NOT_PUBLISHED`。
- ENF-15/16：`data/enforcement/penalty-v2-d-input-v1/manifest.json` SHA-256 `36562aae50ac76a5aa08b1dc48d20439a7a386e3ae85784b2f5674eac9ffb165`；符号化项目公式、未复核Conduct输入、零值/缺值及冲突反例，不含真实处罚数值。
- ENF-14：`data/enforcement/document-draft-v2-d-input-v1/manifest.json` SHA-256 `c1c03c7fc2cb3fcb058c416143328ca5163b0a8779de6a12399e6b1ba7216f5d`；确切绑定ENF-12/13/17 D输入摘要，项目原创案件/字段/上游引用，状态`INPUT_ONLY_NOT_GENERATED`。
- `node scripts/verify-enforcement-followup-d-input.mjs --self-test`：4份完整输入通过，15种状态/摘要/字段/来源/禁止副作用篡改全部拒绝；不执行产品。
- `node scripts/verify-enforcement-document-draft-input.mjs --self-test`：1份完整输入通过，16种状态/摘要/依赖/页面/版本/权限/失败/恢复篡改全部拒绝；不执行产品。
- `node scripts/collect-cnipa-rules-documents.mjs --verify-only`：4份原始CNIPA快照通过，状态仍为`REVIEW_REQUIRED`。

## 6. 预注册场景与D判定

- ENF-12：T01～T06覆盖正向预览、未知/重复占位、长中文/重复区块、宏外链/异常表格、缺字体/SHA漂移、不可变历史/重放/撤权/共同恢复。
- ENF-13：R01～R06覆盖精确模板/Schema正常trial、未知路径/错类型、必填缺失、无真实问答、未确认金额/法律引用、模板变化/撤权/重放/恢复。
- ENF-17：L01～L06覆盖候选不自动确认、无匹配、历史/过渡未知、部分/源失败、SHA/锚点漂移、更新/解绑/撤权/重放/恢复。
- ENF-15/16：PEN01～PEN06覆盖确定性试算、缺金额语义拒发布、缺值与合法零、程序/事实缺口、多规则/多方案、越界调整/撤权/恢复。
- ENF-14：D01～D09覆盖无写入prepare、不可变输入生成、缺项不完整稿、人工修订后候选重生成/显式采用、逐版本审核、同版DOCX导出、按类型选用ENF-11/16、撤权/冲突及DB+文件恢复。

## 6.1 ENF-14 冻结合同

- 输入/输出：固定案件/材料、模板、映射规则、法律引用及按文书类型声明的ENF-11/16确切版本；prepare只返回适用性、已解析版本、缺项和未处理项，不写文书。generate输出不可变输入manifest绑定的结构草稿、字段/段落来源、内容摘要和生成任务状态。
- 修订与审核：人工修订生成新版本；重生成只产候选差异，必须显式选择并采用后才产生新修订。审核绑定确切文书修订，新修订不得继承旧审核；Agent不得审核。
- 导出：DOCX导出绑定同一已审核文书修订、内容摘要、模板/规则版和渲染器版。渲染失败保留结构草稿，不产生空成功artifact。
- 依赖：事实记录不需要ENF-11/16；行为复核稿需要已审核ENF-11；处罚输入复核稿需要已审核ENF-11与ENF-16。未使用依赖不阻塞，声明需要但缺失/未审核时失败关闭。
- 权限/恢复：PREPARE/CREATE/READ/EDIT/REGENERATE/ADOPT/SUBMIT_REVIEW/REVIEW/EXPORT分离核权；业务DB、结构草稿、DOCX、回执/事件共同恢复，备份外现时Grant、来源和上游状态/水位不回灌。
- 禁止：不生成正式文号、签名、签章、签发、送达或正式处罚决定；项目输入和技术审核不等正式业务批准。

Codex具名技术复核结论：上述输入、输出、UI、API、数据、状态、权限、失败、恢复和禁止行为已达到产品D可实施粒度；现实业务事实已明确分离且不需通过伪造来关闭产品D。因此`ARC-ENF-DOCUMENT-TEMPLATE-06`、`ARC-ENF-DOCUMENT-RULE-07`、`ARC-ENF-DOCUMENT-DRAFT-08`、`ARC-ENF-LEGAL-REFERENCE-08`和`ARC-ENF-PENALTY-09`在本文范围通过。ENF-12与ENF-17允许启动各自IMP；ENF-13 IMP等待ENF-12实现，ENF-15/16 IMP等待ENF-17实现，ENF-14 IMP等待ENF-12/13/17实现及按文书类型需要的ENF-11/16。
