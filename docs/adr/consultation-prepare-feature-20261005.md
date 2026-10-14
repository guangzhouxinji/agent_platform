# 专业对照prepare/明确特征确认限定物理设计

日期2026-10-05；PC-PREPARE-FEATURE-CONFIRM-01，属于IMP-CON-PRODUCT-COMPARE-01。依据已过R的CON-06、原对照D §2～6/12。Codex承担工程设计复审，不代签用途、真人确认、法律或专业质量。本文件不另建状态台账。

## 合同与授权

正式路径`/v2/consultation/tasks/{taskId}/patent-comparisons/prepare`，`/features/confirm`，`/features/{revisionId}`。prepare/读取Task READ，确认WRITE且原文READ仍需满足；每份专利CLAIMS同时核DISPLAY与COMPARISON和精确来源Scope Grant，不借Task READ。来源只读取已实现原JSON权利要求，精确source/document/version，直接文献引用不强迫检索；有selection引用则核resultSet/selectionRevision/member与原件绑定。user message与patent目标必须且只能一种。Query与模型摘要不能成为目标原件。只文本TECHNICAL_SIMILARITY/CLAIM_ALIGNMENT；其他目的返回CAPABILITY_UNAVAILABLE或PURPOSE_MATERIALS_REQUIRED，绝不悄悄替换目的。无入模批准/模型执行时featureSuggestions为空，不编造建议，也不因此自动确认。

prepare精确绑定当前Task/goalId+revision/inputId、原件SHA/字段/UTF8证据、所选权项DAG/择一分支、每候选版本、当前来源和授权版本。权项失败逐文献保存稳定缺口码，不造完整分支；目标缺口禁止确认，候选缺口保留供后续独立处理。预算沿原D：20候选、200特征、4000矩阵行；不丢尾。所有材料/权限/Task/原件在响应前重读。期限只取本次Task、message/selection、逐字段批准、Source契约/决议、Scope及Grant的最早截止，不取全库最早到期。原文仅授权响应，准备持久化存引用/哈希/范围/权项关系而不复制第三方全文；没有SAVE不会擅存CLAIMS。

确认必须显式confirmed=true、expectedTaskVersion、preparationId/prepareSha、前FeatureRevision序号、Idempotency-Key及动态特征。每特征稳定ID、原文字符跨度、用户原话/说明、可选父特征；服务器重新定位原件字节/SHA和目标权项上下文，不信任客户端文本/偏移摘要。父特征需存在且无环。保留用户原话为输入，不冒专利原文，不准模型建议自动确认为用户。当前Task锁内再次核目标/来源/prepareSha、前指针CAS，同事务写不可变版本/特征/审计/回执及current指针，任何失败全回滚。同键同载荷且仍有现时访问权重放同回执，无新增版本；异载荷/旧准备/撤权/过期/Task漂移拒绝。新FeatureRevision不改已确认Goal或SearchInput、不造Worker token；将来ComparisonInput须重新固定FeatureRevision并独立实施旧分析失效合同。

历史行不可变。授权读取复核行数/包摘要，重读相同原件；权威授权使用当前状态而非旧快照，允许当前授权代数推进但不得换文献/原件/权项版。不可访问不删除历史。读取不代签采用/专业对应。无真人操作证据时仅机械确认命令C，NOT_OBSERVED边界不变。

## 物理与事务（实施前复审通过的限定D）

当前扫描最大V092，避开裁决拟占V093，采用V094/U094。只新增con5命名空间，不改共享身份、Source或原表/约束。con5_preparation保存命令JSON及不含原文的canonical snapshot、prepareSha/materialSha、Task/组织/Goal/Input/FK、可空目标message FK、原Task保留ref/version/evidenceSHA、原准备主体和期限。con5_prepared_document每target/candidate单独保存契约/决议FK、文献/原件/定位、权项分支metadata、历史两Grant FK与各代数/水位/截止。Grant FK只是历史关联，不是现时权限。

con5_feature_revision不可变序号、同Task父修订FK、同Task preparation FK、prepare/target/packageSHA、用户确认原话JSON、确认主体FK/时刻/期限。唯一(task,revisionNumber)及(task,issuer,subject,requestId)；requestSha与revisionId/packageSha即持久确认回执，不再另存可漂移回执副本。con5_feature保存每稳定ID/顺序、原文引用版、UTF8跨度/摘要、字符跨度、实际权项号、同修订父特征FK及用户原话JSON，不保存第三方原文片段。con5_feature_current同Task revision FK及序号CAS；con5_feature_event一修订一确认事件FK/摘要/主体/时刻。Task的现时版本是可变表，历史版本事实存准备/修订，不能用FK假装当前Task版本永不变。

JSON固定canonical排序键、UTF8、SHA256，数组用明确稳定顺序；包摘要涵盖确认主体/时刻、父版、prepareSha、每行原件/跨度/原话及顺序，读取重核。准备摘要含历史权限，但materialSha只含原件及权项/目标关系，历史读取可用新现时授权重读同材料；确认仍要求完整prepareSha一致。恢复FK/唯一、父版/行数量/包摘要/原件和现时权限须闭合，数据库结构C不冒完整物理恢复I。无运行库迁移/清理；U094第一句空表CHECK校验preparation，已有准备/修订时拒绝再执行DROP，不删历史来回退。无资料引用SAVE许可不会存Source正文；已有Task保留策略是唯一期限依据。

两遍反演：正向核最终合同/两目标/用途/定位/确认/CAS/事务/回执；逆向核跨Task/混目标/旧SHA/源撤权/回放/半字符/父环/丢行/缺表整笔回滚/非空回退。上述字段/约束/事务足以释放本有限切片实施；Source法律权利、DM8/当前维护证书HTTP、浏览器、专业效果/人工成本仍分别取证。此D不重开原Task/Source D，不放行ComparisonInput/矩阵/Worker及其他域。
