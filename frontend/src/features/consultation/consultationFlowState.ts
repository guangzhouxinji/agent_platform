export type ConsultationFlowStep =
  | "patent-search"
  | "trademark-search"
  | "case-search"
  | "patent-comparison"
  | "trademark-comparison"
  | "consultation-answer";

export type FlowStepResult = {
  step: ConsultationFlowStep;
  status: string;
  reference?: string;
  updatedAt: string;
};

export type IntentRoute = {
  intentId: string;
  label: string;
  category: string;
  order: number;
  matchedSignals: string[];
  slots: Array<{ name: string; value: string; sourceRound: number; sourceType: string }>;
  missingSlots: string[];
  plannedRoute?: string;
  routeState: string;
  reason: string;
};

export type ConsultationFlowSession = {
  flowId: string;
  question: string;
  context: string;
  purpose: string;
  createdAt: string;
  intent: {
    status: string;
    parserVersion: string;
    primaryIntentId?: string;
    intents: IntentRoute[];
    clarificationQuestions: string[];
    limitation: string;
    traceId: string;
  };
  results: Partial<Record<ConsultationFlowStep, FlowStepResult>>;
};

const PREFIX = "consultation-flow:";
const CURRENT = `${PREFIX}current`;
const SUCCESS = new Set(["SUCCEEDED", "PARTIAL", "ANSWER_READY", "PARTIAL_RESULT"]);

function available() {
  return typeof window !== "undefined" && Boolean(window.sessionStorage);
}

export function isSuccessfulFlowStatus(status?: string) {
  return Boolean(status && SUCCESS.has(status));
}

export function loadConsultationFlow(flowId?: string | null): ConsultationFlowSession | undefined {
  if (!available()) return undefined;
  const id = flowId || window.sessionStorage.getItem(CURRENT);
  if (!id) return undefined;
  try {
    return JSON.parse(window.sessionStorage.getItem(`${PREFIX}${id}`) || "") as ConsultationFlowSession;
  } catch {
    return undefined;
  }
}

export function saveConsultationFlow(flow: ConsultationFlowSession) {
  if (!available()) return;
  window.sessionStorage.setItem(`${PREFIX}${flow.flowId}`, JSON.stringify(flow));
  window.sessionStorage.setItem(CURRENT, flow.flowId);
}

export function clearCurrentConsultationFlow() {
  if (!available()) return;
  window.sessionStorage.removeItem(CURRENT);
}

export function createConsultationFlow(input: Omit<ConsultationFlowSession, "flowId" | "createdAt" | "results">) {
  const flow: ConsultationFlowSession = {
    ...input,
    flowId: crypto.randomUUID(),
    createdAt: new Date().toISOString(),
    results: {}
  };
  saveConsultationFlow(flow);
  return flow;
}

export function recordConsultationFlowStep(flowId: string, result: Omit<FlowStepResult, "updatedAt">) {
  const flow = loadConsultationFlow(flowId);
  if (!flow) return;
  flow.results[result.step] = { ...result, updatedAt: new Date().toISOString() };
  saveConsultationFlow(flow);
  window.dispatchEvent(new CustomEvent("consultation-flow-updated", { detail: flowId }));
}

export function consultationFlowHref(route: string, flowId: string) {
  const separator = route.includes("?") ? "&" : "?";
  return `/app${route}${separator}flowId=${encodeURIComponent(flowId)}`;
}
