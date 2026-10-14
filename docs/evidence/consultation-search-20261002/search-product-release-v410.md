# Search 原生真实来源发行与正式维护续证

任务：IMP-CON-SEARCH-02。沿用 docs/70 §3.3、docs/02 §74；不重开 Task 或 Source Engine D。

## 实际回归

- v407 全后端命令实际开始于 2026-10-02T23:17:38.3855498+08:00，结束于 23:25:07.4395294+08:00，退出 1。platform-api 日志报告 790 项、0 failure、1 error、39 条件跳过；错误为 DocumentDraftInputAuditTest.publishedTemplateAloneCannotBecomeAConsumableInput 的 profileBinding 空值比较。其他模块不能用此数代替。日志：backend/apps/platform-api/target/backend-regression-v407-product-inputs.log；一次性 TST0944401B 已由运行器清理。
- 接续时工作树已改用 Objects.equals，未覆盖执法修改。v409 使用同一正式输入回归运行器执行 DocumentDraftInputAuditTest / ProductPatentsViewSourceDocumentsTest：两套各 4 项，共 8 项，0 failure/error/skip；TST16DABEBC 已清理。仅 C，不冒真实产品请求 I。日志：backend/apps/platform-api/target/search-v409-regression.log。
- 变更前 verify-all-v409-before.log 退出 1：TESTS_FAILING / TEST_EVIDENCE_STALE。基线 47 文件 / 41 需求未变，不称最新全仓绿色。

## 发行实物与预检

- v410 最终包实际 package 成功（此次跳过测试；不因此称全量测试成功）。jar：backend/apps/platform-api/target/agent-platform-api-search-v410.jar；SHA256：e44b6f70f253f8460d8d92d31a19f5fbf197e840fc80e92f39c1c1d625ff7eb0。
- 当前环境配置 container-deployment-v410.json 复用原正式身份、凭据引用、双库、只读 UID10001 和所有模型配置；仅改 jar、受控 source-v408.sha256 完整清单，以及已过设计的现有 maxBytes 显式 536870912。
- ValidateOnly 实际通过完整 Root 清单、全部原件 SHA、只读权限及双库身份核对。日志：backend/apps/platform-api/target/search-v410-validate.log。
- 替换前实际核对：主库有效 ACTIVE lease=0；独立 CONAUTH Grant PENDING=0、Source PENDING=0。首次替换命令因人工转写容器 ID 漏一字符被精确身份守卫拒绝，未改变运行实例；再次 inspect 确認原 v357 精确 ID 后按原 launcher 受控替换，结果继续追加。

## 正式物理反例与收口

