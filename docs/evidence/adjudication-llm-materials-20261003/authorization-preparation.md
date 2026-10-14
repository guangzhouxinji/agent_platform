# 裁决材料整理：授权前准备证据

工作日期2026-10-03 +08；本轮仅离线准备/公开供应商文档读取，不是业务能力实现、D通过或任务状态台账。总计划是唯一状态来源。

## 1. 材料清单与用途边界

详[清单](material-inventory.json)。01旧manifest SHA和records SHA逐项读取，records与登记SHA一致，双方content与各自source SHA一致；旧包显式notAllowed含EXTERNAL_MODEL_USE，本轮不修改其manifest/用途。旧包只有两条极短描述，不适合重要遗漏和人工整理量的价值比较。

另备[待批准包manifest](proposed-material-package/manifest.json)及申请方/答辩方两份原创文本，共一个替代单案，含结构主张、否认、收样/寄样日期（无年份）、照片解释、组件身份争议、寄件凭证/编号/影像缺项及共同核查意愿。只有OFFLINE_PREPARATION_REVIEW用途；MODEL未批准、来源未入库、执行grant未发。无真实当事人、现实案号、实际专利或第三方隐私。正文是虚构替代输入，在证据manifest明确标识，不植入产品默认数据。不将其称真实案件价值证明，也未声称存在人工金标。

拟人工基线由实际审查人先独立整理，两侧重要主张按源锚点逐项编号。候选输出相对基线评估重要遗漏/方别/虚构/定位、修正字数与项目数；记录人工开始/结束、是否重复练习及操作次数。当前尚未观测，不能报告人工耗时或效率提升。

## 2. DeepSeek公开条款及计价快照

实际只GET供应商公开pricing/privacy/terms网页，未带模型密钥或任何案件文本，未调用chat/completions。详[采集manifest](public-provider-review/manifest.json)：原始HTML、可读文本、HTTP200、最终URL、UTC采集时间、SHA/字节数。价格可变，授权后首呼前必须复核固定model和计价版本；不把公开网页当账户实际启用/供应商专项批准。

本机实际采集价格页：deepseek-flash=DeepSeek-V4.1-Flash，deepseek-v4-pro=DeepSeek-V4-Pro-0813；旧deepseek-v4-flash别名仍可路由但原模型已下线。当前项目配置deepseek-chat不在该表，不能据旧咨询凭据推断已获准且可用。准备建议用deepseek-flash非思考模式做提取，待批准，未修改application.yml或当前服务。

计价快照单位人民币/百万tokens：Flash缓存命中低峰0.02/高峰0.04，未命中低峰1/高峰2，输出低峰4/高峰8；Pro依次0.15/0.30、4.5/9、13.5/27。按全未命中和高峰保守上界，累计输入100000/输出20000 tokens对应Flash 0.36元、Pro 1.44元；这是公开快照的纯token数学估算，不是报价承诺、实际消费或未经批准的费用授权。输入必须计系统提示、格式说明和包络，输出须含可计费思考tokens；不能只按材料字数估算。日期虽是周末，也不以低峰价放宽预算。余额不足/计价变化/别名版本不明确/无法保守计量均停止。

隐私政策更新/生效2026-02-10，用户协议更新/生效2025-09-05。政策涵盖API（另有专项政策时按其范围核实），没有从已采集条款取得本项目API零保留/不训练专项保证。条款描述在条件下可能用输入输出优化/训练，并提产品内退出途径；本轮没有证据证明该产品设置能控制当前API账户，不能冒认已退出。政策对境内运营个人信息有境内存储说明，这不等于数据没有离开项目控制域，不能把第三方外部服务说成本地模型；也不等于每类API日志的具体保留时长已知。必要期间保留及法律/审计/争议例外，不能代替30天API专项承诺。

