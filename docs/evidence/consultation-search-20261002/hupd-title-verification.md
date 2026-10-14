# 原生TITLE内容与引用适配C（2026-10-02）

对应IMP-CON-SEARCH-02，D依据docs/70 §3.2、docs/02 §59。沿现有正式加载、SourceBatch、查询/读取与当前权限链，未新增产品路由、身份、用途、状态或迁移。

## 实现

ProductCollectedPatentTitles按已绑定manifest schema调用ProductHupdSourceDocuments；先复用完整性内核，再精确核sourceId/采集版/manifestSHA/recordsSHA。原始公开号的已核日期后缀不成为标准文献键，文献版仍是该原件SHA。返回文献fields只有TITLE；摘要/权项/正文、个人字段和历史decision均不返回/不评分。

日期/IPC/CPC过滤使用真实原字段，不猜当前法律状态；正式SourceBatch仍按原规则逐过滤字段检查QUERY许可。TITLE定位使用已核原始/title JSON Pointer与原值UTF-8半开区间。引用URL和originalRequestUrl均取真实捕获档案URL，采集时间取HTTP206响应事实，不伪造专利页面/独立DETAIL响应。

ProductPatentSearchCitations原生分支逐member核文献键/版、源/采集版、原件SHA/locator，并沿原有页末逐原件重核；映射只来自本次已核来源，不新增跨请求授权缓存或跳过现时检查。原Sure分支和旧TITLE方法保持。

## 实际测试及修复

hupd-title-directed.log：19项、0失败/1错误，实际退出1。错误是新增测试ObjectMapper未注册Java Time模块，不能序列化Citation.collectedAt；不是服务端日期处理或实际数据错误。原失败保留，未通过改产品日期/结果形态规避。

按最终产品正常Jackson模块配置补齐测试Mapper后，hupd-title-directed-v2.log：26项、0失败/错误/跳过，实际退出0。覆盖4项真实原件TITLE/身份/精确字节值/日期分类过滤/引用/错源版摘要定位反例、7项原件与发布绑定、原生Registry事务、旧引用和原件核验/正式检索事务C。首轮命令中不存在的ProductCollectedPatentTitlesTest未运行、不计通过；后续采用实际存在的ProductPatentCitationRecordsTest和ProductCitationFileVerificationTest。

实际输入20原件；C引用测试里的Active类型仅调用内核的快照DTO，未写Source ACTIVE、未伪造正式维护/Worker身份、未运行HUPD查询或Benchmark。此C不代替SOURCE用途准入、正式HTTP/DM8 I、生产部署或业务验收。当前产品运行仍为v330，HUPD仍未PRODUCT_ENABLED；未开放ABSTRACT/CLAIMS/DESCRIPTION/LEGAL_STATUS。