- v410 实际发行成功，容器 741ee340d90298d563eacebd129f2ca7d672ed6e88f210835f3b47404f06f3a9；旧 v357 容器停止并保留为可回退对象，未删容器、卷或数据。
- v411 正式 mTLS 注册 HTTP503。实际主库 SOURCE_VERSION=VARCHAR(100)，原编码157字符，主库查询确认没有该候选。v412 在同100字符合同上重现1 failure/1 error，TST239CBEEF清理；未以放宽schema隐藏问题。
- 按docs/70 §3.3 / docs/02 §74最小D修正，产品源版=CAPTURE_SHA256:完整manifest SHA（79字符），完整上游版、时间和字节继续保留原件。候选校验按100字符物理合同提前拒绝101，而非存储503；不改旧版、不截断、不新增表。
- v413 五套12项0 failure/error/skip：Candidates3、PatentsViewDocuments4、Verification1、RecoveryFiles1、FieldExecution3；TST694E9BC1已清理。日志search-v413-boundary-after.log。
- v415修正版实际package/发行成功，jar SHA ef4c36cddb78abd40611ec72a699bca80ece636792c8d6beecad169b790e597b；当前容器 b4697c38b0d82290c376ce39696126ebabcdeb5bde84f42b507af2530995d006，旧v410亦保留。
- v417正式维护请求完成候选及VERIFY：sourceId=PATENTSVIEW_ZENODO_2024、contract=patentsview-capture-contract-411；sourceVersion=CAPTURE_SHA256:9c98d2488d0ecf54dd0dcf5a3e84ef29e281c9d382d7d54918d665a9f8569bd1；verificationId=e9d90a03-3f32-4066-b759-c674c1aae7fc、20文献/277原件。日志search-v417-source-maintenance.log；原sourceVersion157字符拒绝没有留下假候选。
- 正式Task 1188085b-0aff-4b09-b49b-09f19e764141 / TaskMetadata DataScope b20e7e55-fdbc-432a-9ef5-46fe158916e4:1通过现有成员OAuth/PKCE创建，Task读200、他Task404；已过期但仍占现时指针的CREATE Grant通过正式路由撤销后才重新签发；操作完成新CREATE Grant也正式撤销，epoch56。第一次客户端检查误用未赋值LASTEXITCODE而报错，但Task实际已创建；保留该失败日志，随后核实际回执、修复客户端判断并撤权，不重复创建Task。
- v418版本化逐字段内部产品用途决议真实原件SHA ed511af8168a70ced68faa95524e5d4899822034275719cbb72d67bf26f04808，经既有readonly Root的受控写入保留UID10001/0640。正式维护入口返回PRODUCT_ENABLED、rightsEvidenceStatus=UNRESOLVED、sourceEpoch=1、decisionId=91b7b22b-7650-449b-884a-451c4725beb4。范围仅本Task/组织/SourceScope；TITLE/CLAIMS=QUERY/SAVE/INDEX/DISPLAY，PUBLICATION_DATE=QUERY；无ABSTRACT、DESCRIPTION或LEGAL_STATUS。决议不表述成第三方完整授权，仓库许可与原上传者未认证事实保留。
- v419以正式CONPROD既有身份实际应用已过D的V084五条语句，新field-scope/evidence两表实际存在；无有效Worker租约，未改旧结果内容/版本。日志search-v419-formal-v084.log。这仅物理迁移，不冒新字段事务或恢复完成。
- 后置全量v416仍运行；verify-all-v418-after-source仅TEST_EVIDENCE_STALE退出1，47/41未变，不冒全仓最终绿色。新源正式模型索引、Worker围栏→结果提交→成员消费及含新原件的联合恢复正在续行；未实际完成不计I/Benchmark。

日期跨度：发行/核验在2026-10-02，后续用途决议/迁移按实际日志跨至2026-10-03。均为当前环境最终产品机制实测，不是未来生产部署或专业业务B验收。

## 新字段实际产品请求 I（v420/v421）

- 正式成员在同Task确认TITLE/CLAIMS、query=brazing rotor blades，Task版本2，input bc5f0a27-d515-4cdc-8605-517f92e6962e。SourceScope随Task版重新授权；不是硬编码业务Task或测试Active记录。
- 正式维护字段索引：TITLE及CLAIMS各20真实文献/20实际模型向量，零复用；模型原件/版本沿已部署ONNX/tokenizer配置。未开放ABSTRACT、DESCRIPTION或LEGAL_STATUS。
- 正式Worker subject 3e74a714-63de-4297-8cdd-f408ae88f87a，实际证书SHA e72232ce08990cf68c7e5fd3f66468bda95ceedd7e231f37ec1903f5319ad7c4，通过已有正式初始化、Grant及成员delegation b456b429-c0e9-406b-8ccb-43aa3b14a86b，不使用测试主体旁路。
- attempt 6d9044e5-755c-4c69-8de7-81725e135d02 / token1，resultSet 81ed8e40-9184-431f-82a7-fbd8b1615a50，20候选/20成员、SUCCEEDED/EXACT，method HYBRID_FIELD_RRF_COMPLETE_CE/v1、digest RESULT_DIGEST_FIELDS_DECIMAL_12/v3、resultSHA 634993b7be701b5b7a1d469ed9c23838c97b16f6298291528c3b3f8cfc8aa990。正式HTTP同键重放结果ID/SHA一致。
- 结果保持LOW_CONFIDENCE、FIELD_CALIBRATION_UNAVAILABLE/v1，不以TITLE标定冒权项标定，不输出法律结论。
- 正式成员取回10+10两页、20唯一ordinal稳定，坏页大小/篡改cursor均409；请求selectedFields与page.fieldEvidence、各citation.fieldLocators集合精确一致。选择revision1 / selection39165f2e-8163-4728-a1fa-a2b2a86344a8 HTTP200绑定实际成员文献版/SHA。跨Task读404沿实际访问守卫。
- 首次命令行数组参数被pwsh -File视为单个逗号字符串，客户端预期字段检查失败；产品读取已200且未修改结果。原失败日志保留，随后用真实PowerShell数组按同正式请求读取通过，没有放宽产品字段守卫。

