# 裁决单实例报告/指定原件恢复：限定工程交付

任务 VER-ADJ-SINGLE-DB-REPORT-RECOVERY-01，依据主计划与single-db ADR§9.6。当前产品域/CaseWorkCommands/JdbcCaseWorkRepository/CaseWorkCodec/CaseSourceAuthority从冻结共享源码独立编译，无共享输出写入；实际加载路径见typed-equality-diagnosis.json。

限定工程出口通过：initialize-run3 19/0，after-restart-typed 6/0，revoke 4/0，after-restore 11/0，各exit0，不累加重复守卫。产品创建→两材料→主张→确定性报告形成5回执/5版/1稿，法律与专业缺项保持阻塞，无CITATION/FINDING/模型。输入仅原manifest允许MECHANICAL_DOMAIN_TEST的原创records；原字节保持，来源SHA及正文不变。

真实同容器持久重启PID82853->0->83494。dexp仅ADJENGMAIN六表，31674bytes，无警告。AES256GCM包67131bytes，认证8个文件（2指定原件+6工程状态/回执/坐标原件），AUTH未打包；篡改AEADBadTagException在目标接触/文件写入前拒绝。AUTH产品撤回generation8->9后才导入REST；dimp显式REMAP_SCHEMA=ADJENGMAIN:ADJENGREST普通REST owner导入exit0无警告，不授全局/跨schema/万能权限。

六业务表完整行数/排序逐行SHA等于导出前；当前AUTH三表完全不回退，REST产品read/originals/versions都因SOURCE_USE_NOT_CURRENT拒绝。product codec单独严格核历史报告不表示授予产品读取。两个指定输入文件恢复字节SHA相同；SRC17/STARTMAIN19/STARTEMPTY18/原三域36共90表行数与SHA前后一致。保护仅本自有隔离实例工程schema，未读取现行业务/凭据。

## 失败原件及准确根因，不降断言换绿

1. initialize8pass/1fail：runtime FK DML缺同schema REFERENCES（-5506），登记事务回滚。仅新MAIN/AUTH同schema补REFERENCES，runtime无DDL/跨schema权限。
2. initialize-resume12pass/1fail：工程Access的identity与actor不同。当前产品仓库line157要求receipt.actor_identity等于audit.actor，实际CaseWorkAccess.identity返回actor。错误create已提交，同原键重放被产品严格完整性校验拒绝；是harness契约错误，不是产品漏洞。无旧行修复/删除、无DROP/重初始化。旧case工程失败历史1版原样保留；成功工程运行用映射case-id engineering-case-001-r3和独立request-key空间，仍同原许可输入，明确不模拟实际业务重试/新Task。
3. 首after-restart4pass/2fail：JsonNode完整等值比较存在LongNode/IntNode、DecimalNode/DoubleNode差异；离线对同一个文件即可重现15处差异。后继采用完整typed State/Version等值，没有去字段或放宽产品验证，且数据库六表完整SHA独立相同。
4. 首native import9warnings：未映射schema无法自动变REST；失败目标0表、源/保护摘要不变。后继实际固定工具支持显式REMAP_SCHEMA，恢复成功无警告；没有用JDBC复制/seed冒原生恢复。

整包还保留前述失败run的1版：MAIN/REST实际2个工程head、6revision/audit/receipt、2case-original、8command-lock；成功run独立5版。不能声称旧失败case产品有效或正式生产迁移通过。

## 范围与结束点

仅新ADJENGMAIN/REST/AUTH和各自runtime；独立严格Access port不冒正式JWT/current-grant链，不创建真实Task/来源/模型/专业评分。DB内来源Use只对自有原创工程输入与确切scope维护。指定文件恢复不是完整生产附件服务验证。

primary5492实际已停止PID0，两个原caplesshelper/卷/loop原件保留；顺序总4GiB、每容器2CPU、有效ext4硬限3094126592bytes不扩大。仅观察配对资源running做预算守卫，不连接/停启A5493，不碰5491/5497。共享backend/db/scripts及全量输出未动；统一检查和主计划状态由主会话负责。交付之后不再启动/扩建此任务。
