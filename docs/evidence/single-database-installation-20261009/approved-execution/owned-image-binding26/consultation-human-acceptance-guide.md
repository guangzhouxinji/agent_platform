# 咨询人工测试验收说明（正常HTTPS已通，普通业务待授权）

证据更新日期：2026-10-10，Asia/Shanghai。本文是人工测试操作与证据说明，不是实施计划/状态台账；任务控制仍仅docs/08。**现在可打开正常咨询页面，HTML及JS/CSS均已真实HTTPS核验；正常维护mTLS及client登记通过，但普通用户登录和业务操作尚未验收。** 不把人工测试活动等同产品人工受理/回流；后者仍受当前D门禁。

## 1. 固定版本与真实可访问状态

- 当前源咨询独立产品JAR：`shared-bootstrap25/agent-platform-consultation-product-20261009-25.jar`，151786998字节。
- SHA256：`69d8462924cd920bbda3d501b92b0b3f492f49844f1f3e7ca811f6dd7c217e1d`。
- 唯一咨询HTML/前端SHA：`4ac2c752f350f2c142d6740039d72b587d46990ad96696af795e8d07d8dd39a1`；前端及62运行库继承24，未重复构建。
- 既有props原件SHA：`60dd2358995082d7873e164586b8ce6ba43ffe6bd91d8b634127b641625061bc`。正常runtime readonly挂载，不用开发配置替代。
- 期望入口/回调：`https://consultation.agent-platform.internal:5446/app/consultation`，Issuer：`https://consultation.agent-platform.internal:5446`，仅发布本机127.0.0.1:5446，不扩大外部暴露。
- **上述地址已是本机正常HTTPS可访问地址**。当前固定25容器`9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f`经完整受管same-ID恢复后Running；原失败`ef041b814cf38d8bef01762214c3799c1c8297d171b0417771655e8251717da4`可逆rename保留，首次9b缺空目录失败log也保留。唯一679透传角色实际127.0.0.1:5446已绑定；严格CA/SAN/原server SHA与维护mTLS通过，原client登记ACTIVE/version1。普通OAuth或首次业务成功回执仍未成立。
- 当前为engineering-candidate验证产物，不是B最终运行发行或专业/业务验收完成。

## 2. 已经实际成立的基础

1. 原105迁移、六schema/15独立账号/266表及674对象权限完整。674自然退出、1348事件/逐项/SHA链完整，无重授/删锁。
2. 正常咨询维护主体于2026-10-09 20:52:05北京完成真实bootstrap。CONAUTH维护主体/水位/审计各1行；组织ORG-CON-PRODUCT、证书e02b…d4de、SUBJECTS/GRANTS/KEYS/SOURCES与原manifest一致；READY/generation0/空历史。七个业务身份表仍0。不是三域身份全部完成。
3. 真实owned-image卷/loop ioctl背文件device+inode/原UUID/3GiB/root:root/0600/容量/三域files UID与历史ENOSPC已精确绑定，无再次在线打满磁盘；实际storage消费者无扩容前置通过。
4. 整套真实installation/application-settings绑定、ValidateOnly以及单域资源/挂载/网络检查通过。70证据准入专项、原launcher136回归通过。
5. 实际首次create因Docker Created态NetworkID为空而前置拒绝，已修创建态/运行态分核及同一Created容器恢复（21新专项、12受影响回归）。同ID于21:58:39受管启动命令及真实NetworkID复核通过；并非HTTP/业务通过。
6. Java随后的TLS错误、同路径挂载类型探针和原失败字节均保留。原shell AND列表缺陷已复现并改为显式if/exit78；真实/bin/sh的10个非秘密正反例全部通过。此修改未自动部署到旧失败容器command，禁止手工restart旧失败容器绕过受管恢复/替换。

