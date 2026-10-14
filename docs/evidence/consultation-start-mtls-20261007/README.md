# VER-CON-USER-START-MTLS-01：真实 TLS / 持久存储工程切片

日期：2026-10-07（Asia/Shanghai）。调度和任务状态仅由主会话维护主计划；本文是验证范围/原件说明，不是第二套任务台账。

## 调查结论与选用环境

已只读核对 AGENTS、主计划 V10.14 第0节/7.8.3/第8节、总体架构/治理规范、`consultation-comparison-input-20261005` ADR、ProductComparisonStart*、ProductRetrievalMtls，以及既有工程专项配置。用户通知前置 verify-all 退出0后新增限定测试。现有启动生产配置依赖 ProductComparisonModelAdmission 的批准配置，并要求 lease 大于批准模型 timeout；此次没有新正式模型/来源/Task授权，因此不能把工程身份或存储 fixture 当成正式准入，不能自动装配正常计算服务冒称通过。

依赖复用仅限已冻结 jvm-six-snapshot-03 的第三方 jar（逐文件 SHA 见原件）；**没有复用旧项目 classes 或 target 输出**。当前 772 个生产 Java 源与两个新增测试分别 SHA 固定并独立 javac 编译。第一次脚本参数文件引用解析失败的原件留存，第二次编译在 build-02 中进行。脚本未调用 Maven、Docker、现行容器、现行 DM8 或正式模型。

## 实际连接图与严格范围

`无服务私钥的工程 HTTP 客户端 -> JDK HTTPS 工程入口适配器 -> 实际 ProductComparisonStartController -> 实际 ProductComparisonStartApplication -> 实际 ProductRetrievalMtls(HttpClient/PKCS12) -> needClientAuth=true 的另一真实 JDK HttpsServer -> file-H2 -> HTTPS GET 原键读回`

- 两台 HTTPS 服务均绑定本机动态端口。独立工程 CA 实际签发 serverAuth/localhost SAN 和 clientAuth worker 证书；SSLContext 使用真实 PKCS12 KeyManager/TrustManager；从 TLS session 读取 peer principal，不设置或伪造 Servlet 请求证书。
- 人工 HTTP 客户端仅有工程 CA 信任根，没有服务 client key；服务 key 只由受管 ProductRetrievalMtls 使用。这里不声称已做真实浏览器证书存储/E2E检查。
- 外层是 **JDK 工程 HTTP adapter**，不是 Spring/Tomcat Servlet 部署。工程 bearer->Jwt 映射是测试适配，不验证生产 OAuth/JWT 链。
- 输入、当前权限、任务目录、委托签发、结果读取端口是明确的 Mockito 测试边界。**撤回验证只证明实际启动 Application 消费当前端口结果，不证明真实 ProductAccessPolicy/来源库撤回端到端。**
- 服务端 claim endpoint 是工程处理器，持久化确切请求键/attempt/fence，**不是实际 ProductComparisonMatrixController / CandidateExecution 域 claim 授权**。不会构建假 Servlet certificate，不替换 ProductRetrievalMtls.Transport。
- 命令 store 是实际 ProductComparisonStartCommands 和 file-H2 事务，使用真实 V095/98/99/100/102/103 SQL；辅助父表只覆盖该工程存储所需列。每条 DB 连接关闭后 H2 文件保持，重建实际 store/application 后 GET 验证，不使用内存 map/cache 充当持久 receipt。
- attempt、委托、输入及 COMMITTED closure 均是显式工程 fixture。已提交 GET 场景验证实际存储闭合校验后的只读恢复，**不是模型计算完成、来源合法性或专业矩阵结果通过**。
- 模型调用数严格为0；execute HTTPS工程端点返回403 NO_MODEL_APPROVAL，只验证 HTTP错误->原键对账，**不声称真实模型准入器已部署并拒绝**。
- 数据/业务 Clock 固定在工程 NOW；90秒超时是未修改的实际 ProductRetrievalMtls 墙钟超时，**不覆盖真实墙钟 task/source/delegation lease expiry**。

## 新增测试应验证的出口（以原件实际结果为准）

1. TLS require-client-auth：无客户端证书和不信任服务器均在 handler 前拒绝；产品 Transport 无信任根反例。
2. 服务端已持久化 claim 后断线：回执 RECONCILE_REQUIRED、确切 attempt/fence、同键不重发、异载荷409；重建 file-store 后 GET 相同回执。
3. 当前权限/来源端口撤回：HTTP读拒绝，不再发送 worker 请求（仅 Application端口边界）。
4. 显式工程 SQL 取消 epoch 在迟到 claim 返回前改变：真实命令 store 拒绝 bind/commit，HTTP GET 仅返回取消坐标；不读失效 input。**不是产品 cancel API或迟到模型提交端点整链**。
5. 实际90秒 Transport timeout：服务端已持久 attempt，原键 POST/GET 不重发。
6. 显式 storage-only COMMITTED fixture：真实 commitFromExactReceipt 闭合校验后重建store，GET不发worker、不调用模型、不重算。
7. mTLS claim真实返回，execute HTTPS 403 工程拒绝，GET/同键回放只对账。

