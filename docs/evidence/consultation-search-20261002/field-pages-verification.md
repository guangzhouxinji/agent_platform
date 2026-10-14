# 字段结果页读取验证

日期：2026-10-02；任务：IMP-CON-SEARCH-02；依据：docs/70 §5.4、docs/02 §69。

产品实现：Page区分v2/v3；v3重读完整字段事实/父Root/实际成员和canonical摘要再分页。只返回本页成员字段定位与语义贡献，所选字段/模型/排序/CE方法随页保留。引用从已核当前逐字段QUERY授权的Source map读取实际原件，再与本页持久定位精确比对。旧TITLE未读V084表，fieldEvidence为null时JSON省略，原构造仍可用。

实际测试：field-pages-v381.log因机械页测试未注册Jackson时间模块失败；修正测试mapper，不修改产品配置。field-pages-v382.log五套22项、0失败/错误/跳过：旧TITLE执行/页、新字段事务存储及完整页、空页不泄漏其他成员、定位不符/缺引用/篡改拒绝、原件逐字段引用与旧引用兼容。verify-all-v382-pre-record.log退出0，47文件/41需求基线未变。

证据等级C：机械H2页/存储测试使用故障替身；HUPD只真实原件定位完整性，不激活、不检索/模型/Benchmark。不是正式字段Worker I或物理恢复证据。运行v357、Task仍TITLE；下一项是字段只读历史恢复闭包，随后适用物理验证/合格来源实际I。
