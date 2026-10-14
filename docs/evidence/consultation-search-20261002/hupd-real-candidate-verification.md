# HUPD真实原件采集与字段核验（2026-10-02）

对应当前 `IMP-CON-SEARCH-02`，设计依据为docs/70第7节与主计划V9.440输入采集窄D。此证据不是来源用途准入、正式字段检索、专业Benchmark或业务验收。

## 实际采集

- 执行：`node scripts/collect-hupd-patents.mjs --output=data/patents/hupd-real-candidate-20261002-v2 --limit=20`，实际退出0。原初次v1目录保留，不改写原件；v2补齐原始字段值的精确UTF-8起止定位。
- 提供方：HUPD；原作者仓库`suzgunmirac/hupd`，作者版本`cc0840297e54178c9e94d7c74e016f716e65ded5`；托管来源`HUPD/hupd`，冻结版本`f570a84b03663180b6034c1f7f4c15864f94385e`。
- 原始档案：`data/sample-jan-2016.tar.gz`，发布大小387636489字节，发布LFS SHA `c8db7a2e730f04a95946847d39a74b4a9ea53b0f4fd357bb8789abd77684e37f`。
- 实际HTTP206范围：`bytes 0-4194303/387636489`；获取4194304字节，实测前缀SHA `fe0570c50031da265abb8473c526cdd7767d4dfef9465e4fe90395e04b6663d5`。**未下载完整档案，未核验完整档案SHA或完整gzip CRC。** 发布全档案SHA只作为发布事实保存。
- 采集时间：2026-10-02T07:02:13.149Z～07:02:26.447Z（15:02 +08）；每份响应的稳定原始URL、状态、时间、字节长度和SHA均在manifest中。CDN临时签名URL不作为源URL保存。
- v2 manifest SHA：`f75d955524ca56f7a3269225f755089269413b6ef9b23e64625593851e7db5f1`。
- 20条完整真实JSON，20个不同原始公开号。title/abstract/claims/full_description实际非空各20条。原始公开号包含发布日后缀，不擅自当作无后缀标准公开号或同族身份；日期及分类保持原值。
- 首记录原始`publication_number=US20160220097A1-20160804`，原始application_number=15009086。文献版绑定该完整原始JSON实测SHA，不捏造专利官方修订版本。
- 每条保留原始JSON字节、档案成员名/解压tar偏移、documentVersion、原始标识、JSON Pointer、键及值的UTF-8半开区间和原件SHA；定位值实际重新JSON解码与原字段逐项相等。未清洗覆盖claims或description。
- 这是HTTP前缀内前20个完整JSON的确定性采样，不代表全库，不产生相关性qrels，不伪造QUERY/DETAIL接口响应。上游档案名sample不表示数据为合成/Mock/Fixture。

## 实读许可差异及边界

三份声明均保存原件，不能择一冒充最终法律事实：

| 原件 | 实际声明 | SHA |
| --- | --- | --- |
| 原作者README | Creative Commons Attribution 4.0 International | d2e191e405a8d1db9dd7e514c7dd9b3412b5343c00d09a0a401b9ed5781c3280 |
| 托管API cardData.license | cc-by-sa-4.0 | cb37f00f6e8fa3c51747e4cbd116b9ea93e746746f845b28c747ba3f85370cf0 |
| 托管README正文Licensing Information | Attribution-NonCommercial-ShareAlike 4.0 International | 4f80628f8b4fdf17307df713073a94630f01b36c2184a29335da79f0c62cb31f |

代码LICENSE为MIT，不代替数据许可。采集保持REAL_CANDIDATE、productEnabled=false、rightsEvidenceStatus=CONFLICTING_PUBLIC_LICENSE_STATEMENTS；未进入Source Registry，未开放任何产品字段。原始decision/issue/abandon字段不推导当前法律状态；inventor/examiner等个人元数据仅随原件受控留存，不新增产品检索字段。

当前只核验原件完整性及实际字段结构；未宣称已满足商业用途/再发布条件。对来源准入及字段消费保持失败关闭，但不重新打开已通过Task/Source Engine设计。

## 自动化验证

`node --test scripts/lib/patent-archive.test.mjs scripts/verify-hupd-capture.test.mjs`：13项通过、0失败、0跳过。

9项解析器机械测试明确使用构造字节，不是专利资料或产品链路验收；覆盖完整/部分tar成员、限量gzip前缀、checksum、路径越界、链接、非法JSON/UTF-8、解压限额、Unicode/转义/嵌套原值定位及重复/错配定位拒绝。4项真实采集验证使用上述20条原件，覆盖实际字段与20个不同公开号、原件篡改拒绝、productEnabled误标/定位篡改拒绝、同目录采集禁止覆盖。

采集器`--verify-only`逐原件重算SHA、重新解压绑定成员、核对文献版/原标识/字段/字节位置、作者版本及托管许可元数据；不靠manifest自称真实。新测试接入verify-all，现有后端全量及正式TITLE链证据直接复用，不因采集脚本改动冒称新后端全量。
