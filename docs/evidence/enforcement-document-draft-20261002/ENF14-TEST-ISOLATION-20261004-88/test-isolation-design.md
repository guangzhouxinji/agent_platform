# 79事故后测试误连与清理防护技术设计（88轮）

范围仅测试/执行工具，ENG-ENF-TEST-ISOLATION-79-01；不改Job Pack产品、冻结领域、咨询/裁决，不新增业务门槛，不替代ENF14产品I。RUN-ENF-UNATTENDED-20261004-01承接本日heartbeat调度边界，到北京时间2026-10-04 23:59停止新实验。87证据直接复用，禁止重跑恢复；5444不启动，主库无新业务写。

现有RealBusinessCollectionDm8Test在连接/迁移后导入固定12任务，finally按目标包及batch删除，不能证明记录属于本轮；79已实际损害现存数据。不得删测试/弱化断言/跳过，须在首次连接、DDL、import之前失败关闭。

技术决策：只接受显式runId、127.0.0.1独立端口、ENFISO_随机专用schema、带相同runId/role标签的独立容器/卷，实时Docker元数据及唯一卷使用者一致。文件根必须TEMP/enf-owned-{runId}/files真实路径、ownership marker匹配容器ID/schema/卷，不允许symlink。运行库/主端口、配置不足、标记错、缺元数据及已有固定ID历史一律拒绝，非skip。schema需确认SESSION_USER/CURRENT_SCHEMA一致、首次启动无业务表；运行后保留独立DB/原件审计，不复用。

清理决策：去掉finally DELETE和递归删除，本轮不自动清理。仅今后可证明归属且获准的独立资源回收可处理，不以异常触发cleanup；固定ID只在已证明空白且独立的schema导入，不复制/修改运行库数据。

验证：组件解析反例覆盖产品端口、schema、标签/卷/容器/marker缺失、共享卷、非TEMP根、绑定错、重复运行；成功结构只算C。专用独立DM8执行原测试才记隔离存储I，控制主体仅机制数据不冒真人。XML独占构建/后缀，tests>0、failures/errors/skipped=0，未执行I不算完成。Importer/重启风险只读核，不开放5444。
