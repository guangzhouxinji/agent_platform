# V084字段结果同库存储C（2026-10-02）

同IMP-CON-SEARCH-02、docs70 §5.4/docs02 §69。V084新增现有主库con4_search_field_scope/con4_result_field_evidence，FK强制Scope对应同Attempt/v3 Result，字段事实对应真实Parent成员；旧TITLE行/摘要不迁移，不新增数据库。U084在任何删除DDL前CHECK拒绝已有v3结果，不能把字段证据降级TITLE。

ProductPatentFieldEvidencePersistence只在实际围栏业务事务内写字段事实，核Root方法/排序/模型/Scope SHA/v3摘要、实际存储成员/十二位值一致，保存后重读；读取核协议、Root、每条字段JSON SHA/canonical、成员和v3摘要。该存储层不发行Task/Source权限。

v375四套15项、v376四套15项、v377五套20项0失败/错误/跳过。使用V084真实DDL的H2机械存储C验证：旧v2 Root不能挂字段Scope、错Parent成员拒绝、已有v3拒绝降级且数据未删、同库事务保存/重读、无事务拒绝、异常Scope/成员全回滚、重复写拒绝、JSON篡改拒绝、仅空字段表可反向迁移，旧TITLE执行回归保持。机械测试数据仅隔离组件故障C，不冒真实DM8/正式Source/业务请求。

ProductPatentSearchPersistence新增persistFields围栏事务接入方法：复用正式旧Root/成员/回执提交内核，同事务转换该新结果为字段v3并保存完整事实；缺schema/事实/Root/重读失败回滚。v377只证明编译及受影响回归；该方法尚未正式Worker路由调用/实测，不把它当实际字段业务事务完成。

待完成：正式执行/页/游标/选择/历史恢复接线、V084真实DM8物理验证与字段数据恢复、新字段合格Source和实际产品I/Benchmark。运行v357、Task仍TITLE、HUPD未启用/模型计算。