## 本轮全量回归与新联合备份（v426～v435）

- v416共享target构建冲突、v423/v425独立构建输入定位失败原件保留。v423只读源码快照与实际源码逐SHA一致（0变化），data/db/docs指向原仓库，不替换产品行为或真实数据；最终v426全量322套1046项，0失败/0错误/39条件跳过，具名TST62E25065已实际清理。原始运行窗口2026-10-03T00:26:10.9108723+08:00～00:40:26.2942527+08:00；日志backend-regression-v426-snapshot-inputs.log，输入清单search-v423-build-inputs.json。
- 结构扫描仅排除存在pom.xml的模块构建target，不跳过源码target包，不降低行数硬限；13模块检查通过、123软警告保留。备份脚本复用正式部署配置maxBytes，不再将单文件限制硬编码为10MB。
- v428原生导出因V084两新增表缺SELECT出现2 warnings，未发包；v429仅为既有CONPROD_BKP增加CON4_SEARCH_FIELD_SCOPE/CON4_RESULT_FIELD_EVIDENCE的SELECT，未授写权限/DBA。v430正式物理保留决议v3过期拒绝未发包。依既有合同形成v4、正式维护证书RSA-PSS签名并验证，SHA b9e394032550c2728bc77a189ac14590bd3e4e610bc885a1b5e35050ec37ef73；同ORG/PT2H、retention-v1摘要与授权/来源历史覆盖，不更改业务期限，原v3保留。
- 完整Linux原件Root共2831文件，旧清单逐SHA不变；source-v432.sha256摘要03103106b73fc5b838a6a7ec0a76a1697165b98c9320077f9bfcad96ccc78262，部署显式使用container-deployment-v432.json，readonly UID10001/0750/0640保持。
- v433正式停写、专用导出身份和现时独立水位生成真实DB+文件加密包：search-fields-v433.conbkp，356445351字节、2711 entries，SHA a1175f7dddab37de469010e57cbdac66277c80ca8ae63a62ca22f7e856d40f6b；原生dmp1704563字节，日志search-v433-native-backup.log。API由finally恢复运行。包位于当前托管Vault backup-fields-v433，不将当前环境实测称为未来生产部署/业务验收。
- v434及本次接续v435 verify-all退出0，47需求文件/41契约基线未变。此段不宣称新字段物理恢复或Benchmark已完成；继续同IMP-CON-SEARCH-02。

## 新字段真实联合恢复与历史键序修复（v436～v443）