主要证据：
- [真实bootstrap对账](../shared-bootstrap25/actual-bootstrap-reconciliation.json)
- [安装与身份分层回执](application-initialization-receipt27.json)
- [当前owned-image实测绑定](owned-image-binding-proof27.json)
- [同ID受管启动回执](consultation-resume27-receipt.json)
- [Java实际运行失败原件](consultation-runtime27.log)
- [相同应用Source的真实文件类型](exact-mounted-server-reference28.log)
- [显式shell失败关闭真实专项](actual-shell-predicate-tests28-02.log)

## 3. 入口已恢复，历史失败保留

原Windows逻辑/物理接线、空source-evidence目录和唯一透传角色已在批准范围内解决。现在原9b/DB均未重启、网络未变，新增679不读取私钥、不终止TLS，也不扩大应用egress。总目标运行资源5.125GiB/2.8CPU；窗口仍截至2026-10-10 18:00北京。

2026-10-10 02:27:47北京：宿主JDK21标准JSSE只读加载原P12到内存，CA/hostname/原server pin均通过；原正常维护身份实际登记client，ACTIVE/version1。错误CA/hostname实际拒绝，无维护证书的维护请求403。Windows Schannel EphemeralKeySet失败是宿主兼容问题，原失败保留，未通过持久导入或复制私钥解决。

证据：[正常mTLS与出口](local-tls-publish33/java-normal-https-result.json)、[原登记回执](local-tls-publish33/java-client-registration-response.json)、[编译实际exit/来源](local-tls-publish33/java-https-preexecution02.json)、[JS/CSS与固定25逐字节SHA](local-tls-publish33/normal-static-resources-result.json)、[原PID/StartedAt/网络不变](local-tls-publish33/post-https-runtime-invariance-result.json)。完整浏览器JS交互、普通成员登录与业务验证仍不能据此勾选通过。旧版说明完整原字节已保存在local-tls-publish33/human-guide-before-normal-https.md，不删除历史失败。

## 4. 正常角色和安全登录

- 部署维护主体`deployment-maintainer-20260928`仅按正常维护证书及现时SUBJECTS/GRANTS/KEYS/SOURCES职责操作现有维护接口；不是普通业务用户，也不是专业人员任命证明。
- 已准备client23正常公开客户端：`consultation-workspace-product`，PUBLIC/NONE/authorization_code/S256/openid，callback为上列正常HTTPS入口；公开ID不是密码。
- 已用原受管maintainer.p12/credential引用完成实际配对/mTLS，通过现`POST /v2/identity/clients`与原body/Idempotency-Key登记，ACTIVE/version1。不运行历史泛授脚本。
- 普通登录须有当前目标已激活的证书绑定成员及当前Task/DataScope/业务Grant；最近已保存bootstrap后只读观察的七个业务身份表均0（历史观测，不冒充此刻全库重查），本轮client操作不创建成员或业务Grant，**尚未有该注册/授权证据**。不能拿SYSDBA、部署DB账号、维护主体或handoff服务证书顶替普通用户。
- 前端使用正常`/oauth2/authorize`和`/oauth2/token` PKCE/S256。普通浏览器须正常信任批准项目CA并使用正常成员证书；不能通过忽略TLS错误/SkipCertificateCheck或修改回调临时跑通。
- 人工验收组织人还需明确本次测试成员的确切subject/组织/证书及目标Task/来源/用途/动作/时效；已批准替代资料不是自动生成该成员或万能Grant的批准。缺专业任命不阻塞独立的技术测试，但不能自行授专业身份。

## 5. 获准输入和保留/窗口边界

输入集`SINGLE-FINAL-SELF-AUTHORED-20261009-V1`的四文件/确切SHA继承原resource-request与人类批准，不改原请求中的申请态字段为结果回执。
咨询两文件：
- technical-disclosure，SHA`b06bf7e3e1bbb72aed96945c672f51b7ea1d3487770fc427d154c9f7daea350e`，项目自编741字节，技术材料原文选区/人工确认/不可变Input。
- candidate-claims，SHA`ef3e24bc78b9652b537fe47bcba26847ebb88a4c1df845c7b31c0527917fb62d`，项目自编757字节；仅在正常Source owner/scope/epoch/用途准入后使用CLAIMS的DISPLAY/COMPARISON，不推断TITLE，不冒真实专利或专业金标。

