# 执法 MVP 用例与服务设计

版本：V0.1  
日期：2026-09-15  
关联任务：`ARC-05-ENF-UC`  
设计输入：`docs/20-执法MVP-V0产品样例基线.md`、`docs/21-执法MVP最小领域模型.md`、`docs/22-执法MVP领域行为与不变量.md`、`ENF-04/05/07/11`。  
设计性质：合成 V0 的应用用例与端口责任设计；不冻结 HTTP 路径/DTO、数据库表、事务实现、异步 Task/Workflow、具体身份协议、规则 DSL 或模型供应商。

## 1. 应用层责任

执法应用层负责编排当前授权检查、权威来源读取、固定输入版本、领域行为、结果持久化和异常分类。领域对象只维护执法工作语义；适配器负责中心 API、文件、规则、模型和 DM8 技术细节。

应用层不得：

- 接受请求正文中的人员名称、机构、来源、审核结论或权限布尔值作为可信事实。
- 让 Agent、模型或外部适配器直接写 `HumanReviewRecord`、权限、正式决定或中心源记录。
- 将技术执行成功映射成证据采信、侵权成立、立案、处罚或正式状态。
- 因中心能力暂缺而构造真实接口结果；V0 只使用显式合成适配器并保留限制标识。

## 2. 调用上下文与通用输入规则

每项写用例从可信运行上下文取得 `actorRef`、`organizationRef` 和 `requestId`，从请求取得业务输入和幂等键。调用方不得在业务请求中覆盖可信身份。

通用规则：

1. 所有资源引用使用字符串稳定标识和明确版本；显示编号或名称不能替代唯一标识。
2. 相同主体、用例、幂等键和规范化完整输入返回同一结果；同键不同输入返回 `IDEMPOTENCY_CONFLICT`。
3. 更新/人工动作提交预期对象版本；不一致返回 `VERSION_CONFLICT`，不覆盖当前记录。
4. 读取正文、历史或来源定位前重新执行当前授权；历史允许记录只用于审计，不能放行。
5. 失败返回稳定分类、可读原因、`requestId` 和是否可重试；不得在错误中返回敏感正文。

## 3. 用例一：OpenEnforcementWork（开始执法工作）

| 项目 | 设计 |
|---|---|
| 输入 | `clueSourceId`、`clueId`、`clueVersion` 或获准摘要引用、可选 `caseRef/rightRefs`、`idempotencyKey`；可信身份来自上下文。 |
| 编排 | 调用授权端口检查当前线索访问；调用线索来源端口核对稳定标识、版本和关联；对可选案件/权利引用分别检查；调用领域工厂创建 `EnforcementWorkItem`；通过仓储原子保存幂等绑定和工作项。 |
| 输出 | `workItemId`、固定线索引用、已接纳的可选引用、创建时间、范围标识 `SYNTHETIC_V0`。不返回未经授权正文。 |
| 领域对象 | `EnforcementWorkItem`。 |
| 端口 | `EnforcementAuthorizationPort`、`ClueSourcePort`、可选 `CaseOrRightSourcePort`、`EnforcementStore`、`IdGenerator`、`Clock`。 |
| 异常 | `UNAUTHORIZED`、`SOURCE_NOT_FOUND`、`SOURCE_UNAVAILABLE`、`SOURCE_VERSION_CONFLICT`、`UPSTREAM_FAILURE`、`IDEMPOTENCY_CONFLICT`。 |
| 禁止 | 不创建中心案件、不回写线索、不自动立案、不启动线索发现。 |

## 4. 用例二：GenerateClueAnalysis（生成线索解析版本）

