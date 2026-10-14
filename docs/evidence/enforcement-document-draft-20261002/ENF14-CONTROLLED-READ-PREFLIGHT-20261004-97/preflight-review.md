# 97轮：条件式受控读取停机预核

日期：2026-10-04。归属 REC-ENF-JOB-PACK-79-01 / IMP-ENF-DOCUMENT-DRAFT-05；状态只在 docs/08。

## 授权与执行范围

用户本会话“按上面顺序处理”授权按96材料顺序落实前置，前置成立才受控启动5444并正常登录读取。此回复不是已证明历史完整，也不是业务写入、重新恢复、专业确认、审核或DOCX授权。不创建定时任务。

本轮只有治理检查、端口/容器元数据、现存文件及源码只读检查；未连接业务或权威数据库，未执行恢复工具、启动容器/后端、登录、claim或任何业务接口。

## 实际结果

- 前置 verify-before.log 中 verify-all 实际退出0，47文件/41台账一致。原组合命令最终退出1来自末尾无监听查询，不是 verify-all 失败；二者不能混记。
- offline-preflight.json 实算13/13原件SHA匹配87 independent-files-after.json：12公开材料原件及 ENF14-FACT-DOCUMENT-20261003-70@1 原件。这里只证明这些已知原件，不能证明未知更晚历史或数据库行仍完整。
- 5311、5312、5444、5174监听均0。Docker可查询；codex-enf-i-business / codex-enf-i-authority 均 exited/255，FinishedAt约2026-10-04T12:38:41Z；各自端口5311/5312和独立现有数据卷已定位。容器退出原因未证明，不把退出255解释为数据损坏或正常冷备。
- 数据库已停，现时16行、事故后文书行、当前独立权威/投影一致性均未重新读取。87对账仍只证明87当时，不将其冒充本次现时SELECT。
- 既有launcher仍强制非空 public-candidates-path，且无显式server.address/cleanup关闭参数；本轮没有直接执行该launcher。已有空值Importer/cleanup=false机制存在，但尚未形成被验证的本轮有效部署配置。
- ExistingRouteSecurityConfiguration 的兜底permitAll不代表领域无鉴权；ProductEnforcementAccess实际验证JWT/session/当前Grant/权威水位。但它不验证只读scope，现有身份scope收窄不能证明写入口拒绝。本轮未找到可复用的全局只读部署控制，不能宣布已存在。
- 当前发行classpath/build固定证据未完成；临时依赖classpath文件存在不是已发行版本证明。本轮不临时编译或把任意target/classes当发行物。

## 停止点与下一项最小动作

A阶段未满足，故不进入B启动/登录，不修改任何业务、授权或数据库状态。5444继续停机；未知历史NOT_PROVED、IMP/REC进行中、BUSINESS_ACCEPTANCE_PENDING保持。

1. owner运行环境负责人：明确现有5311/5312进程可安全恢复的范围与启动前卷保全条件，仅指定这两个当前业务/权威容器，排除5319和88～95隔离卷。当前条件式5444读取授权不扩展为任意旧卷启动。恢复数据库进程后才可新连接SELECT核现时记录及权威；不重跑87恢复工具。
2. owner项目/部署负责人：限定最小部署控制D，使正常OAuth/PKCE所必需身份写允许、业务写请求实际拒绝，并证明不能直接绕过受控入口。优先现有能力；没有可复用控制时须先批准该部署控制设计，不新增业务只读状态、不改Grant、不改冻结领域规则。只监听loopback或承诺不点击不足以证明禁止写入。
3. 两项成立后固定既有发行物/配置，再按96材料做停机SELECT→受控启动→启动后对账→正常登录读取→读取后对账。任一差异停精确本轮PID，不回灌/删除/改授权。

没有新增产品I、Maven或浏览器验收；20历史环境errors/119skip及所有历史失败仍保留。后置治理退出另存verify-after.log，不将治理0当产品恢复或正式业务验收。

后置verify-after.log实际退出1：COMPLETED_WITHOUT_EVIDENCE:IMP-CON-PRODUCT-SOURCE-ENGINE-01、IMP-CON-PRODUCT-SOURCE-01，来自当前主计划咨询任务完成证据检查，不是本轮执法真实I失败。保留原日志，不修改咨询任务或业务以修绿；本轮没有全仓通过结论。