## 尚缺的真实产品整链

正式 Servlet HTTPS 入口和生产 JWT/security chain；真实 task/input/source-policy 和委托消费；实际域 claim/lease/model-admission/矩阵执行与正常批准模型；通过产品取消 API 的取消和迟到模型结果拒绝；正式 DM8 及服务进程重启/浏览器只读恢复；完整真实 Task/source/worker lease 到期。故 **productHttpsFullChainCompleted=false**，不得把本切片标成父任务 PC-COMPARISON-USER-START-01 或产品验收完成。

## 原件与复现

- `原件/compile-01.log`、`compile-01-exit.json`：脚本 javac 参数 quoting 失败，未运行测试。
- `原件/compile-02.log`、`compile-02-exit.json`：当前源独立编译。
- `原件/compiled-source-before-02.json`、`compiled-source-after-02.json`、`compiled-source-stability-02.json`：编译输入逐文件 SHA 与并行源码变化检测。
- `原件/dependency-jars-02.json`：仅冻结第三方依赖 jar SHA。
- `原件/focused-02.log`、`focused-02-exit.json`、`focused-result.json`、`focused-junit.xml`：实际专项结果；runner是反射执行 JUnit @Test/lifecycle/assertions 的隔离入口，**不是 Maven Surefire 报告**。
- 两次执行脚本分别保留；拒绝覆盖已存在独立输出目录。后续复现必须新建唯一 build-NN 并同时新建原件文件名，不能覆盖首次原件。
- CA/worker/server PKCS12、私钥、CSR、工程 keystore 密码仅在 JDK临时目录，finally清除；keytool输出抑制，未复制任何正式凭据至证据。H2工程文件在各测试后清除。

业务 implementation、迁移、既有测试、ADR、主计划未由本切片修改；新增代码仅两个唯一命名 test 文件。专项结束后的 verify-all 为结构/基线检查，不证明业务/法律/专业正确性。

## 本次实际结果

2026-10-07 18:15:34–18:18:26 +08:00 专项退出0，**7项/0失败/0跳过**；实际产品90秒timeout场景墙钟93227ms；772个生产源和2个测试独立编译退出0，编译输入SHA前后一致。产物目录build-02；真实模型调用0。

Windows Java argfile按平台字符集读取中文参数，造成原结果写入“鍘熶欢”子目录。仅两个结果文件随后在限定证据目录内逐文件移动至“原件”，原字节与SHA完全一致，见原件/output-path-normalization.json；两次实际脚本及失败原件未覆盖。此路径编码问题不改变专项执行结果。后续执行宜将Java输出参数换成ASCII独立产物路径，再由PowerShell -LiteralPath按字节保存到中文原件目录。

本切片不执行会写共享docs/project-plan.json的verify-all，后置完整结构检查请协调主会话集成后执行；不把本专项通过当作结构/基线检查完成。

## 主会话指定 round5 libs 的当前源重验（最新）

2026-10-07 18:23:41–18:26:32 +08:00，独立build-03执行退出0，**7项/0失败/0跳过**；实际90秒timeout场景93176ms，worker请求1次，模型调用0。

按主会话指定使用 isolated-build-round5/lib 的现成第三方jar；排除历史agent-platform项目jar，补充已有冻结依赖中的H2/Mockito/ByteBuddy/Objenesis共5个jar。772个当前生产源及2个新增测试已逐字节复制冻结，六份SQL也复制冻结；执行从build-03读取副本SQL，因此主target和主会话的当前源均不被占用/修改。source-stability-03确认编译副本未变化，主工作区相对本次snapshot亦无变化。

最新原件：compile-03-exit.json、compiled-source-snapshot-03.json、migration-snapshot-03.json、dependency-jars-03.json、focused-03.log、focused-03-exit.json、focused-03-result.json、focused-03-junit.xml、raw-copy-03.json、source-stability-03.json。Java结果输出改用ASCII独立目录，PowerShell按字节复制到中文原件，SHA一致，不再发生第二轮的中文argfile路径问题。

两新增Java源已固定，可供主会话fresh RBC04一次集成全量。本轮正常真实模型计算仍未执行/未通过，产品HTTPS完整链仍为false；后置verify-all及主计划状态更新由主会话集成后处理。

# 新增真实 Servlet HTTPS/mTLS 映射出口（本轮最新）

**旧JDK handler 7项仅保留fallback，本次Servlet出口只以focused-05原件判断，不以直接Controller/Application调用冒充HTTPS映射。**

