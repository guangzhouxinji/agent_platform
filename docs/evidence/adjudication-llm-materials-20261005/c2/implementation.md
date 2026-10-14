# 03B-C2受控API与加密版本持久化实现证据

日期2026-10-05；技术责任Codex；任务状态唯一docs/08。结论限定产品代码及机械C/V，**不是03B完整退出、真实DM8 I、真人采用或专业业务A**。

## 准入和范围

继承已过REQ-ADJ-R-CORE-14、ARC-ADJ-LLM-MATERIALS-03B-D和已完成C1。原ADJ-04/07/09/12对应材料版本、原文定位、陈述与事实分离及人工更正留痕；详细/物理D见ADR§9。01/02/C1内核、原API/持久化不重写。V093/U093仅ADJ新增表；并行CON采用V094，无重号。

**治理登记缺陷如实保留**：本轮初始登记脚本误匹配C1后继栏中的03B文本，把C2嵌入C1行并产生一条03B伪重复行。因此verify-after-design及多次后置并非只有freshness，而还报MISSING_PHASE/MISSING_ACCEPTANCE。这不是需求基线变更，也不是其他域造成。收口时从原C1完整前后片段恢复原行（状态/日期/证据不变）、登记独立C2完整行并保留真实03B行。既有R/D未被撤销；不把先前结构失败冒成已通过登记门。所有原始日志保留，修复后结构结果以最终verify为准。

## 实际交付

- 新MaterialDecisionController/Errors/Service/Configuration、JdbcMaterialDecisionStore、MaterialDecisionVault；2份测试及V093/U093。仅扩ADJ SourceAuthority只读截止口和既有保留线程新派生清理，不写Shared/CON/ENF。原require逐字保持、8个既有核心产品类SHA保持，见compatibility-review.json。
- 首次ACCEPT/EDIT/REJECT、确切version更正、afterOrganizationVersion+limit(1..50)列表及详情，均正式API；body严格限定双expected版本/action/humanText/reason，隔离parser拒未知、重复JSON、null/浮点版本和尾随JSON。actor/case/run/candidate/source/语义/期限来自可信服务器链。
- 保留原READ/MODEL_EXECUTE候选读取门，写加逐案WRITE及SAVE/ANALYZE；旧撤源案全历史来源门仍拒绝。确认内容仅HUMAN_REVIEWED_MATERIAL_STATEMENT，不写finding/报告/复核/正式状态，不增inputVersion。
- 现有mutate开启同primary事务，command锁→case→budget读取→整理head/row。人工密文/version/head、案件snapshot/audit/receipt全提交或全回滚。新增强制同一DataSource事务资源检查：同JDBC地址不同DataSource不能偷偷逃逸事务。
- 双CAS、同键同载荷重放、异载荷冲突、首次唯一、明确base链防分叉；案件opaque审计与组织版本索引交叉核验，缺行/改行不能重置候选决定。读也使用同primary REQUIRED事务，避免读取混合头。
- 独立UUID key/AES-GCM/AAD覆盖完整行绑定、密文SHA；最早父run/全部选入来源批准截止及30天边界继承，向前截断UTC毫秒，不从更正重计时。缺钥、坏密文、AAD关联变化、时钟倒退、到期拒读。只删新派生行/keys，head保留单调计数，旧V086历史不删。
- UUID/pending碰撞不能删旧key；部分加密失败仅清理本次拥有的对象，数据库失败回滚后清理新key。逆迁移先head零守卫再空payload守卫；非零历史先拒绝，不能在到期清理后的空payload表残留禁止新增约束。
- 外置授权/来源水位不是分布式事务：提交前失效使primary回滚；提交后撤权拒响应/拒读而保留已提交回执/key，现时合法授权恢复后同键仅返回原确切版本。这一提交后边界已实测，未承诺跨库回滚。

## 验证原件

最终冻结adj-c2-20261005-14：**33套248项，0失败/错误/跳过，Maven退出0**；其中新C2 **39项**（HTTPS/API27、DAO/密钥/迁移12）。包含C1原30及01/02/03A、所受影响Shared/CON/ENF精确机械兼容。见final-test-index.json、reports-final、tests-14.log、maven-exit-14.json；源码、测试、XML及迁移SHA见final-source-binding.json/physical-scope.json。定向不是全仓回归；不将重复的10/12/13轮相加。

真实签名HTTPS、当前测试授权/独立测试source authority、正式启用配置/Controller/DAO、应用重启确切读取均在随机H2组件环境通过。父模型端口只在测试文件装配；配置重启时真实官方adapter仅构造、不调用，没有付费请求。测试身份不替代真实操作人。

所有早期失败保留：03 CLI/编译、04同地址不同DataSource四项失败、06缺行错误码404而非完整性503、08/09测试再授权使用旧epoch错误。10/12/13通过各有范围，12加入来源截止；14再验证最终非破坏性拒逆。见earlier-executions-index.json及各日志，不改原XML内容或时间。

## 尚未执行与下一出口

DM8正逆/持久重启/原生恢复、合法当前案件真实人工采用均NOT_EXECUTED（physical-scope.json）。本轮未连接产品库、未探/启动Docker或旧服务，未复活来源、未修改实际预算/身份/既有历史、未建外部定时续行。最后旧案撤源/stale证据仍为拒绝条件，不冒当前服务可用性。

最小真实I准备见runtime-validation-prerequisites.md。03B整体保持进行中，03C/03D不启动、专业A/ADJ十二项不升级。前后verify原件保留；47文件/41契约基线一致。全仓freshness不靠复制定向XML到canonical或改mtime修绿；本轮canonical XML未写。

最终治理收口：verify-final.log实际退出1，登记误插造成的MISSING_PHASE/MISSING_ACCEPTANCE已消除，只余TEST_EVIDENCE_STALE:project；47/41基线一致。后续证据时间/SHA补记后再次运行verify，原件verify-after-evidence-final.log。未刷新canonical报告或人为mtime以消除全仓freshness。