- v436冻结v415正式开包命令成功认证解密v433包，2711 entries；随后试读不存在的外部manifest.json文件失败只属证据客户端文件路径，开包本身退出0。复用现有恢复容器8734f86b5ce7/5310，未新增容器/卷，旧schema全部保留。
- 初始schema检查客户端正则未识别DISQL带行号输出，拒绝创建；实际只读JDBC/CLI均确认CONREC436不存在。修正客户端后v437创建新CONREC436并原生导入0非零警告，未恢复5302/CONAUTH。v415正式校验器实际拒绝Restored field search evidence inconsistent，原失败日志保留，不以原生导入成功冒产品恢复完成。
- 根因是过滤Map.of的键序跨JVM不确定：历史原filters_json及其filter_sha/scope_sha合法，却被恢复/同键重放当进程重新序列化字节误拒绝。依docs70 §5.4修复为原持久化字节摘要验证+Input/Attempt过滤语义一致校验；不改旧结果/过滤字节/摘要/权限，也不放宽损坏数据拒绝。新定向覆盖合法不同键序、过滤语义/摘要篡改、撤回、撤权、旧token。v438命令行参数未完整引用失败、v439新增反例期望异常类型错误失败均保留；最终v440四套13项0失败/0错误/0跳过。
- v441冻结修正版正式jar SHA c42b3af7b793dd90f3e4376912e207339f53d7b83824062cd91bc5c925c79759，针对已恢复CONREC436实际执行最终产品--task-recovery-verify通过：文件/原始定位、字段v3结果、历史模型与向量、Task/来源/授权独立水位闭包成立，数据库写0。未覆盖数据或重新合成机器结果，不将未来生产部署/业务B计通过。
- v443只读原主库与恢复库13表全部行/字节摘要一致：Task48、Input62、租约43、来源核验50、用途决议49、Attempt50、Source快照50、Result50、成员1793、新字段scope1/字段证据20、selection17/selection成员18。20字段证据SHA ac4c72bc38767bfab1a011f0e90ccd1e1411dd1957fe1b33f41de8fa680815e6；新字段scopeSHA ec894beb8f0dbc5deda6efff0d645e71652006e604fb788ef9955deba76396dc。完整日志search-v443-restore-inventory.log；独立权威未回灌、零写入。
- v442修复后全量正在同源码隔离输出运行，尚未计通过。verify-all-v439-after-fix因TEST_EVIDENCE_STALE退出1，不能用v435之前绿色冒修复后验收。继续当前真实字段Benchmark及正式发行/现时消费；低置信规则不因本次恢复通过升级。

## 修复后全量与当前输入准备（v442～v450）

- v442已完成：322套1046项0失败/0错误/39条件跳过，实际清理TST72D3758C。源码快照与当前源码逐SHA变化0；真实JUnit XML同步canonical保留隔离原件及原属性，不篡改测试结果或触摸假报告。v449 verify-all退出0，之前v439/v444的TEST_EVIDENCE_STALE失败日志保留。
- v445预登记42查询的真实字段工程qrels，数据20篇真实PatentsView原件，标题身份/10组明确权项词的逐原行定位与SHA；不用于置信校准，不是专家相关性金标，尚未运行质量指标。原manifest SHA b8a55f4a92e88c4031af6a1712b96a9cc8569342fe78e73abbb74c3b731ab058。
- v447正式成员OAuth/PKCE创建当前Task41197aa9-d472-47aa-a121-f2bcd6a44494/DataScope ca9af1a2-a4c1-4e42-9028-f2150e2709fc:1；合法读取200、他Task404，CREATE Grant操作后REVOKED/epoch58。不是扩展身份系统或新测试旁路，只处理已到期运行输入。
- v448尝试当前用途续期时，相同sourceId/sourceVersion的新合同被正式唯一约束409拒绝，未创建候选或决议；不制造新sourceVersion、不把旧到期决议当现时许可。继续现有采集器从完整封存真实档案扩展40实际文献，新数据范围/原行及manifest构成实际新来源内容版，旧20篇/旧ZIP/原始链接/许可事实不改。采集/准入/质量均待实际完成才计通过，不以计划文字放行。

## 40篇真实内容新版本及当前正式消费（v450～v472）

