# v342正式产品来源完整性请求（2026-10-02）

对应IMP-CON-SEARCH-02，沿docs/70 §3.1、§3.2及docs/02 §58、§59已有窄D。没有增加路由、用途、身份、库或卷。

## 实际部署

发行agent-platform-api-search-v342.jar，SHA f8da0f59d2068a9551ed924f6216078929622bbc6fe05495474e2b8208b974e6。现运行API容器4cacec1b1467fcae9b0f9493e15c571409d7b245f9135eb0862afda8f28b86bb，旧应用agent-platform-consultation-api-previous-20261002163054保留。5301/5302双库原ID不变，没有新建或删除数据库、容器卷或业务数据。

单一Linux完整原件Root追加候选28文件，原2523文件逐SHA保持，总2551文件。source-v342.sha256完整清单SHA f0ae271505b204d14d04a54907ca84933c6fd54af3adc7689753d7aaf47cfbef。正式部署脚本核jar、双库ID、完整Root和只读挂载。canonical配置已同步v342，v330配置另存原件；配置无明文秘密。package使用skipTests，仅构建发行，不冒新全量测试。

## 正式维护请求

使用已存在正式维护证书和产品mTLS链，通过5443正式Source候选/VERIFY/读取路由；没有component actor、测试profile或身份旁路。

- sourceId HUPD_PUBLIC_ARCHIVE；contractVersion hupd-native-contract-v342。
- 采集版f570a84b03663180b6034c1f7f4c15864f94385e:2026-10-02T07:02:26.747Z。
- manifest/records SHA f75d955524ca56f7a3269225f755089269413b6ef9b23e64625593851e7db5f1。
- 正式核验ID fe550942-4145-407c-aaae-8c835e225e72，20记录/27原件；inventory SHA 5dcdbe799c069bfb9f962a53b68dc306db1a411e832f855fb62a5cb2e3b21a0e。
- 同键重放核验回执一致。候选读取仍CANDIDATE/version1。错原件SHA正式请求503。

回执原件：[candidate](hupd-candidate-v342.json)、[verification](hupd-verification-v342.json)、[replay](hupd-verification-replay-v342.json)、[read](hupd-candidate-read-v342.json)、[refusal](hupd-refusal-v342.json)。实际成功日志hupd-formal-source-requests-v342-retry.log，退出0。

先前PS条件换行解析失败没有发请求；修复后首次POST因PowerShell自动把collectedAt转DateTime、插值变区域格式返回400，未注册成功。改为ConvertFrom-Json -DateKind String，精确保存原采集时间/版本后成功，没有放宽产品ID或版本约束。两个失败日志保留。

## 边界

注册/核验/同键重放/读取及错误SHA是当前正式HTTP/DM8集成证据；未启用此来源、不运行其检索/Benchmark、未开放摘要/权项/正文/个人字段。三份公共许可声明差异仍在，不冒第三方完整权利。完整档案SHA/gzip CRC仍未核；28文件恢复引用C不冒本轮新物理恢复。变更前verify-all-v342-pre.log实际退出0。
