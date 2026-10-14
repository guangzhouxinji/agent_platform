import type { CaseView, Original, Receipt, Version } from "./contract";
import { casePath, request } from "./api";
import { bindCaseRead } from "./caseReceiptBinding";
import { bindOriginalRead } from "./originalReadBinding";
import type { ReportHandoff } from "./reportHandoff";

// Mechanical read integrity only: authorization and professional report methods stay server-owned.
export async function bindWorkspaceRead(target: string, value: unknown, history: unknown, originals: unknown, version?: number, confirmed?: Receipt) {
 const bound = bindCaseRead(target, value, history, version, confirmed);
 return { ...bound, originals: await bindOriginalRead(bound.view.caseInput.source, originals) };
}

export function matchesReportHandoff(current: CaseView, target: ReportHandoff, fresh: CaseView, history: Version[]): boolean {
 const matches = fresh.reports.filter(v => v.report.revision === target.revision), head = history.at(-1);
 const exact = matches.length === 1 ? matches[0] : undefined;
 return target.caseId === current.caseInput.id && fresh.caseInput.id === target.caseId &&
  fresh.caseInput.organization === current.caseInput.organization && fresh.caseInput.dataScope === current.caseInput.dataScope && fresh.inputVersion === current.inputVersion &&
  target.workVersion === fresh.version && target.inputVersion === fresh.inputVersion && head?.workVersion === fresh.version && head.inputVersion === fresh.inputVersion &&
  !!exact && exact.current && !exact.stale && !exact.policyStale && exact.report.inputVersion === target.inputVersion && exact.report.contentSha256 === target.reportSha256;
}

export async function bindAgentHandoffRead(token: string, target: ReportHandoff, fresh: CaseView, history: Version[]) {
 // Reject malformed/cross-case/history reads before even requesting originals. Current-head semantics are intentional.
 bindCaseRead(target.caseId, fresh, history);
 const originals = await request<Original[]>(token, casePath(target.caseId) + "/originals");
 return bindWorkspaceRead(target.caseId, fresh, history, originals);
}
