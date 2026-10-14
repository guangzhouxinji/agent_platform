# 商标真实capture原件映射验证

日期2026-10-13；责任Codex/root统一验证。任务PC-CON-TRADEMARK-CAPTURE-MAPPER-01；R=REQ-CON-R-EXPAND-15，窄D见[商标ADR](../adr/consultation-product-trademark-library-20261013.md)§9.6。本证据只源码/组件，不确认来源权利、当前Grant、DB/API/部署或业务验收。

新增ProductTrademarkCapture不可变输入/结果及Mapper/CapturedFields/CaptureJson。严格有限UTF8/JSON、嵌套重复键/尾随/孤立代理字符拒绝；完整manifest/records/raw集合及原字节SHA/长度、原token UTF8定位/摘要、源登记版与RAW记录版分离、多主体/多类/缺失null/日期精度和00012A保真。来源性质即使提供声明也保持NOT_CHECKED。原有token helper和域约束复用，无新的Bean、表、接口或授权旁路。

root独立审查发现caller ObjectMapper附加serializer可污染固定canonical树，改全新私有mapper并用真实自定义RecordRef serializer反例证明输出与正常mapper相同；不继承caller module/mixin。generic inventory摘要保持原算法；normalized/relation/bindingSet分别固定schema。

JDK21/Maven命令：`mvn -f backend/pom.xml -pl apps/platform-api -am -Dmaven.multiModuleProjectDirectory=D:/daima/xinji/agent_platform -Dtest=ProductTrademarkCaptureMapperTest,ProductTrademarkLibraryServiceTest -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 -DreuseForks=false test`。于2026-10-13 19:17:20北京时间自然exit0，34项（新映射18/既有域16）、0失败/错误/跳过，完整Reactor成功。源前后9文件摘要一致。

原件：[日志](consultation-human-ordinary-20261013/trademark-capture-focused.log)、[确切XML计数](consultation-human-ordinary-20261013/trademark-capture-focused-xml/counts.json)、[前件](consultation-human-ordinary-20261013/return-trademark-before.json)、[稳定性](consultation-human-ordinary-20261013/return-trademark-stability.json)。同批总后检见[统一记录](consultation-human-ordinary-20261013/verification.md)，不把旧全仓健康两红改为绿。

本增量移除旧normalized冒raw的映射缺口，回到ARC-CON-TRADEMARK-PROJECTION-D-01的原子导入/正式SQL读取合同；尚无商标用户查询运行出口。真实capture/逐字段用途/有限保留/人员只门禁对应启用，历史公开开发试点不升级。