新增 `ProductComparisonStartServletMtlsTest.java`。2026-10-07 18:35:39–18:36:33 +08:00 在独立build-05完成4项/0失败/0跳过，模型调用0，编译772生产源+3新增test源退出0。输入/SQL冻结、编译副本与源SHA均无变化。现在三个Java源最终固定，可由主会话fresh RBC04集成全量及后继B打包；子会话没有运行共享全量。

## 实際穿透，不再使用JDK HTTP adapter

`无服务证书的HTTPS工程人类客户端 -> 真实Tomcat HTTPS Connector -> Spring MVC DispatcherServlet -> 实際ProductComparisonStartController /start-commands POST -> 实际StartApplication -> 实际ProductRetrievalMtls -> 第二个Tomcat client-auth=required HTTPS Connector -> DispatcherServlet -> 实際ProductComparisonMatrixController claim / execute 映射 -> 工程domain端口 + file-H2实际StartCommands -> /start-commands?requestId=human-key HTTPS GET`

- Controller对象在独立AnnotationConfigWebApplicationContext内作为实际产品类注册；@RequestMapping由Spring RequestMappingHandlerMapping解析，不由测试handler自行分发；使用真实DispatcherServlet请求体/路径变量/头/主体验证与Controller异常映射。javac启用-parameters。
- 两个Connector都使用真实PKCS12/JSSE和自建CA。Worker Servlet真实读取Tomcat产生的 `jakarta.servlet.request.X509Certificate` / cipher属性；测试从不设置证书attribute或伪造HttpServletRequest。证据记录peer/issuer/serial/cipher及路径，未复制证书/密钥/密码。
- 当前端口注入仍是明确Mockito工程fixture：input/access/task/delegation/result-reader，以及WorkerController后方的CandidateExecution acquire和MatrixExecution authorize/execute。**实际WorkerController映射已执行，不等于真实domain claim/policy/model实现通过。** acquire端口按到达的实际Servlet坐标写工程attempt/fence；execute端口明确HTTP403，无真实模型调用。
- 人类工程Filter实际建立Spring SecurityContext，AuthenticationPrincipalArgumentResolver将Jwt注入实际Controller；没有验证正式Issuer签名/OAuth/生产filter chain，且未运行生产StartConfiguration准入装配。人类客户端不带服务证书；不是浏览器证书存储/页面E2E验收。
- 同键HTTP重放只有读取，异taskVersion/inputSha经真实Controller返回409；GET原键与attempt/fence回执固定。
- 无客户端证书和无服务器信任均在Worker Filter/DispatcherServlet前拒绝，workerServletRequests=0；人类无工程Bearer返回401，不产生命令。
- SQL取消epoch先于迟到实际mapped claim回应：实际命令store拒绝bind/commit，实际GET给CANCELLED坐标；当前READ工程端口撤回后真实HTTP404，不再发worker请求。不冒产品cancel API/真实policy撤回。
- seeded工程COMMITTED closure经实际commitFromExactReceipt验证，真实mapped GET只读，不发worker，不读失效源；source端口撤回真实HTTP409。不冒正常模型计算/矩阵业务结果。

## 首次Servlet失败原件及修正

focused-04：4项中3失败，使用旧工程adapter的/start导致真实DispatcherServlet404（取消场景亦因请求未到达claim超时）；mTLS无证书反例已通过。原件、源码snapshot、退出1均保留。只修正新增测试路径为实际/start-commands，不改产品Controller/共享代码。focused-05四项全部通过，不能用旧7项替代这4项。Tomcat关闭日志有Java模块memory-leak-detection add-opens提示，未关闭校验/未屏蔽失败；本次测试断言及资源stop/destroy均完成。两个迟到请求结束后重建的空临时目录按已解析确切Temp子路径检查为空后非递归删除，原件见servlet-empty-directory-cleanup.json，无私钥/库数据残留。

## 最新原件清单

`原件/compile-05-exit.json`、`compiled-source-snapshot-05.json`、`migration-snapshot-05.json`、`dependency-jars-05.json`、`focused-05.log`、`focused-05-exit.json`、`focused-05-result.json`、`focused-05-junit.xml`、`raw-copy-05.json`、`source-stability-05.json`。当前源码SHA及全部交付文件见file-manifest.json；历史04与03等失败/成功原件均保留。

无需主会话改共享配置即可集成本限定Servlet测试。若继续升级为部署级完整产品验证，需要明确隔离DataSource/schema及V102/V103、正式条件开关、确切HTTPS product-origin、受管worker-subject/keystore/truststore/配置版本/lease（密码仅安全环境注入）、当前合法task/input/source及人类/worker授权对象，并由真实policy/identity/source/domain beans接线。正常模型额度只限制正常计算场景，不能阻止上述已实现的真实HTTPS/MVC/mTLS/持久对账工程出口。

本次只确认 **servletHttpsProductControllerMappingsVerified=true**；**productHttpsFullChainCompleted=false、normalModelCalculationPassed=false**。后置verify-all及唯一主计划状态更新仍由主会话集成后执行，不把结构检查与本机械工程验证混用。
