# 专业对照 prepare / 显式特征确认限定纵切实证

日期：2026-10-05。所属任务：IMP-CON-PRODUCT-COMPARE-01 / PC-PREPARE-FEATURE-CONFIRM-01。
依据：docs/adr/consultation-product-comparison-20261002.md 与 docs/adr/consultation-prepare-feature-20261005.md。状态仅在 docs/08 控制，本文件是实际执行证据，不是并行状态台账。

## 实际交付

- 正式 prepare、features/confirm、features/{revisionId} 路由及正式配置注入，无开发 profile 或旁路。目标只能显式用户 messageId 或精确专利版本；不从检索 query 或模型总结造目标。prepare 不自动确认、无模型调用时 featureSuggestions 为空。
- 当前 Task/Goal/Input 精确绑定、逐字段 DISPLAY + COMPARISON 和两 Grant 重核；直接文献与持久选择引用读取适配，原件/版本/UTF8跨度及权项分支保持。历史材料读取使用现时权限，不复活旧 Grant。
- V094/U094 六表、不可变 FeatureRevision/父版/稳定特征、Task 锁 + 前指针 CAS。特征/版本/事件及幂等回执同事务，最后权限复核失败整笔回滚。无运行库迁移。
- 准备 native documentRows、命令、Task 版、Goal/Input/message、保留策略、期限、materialSha/snapshotSha 交叉核验；版本读取重核行数/包摘要/事件闭包和原件。仅保存第三方引用/哈希/权项关系，不保存第三方全文或原文片段；用户说明单独保存。
- 专用严格 JSON 拒绝 null、重复键、未知字段、尾随对象、标量强转等；目的冻结、父环/父缺失、越界、预算超限、目标权项缺口均默认拒绝。

## 执行与原件

1. v863 前置 verify-all 退出 1：需求 47 文件/41 契约未变化；当前报告 MISSING_PHASE / MISSING_ACCEPTANCE:IMP-ADJ-V2-TASK-03B 与 TEST_EVIDENCE_STALE。未修改裁决记录来绕过。
2. v864 冻结输入缺 consultation/patentcomparison 目录，复制失败导致编译输入不完整；已创建确切目录并补覆盖，失败日志保留，不称有效测试。
3. v865 编译通过，12 个测试均在 setup 因未加载 V039 来源核验表失败；补真实迁移而非删水位约束。v866 另有测试将 /claimsText 定位指针误判为保存原文，以及到期期限设置违反 expires_at>created_at；只修测试断言/期限验证方式，未降低数据库约束。v867 修后成功。
4. v868/v870 新增实际采集适配测试，分别暴露测试来源版及文献标识不符合真实 manifest；改为 CAPTURE_SHA256:manifestSHA 和 US_GRANT:3931897，未改来源数据或产品接口。原失败日志保留。
5. v871 Maven 实际退出 0：9 套 58 次执行，0 失败/错误/跳过。覆盖准备/确认/读取、幂等/异载荷、前版 CAS、权限漂移/撤权、提交末尾权限失败/缺事件表回滚、原件/native projection/feature package 篡改、正逆迁移，以及旧精确签发、目标读取、原件/权项/矩阵/用途读取兼容。BoundaryTest 继承重复 12 项基础测试，58 不代表 58 个独立场景。源码与冻结 SHA、迁移 SHA、九套 XML 见 comparison-v871-build-inputs.json 和 comparison-v871-xml/。
6. v872 只新增预算与目标权项缺口反例，专项实际退出 0：18 次执行，0 失败/错误/跳过，含继承/既有 16 次。未重跑整仓或声称 76 个独立场景；最后测试源 SHA、XML 见 comparison-v872-build-inputs.json 和 comparison-v872-xml/。产品代码未在 v871 后改变，兼容结果复用。
7. v869/v874 独立结构检查实际退出 0，警告保留。v873 后置 verify-all 实际退出 1：47/41 一致，MISSING_PHASE / MISSING_ACCEPTANCE:IMP-ADJ-V2-TASK-03B 与 TEST_EVIDENCE_STALE，原件见 comparison-v873-post-verify.log；不以结构或专项代全仓绿色。补记这项结果后的治理复查另存 comparison-v875-final-verify.log。

真实输入为已采集 PatentsView v3 全 manifest/原件，直接适配测试重新完整读取来源证据并定位 US_GRANT:3931897 权项；既有 40 篇/36 完整闭包/4 缺口证据复用。H2/主体/Source 与授权端口仅测试目录机械绑定，不冒真实主体或本轮来源 PRODUCT_ENABLED。正式 Controller 的 MockMvc C 调用真实业务服务/事务，不冒真实 Issuer/JWKS/HTTP 部署 I。

## 未越过的边界

本有限切片代码及机械 C 已收口；不将其称最终可操作专业对照或父任务完成。Docker 实例环境不可用的既有证据复用，本轮不重复探测、不改运行库、不恢复旧来源/Worker、不付费调用模型、不创建自动调度。

V094 真实 DM8 迁移/事务/恢复、有效正式维护/用户身份与现时来源授权下真实 HTTP、真人明确特征确认、专业效果和人工成本仍未验证。ComparisonInput、矩阵 Worker 提交/持久化、对照解释与工作面不是本切片交付；依原主链按对应限定门禁另行推进，不能以本轮 C 自动解锁人工/专业验收。