| 项目 | 设计 |
|---|---|
| 输入 | `workItemId`、预期线索引用、`idempotencyKey`；V0 不接受客户端传入来源字段、主体核验结论或优先级规则。 |
| 编排 | 加载工作项并检查所有权；重新授权读取固定线索版本；从线索端口取得授权内容及定位；调用 `ClueAnalysisPort` 产生候选；由领域行为验证来源、角色、空值、冲突和禁止字段；追加不可变解析版本。 |
| 输出 | 解析版本标识；主体候选及原文角色、疑似事实、时间/地点原值与可选标准化值、证据提及、缺失/冲突、实际输入和机器/测试替身引用；V0 优先级默认待评估。 |
| 领域对象 | `EnforcementWorkItem`、`ClueAnalysisVersion`。 |
| 端口 | `EnforcementAuthorizationPort`、`ClueSourcePort`、`ClueAnalysisPort`、`EnforcementStore`、`Clock`。 |
| 异常 | `UNAUTHORIZED`、`SOURCE_UNAVAILABLE`、`PARTIAL_RESULT`、`ANALYSIS_FAILURE`、`VERSION_CONFLICT`、`IDEMPOTENCY_CONFLICT`。 |
| 禁止 | 不生成来源、信用代码或缺失事实；不认定违法主体；部分失败不伪装完整成功。 |

`ClueAnalysisPort` 只能返回候选内容和每项来源定位。应用层必须拒绝没有来源的非空业务字段；使用合成适配器时输出必须携带 `SYNTHETIC_V0`。

## 5. 用例三：ReviseOrReviewClueAnalysis（修订/核实解析）

| 项目 | 设计 |
|---|---|
| 输入 | `workItemId`、`analysisId`、`expectedVersion`、动作 `REVISE/CONFIRM/RETURN`、修订项或理由、`idempotencyKey`。 |
| 编排 | 从可信上下文取得身份；检查工作项及解析版本当前访问权限；验证预期版本；`REVISE` 创建新解析版本；`CONFIRM/RETURN` 追加指向确切版本的审核记录。 |
| 输出 | 新解析版本或审核记录标识、被操作版本、动作、时间和 `scopeLabel`。 |
| 领域对象 | `ClueAnalysisVersion`、`HumanReviewRecord`。 |
| 端口 | `EnforcementAuthorizationPort`、`EnforcementStore`、`Clock`。 |
| 异常 | `UNAUTHORIZED`、`NOT_FOUND`、`VERSION_CONFLICT`、`INVALID_ACTION`、`REASON_REQUIRED`、`IDEMPOTENCY_CONFLICT`。 |
| 禁止 | 不信任请求传入的审核人；不覆盖机器版本；测试确认不写专业或正式状态。 |

## 6. 用例四：RegisterEvidenceReference（登记/刷新证据引用）

| 项目 | 设计 |
|---|---|
| 输入 | `workItemId`、来源类型 `CENTER_REFERENCE/AUTHORIZED_UPLOAD`、权威证据标识和版本或受控上传引用、关联线索/案件、`idempotencyKey`。收集信息只接受权威元数据或可信上传上下文。 |
| 编排 | 校验工作项和关联资源；执行当前权限；从证据端口取得来源、版本、受控资源和可用性；有文件字节时由完整性端口计算摘要；领域行为创建引用或派生版本；仓储保存。 |
| 输出 | 本地证据引用标识、权威来源版本、关联、受控资源标识、收集信息/缺失原因、完整性信息、派生关系和可用性。 |
| 领域对象 | `EvidenceReference`。 |
| 端口 | `EnforcementAuthorizationPort`、`EvidenceSourcePort`、可选 `FileIntegrityPort`、`EnforcementStore`、`Clock`。 |
| 异常 | `UNAUTHORIZED`、`RELATION_NOT_FOUND`、`SOURCE_UNAVAILABLE`、`UNSUPPORTED_CONTENT`、`PARTIAL_RESULT`、`UPSTREAM_FAILURE`。 |
| 禁止 | 不暴露磁盘路径；不把上传人当收集人；不自动合并相同摘要；不确认真实性/合法性/证明力。 |

刷新操作只更新当前可用性事实或形成新的来源版本关系；不得改写已经被历史关系集/分析引用的证据版本。

## 7. 用例五：BuildEvidenceRelations（形成证据关系集）