- v450使用已封存原始两ZIP、原来源响应/许可和最终原生采集器提取40真实文献/656权项与元数据原行；完整SHA/公开MD5/CRC及原行定位核验，旧20篇原件未改。此是档案新增实际内容提取，不是另一次下载、上传者身份确认或法律权利升级。新包702文件、manifest SHA54a18995b6272b4dd8d8a473f1ee6f059125bc29a580840459ff8d8e5971a639；Source版CAPTURE_SHA256绑定该新内容清单，原上游版本/链接/ZIP摘要仍保留。
- v451将v441固定SHA最终发行包运行于5443，实际容器67bc098005eede3f73d7233f8341c7f3de4f7d7966a096ebf1162e426c1fcc50；旧v415保留可回退。v452受控导入702文件到同一Linux Root，UID10001/0750/0640保持；未新增DB实例或数据卷。
- v453正式维护注册/VERIFY新合同patentsview-capture-contract-453，先撤回旧到期用途epoch2，再启用新版本化用途决议SHA e6ea061705387a8e20a3b949b1db433ed2b34d42acc4ad7503bc487680dd4195，decisionId d8965003-5e24-4e3f-a91c-2f6cd75f71a7、epoch3。rightsEvidenceStatus仍UNRESOLVED，范围仍TITLE/CLAIMS QUERY/SAVE/INDEX/DISPLAY与PUBLICATION_DATE QUERY；无摘要/正文/法律状态、无全部第三方权利声明。
- v455两字段各40向量、新增20/复用20；TITLE/CLAIMS最终模型与摘要沿当前正式配置，CLAIMS buildSHA6cf5788c8bd035280a9f1fc4917bddd2320675d56af91e38f9d19265ddc4c5dd。v458在同新Task确认版2/input d3fd6f09-a38d-4f03-a392-abd8390f6aa9，旧Worker420注册期限已过导致正式Grant入口503，未冒正向通过；其过期状态保留。按正式初始化机制生成当前Worker460 subject09b2b209-5b7c-4177-b222-f5063cf6f48f/证书SHA e7d1dad7cd8531eaae83d28f6a7377d49f827dcf18154fde1353993c72fb8cce，Grant及delegation687dfb33-a391-426e-8ce7-2b026d005837真实发行，不是测试旁路或重建身份协议。
- 首次提交客户端把Goal与Worker世代混同而缺goal-receipt-v460.json，未取得租约；修正客户端显式读取真实Goal458、不复制假Goal。v461正式租约token1提交40成员，resultSet b7edf35c-f0af-4aaf-8ee5-807e8d049f87/SHA013a3ec0cb5a2409b1ad7c0b829d195b978e56f58d7a14d9b93f4c2bb9144923，同键重放一致，HYBRID_FIELD_RRF_COMPLETE_CE/v1及字段v3摘要，LOW_CONFIDENCE/FIELD_CALIBRATION_UNAVAILABLE仍保留。
- v467实际选择1成员revision1/selection2eb91373-723e-4aec-9493-1f2d3b5f2660成功，随后客户端同时运行假定revision0的旧验证导致409（产品CAS正确拒绝）；未重建或降低产品守卫。v468读取真实选择版1/文献SHA不变，20+20两页40唯一，坏游标/页大小409。
- v464最初Linux路径误用Windows Split-Path未封印；v465按准确Unix路径核旧清单全部SHA不变，完整Root3534文件新封印SHA878d3a9a5a88e72b4d9f950ecdcbbbc3b00cc8db7e50c59949b2ec36530ee1b3；config v464正式ValidateOnly通过。v472核canonical确为旧v357配置后保存原件再更新为已验证v464，未覆盖未知配置。
- v469只读旧恢复CONREC436对当前独立水位实际拒绝Authorization history differs from independent high-water mark，证明新授权/撤权状态不能被旧包主库复活；恢复容器8734f86b5ce7/5310已停止且保留全部旧schema与数据。此是已成功恢复检查之后发生现时状态前进的预期拒绝，不覆盖先前v441恢复成功事实。
- 字段Benchmark v2在任何指标运行前登记40真实文献62查询，manifest SHA45a968d394f0084abd05fdaef4c59b73959dcfda6c86006241f13e18197f9fa5，原文标题身份/预声明权项词支持逐行定位，ENGINEERING_RELEVANCE而非专家/法律金标、不用于置信校准。v1只做20篇候选未评测，未以指标修改qrels。v463旧Windows文件路径只读观察执行4查询后停止，不冒当前Linux SLO；部分日志保留。v470 observer配置误读Authority URL导致拒连，未写DB，失败日志保留、仅本轮新建且全挂载只读的失败observer已清理；不是删除旧DB/卷/业务数据。v471复用正式v441 jar、相同Linux Root、固定模型及原5301/5302，在只读观察器直接调用最终排名/索引/来源守卫评测，尚在运行不计质量通过；不新建产品API/profile/假ACTIVE、DB容器或数据卷。
- 日志search-v420-formal-fields.log、search-v421-formal-consumption-retry.log；持久回执均存当前凭据Vault，但不包含Access Token/私钥。实际字段Benchmark、新原件物理联合恢复和专业B仍未完成，不冒结案。

