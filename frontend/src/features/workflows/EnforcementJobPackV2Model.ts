export type FactType = "TASK" | "SOURCE_RIGHTS" | "ACTOR_POSITION" | "APPROVAL" | "HUMAN_RECEIPT" | "BUSINESS_EVALUATION";
type Evidence = { ref: string; sha256: string; fileKey: string; originalName: string };
type Fact = { type: FactType; fields: Record<string, string>; evidence: Evidence[]; submittedBy: string; submittedAt: string;
  verification: string; verifiedBy?: string; verifiedAt?: string; verificationReason?: string };
export type Snapshot = { id: string; revision: number; contentRevision: number; organization: string; title: string; purpose: string;
  state: string; facts: Fact[]; findings: string[]; sha256: string; trigger?: { requestId: string; taskId: string };
  publicInput?: { claimedBy: string; sourceName: string; sourceUrl: string; rawSha256: string } };
export type Receipt = { packageId: string; revision: number; state: string };
type Field = { name: string; label: string; kind?: "date" | "textarea" | "score" | "decision" };
export type Section = { type: FactType; title: string; fields: Field[]; evidence: boolean; evidenceHelp: string };

const sections: Section[] = [
  { type: "TASK", title: "真实任务", evidence: true, evidenceHelp: "原始任务文件及其可复核 SHA-256", fields: [
    { name: "externalTaskRef", label: "外部任务 Ref" }, { name: "externalTaskVersion", label: "来源版本" },
    { name: "originalTaskSha256", label: "原始任务 SHA-256" }, { name: "currentProcess", label: "当前人工流程", kind: "textarea" },
    { name: "expectedOutput", label: "预期输出", kind: "textarea" }] },
  { type: "SOURCE_RIGHTS", title: "来源许可", evidence: true, evidenceHelp: "许可、授权或合法来源凭证原件", fields: [
    { name: "sourceRef", label: "来源 Ref" }, { name: "sourceVersion", label: "来源版本" },
    { name: "rightsHolder", label: "权利人 / 授权方" }, { name: "allowedUses", label: "允许用途（须含 REAL_INTEGRATION）" },
    { name: "scope", label: "授权范围" }, { name: "validFrom", label: "有效期开始", kind: "date" },
    { name: "validTo", label: "有效期结束", kind: "date" }] },
  { type: "ACTOR_POSITION", title: "真实身份岗位", evidence: true, evidenceHelp: "任命、岗位或授权关系凭证原件", fields: [
    { name: "personRef", label: "当前登录人员 Ref" }, { name: "organizationRef", label: "组织 Ref" },
    { name: "positionTitle", label: "岗位名称" }, { name: "appointmentRef", label: "任命 / 授权 Ref" },
    { name: "validFrom", label: "任职开始", kind: "date" }, { name: "validTo", label: "任职结束", kind: "date" }] },
  { type: "APPROVAL", title: "实际人工批准", evidence: true, evidenceHelp: "批准决定、签批记录或等效凭证原件", fields: [
    { name: "decision", label: "决定", kind: "decision" }, { name: "method", label: "批准方法" },
    { name: "reason", label: "决定理由", kind: "textarea" }, { name: "targetContentRevision", label: "所批准内容版本" }] },
  { type: "HUMAN_RECEIPT", title: "正式人工回执", evidence: false, evidenceHelp: "当前登录人员提交的回执原文及不可变修订摘要", fields: [
    { name: "personRef", label: "当前提交人 Ref" }, { name: "positionTitle", label: "岗位名称" },
    { name: "text", label: "人工回执原文", kind: "textarea" }, { name: "targetContentRevision", label: "关联内容版本" }] },
  { type: "BUSINESS_EVALUATION", title: "业务评价", evidence: false, evidenceHelp: "评价人填写的五项评分、依据与结论", fields: [
    { name: "completeness", label: "完整性（1-5）", kind: "score" }, { name: "traceability", label: "可追溯性（1-5）", kind: "score" },
    { name: "operability", label: "可操作性（1-5）", kind: "score" }, { name: "accuracy", label: "事实准确性（1-5）", kind: "score" },
    { name: "humanEffort", label: "人工投入改善（1-5）", kind: "score" }, { name: "basis", label: "评价依据", kind: "textarea" },
    { name: "conclusion", label: "评价结论", kind: "textarea" }, { name: "targetContentRevision", label: "关联内容版本" }] }
];

