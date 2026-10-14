# 专业对照主链：正式维护签发与技术方案原件读取

日期：2026-10-04；任务：IMP-CON-PRODUCT-COMPARE-01。测试原件说明，不是新增状态台账。

## 本轮交付

PC-SOURCE-COMPARISON-ISSUE-01：新增正式产品维护路由`POST /v2/consultation/source-contracts/scopes/{sourceId}/comparison/authorize`，通过既有正式SOURCES+GRANTS维护证书证明，要求同主体/组织。现时Task、Source、Scope精确版、用途字段与期限、产品决议Task绑定、成员活跃/HUMAN身份、Task READ/DataScope、独立Grant和Source水位均核验。固定COMPARE/COMPARISON，不隐含QUERY/DISPLAY/MODEL，不创建来源决议、Query范围或Worker租约。Grant/epoch/请求SHA/ISSUE审计复用正式产品持久化；摘要含issuer和全部精确输入。幂等不重复Grant/ISSUE事件，撤权及停用后重放拒绝。

V092/U092补齐旧数据库用途CHECK未支持COMPARISON的真实缺口。没有修改旧V037、降低未知值拒绝或删除任何记录。正逆均保留临时有效约束；若存在COMPARISON数据，逆迁移在第一步拒绝，扩展约束及原数据保留。实际脚本[正向原件](comparison-v854-V092-original.sql)/[逆向原件](comparison-v854-U092-original.sql)保存。此次仅H2机械执行，不称真实DM8物理I；没有应用到运行库。

PC-TARGET-ORIGINAL-01：内部正式prepare适配从用户显式指定的同Task持久化messageId读取技术方案原文，绑定Task READ/版、消息SHA及截止，响应前再核原文/Task/现时权限。不拿检索query、模型摘要或原文拼接生成目标，不伪造磁盘原件。USER_MESSAGE_SHA256版及TASK_MESSAGE标识与专利JSON原件区分；UTF8跨度/摘要保持中文、emoji和空白，非法代理对/拆半字符/假SHA/预算越界拒绝。此仅prepare输入读取适配，尚未接完整prepare及特征确认API，不冒真实用户已确认特征。

## 实际执行

- v848前置verify-all实际1，仅TEST_EVIDENCE_STALE；47需求基线/41台账一致。
- v849实际1：机械用例漏加载V042，Task IN_PROGRESS被旧约束拒绝。补真实迁移，不修改状态约束。
- v850实际1：V037 CHECK不含COMPARISON。以已复审V092补齐，而非替换成无约束测试表或放松断言。
- v851实际0：31项0失败/错误/跳过，签发及正逆迁移成立。
- v853实际0：新增空/未实现字段输入反例后32项0失败/错误/跳过。
- [v854最新合并测试](comparison-v854-target-and-issuance-tests.log)实际退出0：9套39项0失败/错误/跳过。包括最新issuer摘要绑定、NULL字段拒绝、技术方案读取/UTF8正反及原Query/Display签发/续期/回流守卫兼容。9套XML另存`comparison-v854-*Test.xml`，不替换规范全量报告。
- [最新24文件输入SHA](comparison-v854-build-inputs.json)逐一核当前与model-v836冻结根；源文件为额外覆盖，db目录沿既有junction读取，明确没有谎称两SQL为独立复制冻结。原件SQL另外保存。既有baseInputs不涵盖全部本轮额外覆盖，故不能只引用原model-v836清单。
- v855独立结构检查实际0，有既存软限警告，无本轮硬限失败。

上述测试为机械H2/独立水位C、保存原文与UTF8C，不是正式当前成员/维护证书HTTP运行I，亦非真实Source COMPARISON批准、Worker矩阵提交、模型执行或专业效果。没有新付费模型调用、正式发行、运行库写入、来源激活、授权续期或其他域操作。v856现时Docker socket核对原件单独保存，真实DM8/HTTP I若不可用继续保持未验证，不作为独立代码/C停止理由。

后置v857实际1：生成派生project-plan.json写入UNKNOWN错误；属性核查显示普通Archive文件，没有删除或绕过。v858正常重试实际1，派生视图生成恢复，47/41一致，仅TEST_EVIDENCE_STALE。这不是需求基线变化或业务实现测试失败，亦不称全仓通过。两份原日志保留；最后治理检查原件单独保存。

## 剩余主链边界

继续在原任务内接完整prepare、用户明确特征确认的CAS/不可变FeatureRevision/审计、ComparisonInput与矩阵/引用/回执持久化、Worker围栏原子提交、正式报告读取和工作面。物理新增表需要原D §6要求的精确设计/正逆迁移复审，不能把此次仅用途枚举迁移当全部矩阵物理设计。Source原件入模仍须独立MODEL_INPUT/供应商精确用途，不能外推旧最小元数据批准。完整纠正、人工与成果业务仍按各自门禁，不从本轮C放行。
