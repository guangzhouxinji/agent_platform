# 原生真实采集格式正式核验与恢复闭包C（2026-10-02）

对应IMP-CON-SEARCH-02；D依据docs/70 §3.1、docs/02 §58。不新增产品路由、身份、权限、状态、迁移、Source用途或备份机制。

## 已实现

- 现有ProductSourceVerification正式服务按HUPD_REAL_CAPTURE/v1调用原生完整性适配；sourceId、provenance、上游版+采集时刻及候选collectedAt精确绑定。沿原候选版本、维护入口授权、幂等和同库事务保存核验回执，不能由此激活来源。
- ProductHupdSourceInventory逐文件SHA/长度重核7份真实HTTP响应和20份完整JSON；发布元数据绑定作者commit/README、托管revision/README/card许可元数据、档案tree发布大小/LFS摘要及HTTP206前缀。三份许可声明均保留，没有选择/代签最终许可。
- ProductCapturedArchive只重解压验证所需完整成员：tar checksum/类型/路径/大小/偏移/原件SHA以及128MiB展开界限。没有文件系统解包、完整档案SHA/CRC认证或伪造DETAIL HTTP响应。
- ProductCapturedJsonFields用严格JSON解码及顶层流式token字节位置重核字段名、JSON Pointer、键/值UTF-8范围、原件SHA/字段值和原始identifiers；相同文本的嵌套位置不能伪装顶层字段。
- ProductSourceRecoveryFiles复用同一核验内核校验已持久原生核验记录，逐项比对源/采集版/recordsSha/inventorySha/数量，返回28个manifest+响应+成员文件引用。recordsSha绑定实际包含records清单的manifest，不捏造独立records.json；原Sure格式保持原分支。

## 实际测试

- hupd-registry-directed.log：初轮8项、0失败/错误/跳过，退出0。
- hupd-registry-strict-directed.log：严格发布和顶层定位版8项、0失败/错误/跳过，退出0。
- hupd-registry-affected-regression.log：platform-api受影响20项、0失败/错误/跳过，另consultation依赖15项通过，退出0。覆盖已有来源准入、恢复和Goal/继续/检索事务/排序C；首轮命令中误拼的ProductSourceCandidateTest未运行，不计通过，后续用真实ProductSourceCandidatesTest补齐。
- hupd-registry-complete-directed.log：新增机械归档测试方法名缺少void空格导致测试编译失败，实际退出1；保留失败，不覆盖。
- 修正上述两个测试方法声明后，hupd-registry-complete-directed-v2.log实际退出0：17项、0失败/错误/跳过，含4项归档机械正反、7项实际原件/字段/发布绑定、1项H2正式服务事务/幂等/28原件恢复引用及原Source候选/核验/准入/恢复回归。

原件C实际输入就是已采20条JSON，不冒Mock数据；归档机械测试仅构造包封头，正文复用真实原件，不是来源HTTP或业务结果。H2和component-maintainer仅组件测试运行对象，不声称正式成员、正式产品维护请求、DM8物理或恢复I。测试没有启用HUPD或使用其字段做检索/Benchmark。

## 未完成边界

当前运行产品仍为既有v330，不把源代码及组件事务验证说成新发行正式HTTP I。来源三份不同许可声明保持独立准入事实；当前原生来源不PRODUCT_ENABLED。内容消费/逐字段正式查询、语义索引、结果持久化与历史重建仍须完成对应接入设计/实现。该原件核验和文件闭包不代替新实例物理恢复，复用的既有真实DM8/恢复证据仍按原时点保留。