| 项目 | 设计 |
|---|---|
| 输入 | `workItemId`、`clueAnalysisVersion`、`evidenceVersionRefs[]`、`ruleVersionRef`、`idempotencyKey`。 |
| 编排 | 加载并校验所有输入属于工作项；逐资源重新授权；从规则端口取得固定且允许使用的规则/合成清单；调用 `EvidenceRelationAnalysisPort` 产生候选；领域行为验证每条关系定位、冲突并存、时间语义和未处理项；追加关系集版本。 |
| 输出 | 关系集版本、固定输入清单、待证明事项、支持/反对/冲突/缺失关系、时间线、缺口、未处理项、完整性分类和机器/测试替身引用。 |
| 领域对象 | `EvidenceReference`、`EvidenceRelationSetVersion`。 |
| 端口 | `EnforcementAuthorizationPort`、`RuleSourcePort`、`EvidenceRelationAnalysisPort`、`EnforcementStore`、`Clock`。 |
| 异常 | `UNAUTHORIZED`、`RULE_NOT_APPLICABLE`、`SOURCE_UNAVAILABLE`、`UNSUPPORTED_CONTENT`、`PARTIAL_RESULT`、`VERSION_CONFLICT`。 |
| 禁止 | 不补造事项或事实；不静默选择冲突证据；不输出“完整有效证据链”或自动采信。 |

## 8. 用例六：GenerateAuxiliaryAnalysis（生成辅助分析版本）

| 项目 | 设计 |
|---|---|
| 输入 | `workItemId`、固定 `clueAnalysisVersion`、`relationSetVersion`、`evidenceVersionRefs[]`、`ruleVersionRef`、可选权利状态版本、`idempotencyKey`。 |
| 编排 | 校验对象关系和当前权限；从规则/权利端口获取确切版本及历史时点信息；历史状态缺失时记录缺口；调用 `AuxiliaryAnalysisPort` 产生候选；领域行为检查事实/陈述/推断分离、逐项来源、不确定性和受限结果分类；追加分析版本。 |
| 输出 | 辅助分析版本、输入清单、逐项事实/陈述/推断、支持/排除依据、缺口、不确定性、受限结果分类、未处理项、需复核原因和 `notOfficialDecision=true`。 |
| 领域对象 | `AuxiliaryAnalysisVersion` 及其固定输入引用。 |
| 端口 | `EnforcementAuthorizationPort`、`RuleSourcePort`、`CaseOrRightSourcePort`、`AuxiliaryAnalysisPort`、`EnforcementStore`、`Clock`。 |
| 异常 | `UNAUTHORIZED`、`RULE_NOT_APPLICABLE`、`HISTORICAL_STATUS_MISSING`、`MATERIALS_INSUFFICIENT`、`SOURCE_UNAVAILABLE`、`PARTIAL_RESULT`、`ANALYSIS_FAILURE`。 |
| 禁止 | 不自动立案/认定/处罚；不选择正式程序；不把当前权利状态替代历史；不把失败解释为不成立。 |

V0 合成适配器不得输出严重程度、处罚、正式程序或专业法律结论。即使输入字段齐全，合成规则也只能证明流程和约束实现可测试。

## 9. 用例七：ReviewAuxiliaryAnalysis（审核辅助分析）

| 项目 | 设计 |
|---|---|
| 输入 | `workItemId`、`analysisResultId`、`expectedVersion`、动作 `REVISE/SUBMIT/CONFIRM/RETURN`、修订项或理由、`idempotencyKey`。 |
| 编排 | 取得可信身份并校验当前权限；确认审核对象确切版本；修订时创建新分析版本，其他动作追加审核记录；测试身份强制写入 V0 限制标识。 |
| 输出 | 新分析版本或审核记录、被审核版本、动作、理由、可信人员引用、时间、`notOfficialDecision=true`。 |
| 领域对象 | `AuxiliaryAnalysisVersion`、`HumanReviewRecord`。 |
| 端口 | `EnforcementAuthorizationPort`、`EnforcementStore`、`Clock`。 |
| 异常 | `UNAUTHORIZED`、`NOT_FOUND`、`VERSION_CONFLICT`、`INVALID_ACTION`、`REASON_REQUIRED`、`IDEMPOTENCY_CONFLICT`。 |
| 禁止 | Agent/模型不能作为人工身份；确认不推进中心状态、不产生处罚或专业采信。 |

