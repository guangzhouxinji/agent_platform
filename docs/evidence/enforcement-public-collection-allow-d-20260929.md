# 执法公开来源 COLLECTION_ALLOWED 变更窄 D

日期：2026-09-29  
具名技术责任：Codex  
适用任务：`CR-ENF-PUBLIC-COLLECTION-ALLOW-02`、`ARC-ENF-PUBLIC-COLLECTION-ALLOW-D-02`、`IMP-ENF-PUBLIC-COLLECTION-ALLOW-02`

## 1. 变更范围

本 D 只替换公开候选的采集许可判定和派生状态，不修改冻结的 ENF-01～11 产品实现，不重开`IMP-ENF-JOB-PACK-COLLECTION-01`，不降低真实内部行为的异人核验要求。

旧合同中“没有独立许可证据即`WAITING_LICENSE`”不再作为项目采集和公开任务包纳入的门禁。旧批次、原始字节、SHA、HTTP记录和失败事实保持不变；实施必须通过新批次重新获取并重新判定，不能只改历史JSON标签。

## 2. COLLECTION_ALLOWED 判定

同一采集尝试同时满足以下条件才为`COLLECTION_ALLOWED=true`：

1. 目录中的来源为公开可访问的HTTPS官方地址，来源机构和URL可追溯；
2. 本轮采集实际获得2xx响应，内容类型、大小、页面/文件签名和同源重定向符合白名单；
3. 原始页面或文件已经写入受控目录，重读SHA-256与manifest一致；
4. 标题、发布日期和正文或原件定位可核验；
5. 未发生访问禁止、认证要求、挑战页、跨域跳转、内容漂移未审、错误页伪装或目录明确禁止用途。

判定证据必须包含URL、来源机构、原始文件、抓取时间、HTTP元数据、采集方法、判定规则版本、SHA和逐项结果。任一条件失败则`COLLECTION_ALLOWED=false`并保存失败原因，不得进入公开数据就绪。

## 3. 权利字段分离

- `collectionDecision=COLLECTION_ALLOWED`：允许本项目按当前采集、保存、解析和任务包使用方式处理该公开材料。
- `copyrightLicense=NOT_ASSERTED`：不声明取得著作权许可。
- `commercialLicense=NOT_ASSERTED`：不声明取得商业许可。
- `legalRights=NOT_ASSERTED`：不声明其他法律权利或法律结论。

这些字段互不推出。HTTP成功只能参与工程采集判定，不能自动改变后三项。

## 4. 事实和状态合同

公开页面可直接形成`PUBLIC_SOURCE_FACT`：真实公开任务、发布机构、页面中明确出现的公开身份/角色描述、公开处理结果和公开意见。每项必须绑定原文定位和原始摘要；未抽取或原文没有的字段为`NOT_PRESENT/NOT_EXTRACTED`，不得猜测。

候选状态按以下方式派生：

`COLLECTING -> PUBLIC_DATA_READY -> WAITING_REAL_BUSINESS_FACTS -> READY -> RUNNING -> PASSED / FAILED`

- 公开采集条件和公开事实完整：`publicDataState=PUBLIC_DATA_READY`。
- 公开数据已纳入任务包但内部五类事实未齐：候选运行态为`WAITING_REAL_BUSINESS_FACTS`。
- 当前真实系统操作者、当前岗位授权、本系统实际人工批准、本系统正式人工回执、内部业务评价均由真实行为提交且通过异人核验：`READY`。
- 只有`READY`才可幂等触发`NEXT-V2-INT-ENF-01`。

## 5. 失败与反例

- 可访问但不能保存或摘要不一致：不得`COLLECTION_ALLOWED`。
- 404/429/5xx、挑战页、认证页、非HTML伪页面、跨域重定向：保存尝试证据，保持非就绪。
- 只改JSON状态、未重新获取原始响应：拒绝。
- `COLLECTION_ALLOWED`同时把版权/商业/法律权利设为已确认：拒绝。
- 公开文书人员或机关被映射为当前系统操作者/岗位授权：拒绝。
- 内部五类事实缺失却`READY`或触发集成：拒绝且触发数为0。

## 6. D 结论

输入、输出、状态、权限语义、失败行为、证据字段和反例已经闭合。`ARC-ENF-PUBLIC-COLLECTION-ALLOW-D-02`通过，只放行采集器/候选包的最小变更及实际网络重采；不批准任何版权、商业或其他法律权利结论，也不放行真实集成。
