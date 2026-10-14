# 裁决已有材料整理Run工作台源码准入D

日期2026-10-13，技术版本ADJ-MATERIAL-WORKBENCH-SOURCE-D1，责任方本共享执行聊天Codex。业务依据REQ-ADJ-R-CORE-14、原03A/03B详细D；现有API/DAO证据为已完成IMP-ADJ-V2-TASK-03B-C2和FIX-ADJ-MATERIAL-STALE-REPLAY-01。两遍只读复核原DTO/MaterialDecisionService/HTTP提交后失权反例及[隔离完整提案原件](../evidence/adjudication-workbench-source-20261013/proposal-original.md)，以下由root明确审定S1～S5，不把原提案或测试替身当真实业务验收。状态只在docs08。

## 范围与源依据

准许原工作台内已有合法Run的候选/原文阅读、显式ACCEPT/EDIT/REJECT及当前版更正、分页历史与失败恢复源码/C；不加POST runs/新模型生成入口，不关闭03B真实DM8及真人采用、03C完整生成/真实API浏览器、03D单案出口，不复活撤源案或旧预算。使用原API字段/错误码/双CAS/幂等/期限，不造第二业务规则。

详细字段、请求、回执、来源绑定、状态/失败反例采用上述冻结原件对应各节；它的“待审”仅描述原提案时点，当前决定以本D为准。后端MaterialDecisionService提交后再次核权可能返回403，故HTTP失权不能证明数据库未提交；确切原键重放由已完成STALE-REPLAY修复提供，当前权限/来源/期限仍必需。

## S1～S5确定技术合同

1. S1分段：独立ARC D依赖CORE-14、03B-C2及STALE-REPLAY；PC源码子项依赖该D/C2/STALE-REPLAY。03C整体仍依完整03B并增加PC，不删除真实I/真人出口；03D仍依03C。唯一owner登记后隔离会话只在白名单实现。
2. S2来源定位：展示确切Source正文与全部同Source/同SHA匹配材料（含材料ID/方别），不声称Run私有materialIds可由sourceTag反推唯一材料。真实UTF16偏移/quote/SHA三者同时一致才高亮，重复quote不靠首个字符串匹配。无法读取/摘要漂移/边界错则移除正文并禁依赖写入，不替用户消歧。
3. S3失权未知：401/403及本地锁定立即清展示正文、编辑和敏感Packet；401沿现机制清token。若POST可能已发且尚无经绑定的确切回执，只保留非正文case/run/candidate/requestKey/bodySHA和UNKNOWN_WITHOUT_BODY。本批不提供忽略/重发原body/换新key/解除围栏按钮；重登录、刷新当前案、子组件重挂载不解除同案围栏。仅失权前已核不可变回执可进入exact version GET，之后读取失败只GET恢复；历史相似内容或无匹配版本不是原key证明。此围栏为当前前端生命周期内，不承诺浏览器进程退出后的持久围栏、远端取消或自动新写恢复；跨生命周期未知新写恢复不在本C出口，若需要可继续写须另接受控回执查询或获准保留合同，不能偷存明文。非失权未知保留原body/key，在现时许可下原键对账，409仍不能凭空清未知。
4. S4正文/理由：采用服务端动态限额与现有非空/UTF16校验，不硬编码2000新业务上限；ACCEPT理由可选，EDIT/REJECT理由依既有合同必填并逐字保留。前端不扩大服务器权限/最早期限，不把提示词或模型输入塞入此工作台。
5. S5父页面：session与非正文未知围栏由AdjudicationWorkspace持有，位于材料tab重挂载之外，按可信身份/案件隔离但身份切换不销毁旧案未知事实。dirty/busy/pending/denied/confirmed接既有父导航互锁；事件入口与epoch/context双检，不能仅fieldset disabled。confirmed先核Decision View，清写Packet并撤旧可写view；exact version/head分页及当前Case版本/输入一致才恢复动作。已提交POST后GET失败不重POST，多GET不冒同事务快照；跨页head变更只读显式刷新。

## 限定写集与出口

仅frontend/src/features/adjudication/AdjudicationWorkspace.tsx最小挂接；新增MaterialOrganization.tsx、MaterialOrganizationRead.tsx、materialOrganizationBinding.ts、materialOrganizationSession.ts及同名四测试和AdjudicationWorkspace.materialOrganization.test.tsx。复用既有CSS、api.ts/casePath/digest/登录、SourceCard/Readers，不改common/contract/Reports/WorkArea/AgentTaskPanel、后端/迁移/身份/Source/模型、CON/ENF。样式确有必要增量先记事实再扩，不新建产品开发页或测试身份。

验收覆盖冻结提案八类场景：已有Run阅读及多材料定位、三动作与更正、未知原键及确认后只读恢复、CAS/stale、提交后403及锁定清body仍保留围栏、上下文/卸载迟到、父导航互锁、版本分页竞争。隔离组件/类型/构建与root共享集成/规定治理分别取证；真实DM8/合法Run/人员/来源/浏览器及业务B未因本D成立。无法证明S3生命周期围栏或回执绑定时该PC不标完成，不能用测试数关闭缺口。
