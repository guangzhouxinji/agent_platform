import { readFileSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";

const evidence = "docs/evidence/v2-contract-detail-20260924/remaining-34-semantic-review.md";
const sha = (path) => createHash("sha256").update(readFileSync(path)).digest("hex");
const read = (path) => JSON.parse(readFileSync(path, "utf8"));
const write = (path, value) => writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
const allIds = ["01", "03", "05", "07", "08"].map(n => `CON-${n}`);
for (const domain of ["ENF", "ADJ"]) for (let n = 1; n <= (domain === "ENF" ? 17 : 12); n++) allIds.push(`${domain}-${String(n).padStart(2, "0")}`);
write("docs/evidence/v2-contract-detail-20260924/remaining-34-before.json", Object.fromEntries(allIds.map(id => [id, read(`governance/contracts/${id}.json`)])));

const consultation = {
  "CON-01": {
    fields: ["3. 商标信息字段"], ui: ["6. 内部验证页面"], service: ["7. 服务输入输出与异常要求"], delivery: ["9. 交付内容"], engineering: ["国产化与达梦 DM8 适配要求"],
    task: "名称或申请/注册号可启动商标资料目标；类别、商品服务和权利人只在对象消歧或当前动作需要时追问，正式详情必须绑定正确源记录和版本。",
    case: ["只凭名称或注册号可开始查询并在歧义时补问", "详情、图样和历史按当前授权重检", "源失败及能力未提供不显示成无异议或无效"],
  },
  "CON-03": {
    fields: ["3. 字段设计"], ui: ["5. 内部验证页面"], service: ["6. 服务契约"], delivery: ["8. 依赖、交付与确认项"], engineering: ["国产化与达梦 DM8 适配要求"],
    task: "案例原文版本、关联程序与已审核派生内容分别供咨询引用；被纠正的事实或原文更新只使依赖结果待复核，保留旧任务和标注版本，不把他案结果当作本案决定。",
    case: ["同一纠纷不同程序和结果仍分别引用", "未经审核的要点不以已校核形式输出", "原文变化只使受影响的下游引用待复核"],
  },
  "CON-05": {
    fields: ["2. 输入输出字段"], ui: ["5. 内部验证页面"], service: ["6. 服务契约"], delivery: ["8. 依赖与交付"], engineering: ["国产化与达梦 DM8 适配要求"],
    task: "自然语言或名称/图样可启动商标检索目标；类别、拟用商品服务、维度和范围在正式检索前按所选能力确认。结果序号绑定具体候选快照，调整条件生成同目标链的新尝试，不覆盖旧结果。",
    case: ["自然语言目标先进入准备而非因未给分类和幂等键拒绝", "图形能力缺失不以OCR文字结果代替", "重排后旧结果集第二项仍指原对象"],
  },
  "CON-07": {
    fields: ["2. 输入输出字段"], ui: ["5. 内部验证页面"], service: ["6. 服务契约"], delivery: ["8. 依赖与交付"], engineering: ["国产化与达梦 DM8 适配要求"],
    task: "商标辅助判断可先受理目标并返回准备缺口；正式逐维分析前确认目标、候选、商品服务、目的和可用维度。用户纠正只重算依赖维度，新版本保持原任务链与旧报告。",
    case: ["缺候选或用途只给准备缺口而非伪完整意见", "纠正商品服务后仅重核相关维度和综合意见", "图样失效时视觉维度未分析且不产生法律近似结论"],
  },
  "CON-08": {
    fields: ["2. 输入输出字段"], ui: ["5. 内部验证页面"], service: ["6. 服务契约"], delivery: ["8. 依赖与交付"], engineering: ["国产化与达梦 DM8 适配要求"],
    task: "咨询问题原文即可启动案例检索目标；宽泛问题先补关键要素，正式检索再锁定范围。调整条件生成关联原目标的新执行，跨轮选用的案例绑定具体文书及结果集版本。",
    case: ["仅输入宽泛问题返回补问而非拒绝创建目标", "跨轮选择第二案仍绑定旧快照的具体文书", "新一次检索不抹掉原目标、范围、案例引用或差异"],
  },
};

function exactSection(text, title) {
  const lines = text.split(/\r?\n/);
  const start = lines.findIndex((line) => line.replace(/^#+\s*/, "").trim() === title);
  if (start < 0) throw Error(`source section absent: ${title}`);
  const depth = lines[start].match(/^#+/)[0].length;
  let end = start + 1;
  while (end < lines.length && !new RegExp(`^#{1,${depth}}\\s`).test(lines[end])) end++;
  return lines.slice(start, end).join("\n").trim();
}

function add(c, id, statement, anchors, given, when, then) {
  const clause = `${id}-MUST-${String(c.clauses.filter(x => x.id.startsWith(`${id}-SEM-`)).length + 1).padStart(3, "0")}`;
  const suffix = `${id}-SEM-AC-${String(c.acceptanceScenarios.filter(x => x.id.startsWith(`${id}-SEM-AC-`)).length + 1).padStart(3, "0")}`;
  const clauseId = clause.replace(`${id}-MUST-`, `${id}-SEM-MUST-`);
  c.clauses.push({ id: clauseId, type: "MUST", statement, sourceAnchors: anchors, acceptanceScenarioIds: [suffix] });
  c.acceptanceScenarios.push({ id: suffix, given, when, then, sourceClauseIds: [clauseId] });
}

function review(c, method, notes) {
  c.independentReview = {
    ...c.independentReview, status: "passed", reviewedBaselineId: c.baselineId,
    reviewedAt: "2026-09-24", reviewMethod: method,
    missingClauses: [], contradictions: [], weakenedConstraints: [], unsupportedExtensions: [],
    reviewEvidence: [evidence], notes,
  };
  c.v2SemanticReview = {
    version: "approved-scope-2026-09-24", reviewedAt: "2026-09-24", evidence,
    approvedSharedBehaviorSource: "docs/02-待确认事项清单.md#23",
    scope: "原文加已确认四项共同约束；其余V2取舍待责任方决定。仅R，不批准D、真实数据、专业验收或部署。",
  };
}

for (const [id, spec] of Object.entries(consultation)) {
  const path = `governance/contracts/${id}.json`;
  const c = read(path);
  if (c.status !== "stale" || c.v2SemanticReview) throw Error(`unexpected contract state ${id}`);
  const source = readFileSync(c.source.path, "utf8");
  if (sha(c.source.path) !== c.source.sha256) throw Error(`source changed ${id}`);
  for (const [name, titles] of Object.entries({ engineering: spec.engineering, fields: spec.fields, ui: spec.ui, service: spec.service, delivery: spec.delivery })) {
    const title = titles[0];
    add(c, id, `保留原文“${title}”的全部适用义务（按操作/用途条件判断字段必填，不把字段表当作冻结的API）：\n${exactSection(source, title)}`,
      [title], [`${title}涉及的条件/数据/失败场景`], `验证${name}义务`, [`逐项满足原文对应范围，不因DEFER或旧V0样例而删除`, `模拟验证、真实DM8与正式验收分层记录`]);
  }
  add(c, id, spec.task, ["docs/02-待确认事项清单.md#20", "docs/02-待确认事项清单.md#23", c.source.sections[1]?.anchor ?? c.source.sections[0].anchor],
    ["用户目标尚未提供正式执行所需全部条件", "已有旧任务及版本化结果"], "启动、补充、纠正和跨轮继续", spec.case);
  if (id === "CON-05") {
    for (const x of c.inputs) if (["target", "classification", "dimensions", "scope", "idempotencyKey"].includes(x.name)) x.required = "仅在正式检索/对应能力执行时按原文条件要求；目标受理可先给自然语言或名称/图样";
  }
  if (id === "CON-07") {
    for (const x of c.inputs) if (["targetSnapshot", "classification", "candidateSnapshots", "scope", "dimensions", "idempotencyKey"].includes(x.name)) x.required = "正式逐维分析前按所选能力和目的确认；目标受理及准备阶段可先返回缺口";
  }
  if (id === "CON-08") {
    for (const x of c.inputs) if (["confirmedElements", "scope", "idempotencyKey"].includes(x.name)) x.required = "正式检索前确认；问题原文可先进入解析和补问";
  }
  c.inputApplicability = "输入的必填性按目标受理、准备、正式执行、复核操作区分；字段名是逻辑概念，不能作已冻结API。";
  c.logicalFieldNamesOnly = true;
  c.status = "ready";
  c.currentProductContract = {
    ...c.currentProductContract, version: "consultation-product-contract-2026-09-24-c4",
    status: "ready", authoritativeContractSections: ["source", "supportingSources", "clauses", "inputs", "outputs", "permissions", "states", "sourceAndVersionRules", "errorsAndExceptions", "decisionPoints", "acceptanceScenarios", "openQuestions"],
    rGateAssessment: "R_FIDELITY_PASSED_CURRENT_APPROVED_SCOPE_D_NOT_GRANTED",
    note: "机读合同保留原文全部适用义务；docs/37的职责说明仍为补充依据，旧DEFER只指实施顺序。",
  };
  c.derivedDesignDecisions = c.derivedDesignDecisions.map(x => ({ ...x, applicability: "历史技术候选，当前Task D门重新判断，不由R批准部署或通用Runtime。" }));
  review(c, "同一Codex源优先再复核：主文全部章节、咨询总目录及已确认决定到机读条款/字段/正反例，并逆向检查旧DEFER未删原文义务；非第二名人员。",
    ["R仅限原文和已确认产品行为，专业标准、数据用途许可、身份和真实验收仍按openQuestions阻相应设计/接入。", "字段和服务按操作条件适用，演示页、Fixture或模型返回不证明产品完成。"]);
  write(path, c);
}

const management = new Set(["ENF-01", "ENF-02", "ENF-06", "ENF-08", "ENF-10", "ENF-12", "ENF-13", "ENF-15", "ADJ-01", "ADJ-02", "ADJ-03", "ADJ-10"]);
const handoff = {
  "ENF-03": "通知投递或待办创建不能称为人员已受理；交接须有可信人员实际受理记录或接收系统真实回执，未受理仍保留待处理和重试/查询入口。",
  "ADJ-04": "本地辅助任务接收记录与案件正式受理分开；前者须有真实人员动作。本地案件受理由授权人员按已确认流程登记实际事实；权威系统模式下只有真实权威回执才同步正式状态，不能用本地领取、模型输出或接口超时推定。",
  "ADJ-12": "人工复核任务交接须有可信人员实际接收记录或外部系统真实回执；分配、通知、审核通过与正式签发分别留证，不互相冒充。",
};

for (const domain of ["ENF", "ADJ"]) for (let n = 1; n <= (domain === "ENF" ? 17 : 12); n++) {
  const id = `${domain}-${String(n).padStart(2, "0")}`;
  const path = `governance/contracts/${id}.json`;
  const c = read(path);
  if (c.status !== "ready" || c.v2SemanticReview || sha(c.source.path) !== c.source.sha256) throw Error(`unexpected state or baseline ${id}`);
  const statement = management.has(id)
    ? "本配置/来源/模板能力参与智能体任务时，服务操作或发布成功仅是执行/资源状态，不直接认定用户业务目标完成；运行结果固定所用来源、规则/模板或审核版本，后续纠正只使依赖结果待复核，历史留存且继续原目标链。若发生人工交接，须有真实受理记录。"
    : "本能力参与智能体任务时，一次工具执行成功、局部输出、通知或审核动作不直接认定整个用户业务目标完成；跨轮对象绑定稳定版本，纠正保留原目标与历史并重核依赖结果；人工接收须有实际记录，业务、任务、审核、正式状态分别表达。";
  add(c, id, statement, ["docs/02-待确认事项清单.md#23", "docs/01-总体需求与架构方案.md#5"],
    ["本能力被领域智能体调用", "产生部分结果、版本变更或用户纠正"], "继续原业务目标或向人员移交",
    ["执行、资源、审核与领域任务完成分别判断", "跨轮对象按原具体版本定位且历史不覆盖", "人工未实际接收时保持待处理，不伪造受理或正式决定"]);
  if (handoff[id]) add(c, id, handoff[id], ["docs/02-待确认事项清单.md#23", c.source.sections[0]?.anchor ?? "1. 功能概述"],
    ["已创建辅助输出或通知，但接收动作尚未发生"], "检查人工交接或权威状态", ["未获得真实记录/回执不得标已受理", "后续人员操作、正式状态分别保存和鉴权"]);
  c.currentV2Review = {
    approvedScope: "原始需求及docs/02第23节共同产品约束；V2其余取舍保持提案", evidence,
    applicability: management.has(id) ? "仅在配置/来源能力被智能体任务调用时施加产品任务语义，不新增配置服务运行时。" : "领域任务调用时施加，完成和证据标准仍由本领域定义。",
  };
  review(c, "旧原文独立反查基础上同一Codex对主文/领域总览及V2共同约束源优先再检查，逐项结论见复核证据；ADJ-01～03并查三份既有法规需求。非第二名人员。",
    ["原文完整义务、模块内部页面/服务/DM8/交付和已有反例继续有效；旧ready不作为本次V2结论依据。", "专业规则、权限、真实受理、数据许可和正式业务效果仍按openQuestions取得外部证据。"]);
  write(path, c);
}
