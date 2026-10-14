# 裁决01C页面实现与实测证据（2026-10-02）

范围：IMP-ADJ-V2-TASK-01C。依照负责人本轮要求、[限定D1](../../adr/adjudication-workspace-20261002.md)和docs02第75节，直接连接已完成01B；任务状态仅以[主计划](../../08-总开发实现计划.md)为准。本文件是原件解释，不是第二套任务台账。

## 实物与边界

- 产品入口 `/app/adjudication`，独立组件 `frontend/src/features/adjudication/`；案件/双方材料/权项/引用/人工对应/报告修订全部具名表单，无工程包默认值、开发身份、JSON产品表单或静态结果。
- `api.ts` 同源调用01B `/v2/adjudication/case-works`，真实OAuth Code+PKCE；token仅内存，锁定清本地正文，不声称issuer会话撤销。独立复核由后端当前逐案授权决定，不由前端身份选择器决定。
- 原文使用折叠/滚动/搜索定位、高亮和来源卡；特征逐项投影 SUPPORT/OPPOSING/UNCERTAIN及缺证，不增加专业评分、等同/侵权/有效性或采信逻辑。
- 领域内核、01B命令、事务、迁移和存储不改。新增 `CaseWorkspacePageController` 只转发既有Spring静态资源；`CaseWorkErrors` 保留403/code，仅给既有来源拒绝补安全reason，避免前端猜撤回。`AppRoutes.tsx` 本任务仅改裁决lazy加载，保留并行ENF路由。
- `compatibility-evidence.json` 对实施前SHA核验真实01A三核心、已录既有文件及任务依赖（实施前快照没有记录共享身份文件SHA，不把空集合当成未变证明；本任务未写身份代码，兼容性另以真实PKCE及01B专项/两域回归验证）；无新增CON/ENF裁决前置。并行代码/任务行变化保留且列出，不倒退或覆盖。

## 真实浏览器闭环

最终原件 [browser-fifth/verification.json](browser-fifth/verification.json)、[浏览器结果](browser-fifth/browser-result.json)、[实际HTTP响应](browser-fifth/http-records.json)、[20项API/双DM8测试日志](browser-fifth/tests.log)。真实Chromium加载实际Vite构建页面，真实BCrypt登录/PKCE交换，作者和独立复核人两个context，另一作者标签制造CAS冲突。没有网络mock、fixture令牌注入或用fetch替代产品操作。测试TLS仅允许隔离环境自签证书；生产TLS校验合同仍沿01B。来源撤回是现有mTLS维护API的真实动作，不给产品用户提供权限旁路。

| 页面要求 | 实现与实测 |
| --- | --- |
| 创建/进入案件 | CaseForm及按ID进入；真实POST创建并GET当前案件 |
| 基本信息/状态 | CaseRead及工作版本/输入版本/辅助复核状态；不伪造法定案件状态 |
| 双方材料 | 两方登记、正文阅读、可读性、替换新ID与历史材料标签 |
| 权项/历史 | 必要要素具名录入、v1→v2、历史权项展开 |
| 原文/引用/定位 | 上下文原文新版本、确切引用，真实浏览器SHA校验与材料片段定位 |
| 特征逐项对应 | 每个必要要素的人工记录和材料确切片段，高亮绑定权项/材料源版 |
| 支持/反向/冲突/缺证 | 初始缺证，替换后旧对应不当当前覆盖；支持+反向冲突、不确定可视化 |
| 辅助报告 | 真实生成v1/v2/v5；分章阅读、诊断与来源 |
| 人工修订 | v2→v3、退回后v3→v4；原因保留，不覆盖旧稿 |
| 有效/历史/失效 | 显示报告current/stale；材料替换后旧稿失效且后端拒绝复用 |
| 确切版本提交 | 所选revision+SHA+expectedVersion；缺证稿409、成功稿提交 |
| 独立复核 | 作者自审409 INDEPENDENT_REVIEW_REQUIRED；独立人员实际复核 |
| 退回 | 独立人员退回v3，展示理由/操作人/确切摘要 |
| 再提交 | 旧退回稿409 REPORT_NOT_DRAFT；修订v4→提交→复核非正式裁决 |
| 审计/操作历史 | 逐版本操作表，工作v1历史快照只读，当前头恢复 |
| 异常可视化 | 缺证/冲突/不确定/旧稿/409 CAS冲突/重复复核/403撤回及清正文 |

