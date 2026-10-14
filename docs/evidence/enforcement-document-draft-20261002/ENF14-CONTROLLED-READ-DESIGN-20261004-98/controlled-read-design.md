# ENG-ENF-CONTROLLED-READ-98-01 最小部署控制详细设计

2026-10-04；技术设计提案，不是实施批准。任务/状态只在docs/08；归属REC-ENF-JOB-PACK-79-01，不修改ENF-14合同或冻结领域。

## 1. 范围与门禁

本会话委派批准只读退出原因核查及最小D设计。96的正常登录/读取条件式运行批准保持，不重问整套重启。新增部署控制实施须用户明确批准本设计范围；此前不改业务代码、不启动原容器/服务或隔离实验。

只允许既有正常OAuth/PKCE和现存 ENF14-FACT-DOCUMENT-20261003-70@1 的页面读取，及明确列出的现存同案JobPack GET；未知更晚历史NOT_PROVED不改变。禁止所有案件/材料/任务/文书写、重复领取、Grant/主体/客户端/签名键维护、专业确认、审核、导出、模型外呼。不新增业务状态或开发页面。

## 2. 原容器安全重新启动合同

97已固定原5311=codex-enf-i-business，数据卷codex-enf-i-business-data；原5312=codex-enf-i-authority，卷codex-enf-i-authority-data。两卷分别映射/opt/dmdbms/data，端口各绑定127.0.0.1。仅恢复这两个既有进程；禁止5319、5367和88～95的旧隔离卷，禁止替代业务库或整库回灌。

97两库FinishedAt分别为2026-10-04T12:38:41.287728118Z和12:38:41.287689962Z（北京时间20:38:41），exit255、OOMKilled=false、错误字符串为空。98本次Docker Linux named pipe已不存在，不能读取新容器日志/events或现时inspect。宿主日志12:38:24Z有starting engine/restarting片段，只证明相邻Docker生命周期变化，不足以判定用户停止、引擎故障、资源不足、数据库启动失败或数据损坏；具体原因保持未证。原97容器快照与98最小化宿主日志摘要保留，绝不根据255断言损坏。

启动前必须：Docker由环境负责人恢复到可查询；新inspect确认原ID/image digest/卷名/挂载/loopback端口与97一致；卷未被其它运行容器挂载写入；原卷停机状态只读保全到独占证据位置，逐件哈希及容量/错误清单保存。不宣称crash-consistent拷贝是已通过完整物理恢复或最新历史证明。不直接读写Docker内部卷路径、不启动旧卷以取证；需辅助复制容器时只允许经归属证明的读取工具、源卷只读挂载，禁止启动DM8进程或挂载其他域卷。

不修改镜像、env、entrypoint、restartPolicy、schema、Grant或权限来强行启动。若只因Docker停止且原配置未变，原两容器启动属于既有运行恢复合同的条件步骤，不再请求全套重启授权；数据库正常启动自身可能恢复日志/写数据库文件，不能记为纯SELECT。OOM/损坏/启动循环/配置漂移/未知共卷写者出现则停此步，另列准确新增操作，不自动修库。

复核卷保全及实际退出/引擎原因线索后才能按第7节条件执行；当前Docker不可用是运行前置，不在本轮擅自启动整个共享Docker以牵动咨询/裁决。

## 3. 选定最小控制：执法启动上下文专属的部署入口过滤器

现有scope、loopback、前端不点击、兜底permitAll均不足证明禁止业务写。新增控制限定在ProductEnforcementApplication显式导入的部署配置与过滤器，先于Spring Security/Controller执行；不在共享扫描中自动装配，不改身份/领域授权逻辑。批准后只改部署入口接线、新增过滤器/策略解析器及执法启动工具参数传递。

输入是固定SHA的部署策略manifest，包含issuer/可用客户端/实际callback/精确对象及revision/允许method+path+query和过期时间。不是业务状态，不改数据库Grant。受控启动模式下manifest缺失、摘要不符、过期、含通配业务写或额外对象、Importer非空或cleanup有效true均启动失败。策略只进一步缩窄入口；每个允许请求仍完整经过原TLS、OAuth、JWT、session、当前Grant、独立水位及原件SHA门禁。