## 10. 用例八：GetEnforcementWorkView（读取工作项、历史和来源）

| 项目 | 设计 |
|---|---|
| 输入 | `workItemId`；可选解析、证据、关系集、分析或审核记录的确切版本；来源打开请求包含本地来源引用标识而非客户端拼接路径。 |
| 编排 | 校验当前工作项权限；按请求版本从仓储读取本地最小记录；需要正文/文件/来源定位时逐资源调用授权端口和来源端口；来源失效或失权时隐藏正文并返回明确可用性。 |
| 输出 | 工作项快照、可访问的版本历史、缺口/冲突/需复核原因、审核记录；来源打开返回受控内容或 `UNAUTHORIZED/SOURCE_UNAVAILABLE`，不返回磁盘路径。 |
| 领域对象 | 六个对象的只读投影。 |
| 端口 | `EnforcementAuthorizationPort`、`EnforcementStore`、`ClueSourcePort`、`EvidenceSourcePort`、`CaseOrRightSourcePort`。 |
| 异常 | `UNAUTHORIZED`、`NOT_FOUND`、`SOURCE_UNAVAILABLE`、`UPSTREAM_FAILURE`；本地记录可见与来源正文可见分别判断。 |
| 禁止 | 不因历史曾授权而放行；不返回已失权正文、临时磁盘路径或模型隐含推理。 |

## 11. 输出端口责任

| 端口 | 业务层所需能力 | 适配器不得做的决定 |
|---|---|---|
| `EnforcementAuthorizationPort` | 按可信主体、机构、动作和资源返回当前允许/拒绝及最小追踪标识 | 不定义执法业务角色，不保存为永久授权凭证 |
| `ClueSourcePort` | 读取获授权线索固定版本、原文和定位，区分不存在/失效/失败 | 不创建本地解析或正式案件状态 |
| `EvidenceSourcePort` | 读取证据固定版本、来源元数据、受控资源和可用性 | 不判定真实性、合法性、证明力或采信 |
| `CaseOrRightSourcePort` | 读取案件/权利固定版本及历史时点状态 | 不把当前状态替代历史，不作正式决定 |
| `RuleSourcePort` | 返回已发布且允许使用的规则版本或显式合成清单 | 不由模型生成规则，不把合成清单称为中心规则 |
| `ClueAnalysisPort` | 产生带逐字段来源的解析候选 | 不授予权限、不确认违法主体或正式优先级 |
| `EvidenceRelationAnalysisPort` | 产生带定位的事项—证据候选关系、时间线、缺口和冲突 | 不采信证据、不删除冲突、不宣称完整证据链 |
| `AuxiliaryAnalysisPort` | 产生逐项候选分析、来源、不确定性和受限分类 | 不立案、认定、选择正式程序、处罚或写正式状态 |
| `FileIntegrityPort` | 对实际获得的文件字节计算完整性摘要 | 不证明真实性/合法性，不暴露存储路径 |
| `EnforcementStore` | 原子追加对象版本、审核记录和幂等绑定；按确切版本读取 | 不拥有授权、规则、正式状态或中心源数据 |
| `IdGenerator/Clock` | 生成本地技术标识和服务时间 | 不生成业务事实时间或中心编号 |

端口接口属于执法应用层；DM8、中心 API、文件系统、规则引擎和模型 SDK 只能出现在适配器实现中。

## 12. 事务、幂等与版本边界

- 本任务只定义原子结果：单次写用例的对象新版本/审核记录与其幂等绑定必须同时成功或同时失败。
- 外部读取和模型/规则调用不放进长数据库事务；后续实现必须在保存前复核输入版本和授权，防止检查后变化被静默接受。
- 同一幂等键的完全相同输入返回首次结果；不同输入不重放旧结果。
- 所有人工动作使用预期版本；修订与审核并发时不允许后写覆盖先写。
- 来源、证据、规则或权利更新不会改写旧结果；通过新版本和需复核原因表达影响。
- 是否使用异步 Task、重试和补偿由 API/技术 Spike 决定；不得为了 V0 预建完整 Workflow Runtime。