## 字段真空候选语义修正（v476～v479）

- 反查docs70 §4/§4.7：成功检索的真正空池应返回NO_RESULT/NO_MATCHING_CANDIDATE，而不是FIELD_CALIBRATION_UNAVAILABLE。ProductPatentFieldSearchRanking仅此分支复用既有PatentRetrievalConfidence.assess(empty)，有候选、未校准、失败降级和部分来源语义不变，旧TITLE规则不变。
- 同原空池反例同时断言成员为空、规则版/原因/状态和无虚构重排；真实字段Benchmark仍由未修正的正式v441包继续采样，保留其原失败证据，不覆写结果或改变预登记阈值。
- v477源码快照1122文件，输入原件search-v477-build-inputs.json；首Maven命令因PowerShell未引用带点参数解析失败，不计测试。v478纠正引用后实际运行ProductPatentFieldSearchRankingTest 6、ProductPatentFieldExecutionTest 3、ProductPatentSearchExecutionTest 5，共14项0失败/错误，BUILD SUCCESS；日志backend/apps/platform-api/target/search-v477-targeted.log及search-v478-targeted.log，原JUnit XML保留独立target并同步canonical。
- v476前置、v479后置verify-all实际退出0，47需求文件/41契约基线未变。当前5443运行v441；此次修正尚未正式发行或完成修正后真实Benchmark/全量，不将组件通过当作业务验收。IMP-CON-SEARCH-02保持进行中。

## 固定真实模型缓存、内容合同续期与最终发行（v483～v511）

