# 保留既有 manifest 修正与源码冻结专项原件

日期：2026-10-07（Asia/Shanghai）。仅专项技术修正证据，不是任务/门禁状态台账。

## 本次最终行为

- 输出已存在时拒绝 EVIDENCE_OUTPUT_EXISTS，原文件字节和 SHA 不变，不删除、不覆盖。
- 成功只发布到不存在的新路径；临时文件使用 wx，发布用同目录 hard-link 的排他创建，防止并发创建输出被 rename 替换。EEXIST 映射为 EVIDENCE_OUTPUT_EXISTS；仅清理本次自己的临时文件。文件系统不支持 hard-link 时拒绝，不退回覆盖行为。
- 失败在本次新输出路径不发布成功 manifest。独立反例验证既有证据字节和 SHA 不变；CLI 反例也证明已存在证据优先拒绝、另一个新路径读取失败时不留 manifest。
- 历史 README.md、special-tests.log、current-cli.log 原件完整保留。历史 README 对失败清除旧 manifest 的描述是修正前行为，不再代表当前实现；本文件明确此次修正，不改写历史原件。

## ZIP 支持及限制

支持普通单盘 ZIP 的 STORED(method 0)、DEFLATE(method 8)、bit-3 数据描述符成员、Spring Boot 的 BOOT-INF/classes 和 BOOT-INF/lib 外层布局。bit-3 成员使用中央目录的压缩长度、展开长度、CRC定位/验证真实 payload，不依赖本地头零值，不单独校验数据描述符字段。全外层成员校验 CRC/展开长度；BOOT-INF/lib 的内嵌 jar 是外层不透明字节，不递归判定内嵌依赖语义或可运行性。

新增真实受管 JDK jar --create 的隔离测试，直接检查该 JDK 输出中央目录确有 DEFLATE 和 bit-3 成员，再调用核验 helper 通过；另有 STORED 的 BOOT-INF/lib 内嵌 jar 测试。不是仅用自造 STORED fixture 判支持，也不是 Maven/Spring Boot 产品构建。当前真实 Spring Boot 产品 jar 的整体通过结论仍由主会话实际执行，不冒已验证。

不支持 ZIP64、多盘、加密以及其他压缩算法；单成员展开上限 512 MiB、全包展开总量上限 2 GiB，业务类/资产的读取另有 32 MiB 上限。CRC/目录/类头不是运行验证或正式发布授权。

## 最终专项

命令：node --test scripts/lib/consultation-comparison-release.test.mjs

结果：33 tests / 33 pass / 0 fail / 0 skipped / 0 cancelled / 0 todo。新日志：docs/evidence/consultation-comparison-release-20261007/package/freeze-special-tests-20261007.log，SHA256 7c804ecdeac99840a72ae30fce482d280a156b4ff71215d9855ce3c121773a09。

未运行当前 root CLI、Maven、前端构建、verify-all、Docker、DB、SQL或模型。本次只修改获准三个脚本及新增专项证据；从此冻结三个源码文件，不扩展。

### 冻结源码 SHA256

- scripts/lib/consultation-comparison-release.mjs — d1d855ec4d09c0ee3c61569544f305ccfbd35d5c90cc4a552f8770203d9ce48b
- scripts/verify-consultation-comparison-release.mjs — 9c5bc2ca48fed4a65ee4ab43a8a47bfdbf0b05082d97707422fc2546ee451820
- scripts/lib/consultation-comparison-release.test.mjs — 66ec3d5586e982f60fb93606dfb16dae14df10653dc24f9380f0254ba8e4d7ad

### 旧证据保留验证（本轮前后 SHA 相同）

- docs/evidence/consultation-comparison-release-20261007/package/current-cli.log — 9b307118990cca09bfedb304d13c477e33474cddafd723486cd383d3cffa980b
- docs/evidence/consultation-comparison-release-20261007/package/README.md — 5271a7ddfc1b78ada797ba7d22b53d0cae30b13da0896f543686a17fbdda0285
- docs/evidence/consultation-comparison-release-20261007/package/special-tests.log — f0c4db08704ee38c747f780b1a39ac6bf8559449d050c06f9c8c9ea660f188b2
