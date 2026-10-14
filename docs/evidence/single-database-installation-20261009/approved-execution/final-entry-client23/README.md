# 三入口公开启动接续输入（仅准备，不是执行回执）

归属：主计划 `IMP-SINGLE-DB-DEPLOY-REFERENCE-01`；技术D见正式部署ADR §6.11。唯一进度账仍为总开发实现计划。本目录是输入、来源、原字节和检验原件，不是另一状态表。

## 现接口可直接读取的输入

- `consultation-workspace-client-body.json`：现 `ProductClients.Input` 原body，客户端ID从正常咨询PKCE来源继承。最终回调 `https://consultation.agent-platform.internal:5446/app/consultation`。
- `adjudication-workspace-client-body.json`：同接口原body，ID从既有正常登记源码继承。最终回调 `https://adjudication.agent-platform.internal:5448/app/adjudication`。
- 两域 `*-build-public-environment.json`：现Vite所读环境变量和值，不是秘密。构建使用副本，不写共享target。
- `public-client-and-binding-inputs.json`：源SHA、三域精确Issuer/回调、正常维护主体/证书/凭据**引用**、请求header、容器manifest/五账号bootstrap环境和原件差异。它不是launcher配置，不可把它送给launcher声称准入。
- `*-inherited-bindings-not-launcher-config.json`：原17只读挂载、受管引用和非秘密environment继承片段；刻意不提供新JAR/properties/SHA或真实初始化/库存回执，避免假可执行配置。现compiler产出与原properties字节相等时可继承原件，否则保存新文件并stage新名；绝不修改17原件。

本批只定位现正常维护私钥store/DPAPI引用并核文件摘要，没有解密或读取密码；证书/private-key配对和DPAPI可用性留到获准运行加载。`domain-references-*10.json` 的 `sourceSignerStore` 是CA签名store，**不能拿CA冒正常维护主体**。本目录另定位原维护目录的maintainer.p12，配对未经本轮实际加载检验。不能把公开maintainer.cer当成可发mTLS的私钥证书。

## 按原接口登记（尚未执行）

在真实应用已启动、服务端信任正常且当前维护证书加载/配对确认后，用现PowerShell HTTP接口读取body。以咨询为例（`$maintainerCertificate` 必须是获准加载的真实带私钥证书，不由本文件创建；不跳过TLS校验）：

```powershell
$root='D:\daima\xinji\agent_platform\docs\evidence\single-database-installation-20261009\approved-execution\final-entry-client23'
Invoke-RestMethod -Method Post `
  -Uri 'https://consultation.agent-platform.internal:5446/v2/identity/clients' `
  -Certificate $maintainerCertificate `
  -Headers @{'Idempotency-Key'='single-final-consultation-workspace-20261009-23-01'} `
  -ContentType 'application/json' -InFile "$root/consultation-workspace-client-body.json"
```

保存真实HTTP状态与clientId/version/status回执；请求相同且actor一致时按原幂等合同接续。同ID冲突不能通过换key/覆盖/自动撤销解决。历史register-adjudication-product-operator脚本还会操作密钥、成员、Grant及MODEL_EXECUTE，且锁定旧localhost/凭据root，**不得当本批通用client登记工具执行**。旧product-http helper的TLS回调锁定旧主机/端口，也不能不改源直接调用新域；本批使用原HTTP API和现可信证书链，不引入另一个身份执行器。

## 咨询优先且不放松共同单库门禁

现launcher继续读取真实 `SINGLE_DATABASE_APPLICATION_DEPLOYMENT/v1`，只要求applications为批准域的非空无重复子集；咨询配置可只包含咨询应用的真实材料。完整三域application-settings实际编译结果、完整六schema/十五账号权限库存、初始化/存储/全部目录和资源批准仍必须齐备，不能把子集当缺权限豁免。域UID/宿主端口按原固定映射而非子集序号：咨询10001/5446、执法10002/5447、裁决10003/5448。后续执行入口仍是：

```powershell
# $actualSelectedDomainDeployment 指向获准执行时由真实原件组装的新JSON；本目录没有伪造它。
./scripts/start-single-database-applications.ps1 -Configuration $actualSelectedDomainDeployment -ValidateOnly
./scripts/start-single-database-applications.ps1 -Configuration $actualSelectedDomainDeployment
```

ValidateOnly只做配置校验，不读解密秘密、不访问Docker、不启动应用；真实启动另取证。不得把候选16的SHA复制成新三域独立包SHA；旧candidate16/formal17保持可恢复原件。

实际依赖链：原授权自然退出完整674 SUCCESS/逐项链/真实回执/进程退出/双锁自然释放 → 新鲜完整权限 → 现shared-instance正常维护bootstrap及真实存储/初始化证据 → 构建并stage新独立咨询包/JAR、现compiler绑定新真实材料 → 受管启动咨询 → 正常SUBJECTS维护证书client登记 → 当前正常成员/授权/来源权利下PKCE及无模型Input首次操作。构建输入的公开client ID可以前置确定，**实际HTTP登记不能先于提供该API的应用启动**。

执法新workspace/job-pack及各页面的精确client ID在本批限定来源中尚无批准原件，保持null且不生成空body；单回调合同不允许workspace和job-pack共用ID。这不是新的模型、专业角色或业务用途决定，也不拖咨询。具体责任为现登记部署维护主体；退出为确切ID/页面启用绑定的来源或正常维护登记原件。模型专业计算、真人正式受理、任命与资料授权仍分别核原门禁。