## 13. 错误语义

| 分类 | 含义 | 是否可重试的决定来源 |
|---|---|---|
| `INVALID_INPUT` | 字段格式、缺失或不允许的客户端事实 | 通常否；调用方修正输入 |
| `UNAUTHORIZED` | 当前主体不能执行动作或读取资源 | 否；由授权责任方改变权限 |
| `NOT_FOUND` | 本地工作对象不存在；不得用此码泄露受限源对象 | 否 |
| `SOURCE_NOT_FOUND` | 有权查询且确认权威源对象不存在 | 否，除非中心语义另有说明 |
| `SOURCE_UNAVAILABLE/UPSTREAM_FAILURE` | 来源失效或技术调用失败 | 由适配器根据确认契约返回 |
| `RULE_NOT_APPLICABLE` | 固定规则明确不适用于输入 | 否；不是资料不足或“不构成” |
| `MATERIALS_INSUFFICIENT/HISTORICAL_STATUS_MISSING` | 输入事实不足，只能给部分结果或无法判断 | 补充材料后新请求 |
| `PARTIAL_RESULT` | 部分来源或项目处理失败，附未处理项 | 对失败项可按策略重试 |
| `VERSION_CONFLICT/IDEMPOTENCY_CONFLICT` | 并发版本或同键输入冲突 | 重新读取后由调用方决定 |
| `ANALYSIS_FAILURE` | 候选分析未产生有效结构 | 可重试性由批准模型/测试策略决定 |

## 14. 三类样例的用例序列

| 样例 | 用例序列 | 关键结果 |
|---|---|---|
| 正常 | Open → GenerateClueAnalysis → ReviseOrReviewClueAnalysis → RegisterEvidenceReference ×2 → BuildEvidenceRelations → GenerateAuxiliaryAnalysis → ReviewAuxiliaryAnalysis → GetView | 全部输入版本和来源可回查；确认只落在确切辅助版本，`notOfficialDecision=true`。 |
| 资料不足/冲突 | Open → GenerateClueAnalysis → RegisterEvidenceReference ×3 → BuildEvidenceRelations → GenerateAuxiliaryAnalysis → Review(RETURN) → GetView | 冲突并存、未知值有缺失原因、不可解析材料列出、输出部分结果/无法判断和补证事项。 |
| 无权限/来源失效 | Open 或 Register/Build/Generate 阶段授权拒绝；来源变化后 GetView/重新 Generate | 无权限不泄露；失效版本不进入新结果；旧版本保留并标记需复核。 |

## 15. 需求一致性复核

| 检查项 | 结果 |
|---|---|
| 涉及需求 | ENF-04、ENF-05、ENF-07、ENF-11 |
| 用例覆盖 | 8 项用例覆盖 `docs/22` 的 8 项行为及三类产品样例 |
| 端口责任 | 11 类输出端口均由应用层定义；外部适配器无权限、采信、人工决定或正式状态所有权 |
| 无来源业务行为 | 0；身份、来源、规则、时间和版本均标明可信来源或技术性质 |
| MUST_NOT 违规 | 0；用例不生成/改写来源、证据采信、正式认定、程序、处罚或中心状态 |
| 未进入范围 | HTTP/DTO、DM8、具体事务、异步 Task、规则 DSL、模型供应商、真实身份和正式流程 |
| 验证范围 | 文档语义和三类合成样例序列；未执行代码、真实接口、真实权限、模型效果或业务验收 |

结论：8 项应用用例和 11 类输出端口足以编排当前 V0 行为，并保持领域、授权、外部来源、机器候选和人工决定的责任分离。该结论只完成 `ARC-05-ENF-UC`；下一步可以设计最小 HTTP API，仍不得直接开始数据库或代码。
