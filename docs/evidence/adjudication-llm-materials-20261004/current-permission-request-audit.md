# 03A现时许可与首个请求核查（2026-10-04）

只读核查范围是本机已登记正式5301/CONPROD、5302/CONAUTH及5301/ADJLLM20261003；不是全世界主体库存，更不是本对话用户的登录身份认证。原件见runtime-read-only-audit.txt及同目录只读Java源，未查询密码/activation/JWT/会话标识/模型正文。

## 实际发现

- ADJLLM20261003仅有ADJ3_MODEL_BUDGET、ADJ3_MATERIAL_RUN，恰好1份唯一approval绑定和1个COMMITTED回合。calls=1、input=687、output=1149、costMicros=10566、frozen=0；截止2026-11-02 00:00:00 +08，未重置。
- 当前已登记正式权威维护主体deployment-maintainer-20260928，组织ORG-CON-PRODUCT，ACTIVE，版本1，角色SUBJECTS/GRANTS/KEYS/SOURCES。它不属于本轮caseInput的project-adjudication。其维护证书存在不等于能给其他组织代签。
- 当前CONPROD没有ADJ2/ADJ3表，ADJUDICATION grant=0；未找到login_name匹配adjudication/adj-的HUMAN。这不是所有用户名的无主体证明，但确实未取得可识别为本案授权业务主体的凭据。
- 01/02测试case-author/测试issuer不用于本轮。咨询主体/供方批准、咨询运行配置/模型开关不修改；不连接执法5444或其事故恢复库。

## 正常落实动作及第一个请求

1. 由组织project-adjudication的当前已登记有权维护主体，通过其正式mTLS维护入口注册/核验当前业务HUMAN账号，正常激活及OAuth2/PKCE获得可信JWT；不手写JWT、不直写Grant、不用数据库密码代表用户权限。
2. 在本轮正式裁决实例复用既有身份/权限/DM8受控部署合同落地同案，不复制咨询授权、不覆盖既有模型预算。维护POST /v2/adjudication/grants，为该主体签发case=adjudication-materials-case-01、dataScope=adjudication-materials-case-01-materials、organization=project-adjudication、domain=ADJUDICATION、resourceType=ADJ_CASE、purpose=CASE_AUXILIARY的CREATE/READ/WRITE及独立MODEL_EXECUTE；各动作当前epoch独立读取，不假定0。
3. 维护POST /v2/adjudication/source-uses，登记确切材料material-1@v1 SHA783f0a88d8b385334c65ee92e6b836e6a07e8b00e33e84c0499c84a11c9a56f2和material-2@v1 SHA cb265515db92f22e8f78774a76d3c5c693570fc96741046943cd25587043e33d，明确READ/SAVE/ANALYZE/MODEL。案件上下文仅按原用途READ/SAVE/ANALYZE，不扩为MODEL。许可截止不能超过本轮批准。
4. 正常创建同案和两份材料后，正式GET读取现时headVersion；首个模型POST /v2/adjudication/case-works/adjudication-materials-case-01/material-organization/runs，Idempotency-Key=adj03a-http-20261004-01，body仅expectedVersion（上述真实GET所得）、materialIds=[material-1,material-2]。不从旧测试推断版本3，不指定客户端model/actor。
5. 调用前从现存V090/V091账本核1次，原同一policy/schema追加；只需要一轮必要新增调用。查询/幂等/授权负例优先复用该回合，不二次付费；恢复读取仍核现时授权。

## 本轮未发送的原因

没有该组织有权维护主体及可核实的当前业务账号/凭据，且本机正式业务库尚无本案API运行数据。不得以咨询维护证书、01/02测试身份或管理员数据库密码替代。因此本轮正式模型HTTP请求NOT_SENT，新增provider调用0。历史用途/供应商/费用批准不重问；只需要项目负责人明确当前project-adjudication有权维护主体及执行账号的受控凭据入口。本轮可安全完成专属旁表的独立备份导入验证，不依赖业务账号。
