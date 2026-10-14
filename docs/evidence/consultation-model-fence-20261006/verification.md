# 咨询Agent连续实现验证原件索引（2026-10-06）

本文件仅保存执行事实，不维护另一本任务或需求状态表。主任务/完成状态唯一见docs/08。

## 已执行的外呼预留与HTTP合同切片

- 旧代码12项中5项真实反例失败：外呼不确定、落库失败、同键并发、PROPOSAL重试及撤权后重试。原件red.log、red-reports保留。
- 修复后回合/预留/保留与恢复等定向51项通过，其中6项真实DM8场景通过；dm8.log及dm8-exit.json。第一轮DM8场景本身6项通过，但JUnit因测试类双构造器报错，修为单构造器子类后整轮通过；dm8-first.log与原XML保留，不覆盖失败。
- 接续补Checkpoint保存失败后回放及未知模型结果停止，HTTP合同5项与回合14项定向通过：http.log。首次测试代码字段拼写编译失败原件http-compile-first.log保留；未放宽产品合同。
- 首轮未筛选全后端：1541项、0失败、0错误、40条件跳过，377份XML。原件full/full-round02-result.json、full/full-round02-exit.json及同目录源码前后SHA核查、scope、未筛选日志。包含新增8项DM8和Checkpoint窗口场景；不是专业Golden或业务验收。
- 全量准备第一次因刚启动的自有DM8连接6001中止，未进入Maven；full/fixture-prepare-first.log保留。待资源正常并将本轮KB初始化限定端口设为5384后通过，不接触原业务库。
- 汇总器最初拒绝库存旧IT XML；随后以本次Maven实际Running清单与XML名字、时间逐项绑定，未执行库存单列excludedInventory；已执行套缺原件仍拒绝，不累计旧库存。
- 前端21套81项及生产构建退出0，frontend-test.log/frontend-build.log/frontend-exit.json；本轮未改UI，不冒浏览器E2E。
- 原资源5381/5382/5383按容器ID、标签、端口确认后临时启动；新增自有5384仅供新随机隔离schema与无筛选回归。原5237/5311/5312不停止、不迁移、不新签Grant。

## 接续发现的回合时间精度问题

- PrecisionReplayProbe.java复用真实SQL/Dialogue及纳秒时钟，在修复前输出PERSISTED_REPLAY_BYTES=FAIL；precision-probe-red.log。初次内存Turn与SQL留存时间精度不同导致同一Turn的证据SHA不一致；不是供应商重复调用问题。
- 此问题登记FIX-CON-DIALOGUE-PERSISTED-VIEW-01，限定D见关联ADR。修复与最终回归形成新原件后追加，不把首轮全量当作精度修复的证据。

所有模型/身份/检索端口替身均为工程边界，不代表实际供应商调用、正式身份认证、Worker传输或专业质量。DM8随机schema真实创建/删除；V097未向原业务库执行。旧物理快照后的外部调用对账不由当前预留表证明。

## 精度切片接续执行

- JUnit正式反例precision-red.log：15项中1项失败，确切同一answer-turn引用的两次Observation SHA不同；precision-red-reports保留，不覆盖独立探针。
- 提交后读取留存规范视图修复，H2/HTTP/实际DM8/预留与恢复等定向62项、0失败/0错误/0跳过；precision-green.log及precision-green-exit.json。DM8精度与Checkpoint窗口均使用随机schema；供应商仍组件端口。
- 精度修复后工程全量必须重跑：full-final/run.ps1另起自有5385与新空schema，源码前后SHA重新捕获，原首轮1541项原件不覆盖。最终结果形成后追加。

## 最终未筛选回归原件

- 2026-10-06 11:25:56 +08:00，完整Maven Reactor退出0：1543项、0失败、0错误、40条件跳过、377份本次XML；platform-api 1181/0/0/40。full-final/full-round02-result.json、full-round02-exit.json、full-round02-unfiltered.log、full-round02-scope.json及source-inputs.json保存原件。
- 2459个输入文件在命令前后SHA一致；最后一轮代码变更之前的首轮1541项原件保留，不作最后版本证据。XML与同一Maven实际Running清单及时间逐项匹配；未执行库存单列，40跳过逐项保留。
- 本轮额外真实DM8 9项：未知结果与重启、保存失败、同键并发、撤权、恢复元数据、回答后Checkpoint失败、未知结果STOP、纳秒精度及SQL Runtime恢复；全部通过、0跳过。模型和权限边界仍组件端口，不冒供应商/正式身份/专业验收。
- 三个限定切片完成，咨询父任务保持进行中。下一正式JWT/Worker/受管真实模型/浏览器同Run链及后台/专业能力按现行主计划推进；旧物理快照外呼不确定性和历史原封套缺失不由这些测试消除。

## 验证收尾与资源

最终verify-all退出0（final-verify.log）；47需求文件/41契约基线一致。自有5381/5382/5383/5384/5385容器已按ID、标签与独占卷验证后停止，卷及原件保留，resources-stopped.json可核查。原5237/5311/5312未停止未删除，原业务库未运行V097、未新签Grant。source-final.json保存本轮17个关键源码/设计/计划文件SHA，既存其他工作区修改未重置。

收尾命令第一次在写project-plan.json时出现UNKNOWN文件系统错误，原件closeout-first-failure.log保留。检查文件非只读且可正常读取；重新生成派生计划并执行closeout退出0（final-closeout.log）。错误根因未确认，不推断为用户操作或外部业务阻塞，未放宽验证。进度看板桌面/移动检查退出0，dashboard-verify.log保存原件。
