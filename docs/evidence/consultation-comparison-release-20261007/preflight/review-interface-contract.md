# ValidateOnly / OfflineOnly 接口保留专项证据（2026-10-07）

限定修正 PC-COMPARISON-RELEASE-PREFLIGHT-01；仅改部署入口及其新增独立测试，helper 字节未改。scripts 已冻结。

- OfflineOnly：仅执行共用离线 helper 后返回，输出 offline_deployment_configuration=verified，不发原 Docker 发行检查成功标记。
- ValidateOnly：共用 helper 在所有 Docker 操作前执行；随后保留 DB exact ID、网络归属/原 DB 成员、镜像 volume、完整证据只读 manifest、实际挂载 JAR SHA、可选 private runtime manifest 检查；原检查全部通过才输出原 readonly=verified 标记并返回。无 ps/create/start/replace。
- 正常部署：同一 helper、同一原发行检查，继续原部署流程。独立 stub 在随后 ps 边界终止测试，未执行创建/启动/替换。
- 双开关：配置读取及 Docker 之前拒绝。

最终命令：node --test --test-reporter=tap scripts/lib/consultation-comparison-deployment.test.mjs
结果：退出码 0，75 tests / 75 pass / 0 fail / 0 skipped / 0 todo，69541.5685 ms。
完整输出：node-test-interface-contract.tap。原 node-test.tap 的 64 项结果及原 review/source-sha256 文件均未覆盖；其中旧 ValidateOnly=offline 描述仅为前一版历史证据，本次修正恢复原合同。

新增 11 项接口测试先经定向执行通过，随后冻结源码执行上述整个独立文件；三路坏配置拒绝、保密反例和原有配置测试均通过。Docker function stub 校验原检查顺序和安全参数，并逐一反演数据库/网络/成员/volume/证据/JAR/runtime 失败；无真实 Docker/DB/模型/业务请求，没有构建、全量或 verify-all。本证据不证明真实 Docker/TLS/DB/业务验收。主会话负责 ADR/唯一主计划和最终集成。
