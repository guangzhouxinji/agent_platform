# Codex 具名限定技术复核：99部署控制实现

日期2026-10-04；复核人Codex（本会话主代理），依据docs/09第5.1节同人执行/复核两步。不是第二名独立人员复验，也不是IMP-ENF-DOCUMENT-DRAFT-05最终复核或业务验收。

## 范围与当前结果

用户“批准”原件user-approval.json明确限定98部署D。实际仅新增四个执法deployment类、ProductEnforcementApplication显式import、三个组件测试、专用测试profile及受控启动工具/校验器。冻结ENF领域及咨询/裁决业务代码、库、身份和服务未由本轮修改。

Java组件当前覆盖以C2的Filter36/Policy9与C3的Deployment8组合：53/0/0/0。不是将C2全52与C3八项累加成60；C2旧Deployment7由受影响C3八项替代。相关9个后端实现/测试/入口/pom文件与各自独占构建输入SHA均相等，原XML及源码锚点见report-and-source-integrity.json。Maven C2/C3实际退出0；首次C1=52/0/1/0、退出1（测试反查未处理不存在Import注解），该原报告、日志和独立构建完整保留，未删除断言，只按实际无显式Import情形修正审计。

工具C2=11/0/0/0，独立PowerShell进程实际退出0；C1工具10/0/0/0原结果保留。只操作独占TEST_ONLY文件，测试发行记录/假class字节不是真实发行，未传入产品启动。夹具留存，无测试删除历史、数据库连接、密码读取或容器/产品启动。

## 逐项代码反查

1. plain部署类没有Component/Configuration扫描stereotype，正式执法应用显式导入，enabled未开不注册过滤器；另一产品无该显式Import的组件反查通过。这是限定装配验证，不称完整其它域运行回归。
2. FilterRegistrationBean覆盖全部DispatcherType、/*且HIGHEST_PRECEDENCE/matchAfter=false，在Controller/Spring Security前形成拒绝门。既有TransportFilter亦最高优先级，未声称二者tie顺序已实测；该原filter只校验TLS/权威，不承担OAuth/业务写。实际容器装配/直连顺序仍须隔离I验证。
3. 精确method/path/query及对象版本白名单；请求路径编码/点段/分号、参数重复、方法覆盖、外对象、任意业务POST和GET导出均拒绝。允许页的内部forward仅index.html；ERROR仅既有允许请求的error渲染，保留原503，不派至业务；未知INCLUDE/forward拒绝。
4. manifest逐字节SHA、完整字段集、重复JSON键、过期、revision范围/溢出、对象及资产通配拒绝；运行中到期同样拒绝。白名单不新造角色或改变原Grant，所有允许请求继续原身份/current/session/watermark/SHA链。
5. 正常login POST不读取参数体；日志只有固定route ID/结果/请求UUID/策略SHA，不记URL查询、密码、token、code或verifier。token仅批准authorization_code及现有client/callback，consent默认拒绝，实际client须与政策要求完全一致。
6. 启动Bean明确要求正式身份与执法启用、固定issuer/audience、现有ACTIVE public PKCE客户端/OpenID/callback、Importer有效空值、cleanup=false、loopback/TLS及SQL init/migrations/method-override关闭。缺项或不符失败关闭，不初始化主体/client/Grant。
7. 受控launcher限制原5444/5311，拒绝5319/跨库覆盖；原非受控模式未替换业务合同。控制参数实际数组展平/Windows引用经AST执行组件核查，Importer空CLI不被旧非空清单覆盖。只消费逐件SHA和发行证据固定的classpath，无自动Maven解析/任意target扫描；已有retention缺失拒绝生成。
8. 新受控日志/PID使用独占UUID前缀，不覆盖历史server日志；秘密注入之后有finally清除进程变量，不打印秘密。当前11项工具只证明相应窄路径，未实际解密或启动JVM。

## 未覆盖与停止点

Docker Linux engine named pipe仍不可用（tools C2后的只读probe实际1）。没有启动共享Docker、原5311/5312、5444或旧卷；没有运行87～95恢复/测试工具。真实DM8/OAuth/浏览器I、startup前后域数据零变化、DB与当前独立权威、新卷停机保全、可信完整发行物/前端托管仍未实测，不用组件结果替代。

已知13原件SHA沿用97，最新历史NOT_PROVED不关闭；来源/专业确认和正式B仍Pending，无模型代签或新增审批字段。剩余安全推进顺序仍是98设计：引擎可用→独占隔离I→原卷保全/核原ID与配置→原两库进程恢复和新SELECT→发行及有效配置固定→条件式5444/login/read前后对账。任一条件失败停其步，不改主库、权限或未知历史。

本轮前置及实现后verify-all实际1/TEST_EVIDENCE_STALE:project，47/41基线一致。此轮没有完整全仓Maven新证据，不把专项组件XML推广全仓新鲜或产品恢复。历史20环境errors/119skip不删除、不复活旧报告或篡改mtime；后置原日志另存。

结论：批准部署控制的限定实现与上述组件/工具切片复核通过；ENG/REC/IMP仍进行中，真实I和受控读取出口未过，不能记RUNTIME_READY或冻结。

后置verify-after.log实际退出1，仅TEST_EVIDENCE_STALE:project；47文件/41台账一致。当前全仓门禁仍未通过，不假称失败只来自旧污染；本轮新增后端源没有完整全仓最新执行证据，专项组件不替代。最终记录复核原日志另存verify-final-records.log。