准备建议的本地30天保留只针对获准的本轮替代输入实验对象及其衍生，不能默认删除01/02真实持久记录、既有案件或正式审计；也不能约束供应商服务器。最终产品真实案例保留/专业用途仍需独立证据，本轮不决定其默认。

## 3. 兼容设计审阅

详[ADR V1.1 §7/8](../../adr/adjudication-llm-materials-20261003.md)。既有State、Codec、V086表与旧哈希链必须保持，不用在旧payload补字段的办法兼容。候选/人工整理用裁决独立旁表；确切案件事务、审计事件及引用约束需窄增量，不重做内核。来源MODEL许可旁表与独立撤回generation，不把原READ/SAVE/ANALYZE授权拓宽；逐案MODEL_EXECUTE动作仅裁决控制器增量，沿现有维护库与当前可信主体。共享身份内核/CON ENF模型路径不改。model-deepseek本有咨询/专利依赖，但新裁决类依赖裁决端口，禁止直接导入咨询业务注册表/授权/候选对象。

原文substring/SHA验证是机械一致性，不能证明语义无虚构，人工必须核查每项归纳及话语归属。生成模型失败不替换成功稿；候选无引用、未知源/方别、偏移非法等整次机械拒绝；语义可疑则禁止未经人工采用进入人工版本，不宣称规则能全面识别幻觉。

## 4. 03A开工条件（不是已通过事项）

1. 负责人回复具体材料MODEL/出域、费用、模型选型及本地保留提案；证据含责任、日期、范围/版本、结论。
2. 待批准材料manifest有独立授权记录；不改01旧禁止用途，逐件版本/SHA和允许字段匹配。
3. 明确实际当前产品主体及由授权维护路径发放逐案MODEL_EXECUTE；SOURCES维护方登记本案MODEL用途。准备本身不签发任何grant。
4. 固定approved provider/model/endpoint/config/price/prompt/retention版本、总预算/次数/token限制；环境缺项关闭且无固定回退，不用一次真实外呼探测未经批准模型。
5. D二审关闭权限/用途/保留/别名与计费阻断，补确切DAO/Codec/事务兼容及负例合同；全部依据在docs02和唯一总计划回写后才可业务编码。
6. 受影响实施前重新verify-all及基线核查；01/02和CON/ENF回归在实现后按真实窗口执行。本轮没有业务代码变化，不冒充重跑了既有回归。

## 5. 实际执行边界

本轮没有产品代码、API、迁移、UI、当前授权或source记录写入。没有模型请求/费用/连接测试，没有接受候选、实际人工基线或DM8实现验证。只做本地材料/公开条款快照完整性检查，详preparation-integrity.json，不等于领域组件验证、专业验证或全仓回归。修改前verify-preparation-before.log退出1，47/41基线一致，错误TEST_EVIDENCE_STALE；不修改旧报告时间或弱化校验。


## 6. 准备校验结果与失败保留

[离线准备复核](preparation-integrity.json)28项通过，脚本[verify-preparation.py](verify-preparation.py)只读取本地清单/材料/文档并校验摘要、双方案别、用途未批准和UTF-16摘录往返；不发送HTTP、不接产品接口，不算模型或领域能力实现。首轮文本摘要检查失败原件[preparation-integrity-first-failure.json](preparation-integrity-first-failure.json)保留：Windows换行转换导致保存文本的CRLF字节摘要不同于逻辑LF摘要。原采集manifest保留，修正仅记录实际文本字节SHA并保留logicalLfTextSha，HTTP/raw HTML和采集时间未改；重跑原件[preparation-integrity-rerun.log](preparation-integrity-rerun.log)退出0。

[准备轮后verify-all](verify-preparation-after.log)实际退出0，前置退出1的TEST_EVIDENCE_STALE保留。全局检查当前窗口通过并非本轮新增全后端/浏览器/DM8或业务价值验证；本轨道没有重跑三域回归，没有更新旧报告时间。未新增任务状态完成声明，仍等待原具体授权回复。