export const stateIndex: Record<string, number> = { DESIGN_READY: 0, COLLECTING: 1, WAITING_DATA: 2, WAITING_REAL_HUMAN_ACTION: 2, READY: 3, RUNNING: 4, PASSED: 5, FAILED: 5 };
export const stateLabel: Record<string, string> = { COLLECTING: "采集中", WAITING_DATA: "等待真实数据", WAITING_REAL_HUMAN_ACTION: "等待真实人员操作", READY: "已就绪", RUNNING: "真实集成执行中", PASSED: "已通过", FAILED: "未通过" };
export const verificationLabel: Record<string, string> = { PENDING: "待异人核验", VERIFIED: "异人核验通过", REJECTED: "核验未通过" };

export function findingLabel(finding: string) {
  if (finding.startsWith("MISSING:")) return "尚未提交";
  if (finding.startsWith("UNVERIFIED:")) return "尚未通过异人核验";
  if (finding.startsWith("MISSING_FIELD:")) return `缺少必填字段：${finding.split(":").at(-1)}`;
  if (finding.startsWith("EXPIRED:")) return "有效期已过";
  if (finding === "USE_NOT_ALLOWED:SOURCE_RIGHTS") return "来源用途未覆盖当前任务目的";
  if (finding.startsWith("STALE_TARGET:")) return "关联的内容版本已失效";
  if (finding === "NOT_APPROVED") return "批准结论不是已批准";
  if (finding.startsWith("INVALID_SCORE:")) return `评价分值无效：${finding.split(":").at(-1)}`;
  return finding;
}

export function sectionsFor(pack: Snapshot | null): Section[] {
  if (!pack) return sections;
  let result = pack.publicInput ? sections.filter(section =>
    ["ACTOR_POSITION", "APPROVAL", "HUMAN_RECEIPT", "BUSINESS_EVALUATION"].includes(section.type)) : sections;
  if (pack.purpose === "FUNCTIONAL_VALIDATION") result = result.filter(section => section.type !== "ACTOR_POSITION").map(section => {
    if (section.type === "TASK") return { ...section, title: "测试任务输入", evidenceHelp: "脱敏或项目测试任务原件及其可复核 SHA-256" };
    if (section.type === "SOURCE_RIGHTS") return { ...section, title: "测试数据来源", evidenceHelp: "测试数据来源、允许用途和范围凭证" };
    if (section.type === "APPROVAL") return { ...section, title: "测试人工批准", evidenceHelp: "由另一测试主体实际提交的功能验证批准凭证" };
    if (section.type === "HUMAN_RECEIPT") return { ...section, title: "测试人工回执" };
    if (section.type === "BUSINESS_EVALUATION") return { ...section, title: "功能评价" };
    return section;
  });
  return result.map(section => ({ ...section, fields: section.fields.filter(field =>
    !(section.type === "ACTOR_POSITION" && ["personRef", "organizationRef"].includes(field.name))
    && !(section.type === "HUMAN_RECEIPT" && field.name === "personRef")
    && !(pack.purpose === "FUNCTIONAL_VALIDATION" && section.type === "HUMAN_RECEIPT" && field.name === "positionTitle"))
    .map(field => pack.purpose === "FUNCTIONAL_VALIDATION" && section.type === "SOURCE_RIGHTS" && field.name === "allowedUses"
      ? { ...field, label: "允许用途（须含 FUNCTIONAL_VALIDATION）" } : field) }));
}

export function sectionFindings(pack: Snapshot, type: FactType) {
  return pack.findings.filter(item => item.includes(type)
    || (type === "APPROVAL" && item === "NOT_APPROVED")
    || (type === "BUSINESS_EVALUATION" && item.startsWith("INVALID_SCORE:")));
}

export function displayTime(value?: string) {
  if (!value) return "-";
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? value : parsed.toLocaleString("zh-CN", { hour12: false });
}