- v471原完整字段Benchmark和v484真实TinyBERT候选均失败，原报告保留：前者空过滤0、p95 14766.805727ms，后者空过滤1但p95 1541.575759ms。不删查询、不改qrels/阈值。限定D允许精确完整评分LRU及独立字段模型，不缓存授权/候选/来源状态、不缩短权项窗口，关闭清缓存；旧TITLE模型不变。
- 字段最终TinyBERT revision81d1926f67cb8eee2c2be17ca9f793c7c3bd20cc、ONNX SHAa1ebdfe09dc81382cfabe64ddce946c1e75c9afaef0ec4e8d4f6d8274b23b48c、tokenizer SHAd241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66及Apache-2.0卡原件固定。不以通用MS MARCO指标替代专利实测。
- v497全量唯一错误为DM8测试清理遗漏V088锚表，失败原件保留；精确清理本轮单行机械schema，补U088逆序及锚清理。v500受影响7项6通过1条件跳过；v502完整同源码323套1053项0失败/错误、39条件跳过实际退出0，TSTD6C687DD已清理，源码SHA无漂移；v507/v508 verify-all实际退出0。
- v505最终包SHA8bbad55e88085cf4d8195ccec988049afac588498a6dcbfe9e6767c21f2f48fa，正式API容器186edf11999e998576dc9bb33f918fe8e045cf476c299bb1dd5b08179058a19f于2026-10-03 04:37 +08启动；原API保留。5301实际V088→U088→V088，51合同锚完整复制，原合同/current/event摘要583ddc4256a660f7ba13e803f4dddd1d8f2538dfec6d104523dfb97fa645cbde未变，仅BKP增加SELECT，不改Source水位。
- v506正式维护撤回到期epoch3→新同内容合同patentsview-capture-contract-506→VERIFY40原件→版本化决议25dbe896-1711-484b-bc08-9bec4166bbe6→PRODUCT_ENABLED epoch5。原内容manifest/sourceVersion不变；决议SHA14c65397b6e1ec8c869985da51b53e19d462e585668882729ab74a5549020374，rights仍UNRESOLVED，未新增摘要/正文/法律状态授权。
- v510先核旧3534文件全SHA不变，新Root3535文件封印SHA18c400b3907bf349ca66e89d83113a3687588f4fa6f9abee160a3cb9ba21aefc。初次封印进程drop所有cap但未指定UID，正确拒读750目录；以正式UID10001重试成功，不放宽权限。ValidateOnly通过，canonical在核旧SHA/保存原件后更新为最终v505包配置。
- v509观察器只含最新RealFieldBenchmark类，against最终jar编译，不混旧排名类；同原真实5301/5302、Root、固定模型/索引只读执行。原冻结40篇62查询60标注完成，Recall@50=1、nDCG@10=1、Precision@10=.1、空过滤1、全结果SHA/顺序重放一致，全部非空重排成功，p95 457.747891ms/p99 468.071516ms，exit0。完整报告field-benchmark-v509-results.json、原stdout日志field-benchmark-v509-native.log保留；性能明确warmup5后100次精确完整真实评分缓存温态，不冒冷推理吞吐/专家金标/置信校准。
- v511准备当前Task495的正式请求；客户端误用启用回执缺失的version而POST Scope400，未形成非法授权。依据现时Source rowVersion5修正客户端，不修改服务合同或降低守卫；正式请求/消费及新40篇/V088恢复闭包续行，不提前结案。

## 本轮实际检索提交、消费、选择和定位（v511～v523）

- 正式Task495确认目标版2/Input58e1b2c9-0a33-4630-84df-6ef5a720730a，TITLE+CLAIMS查询brazing rotor blades；SourceScope绑定现时合同/决议/rowVersion5，两字段各40向量均复用原件不重新采集、不重建模型。当前Worker511 subjectf48d7546-cf41-4be3-91b0-bf475c0be5d8、证书SHA330784e5d34a211d7ee9a0a9aa850ab3d2bd58702b61d4cdf8c677cfd69a85cf经正式注册/Grant/委托，不使用测试主体或假ACTIVE。
- 实际租约attemptf7b83f8d-4697-49af-8e33-825775f0d9df/token1，结果6b3ff974-76cf-4e3e-88ca-221e56de68fb/40成员、SHA185d4a847a3d526db1e567751f7201c94bc8c2254480ee860adb1e5bd4024f83，合法提交与同键重放一致。字段事实绑定最终TinyBERT完整窗口方法/固定模型、重排SUCCEEDED，LOW_CONFIDENCE/FIELD_CALIBRATION_UNAVAILABLE保持；首位US_GRANT:3986239支持原始brazing/rotor/blades词，不称法律结论。
- v513消费客户端默认RequestedFields=TITLE，误拒合法TITLE/CLAIMS响应；没有选择写入。v514以实际确认字段重试：成员Task200/跨Task404、两页20+20共40唯一且顺序完整、坏游标/换页大小409；实际文献选择0f43cf7e-021e-4185-b36e-ec4adabb1a40/revision1、源版/文献版/原始SHA与结果同版。
- v515正式Worker跨Task提交503；维护入口撤Grant75151e55-b5d7-4cdd-be0e-b33e32dae9bd后同键提交503，再正式停用Workload后同键仍503。只记录实际拒绝状态，不将503表述为404/领域细分错误码；真实撤权/停用回执与审计存在，不再次建设身份或围栏机制。
- v523将实际两页响应全部40文献反查到真实原件：每篇rawSha/documentVersion与采集文件SHA相同，80条TITLE/CLAIMS原始UTF8 token与JSON Pointer一致、模型字段定位与引用定位一致，选择版1/resultSHA相同。actual-result-provenance-v523.json保存逐篇结果；这是实际产品响应可追溯性，不冒正式原文内容路由、专家金标或专业分析效果。
- 当前40篇小型工程Benchmark Recall@50覆盖全集，Precision@10=.1、标签为真实标题身份与预声明原权项词支持，不是人工穷尽相关性金标；缓存温态达标不代表大库召回/冷推理/混合多语言质量或置信校准。原失败和预登记规则全部保留。
- v516同最终物理保留合同签署新v5：responsibleParty/organization/PT2H/retainedEvidence/history覆盖未扩，历史审计截止续签2小时、维护RSA-PSS签名及证书指纹实际验证，不延长任何Task/Source业务期限。v517完整Root3536文件/封印fd5536e7e5e3deeb93b639c94e239b4f7b6b3e3c214457454c61c5be4ba7a509正式ValidateOnly通过。v518已停写实际原生导出，当前正式认证DB+文件封包仍运行，目标新隔离schemaCONREC520；不复用旧包或提前称恢复通过。
- 本轮产品代码未在v502全量后改变，直接复用323套1053项0失败/错误39条件跳过；v512/v521 verify-all实际退出0。专业/生产/业务验收各自独立，IMP-CON-SEARCH-02仍进行中。

