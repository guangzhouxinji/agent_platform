# 2026-10-06 执行性缺口与纵向合同实证

状态唯一见docs/08。此文件是执行证据索引，不是新计划或状态表。

## 依赖修复

源码scripts/lib/plan-dependencies.mjs及其测试：严格完整ID、显式默认未满足Gate、表格列数/结束符/重复行检查；生成器、验证器及当前简报同步。前置唯一形成硬图，后续列仅导航。185行依赖表达归一，原表达保留在行内备注及dependency-normalization.json；23行列数/内部竖线修复原件见table-format-repairs.json。未知抽象前置未批准，仅变为明确未满足Gate。

27项反例和当前全表测试通过，dependencies-tests-first.log；桌面移动看板通过，dependency-dashboard.log。前后verify-all均实际1，47/41基线一致；依赖/门禁/图审计通过后仅既有TESTS_FAILING。该红灯不改写。

四项原进行中任务阶段门未满足，现在派生WAITING而不再RUNNING；人工状态/历史完成日期不改。不会根据IN_PROGRESS旁路门禁。

## 后继合同评测

先固定已有三个切片的Goal/白名单/缺口/完成与人工门、撤权、预算和重放反例。组件测试端口只证明Agent执行合同，不算真实业务Golden或专业验收。具体命令和结果在执行后追加。

三个独立TaskContractTest各10项，连同AgentRuntimeTest定向Maven退出0（agent-contract-tests.log，报告后缀AGENT_CONTRACT_20261006）。仅测试端口/内存CAS合同，不是专业或真实数据Golden。治理原件contracts-verify-after.log退出1，仅既有TESTS_FAILING；47/41基线无漂移。

## 咨询解释接线

真实Dialogue的EXPLANATION合同禁止messageText，而Agent draft传原始任务文本。explanation-red.log：6项，2失败/1错误，均在预期后继前被400截断；修复调用方传null，领域校验不放宽。explanation-green.log退出0。

新增7项组件测试：真实Dialogue+SQL事务/留存、当前结果绑定、失权、版本、模型非法引用留失败及同键重放；再穿透真实ConsultationAgent/Runtime/加密SQL Checkpoint，每步重新创建适配器，完成检索→来源→回答→Evaluate→STOP。首次SQL扩展测试因测试回调未处理创建前null checkpoint失败，原件explanation-sql.log保留；修复夹具后explanation-sql-fixed.log退出0，关联Runtime、三域合同、Checkpoint、解释输出和恢复回归全部通过。外部检索/身份/模型仍用测试端口，绝不宣称真实HTTP、DM8或专业验收。后置explanation-sql-verify-after.log退出1；聚合旧/带后缀XML还包含本次刻意红测和已修复夹具原件，不能当一次新全量。

## 工作台失权恢复

workspace-red.log：新增401/403反例均失败（旧不确定提示残留）；修复明确失权时清除uncertain，不改授权、不新增自动执行。workspace-green-full.log：21套81项通过，workspace-build.log构建退出0。jsdom既有伪元素提示保留；这是组件而非浏览器E2E。workspace-verify-after.log仍因聚合历史测试红灯退出1。

## 执法确切案件绑定

原Agent只比较case ID，未绑定文书caseInput.revision与程序caseVersion，允许同案错版进入程序检查；空document还会NullPointer。新增联合Input构造校验供HTTP反序列化/Checkpoint恢复共用。enforcement-binding-red.log：6项中5失败；修复后vertical-green.log：8套88项全部通过，其中Runtime30、三域合同各10、执法绑定6、咨询SQL链7、Checkpoint10、裁决真实HTTPS/JWT/SQL恢复5。没有DM8或真实专业验收的新结论。旧错版Checkpoint不可继续，历史数据不改。enforcement-binding-verify-after.log仍历史XML健康红灯。

## 独立验证出口

7项verification-stages测试通过，independent-checks-tests.log。首次接线因换行格式替换未覆盖产生语法错误（independent-verify-after.log），已修复并保留原件。independent-verify-fixed.log：verify-all实际运行全部独立边界检查，末项裁决v2自测也执行；唯一延后测试健康阶段仍失败，总退出1。不忽略基线/依赖/结构错误，不删除历史XML、不把新组件通过当作全量绿色。

## 真实DM8验证前置恢复

RealBusinessCollectionDm8Test已在自有新实例/卷、5381端口、随机全新schema和独占TEMP原件目录上实际退出0。Run=AGENT-RBC-20261006-66113B77；原件docs/evidence/enforcement-document-draft-20261002/AGENT-RBC-20261006-66113B77/isolated-execution-result.json及resource/schema manifest。当前源码经过独立构建快照，不使用产品库或权威库，不制造真实业务验收。后续JobPack使用另一个新实例/卷5382，避免共享空schema前置冲突；旧全量1128/0/4/40不改写。


