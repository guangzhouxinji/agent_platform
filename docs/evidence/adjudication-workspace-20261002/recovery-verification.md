# 01D同案重启工程检查原件

依赖01C实际已过，按[验证D1](../../adr/adjudication-product-validation-20261002.md)及docs02第76节续行；本文件只解释实测，01D仍以主计划为唯一任务控制，不冒完整验收或裁决V2全部完成。

## 实际执行

[recovery-second/verification.json](recovery-second/verification.json) 在隔离双DM8 schema清理完成后写 passed=true。浏览器在同一案件完整22版生命周期/5份报告后暂停；Java真正关闭原Spring服务，再以原端口、原两库、原数据和签名密钥启动，未回灌/重建案件；浏览器随后真实请求继续读取。

- [重启握手与逐表SHA](recovery-second/service-restarted.json)：head、22份revision、22份audit、22份receipt及全部case_original正文摘要前后逐项一致。数据库payload及保存的SHA均保留，不以计数代替正文一致性。
- 原作者浏览器会话刷新后完整CaseView与重启前深比较相等；重新加载页面并重新走真实PKCE登录后再次比较相等。不是从localStorage/token或测试fetch填充页面。
- 恢复后查看工程引用原文、阻断v5、失效v4，以及工作v17内已复核非正式的v4；[历史报告截图](recovery-second/recovered-reviewed-history.png)和[当前审计截图](recovery-second/recovered-current-history.png)实际可读，历史操作禁用，不冒当前案件有效结果。
- 再继续真实mTLS来源撤回，页面403清空正文/历史；未以服务重启复活失效来源权限。
- [浏览器结果](recovery-second/browser-result.json)：137个真实响应、4份真实PKCE会话、0页面异常；作者/独立人员/并发标签、退回重提/旧稿/缺证/冲突路径再次实际通过。两ADJ隔离schema已清理。
- [测试日志](recovery-second/tests.log)：原01B18专项+原双DM8+同案恢复浏览器共20项，0失败/错误/跳过。构建实际通过。

## 保留的失败与全仓核验

`recovery-first` 是新增测试取证Map误用set导致编译失败，改为put，不修改业务代码，失败原件未删除。第二轮测试本身已通过，但其后verify报告TESTS_FAILING。`concurrent-classpath-failures.json`/zip保留30套当时canonical失败XML：主要是NoClassDefFoundError/ClassNotFoundException或Spring class path resource缺失。此前完整正式输入回归的322套1045项0失败/错误/39条件跳过及后verify退出0仍有独立原件，不能把后续共享target缺类报告抹去或称最新全仓绿。

对受影响套件使用既有正式输入runner串行实际补跑，不改测试阈值/断言、不删失败报告冒通过，不修改CON/ENF任务行或依赖；实际补跑 [affected-rerun-summary.json](affected-rerun-summary.json) 为30套115项，0失败/错误/跳过，TST隔离schema已清理；[verify-affected-after.log](verify-affected-after.log) 退出0。失败XML保留于zip，当前canonical报告由实际重新执行覆盖，不从旧绿原件伪造。01D完整异常矩阵证据归并/独立退出复核尚未完成，任务保持进行中。

工程包/上下文/引用均明确非现实案件、真实专利或法律依据；本恢复检查不代替现实案件专业质量、正式岗位准入或法律业务验收。
