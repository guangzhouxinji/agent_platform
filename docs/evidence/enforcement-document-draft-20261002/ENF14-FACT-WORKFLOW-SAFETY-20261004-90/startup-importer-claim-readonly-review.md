# 79事故后的启动/Importer/重复领取风险只读复核（2026-10-04，90轮）

仅代码/既有87恢复/89独立反例证据复核，无主库连接或产品启动，不重新恢复16条。5444必须继续停止；此文只说明技术分支，不决定未知历史、不授权重启、不改冻结Job Pack。

## 固定来源版本

- PublicCollectionTaskImporter.java：SHA256 fe4a892d804f6193d0cca915122b18ed11798301d8a9348ad770ef812aee6b24。
- Dm8RealBusinessCollectionTaskStore.java：SHA256 2f669ebd6e2684ecff2355c15112c11e6d360965f559bff5e9651bcdba4b7af7。
- EnforcementJobPackController.java：SHA256 c3604fc7bd86147b7af876afc78a890311b92a36e89b64bb1cfda7259bf58833。

以上版本仅实读，不修改。后续来源变更时本读结论需重新比对，不能冻结或推测当前权限。

## 已明确的代码行为与范围

1. Importer仅在enforcement.v2.enabled=true装配，public-candidates-path为空直接return。配置有效路径时读取符合PUBLIC_DATA_READY/COLLECTION_ALLOWED的候选，先tasks.find(taskId)，已有任务即return，不覆盖既有CLAIMED任务/包/事件，也不自动领取/批准。已有任务路径不重读新原件；不能将Importer启动无错误当作全部旧原件当前有效。
2. seed二次find且数据库唯一冲突返回false；其新插入仅UNCLAIMED/v1/WAITING_REAL_HUMAN_ACTION。只有新对象会stage原件；并发seed失败只discard自己的staged key，不是batch删除。没有自动凭猜测补历史的合法路径。
3. claim同request_key必须fingerprint/actor/task全部一致才能返回旧结果；新key会行锁。其他actor或组织领取已CLAIMED对象拒绝；同actor/同组织使用新key时不重置领取版本/时间，但仍可写一条新CLAIM事件。这是已冻结现有行为，不能改成系统自动重新领取，也不能把新事件假称原事故丢失历史。
4. Controller在claim前核现时CREATE/COLLECT/READ并实读原件SHA，不读历史批准恢复权限；若任务的package latest已存在则不createPublic。若包缺失会按当前请求createPublic，此分支在最新历史未知时不能自动调用，不把新v1冒原旧包。此复核不是HTTP或真实授权I。

## 已有实际证据

87只证明16已知历史精确恢复，目标任务v2/CLAIMED、包v1、原领取事件1、原包事件/回执各1、触发0。最新完整性NOT_PROVED及准确事故前截止未知继续独立。

89独立5382/新随机schema/卷/TEMP根，以明确机制主体运行原存储I及新增Importer重入断言：tasks.list及包事件/回执前后不变；领取同键重放无重复，异人领取拒绝。组件授权端口不冒真实产品授权/真人行为。89真实主端口拒绝及同轮重复入口拒绝后七表行fingerprint和本轮文件SHA不变，主连接0、业务写0、cleanup0。保留原88 error1/skip0及独立设施，不删失败。

## 重启前仍未解除事项（不是新业务开发门槛）

- owner：项目负责人/数据负责人员；exit：对只恢复16已知原历史、未知最新区间及潜在重复领取风险作明确受控重启决定。当前本日无人值守许可不包含此决定，不重复催问。
- 真实恢复后OAuth/UI读取与当前授权/材料用途检查尚未执行，因为5444停机；不能用代码审阅或隔离机制I冒运行环境READY。
- 未检查的新Importer/seed配置不能推断安全，不自动激活/改配置/发业务请求。未知历史不重造、不回灌旧权限、不恢复源卷、不自动claim/create/批准。
- 冻结域如需改变新key重复领取/缺包createPublic语义，须另行批准和合同评估；本轮仅留边界，不实施。

可继续的安全工作：既有ENF14 D内事实记录C及严格声明范围的独立DM8工作流/失败补偿验证。所有业务审核/DOCX仍要求原必填和同案当前CONFIRMED/ACTIVE引用及有权主体实际行为；缺失时继续拒绝。
