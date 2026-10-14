import type { CaseView, Receipt, Version } from "./contract";

export type CasePacket = { path: string; body: unknown; key: string };
type ReportViewLike = { report: { revision: number; contentSha256: string } };
const root = "/v2/adjudication/case-works";
const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) > 0;
const sha = (v: unknown): v is string => typeof v === "string" && /^[a-f0-9]{64}$/.test(v);
const caseId = (v: unknown): v is string => typeof v === "string" && /^[A-Za-z0-9_-]{1,120}$/.test(v);

// A malformed 200 is not a deterministic HTTP rejection of the write.
export class CaseBindingError extends Error {
 constructor(public kind: "read" | "receipt", detail: string) {
  super(kind === "receipt" ? `回执未能绑定原请求：${detail}。写入结果尚未确认，请使用原请求键对账。` : `案件响应未通过精确校验：${detail}。未展示本次响应，请刷新案件。`);
 }
}
function readFailure(detail: string): never { throw new CaseBindingError("read", detail); }
function receiptFailure(detail: string): never { throw new CaseBindingError("receipt", detail); }

export function bindCaseRead(target: string, value: unknown, history: unknown, version?: number, confirmed?: Receipt): { view: CaseView; versions: Version[]; head: number } {
 if (!record(value) || !record(value.caseInput) || value.caseInput.id !== target) readFailure("案件与请求不一致");
 if (!positive(value.version) || !positive(value.inputVersion) || value.inputVersion > value.version) readFailure("工作或输入版本非法");
 if (version !== undefined && (!positive(version) || value.version !== version)) readFailure("不是请求的确切历史工作版本");
 if (!Array.isArray(history) || !history.length) readFailure("缺少当前版本头");
 let previous = 0;
 for (const entry of history) {
  if (!record(entry) || !positive(entry.workVersion) || !positive(entry.inputVersion) || entry.inputVersion > entry.workVersion || entry.workVersion <= previous) readFailure("版本历史非法或重复");
  previous = entry.workVersion;
 }
 const head = history.at(-1) as Version;
 const exact = history.find(entry => entry.workVersion === value.version) as Version | undefined;
 if (!exact || exact.inputVersion !== value.inputVersion || (version === undefined && head.workVersion !== value.version)) readFailure("快照与版本历史或当前头不一致");
 for (const field of ["materials", "claims", "findings", "citations", "reports", "inputContributors", "audits"]) if (!Array.isArray(value[field])) readFailure("案件结构不完整");
 for (const field of ["materials", "claims", "findings", "citations"]) {
  for (const item of value[field] as unknown[]) if (!record(item) || item.caseId !== target) readFailure("案件记录属于其他案件");
 }
 const revisions = new Set<number>();
 for (const item of value.reports as unknown[]) {
  if (!record(item) || !record(item.report)) readFailure("报告结构非法");
  const report = item.report;
  if (!positive(report.revision) || revisions.has(report.revision) || !positive(report.inputVersion) || report.inputVersion > value.inputVersion || !sha(report.contentSha256)) readFailure("报告版本或摘要非法");
  revisions.add(report.revision);
 }
 if (confirmed) {
  if (value.caseInput.id !== confirmed.caseId || value.version !== confirmed.workVersion || value.inputVersion !== confirmed.inputVersion || exact.snapshotSha256 !== confirmed.snapshotSha256) readFailure("读取不是已确认命令的确切结果版本");
  if (confirmed.reportRevision !== null && !(value.reports as ReportViewLike[]).some(item => item.report.revision === confirmed.reportRevision && item.report.contentSha256 === confirmed.reportSha256)) readFailure("读取报告与已确认回执不一致");
 }
 return { view: value as unknown as CaseView, versions: history as Version[], head: head.workVersion };
}

export function bindCaseReceipt(packet: CasePacket, value: unknown): Receipt {
 if (!record(value) || value.requestId !== packet.key) receiptFailure("requestId 与原请求键不一致");
 if (!caseId(value.caseId) || !positive(value.workVersion) || !positive(value.inputVersion) || value.inputVersion > value.workVersion || !sha(value.snapshotSha256)) receiptFailure("案件、版本或快照摘要非法");
 const hasReport = positive(value.reportRevision) && sha(value.reportSha256);
 if (!hasReport && !(value.reportRevision === null && value.reportSha256 === null)) receiptFailure("报告版本与摘要必须成对合法");
 if (packet.path === root) {
  // No existing Case identity is known at create time; never bind to the editable entry field.
  if (value.action !== "CREATE" || value.workVersion !== 1 || value.inputVersion !== 1 || hasReport) receiptFailure("创建回执坐标不符合原合同");
 } else {
  const match = packet.path.match(/^\/v2\/adjudication\/case-works\/([^/]+)(\/.*)$/);
  let originalCase: string | undefined;
  try { originalCase = match && decodeURIComponent(match[1]) || undefined; } catch { receiptFailure("原案件路径非法"); }
  if (!match || !caseId(originalCase) || value.caseId !== originalCase) receiptFailure("caseId 与原请求路径不一致");
  if (!record(packet.body) || !positive(packet.body.expectedVersion) || value.workVersion !== packet.body.expectedVersion + 1) receiptFailure("回执不是原命令的确切工作版本");
  const actions: Record<string, string> = { "/context-revisions": "CONTEXT", "/materials": "MATERIAL", "/claims": "CLAIM", "/citations": "CITATION", "/findings": "FINDING", "/reports": "GENERATE_REPORT" };
  const reportAction = match[2].match(/^\/reports\/([1-9]\d*)\/(revisions|submit|reviews)$/);
  const action = reportAction ? ({ revisions: "REVISE_REPORT", submit: "SUBMIT", reviews: "REVIEW" } as Record<string, string>)[reportAction[2]] : actions[match[2]];
  if (!action || value.action !== action) receiptFailure("动作与原请求不一致");
  if ((reportAction || action === "GENERATE_REPORT") && !hasReport) receiptFailure("报告命令缺少报告坐标");
  if (reportAction) {
   const base = Number(reportAction[1]);
   if (!positive(base) || value.reportRevision !== (action === "REVISE_REPORT" ? base + 1 : base)) receiptFailure("报告版本与原动作不一致");
   // Submit/review preserve content. Revision creates new content; its SHA must not be compared to the old draft.
   if (action !== "REVISE_REPORT" && value.reportSha256 !== packet.body.reportSha256) receiptFailure("报告摘要与原提交不一致");
  }
 }
 return value as unknown as Receipt;
}
