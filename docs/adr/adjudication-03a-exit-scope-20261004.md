# ADR：03A冻结退出范围正式复核

日期：2026-10-04；复核责任：本轮执行代理Codex（技术范围核查，不是第三方/专业业务验收）。来源：负责人要求按“验收归属→适用隔离验证→既有证据不重做→03A退出后03B设计”顺序执行。状态只见docs/08。

## 原冻结合同，没有降低MUST

IMP-ADJ-V2-TASK-03A原退出列要求真实模型调用、原文定位校验、前后授权/来源检查、失败不覆盖、DM8重启一致。现行材料整理ADR§1、§9、§10要求真实获准输入、持久总预算、30天/密钥分离、旁表物理恢复、可信HTTP同案与适用异常、01/02及CON/ENF受影响回归。负责人明确“保留回归、不把单项通过称全仓通过”，并没有要求裁决完成每一个执法自有产品场景才退出。

过去把API模块全量尝试的20个ENF失败一概当03A前置，是测试运行范围扩大成业务依赖的过度推断。此次正式纠正依赖归属；不是降低上述要求、修改测试为skip或把失败说成通过。现20项的未执行/环境阻断及原XML继续保留，不能替代或取消执法自身验收。

## 实际调用图与源证据

03A本地ControllerAdvice只针对MaterialOrganizationController，独立ProductAdjudicationApplication只扫描CaseWorkConfiguration所在裁决包并显式Import共享Identity接线，不扫描CON/ENF业务。48个裁决主源文件全部与真实HTTP部署构建SHA一致；当前ADJ与共享Product源184文件与已实际受影响回归快照一致。5个其他会话更新的CON模型恢复/备份类不被ADJ接线引用，未归因于裁决，也不冒其回归已证明。

13个实际Identity/Grant/Source接线类与运行部署构建确切SHA一致。20个失败suite源码不引用ADJ，6个只执行ENF数据库Store/迁移，14个只对账ENF rule/attempt/receipt/artifact及当前产品场景；都不执行03A模型、引用validator、CaseSourceAuthority、vault、budget/run或新增身份维护控制器。相关共享Identity兼容的必须项另由精确接线当前179项与实际ADJ mTLS/PKCE/撤权/撤源反例证明，而不是由包名无错误推导。

## 按冻结要求具名验收证据

| 冻结要求 | 实际证据 | 限定范围 |
| --- | --- | --- |
| 获准单案真实模型/引用定位 | 第2次正式HTTP、16候选、服务器原文定位；actual-http-model-run.json及material-model-http.json | 项目自有去敏获准替代材料，不是真实案件价值或专业A |
| 当前独立主体/精确逐案授权 | 正常mTLS注册/激活/Grant、PKCE；subject-maintenance-http.json与actual-runtime-after-negatives.json | 委托交互账户、不赋HUMAN_REVIEW、不认证现实人员资质 |
| API同案保存/新run恢复 | actual-http-negatives-summary.json真实服务重启完整view SHA一致 | 新run及DM8/API读回，不冒原生包内含第二次run |
| 总预算不可重置/并发/重复/失败 | 同key同run无新外呼、原approval/policy/2次账本一致；MaterialRunStoreTest当前15项 | 真实顺序负例与机械并发/失败组件分开，不声称外呼中撤权实测 |
| 来源变化/撤回/版本冲突/权限异常 | 实际403/404/409/401、v2无MODEL、旧run stale、原密文不覆盖 | 最终旧案撤源403保持，未恢复来源 |
| 30天/加密/固定期限/物理恢复 | native-recovery-02原生旁表独立导入、缺钥/损坏/到期拒读、源不回灌及清理 | 只第1次组件run；新run有API重启证据，冻结合同无逐回合重复原生要求 |
| 01/02与改变接线受影响回归 | 当前1193源SHA绑定23套129项 + 7套50项，0失败/错误/跳过；13接线SHA核查 | H2/内存/本地HTTPS机械兼容，不冒全域产品I/全仓通过 |
| 治理/基线/不改其他域 | 前后verify-all、47文件41契约一致；全部非03A/03B任务行边界SHA | 结构检查不证明专业/法律正确性 |

证据入口：docs/evidence/adjudication-llm-materials-20261004/exit-contract-02；原始实际记录仍在subject-initialization-01、native-recovery-02和exit-scope-01，不复制/改造原先模型输出或数据库原件。

## 复核结论

上述限定03A技术实现与验证已满足。20个ENF自有项不成为03A的新业务前置，原全量尝试仍失败；不是当前部署可用性声明（当前Docker daemon不可用，未启动服务）。03A完成只表达当前限定后端候选生成/持久恢复能力技术退出，不等同整个03、人工采用、页面、真实案件价值或专业验收完成。

适用隔离补证检查没有剩余未过的03A MUST；六项ENF新隔离测试仍NOT_RUN/ENVIRONMENT_BLOCKED，十四项ENF产品上下文仍未获得，原记录保持。Docker启动可能影响其他域，故本轮不启动它、不连接事故库、不做自动续行。后继03B必须另审当前详细D并保持撤源案件不可采用；不能因为03A退出而复活历史来源/候选。