JobPack独占5382专项也退出0（jobpack-run-id.txt对应原件），脚本只扩展显式允许的测试类，继续全新实例/卷/schema保护。EnforcementFollowupAcceptanceGrantFixtureTest在两个自有实例5381/5382中新建两个独立schema，按既有V032/V033/V057及authority V001准备，仅测试FUNCTIONAL_VALIDATION身份/Grant；grant-fixture-result.json退出0。grant-fixture-command.ps1与Java保留可重现命令，ownership.json不含口令；未连5311/5312/5237、未向原主体发Grant。三个历史环境错误均有本轮专项前置恢复证据，不能因此改写旧全量或关闭VER-AGENT-REGRESSION-ENV-01。

## 测试证据口径纠偏

原生成器累加所有默认/带后缀XML，连刻意红测及被修复夹具原件也当作“最近Java结果”。现改为读取主计划第0.2唯一指定的不可变未筛选报告与实际退出；逐一核对318个XML、套件名称/计数/合计、退出时间、目录及路径边界。本地混合XML保留为localInventory而非全量结论，不删旧红、不把专项绿冒最新全量。

10项反例通过（evidence-scope-tests-final.log），桌面/移动看板通过dashboard-final.log。首次独立JSON核对错用了evidence而非actualEvidence键，evidence-scope-verify-final.log保留；修复后evidence-scope-verify-fixed.log完整执行，最终仍退出1：指定历史回归0失败/4错误且早于当前业务源码，即TESTS_FAILING及TEST_EVIDENCE_STALE。这个红灯现在有确切来源和范围，不再用累积库存虚报当前失败数。

本轮没有执行新的未筛选全仓Maven；88项Java定向回归、81项前端、三个真实DM8专项（含新隔离授权夹具）不能合并冒充一次全量通过。Consultation/Enforcement/Adjudication整体仍按原领域任务进行中；固定动作计划不等于全部动态Agent化，真实业务Golden和专业验收不关闭。咨询外呼与Turn提交之间的不确定窗口仅为源码确认的待补验证风险，不冒充本轮已修复。

## 未筛选全仓回归（后续实际执行，替代前文“尚未执行”的当时状态）

夹具隔离代码6项测试通过（fixture-scope-tests.log）；JobPack/Grant提供独立显式命名空间，任何部分配置均不得回退历史连接。隔离工具PrepareOnly仅准备新空schema，不运行RBC；schema密码以本机受保护凭据保存在仓库外，未写入本证据。

第一轮未筛选命令在源码快照目录执行，咨询测试需要仓库相对原件/真实语料，快照不含这些资源，146项/0失败/8错误即止于consultation；原件full-unfiltered.log、full-command-exit.json、full-round01-result.json及XML完整保留。没有伪造/替换语料或放宽测试。第二轮先逐文件核当前工作区与源码快照SHA完全一致，改在真实工作区执行原未筛选Maven命令，结束后再次核所有输入SHA一致。

**2026-10-06 10:21:03+08:00，完整Reactor包括mock-center-api全部成功。373份本次XML合计1509项、0失败、0错误、40条件跳过，Maven实际退出0。** 其中platform-api321套1147项/0/0/40；旧1128项是该单模块统计，不与本次全后端1509直接做增量比较。40项环境条件跳过仍逐套保留，不算已验证，不删原测试、不通过筛选制造成功。

命令full-command-round02.ps1、输入SHA核对full-round02-input-check.json、隔离范围full-round02-scope.json、全量日志full-round02-unfiltered.log、实际退出full-round02-exit.json、逐XML报告full-round02-result.json/full-round02-reports。普通DM8及RBC分别使用5383的SYSDBA/独占空schema；JobPack独立5382新schema；Grant准备仅在5381/5382新schema，旧5311只读原件检查及预检已存在的V081表，未向原5312发Grant。

主计划的唯一回归证据链接现已指向这次真实未筛选原件，而非专项或混合库存。VER-AGENT-REGRESSION-ENV-01仅工程环境/回归出口完成；三个业务Agent、专业Golden、动态规划、最终身份/模型治理和交付门禁没有因此完成。

## 最后核对与资源保全

主计划更新至V10.2并链接真实第二轮全量后，final-full-green-verify.log实际退出0（47基线/41契约一致，所有独立检查均执行），final-full-green-dashboard.log桌面/移动看板退出0；当前接续由同一主计划指定IMP-AGENT-CONSULTATION-01，三域产品总任务不冒完成。

所有本轮自有5381/5382/5383容器逐一复核ID、run-id/role标签及独占卷后已停止，容器、卷、隔离原件和源码快照保留（owned-resources-stopped.json）。原5237、5311、5312实例仍运行，未停止/删除原容器或卷。新准备凭据保存在本机受保护目录，不在仓库原件中。
