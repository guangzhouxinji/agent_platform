# PatentsView最终产品格式适配与原件C

日期：2026-10-02；任务：IMP-CON-SEARCH-02；设计：docs/70 §3.3、docs/02 §74。

已接入现有正式SourceVerification、SourceRecoveryFiles、CollectedPatentTitles、Citations；不增加HTTP、profile、权限、状态、表或固定候选。新增格式完整性内核绑定仓库声明/公开MD5/全ZIP SHA+CRC、252原行区间/字节、20派生封套、字段及源版。完整ZIP流式读取仍受原Root/maxBytes配置约束；实际消费只从已有VERIFY绑定的当前/历史快照重读选定小原件和档案存在/长度，不冒每次查询重核全ZIP。恢复深核并收集manifest+277 artifact全部278引用。

TITLE/CLAIMS取精确实际值，字段locator从封套实际JSON UTF8 token流取得，原TSV/档案关联保留；缺ABSTRACT/LEGAL_STATUS等不回退或填空。抽取HUPD原Capture/Entry纯数据为共用CollectedPatentContent，不改变旧格式/旧TITLE/旧回执或角色边界。

v403实际7套32项0失败/错误/跳过：PatentsViewSourceDocuments4、ArchiveProof3、HupdSourceDocuments6、HupdSelectedFields10、SourceRecoveryFiles1、PatentSearchExecution5、PatentFieldExecution3；所列CapturedPatentFieldsTest不存在，未执行/未计数。深核真实两完整ZIP、精确字段/原件版/缺字段、存储H2恢复引用、低文件上限/路径越界、quote/tab/中文/换行、错span/多成员/错MD5/SHA及旧来源/围栏反例均实际执行。TST4C67F91D日志清理，verify-all-v403-native-tests退出0。v404后置全量321套1040项0失败/错误/39跳过、verify退出0，详backend-regression-v404-summary.json。

随后反查正式SourceCandidates注册ID合同发现源版拼接原collectedAt的+00:00不合法；v405新增真实可注册格式反例实际失败（1失败/4项），TSTDE613E17已清理。修复为Instant.parse(collectedAt).toString()规范UTC Z，原时间/原件/manifest字节不变；v406同7套32项全部通过，TSTF7427718已清理。v406后置verify因另一域代码晚于报告而TEST_EVIDENCE_STALE退出1，必要最终全量v407已启动；未冒全仓最新绿色。

v408实际将完整可移植包复制至现有Linux原件Root/data/patents/patentsview-real-candidate-20261002-v2，278文件，manifest SHA9c98d2488d0ecf54dd0dcf5a3e84ef29e281c9d382d7d54918d665a9f8569bd1；复制前后旧完整Root清单所有SHA均相等。新目录UID/GID10001、目录0750/文件0640，应用现有只读挂载不变，未删除现有数据/容器/卷。完整新Root2829文件已核全SHA，独立清单source-v408.sha256的SHA2595ceee6ddb87fde11d75a6c5e689e2386dee4da6bf6c0d012c500ddae5f998。原清单仍保留；运行仍v357及旧清单启动，不冒新发行、VERIFY或PRODUCT_ENABLED。

C范围：机械归档故障/H2关系与真实原件适配；未注册测试ACTIVE或冒实际维护主体、Worker/模型/Benchmark/联合恢复I。真实数据一直是REAL；用途许可声明不是正式上传者验证或全部权利。下一可执行项为最终回归→发行既有产品包（当前配置10MB必须按已过D显式允许512MiB，不能绕过上限）→正式维护候选/核验→版本化逐字段产品用途决议/实际请求；V084仍未应用主库，正式字段结果事务和新包恢复尚缺。