## 新40篇与V088内容锚同批物理恢复（v518～v530，2026-10-02 UTC）

- v518正式备份同完整Root/专用CONPROD_BKP停写实际原生加密导出1832124字节；认证DB+文件包search-fields-v518.conbkp为667786965字节/3415条目、SHAf279809dc478098547209fd38616acef96ab95b53c994abcbe21458a1ec124a7。历史原件按既有保留覆盖，无裁旧版本/假数据；封包前后水位/引用不变，发布后API恢复，原native-export.log/search-v518-native-backup.log保留。
- v519同最终v505包/托管密钥认证解密及3415条目核验，新restore-fields-v520无覆盖。依本机剩余9.35GiB配置Xmx4g，未改认证/大小规则。新CONREC520在已有5310隔离实例创建，不增DB容器/卷，旧schema保留；446表原生导入7.072秒、零警告；正式双水位/Task/原件/Source/字段结果/选择/内容锚恢复校验通过，5302未回灌。
- v522原库/恢复库35表行数/每列字节SHA相同，含52合同、51内容锚、50 Task、64输入、45执行租约、当前40成员/字段证据/选择版、291 Grant/205当前指针/387事件、43 Workload及47维护事件。每表原件search-v522-restored-inventory.log，不以连接成功代一致性。
- v525认证恢复文件3414条复制到新Linux只读Root，封印SHA65bacfc41d617cab669f5304f52b3077ba43833b2fd0499f7332ee7c404532b4，UID10001/0750/0640及同代码/模型/证书配置ValidateOnly通过；v527同5443正式API实际接恢复库，容器b473c008438a008e40c6805a0a401b4c6cf52b6a1fe1c7eab7c31b5ba0121c8e，不增测试产品API/profile。
- v528正式OAuth成员恢复Task200/跨Task404；SourceScope截止2026-10-02 21:20:57.822UTC、决议21:26:36.718607UTC自然到期，结果读取503，未改期限冒恢复后来源正向。撤权/停用Worker511旧同键503不复活；search-v528-restored-requests.log/search-v528-expired-source.log/search-v528-restored-worker.log保留。正向仅Task资源，不扩张为来源查询/全文/业务验收。
- v529切回原主库/Root，API5e7f61cec2706d7b5cef4302821a36e306687830191a780c2b4847e5219f9543，恢复代码/数据保留，canonical仍原主库v517；v530 verify-all退出0。物理闭包及默认拒绝通过，Current Task495已到期不作新请求。下一原文资源出口在CON-04既有D内细化/实现，不重建模型/身份/Task/恢复。