最终案件head=22，数据库22份快照、22条审计、22条幂等回执，实际PKCE会话3份；124个裁决API/令牌状态响应，页面异常0。独立来源库真实REVOKED后当前/历史正文均不展示。两个ADJ随机schema已实际清理，`verification.json passed=true` 在关闭/清理完成后才写。

截图： [特征与原文定位](browser-fifth/feature-correspondence.png)、[退回报告](browser-fifth/returned-report.png)、[版本冲突](browser-fifth/version-conflict.png)、[阻断报告](browser-fifth/blocked-report.png)、[移动端审计](browser-fifth/mobile-history.png)、[撤回清正文](browser-fifth/source-withdrawal.png)。1440宽与390宽真实截图保留，浏览器断言无整页横向溢出；桌面特征页已人工视觉检查。

## 回归与失败原件

- `frontend-complete.log`：19文件64项全部通过（裁决11项）；包括既有咨询/执法页面测试。`browser-fifth/frontend-build.log` 实际TS/Vite build通过。
- `domain-regression.log`：咨询106、执法100、裁决01A41项，共247项，0失败/错误/跳过。
- `browser-fifth/tests.log`：01B原18专项+原真实双DM8+真实页面/持久计数，共20项，0失败/错误/跳过。
- 全后端正式输入回归 `backend-final-summary.json` / `backend-final-xml.zip`：322套1045项，0失败/错误、39项条件跳过（如需另行提供环境的集成分支，不冒已执行）；实际时间窗canonical XML/逐报告SHA归档，临时TST库已清理。`verify-full-after.log` 退出0，未修改外域测试或checker。
- 第一轮来源select隐式名称不稳定；第二轮预填textarea把原文并入隐式名称，统一与视觉字段一致的aria-label修复并补单测；第三轮测试输入LocalDate编码为数组，改为ISO日期字段；第四轮页面全链已通过，数据库会话取证却错误使用公开client ID查询内部registered ID，修正为注册表JOIN后第五轮整轮通过。失败目录/截图/原日志全部保留，不改领域行为、放宽断言或跳过断言冒绿。
- 首次前端全量61/62，既有组织面板超时；限制并发到2后两次全量62/62、64/64，不改外域测试阈值。各轮verify-before/after原件保留，历史TESTS_FAILING不删除；结构核验47基线/41需求行未漂移，结构checker不证明法律/业务专业正确性。

## 部署与使用边界

构建时必须配置已通过维护渠道注册的 `VITE_ADJUDICATION_CLIENT_ID`（固定同源 `/app/adjudication` 回调、PKCE public client）；以既有bundle-frontend profile随真实裁决服务打包，启用既有adjudication.v2.enabled/identity/TLS/DM8与独立授权来源库。缺配置会明确拒绝登录，不回退开发身份。用户按获授权案件ID进入或新建，材料/原文来源用途仍须由正式维护通道准入；本页面不发行来源许可。

工程输入仍为 `data/adjudication/task-first-loop-input-v1/records.json` 加本轮明确记录的工程上下文/引用补正。页面和来源卡始终标明非现实案件、非真实专利、非正式法律依据。复核通过只表示辅助工作状态，不是正式裁决。01C产品操作性与专业验收分开；01D需后续在此成果上进行同案重启/权限/完整产品验证，本证据不宣布裁决V2全面完成。

## 01D接续与最终核验补充

01C完成后仅在测试框架加入真实同案服务重启调度，产品代码不加测试恢复分支。[恢复证据](recovery-verification.md)已实际通过：22版同案原文/快照/审计/回执逐项字节一致、原会话及新PKCE页面再读一致、历史v17复核结果与当前失效/阻断状态不混淆，137响应/4会话/0页面异常，双ADJ schema清理。D首次取证代码编译错误及后续共享target缺类失败均保留；串行30套115项实际补跑及后verify退出0。01D仍进行中，完整异常证据矩阵归并和独立退出复核尚未完成；父任务不自动完成。