服务绑定loopback只作减暴露；后端自身过滤器对直连5444、Vite代理和其它代理同样生效，不能靠隐藏端口证明不可绕过。校验规范化路径和原始路径，拒绝编码斜杠/反斜杠、双编码、点段、分号、方法覆盖头/参数、重复危险参数。REQUEST/ASYNC/FORWARD/ERROR分派均不得绕过业务入口策略；只允许受审核的内部静态forward目标，不给任意/app/**通配业务入口。拒绝返回固定403/部署控制原因，不进入Controller、不新增领域事件/回执。日志仅记录时间、method、固定route ID、拒绝类别/请求ID和策略SHA；不存查询全文、body、密码、code、code_verifier、token、cookie或私钥。

## 4. 精确入口及副作用

逐方法源码及SHA见route-source-audit.json，不以所有GET安全为前提。

| 入口 | 控制与副作用范围 |
| --- | --- |
| GET /login、POST /login | 使用原表单/CSRF与真实安全会话；POST不记录body。原productUsers只SELECT主体/密码hash供框架验证，工具不读取/输出hash；不登记新主体 |
| GET /oauth2/authorize | 固定既有client、实际registered callback、response_type=code、scope=openid、PKCE S256；原框架处理认证/授权，不绕过 |
| POST /oauth2/token | 只允许authorization_code及相同client/callback，保留框架code/verifier验证；拒绝refresh/client_credentials等本轮未授权grant |
| POST /oauth2/authorize | 仅当实际既有client要求正常consent且最小批准含此路径才纳入；否则默认拒绝，不臆造consent配置 |
| GET issuer discovery/JWKS | 只精确列出实际注册的.well-known/oauth-authorization-server、openid-configuration、/oauth2/jwks；不是任意.well-known/** |
| GET /v2/identity/me | 原session/JWT/水位/Grant查询；不扩大授权 |
| POST /v2/identity/logout | 只原当前sid/jti的session撤销；批准范围含正常退出，不允许管理revoke |
| GET /v2/enforcement/document-drafts/ENF14-FACT-DOCUMENT-20261003-70?revision=1 | 现存@1 SELECT、当前权限及原件SHA；禁止重复/非1 revision或其它参数 |
| GET 同文书/history | 只同一ID；现时SELECT预核必须证明既有head/全历史与固定边界一致，未知新增不猜补；history不是新的版本写入 |
| GET 同文书/candidates?revision=1、/reviews?revision=1 | 现有正式页面read()会调用这两条，分别只读既有候选/审核。读取空结果不等于已生成/已审核 |
| GET /v2/enforcement/job-packs/ENF-JOB-CNIPA-GUIDANCE-CASE-1 | JobPackService.read→required→latest/READ；缺包拒绝，不createPublic。本轮不开放泛任务列表或其它包 |
| 现有/app/enforcement/document-drafts及callback、固定前端资产 | 只已发行前端页面/确切资产，使用原页面登录/读取；实际托管route必须可用且版本固定。不新增只读开发页；若仅Vite存在则如实限定当前产品入口，不冒已发行托管 |
| 其余所有入口/方法 | 默认403。包括prepare/generate/revisions/candidates POST/adopt/submit-review/reviews POST/exports、claim、facts、verification、上传、integration-result、模板映射发布及身份/客户端/Grant/键维护，禁止GET导出下载和未知GET |

POST claim在包缺失时createPublic，新key可增CLAIM事件，必须拒绝；这不是GET自动创建。现JobPack openTask只是GET。事实记录页read()读取history→revision→candidates→reviews，登录完成只me；读取流程不自动prepare或写业务。当前原文书12来源缺项继续保留。

允许数据库写只限正常本次OAuth的oauth2_authorization与id2_session；既有client必须正常consent时oauth2_authorization_consent需单独在同一最小范围内明确，未确认默认不启用。HttpSession安全cookie/CSRF保持原框架。禁止新增主体/Grant/epoch/client/key/authority水位或所有ENF业务行。实际登录身份不是本轮新造身份；不要求用户提供密码，不以测试账号冒真人。

后台控制独立于请求过滤：最终有效public-candidates-path必须为空（旧launcher显式CLI不能被空env抵消）；cleanup.enabled=false；只启动--product-enforcement正式分派，不启默认root/synthetic profile。原件根拒绝被cleanup改写。显式SQL init/migration关闭并核产品实际无自动DDL/bootstrap；环境中的其它writer/未知线程未清楚则不启动。需确认构造器/Runner清单及有效配置，不能只凭源码默认值。

## 5. 发行物、参数与回退

不得用任意target/classes拼装证明发行。需寻找既有发行manifest，固定应用/依赖每个JAR或classes文件集合SHA、前端bundle、classpath顺序、现用case/catalog/profile/模板/规则/原件根、保留文件SHA、issuer/证书公有指纹、现有PKCE客户端与callback。只有Git HEAD/临时classpath或旧server日志不是发行证明。原发行证据不存在/不兼容时保持门禁；不得静默全模块编译并替代原发行。

若批准新增过滤器，受控发行的变更仅该过滤器/导入及参数工具；输入依赖来自被固定的可信现有发行或经另行确认的窄构建源集合，产物另命名/hash/留存，不覆写旧发行。新配置由专用manifest及既有property传入；秘密仅DPAPI进程注入，不存入manifest/命令回显/证据。

启动失败/异常业务变化只停止本轮精确PID，留存配置SHA/日志/前后快照/原件；数据库或历史不回滚、不重跑87、不删新增行、不改Grant。数据库进程停止若会影响其它会话则不自动执行，交环境负责人；保持产品停机是首要回退。

## 6. 批准后隔离验证计划（本轮尚未执行）

依赖ENG-ENF-TEST-ISOLATION-79-01既有防护，但不重跑88～95。每轮随机ID、新专属容器/卷/端口/空schema/文件根/当前归属manifest，禁止5311/5312/旧产品schema/固定历史ID；连接前和清理前均核，失败不finally删除既有对象。独立DM8/HTTPS及正常产品issuer，不用H2/Mock/Stub/skip冒真实链。技术身份只在隔离环境、明确测试性质，不冒本案主体。

C：精确method/path/query、canonicalization、分派/override、manifest损坏/过期、issuer/client/callback不符、effective Importer/cleanup冲突和上下文仅执法装配。必须证明其他应用不装配本过滤器，不改其代码/身份/服务；兼容验证只隔离测试。

隔离I/浏览器：原OAuth/PKCE login→callback→me→现存隔离测试文书/history/candidate/review/pack读取，正文/版本/原件SHA比对；正常logout当前session撤销。真实HTTP直连与代理分别尝试业务写、claim缺包、GET未知/导出、编码/方法覆盖/forward，均在controller前拒绝。startup前后/login后：全部业务表/原件摘要不变，事件回执0新增，只有已列OAuth/session差异；权限水位/Grant/client/key不变。预期拒绝以测试断言记录，不算产品失败；执行错误/skipped不得记通过。

数据库列变更与文件变更逐项对账，报告tests/failures/errors/skipped及独占原XML/截图/响应脱敏摘要。隔离I不证明当前本案产品READY，也不替代前后主库SELECT。当前Docker不可用且新增D未批准，结果NOT_EXECUTED，不造XML0/0通过。

## 7. 两项前置通过后续行

固定原容器/保全与隔离验证、部署D/范围批准→仅原两库进程恢复→新连接SELECT原16可信行/文书@1/当前权威及digest（不回灌旧581）→当前原件/manifest/授予用途和过期核验→固定发行/有效配置启动5444→启动后零业务变化→正常有权会话OAuth/PKCE登录→既有文书页面读取→读取后业务/原件/授权一致，身份会话变化另计。任一项失败停在对应步骤，不冒RUNTIME_READY。

## 8. 唯一新增批准问题

是否批准本设计限定的“执法启动上下文专属部署入口白名单过滤器及必要启动参数传递”，允许既有正常OAuth/PKCE登录、当前会话退出和上述现存对象读取，默认拒绝全部业务写与绕过路径，并先在归属已证明的独立DM8/HTTPS环境验证；不修改冻结领域、现行Grant或其它产品，隔离验证与原卷保全/现时SELECT全部通过后才执行已经批准的条件式恢复读取？

这不是重问整个恢复流程，不批准本案revise/adopt/法律确认/审核/DOCX；具体client是否需consent只由现有配置证明，任何超出本表身份写必须另停。Docker恢复及退出确切原因无法从现有日志证明时明确环境owner/exit，不猜秘密或历史。

## 9. 本轮实际验证范围

前置verify-before.log实际1：咨询两项COMPLETED_WITHOUT_EVIDENCE及TEST_EVIDENCE_STALE；后置verify-after.log实际1，仅TEST_EVIDENCE_STALE:project，47文件/41台账一致。前置错误原件未改；其它会话证据修正不冒本轮修复。未运行C/Maven/浏览器/隔离I或启动原两库，隔离验证结果NOT_EXECUTED（新增D未批准且Docker不可用），不是skipped通过。最终记录复核日志另存verify-final-records.log。
