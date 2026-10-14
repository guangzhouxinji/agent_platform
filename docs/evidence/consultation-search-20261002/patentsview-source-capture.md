# PatentsView真实档案采集原件证据

日期：2026-10-02；任务：IMP-CON-SEARCH-02；采集协议：docs/70 §3.2、docs/02 §73。

三个Zenodo仓库原JSON及CC BY4许可全文于13:47:58～13:48:03 UTC实际HTTP200保存于patentsview-public-evidence，具体SHA见docs/02 §73。这是镜像仓库的许可/作者声明，不是已核USPTO上传身份，不推出全部第三方权利。完整权项包与元数据包实际HTTP200下载，原Vault副本保留：

| 原档案 | 公开MD5（实际完全相等） | 字节 | 实际SHA256 | 完成UTC |
| --- | --- | --- | --- | --- |
| g_claims_1976.tsv.zip | 4f401dde1fb449eea2d44f3850e67e75 | 87377250 | a6b304d90e368431578e8e989dfc3c8fd589ff14bd321fe29eb2c36ad631d7ca | 13:49:48.3744697 |
| g_patent.tsv.zip | f74fbde4b2adbf980b8e4ed5394f16d2 | 223192856 | 46586069f04a24265f8e80e36bfa38d97ed1b703fa0e63918f001038c563e7cf | 13:51:44.3847362 |

claims TSV解压309970391字节；metadata TSV解压1077805005字节，完整扫描至EOF核ZIP CRC，不用prefix冒完整档案。v397首轮错误把所有grant ID限定纯数字，真实源含RE29015/D242832等标识而失败；保留失败日志，调整为不改变原值的安全字母数字标识，未跳过这些来源行或更改数据。v398按claims原文件前20个不同grant ID选取并全扫描两表，取得20真实文献、232权项原行+20元数据原行；各num_claims与实际权项数完全一致。v399重新全扫描重建核验通过。

首轮v1保存完整ZIP在Vault，其manifest含当前机器绝对路径，故不作为产品可移植来源格式。v400按最终原件边界生成v2：data/patents/patentsview-real-candidate-20261002-v2，完整ZIP、原仓库JSON/许可、252原行及20派生封套同包，277个artifact合计311083133字节，manifest SHA9c98d2488d0ecf54dd0dcf5a3e84ef29e281c9d382d7d54918d665a9f8569bd1。封套是明确记录的真实TSV确定性表示，不冒原HTTP/USPTO原件，不填摘要/说明书/法律状态或权项解释。

v401 verify-only实际退出0：重建所有原行/封套，核包内外两完整ZIP/元数据版、所有byte/SHA/path集合，fullArchiveCrcVerified=true、portableArchiveProof=true。未覆盖/删除v1或原下载包。sourceId=PATENTSVIEW_ZENODO_2024，REAL_CANDIDATE，productEnabled=false，rightsEvidenceStatus=REPOSITORY_CC_BY_4_0_ASSERTION，不将数据标Synthetic/Fixture/Mock，也不宣称已经写入正式Source Registry、获准模型/Benchmark/业务检索。

工具：scripts/collect-patentsview-archive.py；机械解析C为scripts/verify-patentsview-capture.test.py，v398/v400各6项通过，包括多行quoted tab/引号/中文精确原始byte locator、额外ZIP成员、缺列、路径注入、坏UTF8、CRC损坏。该C只证明解析/边界，不是专业权项或业务验收。实际日志在backend/apps/platform-api/target/patentsview-capture-v397.log、v398.log、v399-verify.log、v400.log、v401-verify.log及parser-v398/v400-tests.log。

后置verify-all-v401-capture退出1，当前只报独立Java TESTS_FAILING，需求47/41仍一致；不降检查标准、不冒全仓绿色。当前唯一咨询产品剩余接线是该真实格式的正式核验/字段投影/引用/恢复，再由正式维护决议逐字段准入；取得公开原包不等于自动启用。
