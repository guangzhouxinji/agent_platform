# ADR-ENF-14-FACT-INCOMPLETE-01：事实记录缺引用与不完整草稿分层

- 日期：2026-10-03；责任人：Codex。
- 范围：IMP-ENF-DOCUMENT-DRAFT-05 内 FACT_RECORD；不是新D，不修改冻结领域。
- 来源：requirements/知识产权执法助手/14-执法文书自动生成需求文档.md §2、§4.2、§7.1；13-执法文书生成规则配置需求文档.md §4.3；docs/evidence/enforcement-followup-v2-d-20260929.md §6.1；本轮用户明确要求不完整稿与必填/批准拒绝分层。

已有实现把“同案确认引用缺失”在Input构造和集合筛选阶段一律拒绝，无法保存需求允许的显式缺项事实记录稿。法律引用仍为当前基线必填；本决议不取消引用，也不确认任何法律内容。

FACT_RECORD仅在引用列表为空时允许进入prepare/generate，产生blocking MISSING_SOURCE_VALUE，固定空列表和缺项到manifest及不可变原件，状态INCOMPLETE_DRAFT；审核/导出仍拒绝。provided引用仍必须精确同案、当前、CONFIRMED/ACTIVE、SHA/现时授权一致，失效、不可读或未确认不得转换成缺项来绕过检查。CONDUCT_REVIEW和PENALTY_INPUT_REVIEW仍拒绝空引用并消费原声明审核依赖。ENF-11/16不是事实记录未声明的直接依赖，不再为事实记录造商标报告/测试批准。

模板、规则、案件、材料、原件与来源准入门禁不变；12个未知来源路径不猜填。不改LegalReference目标/评估器/发布分类/专业法律审批。不完整稿不是产品最终验收，不具备审核/同版DOCX资格。正式B保持Pending。

69轮四套36项组件0失败/错误/跳过；80轮真实浏览器同案输入1、prepare/generate/query实际成功且审核/导出禁用；81轮实际DM8/文件/原件SHA/事件/回执对账1项0失败/错误/跳过。实际角色为受控技术主体，不宣称业务批准。79轮配置恢复回归出现固定ID清理事故，完整原失败和影响证据保留，另见执行证据；不以局部成功覆盖该事故。

