# 执法 V2 真人业务验收现场收口证据

- 任务：`IMP-ENF-HUMAN-ACCEPTANCE-SITE-04`。
- 执行编号：`ENF-HUMAN-SITE-20260929-01`。
- 目标任务：`ENF-RBC-CNIPA-GUIDANCE-CASE-1`，包 `ENF-JOB-CNIPA-GUIDANCE-CASE-1`。
- 复核人：Codex（技术复核）。

## 实现结果

1. `GET /v2/enforcement/job-packs/collection-tasks` 对未领取公开待办保持登录可见；对已领取待办逐任务检查当前 JWT 与确切包的 `READ/JOB_PACK` Grant。无 READ 的第二人列表为空，获得现时 READ 后可从列表直接打开已领取包。
2. 工作面新增已领取待办的“打开任务”命令；不需要人工查库或复制内部主键。
3. 工作面退出先调用 `/v2/identity/logout` 撤销 bearer session，成功后清空本地 token、任务和受限内容。
4. 新增双人交叉固定回归：`mechanism-operator` 提交岗位、回执和评价，`mechanism-reviewer` 提交批准；两人交叉核验，无自批、无自验。名称和内容均明确标注为机制测试，不是真人业务事实。

## 实际执行

| 层 | 结果 | 证据 |
|---|---|---|
| 领域 C | `tests=5 failures=0 errors=0 skipped=0` | `backend/enforcement/target/surefire-reports/TEST-cn.xinji.agentplatform.enforcement.jobpack.JobPackServiceTest-ENF-HUMAN-SITE-20260929-01.xml`，SHA-256 `87287cd23ad86f38950a3cee02aa4196702049af3b669a526ada0f5c8f97bd78` |
| 登录 HTTPS | `tests=1 failures=0 errors=0 skipped=0` | `backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.enforcement.jobpack.JobPackHttpsTest-ENF-HUMAN-SITE-20260929-01.xml`，SHA-256 `aca2d102622f191adf74110c163a0280250e5a0378a18dd878e1f4dc7f1a05ba` |
| 真实 DM8 I | `tests=1 failures=0 errors=0 skipped=0` | `backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.enforcement.jobpack.RealBusinessCollectionDm8Test-ENF-HUMAN-SITE-20260929-01.xml`，SHA-256 `a9a207b709a0412012d1cabed0a9f97fcb0c3bdfb9244137ec70a6dc57446f55` |
| 前端 | 1文件3项通过，生产构建退出0 | `EnforcementJobPackV2Page.test.tsx`及`npm run build` |

真实 DM8 I 使用执法专用 `codex-enf-i-business:5311` 和本地 Secret 非泄密注入。轮次实际导入12个公开候选，领取首任务，经双人交叉完成四类机制事实，产生九条产品命令回执、九条审计事件和一条 `NEXT-V2-INT-ENF-01` 触发；公开原件可读且 SHA 绑定不变。轮次结束删除机制任务包/回执/事件/触发与临时文件；事后只读实查该包 revision、trigger 和 collection task 计数均为0，不改变现实 DATA 状态。

## 审计与状态结论

- 人工每次提交、核验、驳回、重放和最后触发仍由现有不可变 revision、receipt 和 event 记录操作者、时间、版本、状态和内容摘要；原件另有 SHA 与文件键。
- 最后一项异人核验在同一交易中派生 `READY`并写入唯一 trigger，对外快照为 `RUNNING`；同键重放返回原回执，不增量触发。
- Codex 具名技术复核：现场就绪收口符合窄 D，`IMP-ENF-HUMAN-ACCEPTANCE-SITE-04` 完成。
- 现实状态不变：尚无两名非测试真实人员完成操作，因此 `DATA-ENF-JOB-PACK-01 = WAITING_REAL_HUMAN_ACTION / BLOCKED`，`READY=0`，`NEXT-V2-INT-ENF-01`未触发。

## 真人采集浏览器运行态预检

- 执行编号：`ENF-HUMAN-BROWSER-PREFLIGHT-20260929-01`；执行者：Codex；工具：项目现有 Playwright Chromium；时间：2026-09-29。
- 实际打开 `http://127.0.0.1:5174/app/enforcement/job-pack`，页面标题与“真实任务包采集”工作面加载成功；点击真实“登录”按钮后页面原位显示“产品身份入口不可用”，未导航至 OAuth 授权端点。
- 网络前检实际结果：5174、5175工作面HTML均200，但其 `/v2/enforcement/job-packs/design` 均502；5443当前进程以 `--product-consultation` 启动，执法设计路由404；5444未监听。现有页面壳不得计为执法产品运行态。
- 截图和结构化浏览器记录保存在当前机器临时目录 `ENF-HUMAN-BROWSER-PREFLIGHT-20260929-01` 的 `01-entry.png`、`02-after-login.png`、`preflight.json`；未包含密码、token或真人资料。
- 本轮没有提交组织、文件、事实、批准、回执、评价或核验，没有创建/修改任务包、回执、事件或触发。当前状态增加 `WAITING_RUNTIME`，并继续保持 `WAITING_REAL_HUMAN_ACTION / BLOCKED`、`READY=0`、真实触发0。
- 已启用当前线程的自动复查：独立执法 HTTPS、正式 OAuth/PKCE clientId 和安全会话全部真实可用后才继续 Playwright；到人工真实性节点必须停驻并由相应真人本人操作。

## 独立执法真人验收运行时收口