限定内部功能验证，不导入历史过期资料/旧结果，不因输入批准打开模型/法源/专业计算或正式签发。现时Source授权记录、当前Task/Grant须另核。本窗口截止**2026-10-10 18:00北京**，不自动延长；资料保留上限**2026-10-14 18:00北京**不等于运行窗口续期。

## 6. 门禁解除后的人工步骤（目前均不可标通过）

| 步骤 | 正常操作与预期 | 当前实际证据/边界 |
|---|---|---|
| 1. 版本/连通 | 核25包/props/挂载SHA、正常UID/资源，DNS唯一127.0.0.1，TLS链/SAN/有效期正确，Issuer一致，入口加载唯一咨询页 | 固定25/props不变，实际端口、标准TLS、HTML/JS/CSS原字节SHA与Issuer均通过；完整浏览器交互另验 |
| 2. client/登录 | 正常维护身份按client23原POST登记；普通成员证书完成PKCE，错误证书/失效成员拒绝 | client已真实登记ACTIVE/version1；普通成员/业务Grant依据仍未齐，普通OAuth未验 |
| 3. 原文Input | 普通成员选确切Task，使用获准材料原文/版本/选区，保存并读回不可变Input与版本/SHA；不得发送模型请求 | 现源UI/消费者工程证据可继承；当前新实例首次操作未执行 |
| 4. 来源/权限 | 缺来源授权、用途不符、跨Task/失权拒绝；不得显示缓存旧结果或假候选 | 历史切片反例仅历史适用；当前新实例反例未执行 |
| 5. 無模型边界 | 明确未准入模型/计算保持关闭，必要未完成项可见；不输出伪模型答案/空矩阵冒成功 | 当前部署设置模型/语义/重排/计算均关闭；运行界面/首个请求未验 |
| 6. 撤权/重启 | 正常维护合同下撤确切Grant，当前响应拒绝；受管重启后撤权仍有效，原Input/历史版本按当前权限可读 | 当前实例未执行；不能复活原674/bootstrap或以测试替身证明 |
| 7. 检索/模型/矩阵 | 只有来源权利/当前作用域、模型预算/方法/资料及本Task D同时满足，才测真实检索/解释引用/非空矩阵/专业效果 | 这不是本轮无模型烟测已交付结果；不得因人工验收优先级开放未准入分支 |

## 7. 全咨询范围与已有切片的差异

CON-01～CON-12全项D/验证/验收继续以docs/08第8节为准，未被本说明升级。商标/案例库、商标检索/判断、完整配置与提示词治理并非“部署一个咨询页面”即实现。

已有真实切片：旧正式OAuth/Task/模型补问/检索/引用链在IMP-CON-LLM-RETRIEVAL-01、IMP-CON-SEARCH-02有原版本实际证据；当前授权/来源已过期或另有未观察的Golden/人工成本，不能复用为新单库验收。10月8日正式Input/单候选start/结果消费者与中文工作面有源码/专项/工程包证据，当前25继承对应前端；这不证明当前源有非空矩阵、专业准确性或实际人工回流。旧DevHuman*固定意见/退回是历史开发行为，不是最终产品人工受理D或验收。真正产品人工源证/详细D尚未通过，不自动移植旧演示路径。

## 8. 全局未退出项

`verify-all`最新仍`TESTS_FAILING:project`和`TEST_EVIDENCE_STALE:project`，47基线/41需求一致，不称全绿；真实发行前仍要准确处置原失败/证据时效，不以skip求绿。三域全量回归、完整停写/原生独立恢复、最终发行、A/B/C与专业/业务验收未退出。当前可打开并检查页面；提交材料等业务测试须先解除第4节普通身份/权限门禁。