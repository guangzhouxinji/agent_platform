# ADR：裁决01C单案工作台（D1，2026-10-02）

范围：IMP-ADJ-V2-TASK-01C，依赖已完成01A/01B；REQ-ADJ-R-CORE-14及ADJ-04/06/07/09/11/12限定切片。来源为项目负责人2026-10-02本轮16项页面要求与既有01B合同，不重新规划十二项能力。技术责任Codex；专业/正式岗位和生产用途不在本技术决定中确认。

## 页面/数据/权限合同

现有`/app/adjudication`路由替换历史开发页面，使用独立`features/adjudication`组件，全部请求现有`/v2/adjudication/case-works`，不保第二套任务状态/分析算法或固定案例。新案件/上下文、材料、权项要素、引用、人工对应均为具名表单；源id/version/locator/SHA/kind必须显式填写或对确切原文计算SHA（只校文本，不发行来源许可）。长期内容使用折叠原文/搜索高亮/要素锚点和来源卡，不以JSON代替业务阅读。

登录复用既有真实OAuth Authorization Code+PKCE，构建配置VITE_ADJUDICATION_CLIENT_ID必须具名部署，固定回调同源/app/adjudication；token只在内存，PKCE一次使用、校state、回调清理、不接受URL token/粘贴token/开发身份。锁定工作台只清本地正文和token，不声称撤销issuer会话。重新登录由issuer当前会话/权限决定。不导入依赖咨询Task的ProductIdentityController，不新建身份/授权接口。

案件列表接口尚无，按案件ID进入，用户可保存浏览器URL中的案件ID（不保正文/token），不编造全部案件目录。页面显示工作version/inputVersion、当前/历史快照、权项历史、报告current/stale/reviewState/诊断，历史快照只读。所有写操作携加载时expectedVersion、唯一Idempotency-Key；报告操作明确所选revision+SHA，弹出或显示确切提交目标。按钮只因busy/历史浏览禁用，不把合法性藏在前端：旧稿/未完整/重复提交/自审等均可实际请求并显示后端拒绝代码。提交后只以receipt与重新读取结果推进展示。

必要要素分组仅投影既有Finding.SUPPORT/OPPOSING/UNCERTAIN，支持+反向共存明确称“存在相反记录（非采信/法律判断）”；没有对应称“未记录对应/缺证提示”而不宣称不存在证据。不新增专业评分/侵权/有效性结论。来源种类PROJECT_AUTHORED始终显示“原创工程输入，非真实案件/专利/法律依据”，无默认工程数据/演示入口。

## 并发/失败/恢复

请求封装只接受同源裁决路径；失败保留status/code/安全reason。409保持用户未提交表单并提示刷新/核版本，不自动改版本重试。网络未知结果保存原body/key供“重试同一请求”对账，禁止新key盲目重发；刷新成功后丢旧表单版基准，不把旧输入自动提交。401/403/503/网络故障清空所有已加载正文（表单也卸载），防止失权或来源撤回后残留历史内容；403来源撤回区别其他授权错误。

最小兼容接线：独立运行入口新增GET /app/adjudication→静态/app/index.html转发；静态资源沿现有Spring资源服务和打包profile，不改共享平台入口。CaseWorkErrors仅对既有SOURCE_USE_NOT_CURRENT拒绝新增安全reason=SOURCE_REVOKED，保留原403/code=CASE_ACCESS_DENIED；EXACT_SOURCE_USE_REQUIRED附SOURCE_USE_UNAVAILABLE，其余403保持原合同。必要性是当前API泛化403无法诚实区分来源撤回，不能让前端猜。无业务命令、领域、数据表、成功回执/事务改变；01B原测试及真实DM8再证兼容。

## 验证与退出

前后verify-all；前端全量测试/build、01A/CON/ENF与01B18项回归。独立真实DM8 schema/现有工程包/真实issuer/BCrypt登录/注册PKCE客户端、真实mTLS源批准；浏览器运行真实构建页面，不mock网络、不从fixture注入token、不用代理伪造结果。浏览器逐表单创建/双方材料/权项v1→v2/上下文/引用/要素对应/缺证报告拒提交/人工修订/自审拒绝/独立退回/新稿重提/复核，另一真实标签造成版本冲突，材料追加导致旧稿失效，来源通过真实维护API撤回后清正文。HTTP原件、截图、持久DB计数与浏览器结果齐全后才完成01C。01D须在此后另启动，不能以能打开页面或机械工程输入冒V2全量专业验收。

设计复核：先按输入→命令→状态→数据→展示正向反演，再从拒绝/失权/旧稿/历史反向核对；没有新增法律/法定状态/许可假设。安全可继续范围为辅助工作台；真实/生产身份和源权利仍逐部署准入。