- 任务：`INFRA-ENF-HUMAN-RUNTIME-05`；执行编号：`ENF-HUMAN-RUNTIME-20260929-01`；执行者/技术复核：Codex。
- 新增唯一最终产品的隔离启动入口`--product-enforcement`。该入口只加载共享产品身份/OAuth、冻结的执法MATERIAL与Job Pack组件，不加载咨询来源候选组件；咨询5443持续独立运行，未停止、未改配置、未复用为执法后端。
- 执法后端实际运行于`https://127.0.0.1:5444`。业务与独立权威分别使用执法专用DM8 `codex-enf-i-business:5311`和`codex-enf-i-authority:5312`上的新`ENFACC_BIZ`、`ENFACC_AUTH` schema；权威服务/备份角色分离。口令、主密钥和证书库密码仅保存于当前Windows用户的加密凭据文件，没有写入仓库、日志或本文。
- 空身份域通过正式`--identity-bootstrap`登记具名技术维护证书；随后由真实mTLS维护入口轮换签名密钥并注册无secret、强制PKCE的公共客户端`enforcement-job-pack-human-20260929`，确切回调为`https://127.0.0.1:5174/app/enforcement/job-pack`。技术维护主体不作为业务人员，也未获得任务包Grant。
- 5174以同一受信本地证书启动HTTPS，`/v2`、`/oauth2`、`/login`、`/.well-known`均代理至5444。只读检查实际为工作面200、OIDC metadata 200、未登录`/v2/identity/me` 401、未登录Job Pack待办401；不再出现502。
- 产品启动使用明确候选清单参数自动导入，不手改业务表：DM8实读12个公开待办，目标`ENF-RBC-CNIPA-GUIDANCE-CASE-1`为`UNCLAIMED`；独立文件根12份原件；Job Pack revision 0、integration trigger 0。由此证明运行时可领取，同时证明未伪造领取、批准、回执、评价或READY。
- Playwright未设置`ignoreHTTPSErrors`，实际访问5174工作面、点击登录，并取得`/oauth2/authorize` 302和同源代理`/login` 200；交互浏览器已停驻在真实用户名/密码页。结构化记录及截图位于本机受控证据目录`%LOCALAPPDATA%/AgentPlatform/enforcement-product/evidence/ENF-HUMAN-RUNTIME-20260929-01`：`preflight.json` SHA-256 `724dc173fe866da66ca23e96e10284cd3d73d14ce305746112b4e188fd2313cb`，登录就绪截图SHA-256 `071966be7dc0a8b169a0657c3a4d180f24ebc5f69db3eac6d931e32759ea2765`。
- 专项回归：`ProductIssuerHttpTest`为`tests=2 failures=0 errors=0 skipped=0`，XML SHA-256 `10d171ba3b2aef5311a19c94ae31bb45b8b0b22d1a348676913f2b2fea8515f3`；`JobPackHttpsTest`为`tests=1 failures=0 errors=0 skipped=0`，XML SHA-256 `188d0728ce3cee82e8f1d03e50eedc91286f0a403e88292ab9cb29f84518c05a`；前端16文件48项通过，生产构建退出0。两份XML已在本轮完成后立即固化到上述独占证据目录。
- 状态复判：`WAITING_RUNTIME -> RUNTIME_READY`；`DATA-ENF-JOB-PACK-01`仍为`WAITING_REAL_HUMAN_ACTION / BLOCKED`，已采集真人事实0、异人核验0、`READY=0`、`NEXT-V2-INT-ENF-01`触发0。
- 唯一剩余外部真实性条件：真实管理员按现有正式身份入口登记两名具现时岗位授权的非测试真实人员，由两人本人激活并在已打开的产品登录页实际登录。系统和Codex不得生成其身份、岗位、密码、批准、正式回执、评价或异人核验。

## 真人现场运行进展

- 两名真人验收主体`enf-acceptance-a`与`enf-acceptance-b`均已由本人完成密码设置和激活；凭据未进入聊天、日志或仓库。A具`CREATE/COLLECT/READ/VERIFY`，B具`COLLECT/READ/VERIFY/TRIGGER`，没有扩大既有Grant。
- A通过真实OAuth/PKCE产品入口登录后实际领取`ENF-RBC-CNIPA-GUIDANCE-CASE-1`；产品DM8保持唯一领取，任务包`ENF-JOB-CNIPA-GUIDANCE-CASE-1`为revision 1/contentRevision 1，集成触发0。领取由产品命令完成，未手工修改数据库。
- A重新登录后，受控浏览器实际显示当前Subject `3d2c34cf-4beb-42e1-9df8-5ff7158a4e83`、组织`ORG-ENF-ACCEPTANCE`、目标任务已领取，并停驻“真实身份岗位”节点。证据截图`%LOCALAPPDATA%/AgentPlatform/enforcement-product/evidence/ENF-HUMAN-RUNTIME-20260929-01/05-a-relogin-position-node.png`，SHA-256 `152057B5E264DB3BD89F25469FC8E174CCFD0E66250570549F6BF22108F0A338`。
- 当前公开事实1/5；真实身份岗位、实际人工批准、正式人工回执、业务评价均未提交，异人核验未通过。`DATA-ENF-JOB-PACK-01`继续`WAITING_REAL_HUMAN_ACTION / BLOCKED`，`READY=0`，`NEXT-V2-INT-ENF-01`未触发；不得将登录或领取解释为岗位授权或人工事实。
- 本轮全仓`verify-all`的治理与计划检查通过，结构检查仅被无关安全模块`ProductPatentSearchExecution.java` 346行超过300行硬限截断；未因此修改执法或无关业务代码，亦未将全仓校验记为通过。
