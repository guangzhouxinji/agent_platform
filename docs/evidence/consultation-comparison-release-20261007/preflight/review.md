# PC-COMPARISON-RELEASE-PREFLIGHT-01 离线前检工程证据

日期：2026-10-07。本文件仅为本次专项测试及字段合同证据，不是任务、Gate 或批准状态台账。

## 实现及实际字段合同

- 唯一部署入口在任何 Docker 调用之前调用 Get-ConsultationDeploymentSettings；ValidateOnly 在离线检查后返回。实际部署继续执行原有 Docker 身份、网络归属、完整证据 manifest、实际挂载 JAR、镜像 volume、只读与回退检查。
- 配置根必须为字典；schema=CONSULTATION_CONTAINER/v1；settings 必须为字典，环境键为大写字母/数字/下划线，标量值保留原转字符串行为；secretFiles 可省略，存在时必须为字典。
- PRODUCT_SOURCE_EVIDENCE_ROOT 精确为 /product/evidence。
- PRODUCT_CONSULTATION_COMPARISON_ENABLED 为已有功能开关；省略保持未启用，提供时只接受 true/false（不区分大小写）。不要求任何新增 false 标志，不把关闭计算视为计算获准。
- 显式开启计算时，PRODUCT_IDENTITY_ENABLED、PRODUCT_SOURCE_ENGINE_ENABLED、SERVER_SSL_ENABLED 必须 true。
- PRODUCT_CONSULTATION_COMPARISON_APPROVAL_REF 为证据根相对引用，匹配 [A-Za-z0-9][A-Za-z0-9._/-]{0,499} 且不得含 ..；不允许绝对路径、URL、反斜杠、编码分隔符或换行。
- PRODUCT_CONSULTATION_COMPARISON_APPROVAL_SHA256 为精确 64 位十六进制，可大小写。
- PRODUCT_CONSULTATION_MODEL_API_KEY、SERVER_SSL_KEY_STORE_PASSWORD、SERVER_SSL_TRUST_STORE_PASSWORD 必须非空白。沿已有 settings 明文或 secretFiles 受管 CLIXML 引用解析；接受 SecureString / PSCredential 密码，不接受普通对象、缺失/空引用或同键双重配置。只在本进程内使用，不写环境、不复制凭据文件。
- SERVER_SSL_CLIENT_AUTH 为 want 或 need，不新增 Worker transport 模式。服务器证书库 SERVER_SSL_KEY_STORE 与信任库 SERVER_SSL_TRUST_STORE 为容器内绝对文件路径或 file:/、file:/// URI，必须各自精确对应一个既有 mounts.target；mounts.source 为存在的本地文件。拒绝远端 URI、路径穿越及缺失/重复挂载；沿现有部署 args 只读挂载，不改变对象/网络/监听。
- 不要求或新增 Worker 选择、领取、启动、后台运行字段或标志。普通用户选择/启动到服务执行合同未闭合，继续门禁；既有人类逐候选 delegation API 不被本脚本当作启动合同。

## 权威及检查边界

现有源码依据：ProductComparisonMatrixConfiguration 的 identity/source/comparison 条件及模型凭据；ProductSourceEvidenceFiles 的相对引用/SHA 词法合同；ProductComparisonWorkerTaskAuthority 的 secure request、可信容器 X509Certificate 和 clientAuth 检查；已有部署使用 SERVER_SSL_* 设置。

本 helper 只证明离线配置形态、凭据可解析/非空和挂载引用存在，不读取批准正文、不判断当前 Task/候选批准，不延续旧批准，不启动 Worker、不外呼模型。主会话报告新增的 MatrixConfiguration Bean 对批准文件实际 SHA/期限、HTTPS/OPENAI_CHAT_COMPLETIONS 的启动校验及运行逐次授权仍为各自权威；本专项没有测试或替代这些业务检查。

ValidateOnly 不再输出 Docker readonly/完整证据/DB 已验证标记，只输出 offline_deployment_configuration=verified 及未执行 runtime approvals / Docker checks 的明确提示。真实 Docker 校验仍仅在实际部署分支执行。

## 实际测试

命令（工作目录 D:\daima\xinji\agent_platform）：

    node --test --test-reporter=tap scripts/lib/consultation-comparison-deployment.test.mjs

最终进程退出码 0；64 tests / 64 pass / 0 fail / 0 skipped / 0 todo；67233.0631 ms。完整机器输出：node-test.tap。

真实 PowerShell 解释执行 helper 和入口，使用临时 JSON/JAR/证书库存在性文件及本机临时 CLIXML；所有 docker 调用由抛出边界标记的 PowerShell function 拦截。测试覆盖合法 ValidateOnly 不到 Docker、合法部署到 mock Docker 边界、缺凭据/坏 flag/ref/SHA/TLS 在两种入口提前拒绝、SecureString/PSCredential、空/错/丢失/重复凭据、环境及配置无修改、示例凭据不出现在 stdout/stderr、无计算时不新增 Worker 准入、既有部署保护条件保留。

TLS 文件仅为存在性 fixture，不是真实证书；保留回退逻辑以源码断言验证，没有执行真实回退。无真实 Docker、DB、业务请求、模型调用、正式批准、证书信任或业务验收。

## 留给主会话的检查

按授权未运行 verify-all、全量、构建、package 或正式部署；主会话统一执行强制检查和 package 检查。真实证书/批准/迁移/部署/回退及业务验收不在本专项完成范围。未修改主计划、ADR、requirements、后端、前端、共享 assets 工具或既有测试。
