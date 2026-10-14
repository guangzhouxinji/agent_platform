# 质量入口执行顺序限定技术设计

日期：2026-10-10（Asia/Shanghai）。Task：FIX-QUALITY-GATE-EXECUTION-ORDER-01。这是共享工程技术决定，不是业务需求扩展；状态只在docs/08。

## 1. 来源、限定R与依赖

人类优化/有界并行/受阻先做其他项真实消息及当前限定指令见[来源](../evidence/single-database-installation-20261009/approved-execution/quality-gate-order34/human-source-records.json)。原始业务requirements只读；docs01的独立Agent边界与docs09第5/7节治理合同不变。PLAN-02、PLAN-03、ARC-AGENT-REFACTOR-01已完成；既有verification-stages的纯TESTS_FAILING/TEST_EVIDENCE_STALE分类与7项测试为机械基础。当前前检实际exit1，仅既有健康红；run-quality-gates先verify-all并process.exit，因而新测试不可达，这一推断由原源码/实测退出支持，未重复运行整套入口冒真实新回归。限定R为共享工程执行缺陷，未新增业务API、身份、资料用途、费用或权限。

## 2. 限定D与失败语义

1. 复用原verify-all和verification-stages，显式选项仅把“已经完成全部独立检查，且只有测试证据阶段红”的聚合结果标为exit2。原默认verify-all仍exit1；基线、结构、未知失败、进程缺失/信号仍硬exit1，不按stderr文字猜分类。
2. 质量入口首个治理调用使用该显式协议。硬失败立即停，exit2只允许继续原独立前端组件、构建、浏览器与Java命令；历史红仍进入失败集合，绝不自动变绿或删原报告。独立阶段错误/缺失/信号/异常逐项收集，仍尝试其他独立阶段和后置检查；不伪造阶段执行或把环境缺失改skip。
3. 全阶段后再调用原治理入口核当前证据。后置任何非0保留失败；执行期间源码/需求/构建配置集合的前后路径+SHA必须一致，否则最终非0。生成物、target、node_modules、dist、证据/派生计划不冒源码。源目录符号链接或无法读拒绝；稳定排序，无外呼。
4. Java阶段即使子进程0，仍需实际新Surefire XML，完整观察到的报告集合须新生成、计数合法且无失败/错误；缺失/陈旧报告非0。计数/跳过如实记录，跳过不证明真实集成或专业验收。该有限入口不是自动推广主计划最近未筛选回归引用；旧20错误/143跳过仍原件保留，最终绿需原完整证据合同另满足。
5. main入口与纯调度测试分离，测试注入子进程结果只测工程调度，不是新增产品运行路径；生产仍原固定命令，不提供绕基线/跳测试的选项。不在本轮调用现存真实DB/浏览器/Java环境套件作调度证明，不改部署/包/旧环境或模型。

## 3. 审查与准入结论

逐条反演：旧健康红+其他成功必须全部可达且总红；基线/结构/未知exit2都不得被错当健康红（verify-all仅无fatal/deferred时给2）；阶段非0/信号/缺状态/抛异常不得绿；后置不一致或无新报告不得绿；全绿且源/报告有效方允许0。数据/流程/权限/法律均无新决定，限定技术D在上述合同及现有7项分类证据基础上通过，由开发责任方Codex实施和复核；不是靠本次监控指令直接宣告业务D。详细准入只限工程脚本，父单库/A/B/C和普通成员分支不关闭。

## 4. 写集、验证和退出

主执行独占scripts/run-quality-gates.mjs、必要verify-all/verification-stages复用及新正反测试、docs08/本ADR和原证据目录，串行运行共享verify-all。可并行侧核普通成员候选原批准，代理只读有限证据、不得访问secret/DB/运行资源，不改状态台账。实现前保存原字节；新增工程测试覆盖分类/阶段到达/聚合/缺证/源漂移/绿色路径、实际CLI工厂接线，受影响7项回归。治理前后和原stdout/exit/源SHA保存，完成仅表示入口工程修复，不表示实际整套测试或业务验收通过。
## 5. 修后机械反查证据

33/33正反专项及实际分类治理见上述engineering-evidence。原verify-all默认退出语义不变，显式分类实际exit2仅旧TESTS_FAILING/STALE；源码SHA前后一致。前置硬拒绝、阶段聚合、缺/陈旧XML、源集合/字节漂移、实际Windows junction拒绝均已覆盖。未执行真实整套quality入口，不声称其前端/Java/DB已通过，也不自动推广主计划旧回归。有限并行审计只有指定报告写集，原消息换行差异已在既有conditional-byte-reconciliation另证，审计不改原SHA、不泛授。
