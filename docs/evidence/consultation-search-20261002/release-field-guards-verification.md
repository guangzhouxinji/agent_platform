# v357最终产品发行与字段准入拒绝I（2026-10-02）

对应IMP-CON-SEARCH-02及docs/70 §3.3、§4.1～4.3；没有新数据库、卷、维护身份或新恢复演练。

## 后置回归与发行

backend-regression-v356-product-inputs.log真实退出0；已有真实DM8/执法输入编排生成一次性schema TST8BA3DB39，结束具名清理，仅清理本次测试数据。按本轮开始/结束时间精确采集实际报告摘要于backend-regression-v356-summary.json：300套908项、0失败/0错误/38条件跳过，不把跳过的物理/专业/模型用例计通过。完整模型2项不跳过证据另见v355真实定向原件。

verify-all-v356-full.log实际退出0。package-v357.log以skipTests构建发行，退出0，不冒再次测试。发行jar SHA27dab1e4605009961db9beff0541b87b072b599e50b0fa4296db72a2959c4eb0。

scripts/start-consultation-container.ps1核精确旧API、原双库ID、发行SHA、同一完整Root清单/只读权限后替换；新API9591b961a7d4f53f72a0dc6efa46c6237e70b4b3f3e0dc428850d123686a4c7f，旧v342保留为agent-platform-consultation-api-previous-20261002175550。实际readOnlyRoot=true/UID10001，原两库及卷不变；Root仍2551文件，使用原source-v342.sha256完整清单，不用旧/部分Root冒当前。v342配置原件保留，canonical同步v357。新完整tokenizer正常启动，未禁用模型/JNI以过启动。

## 正式请求与真实DM8零写

field-index-admission-v357.log实际退出0；field-index-admission-v357.json保存实际请求和响应，全部经正式5443维护路由，维护证书/组织职责/水位/Source准入守卫保留，没有test profile或component actor。

- HUPD候选请求ABSTRACT索引503，来源未准入不进入模型。
- LEGAL_STATUS未知字段400；显式空字段400，不悄然默认TITLE。
- 缺省TITLE沿兼容入口，但候选未ACTIVE仍503。
- 同路由无维护客户端证书403，不能借Source字段参数绕过维护授权。
- 具名CONPROD最小既有DB身份只读查询：HUPD的con4_vector_build前后都0，未产生构建成功回执。
- 正式读取候选仍CANDIDATE/version1；该旧候选validUntil已到，不用其形成新正向证据。

这是真实产品部署/拒绝HTTP和主DM8零写I；不是HUPD PRODUCT_ENABLED、真实字段索引成功、字段检索/Benchmark或业务验收。候选三份许可差异不由代码或完整性核验解决，未代签第三方权利。既有TITLE正向/模型/恢复证据保持其原时点；本轮没有重做Task/物理恢复或伪造新正向。
