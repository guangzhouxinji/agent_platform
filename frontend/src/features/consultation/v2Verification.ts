import { FEATURE_MANIFEST, V2DevError, type Confirmation, type HumanOpinion, type HumanRequestCurrent,
  type HumanReturn, type ResultSet, type Task, type WorkState } from "./v2DevApi";

export type VerificationSnapshot = {
  task: Task; work: WorkState; result?: ResultSet; focused?: ResultSet;
  confirmation?: Confirmation; human?: HumanRequestCurrent; opinion?: HumanOpinion; returned?: HumanReturn;
  resultError?: string; focusError?: string; featureError?: string; humanError?: string;
  opinionError?: string; returnError?: string;
};
type Mark = "未开始" | "历史" | "待核实" | "已记录";
export type VerificationLine = { label: string; mark: Mark; detail: string };

export function assertSameTask(task: Task, work: WorkState) {
  if (task.taskId !== work.taskId || task.revision !== work.taskRevision || task.state !== work.taskState) {
    throw new V2DevError(409, "READ_SNAPSHOT_CHANGED");
  }
}

export function verificationLines(s: VerificationSnapshot): VerificationLine[] {
  const { task, work } = s;
  const sameTask = task.taskId === work.taskId && task.revision === work.taskRevision
    && task.state === work.taskState;
  if (!sameTask) return [{ label: "任务版本", mark: "待核实", detail: "任务与工作状态不一致" }];
  const resultRef = work.resultSet;
  const resultMatches = resultRef && s.result?.setId === resultRef.setId
    && s.result.sequence === work.resultSequence && s.result.inputRevision === resultRef.inputRevision
    && (!resultRef.currentInput || resultRef.inputRevision === task.revision)
    && s.result.members.every((member) => member.available);
  const result: VerificationLine = !resultRef && !s.resultError
    ? { label: "检索记录", mark: "未开始", detail: "尚无保存的结果集合" }
    : !resultMatches || s.resultError
      ? { label: "检索记录", mark: "待核实", detail: "集合或来源需要重新核对" }
      : { label: "检索记录", mark: resultRef.currentInput ? "已记录" : "历史",
          detail: `集合序号 ${s.result!.sequence} · 合成范围 ${s.result!.actualScope} 项` };
  const focusRef = work.focus;
  const focusSet = focusRef?.setId === s.result?.setId ? s.result : s.focused;
  const focusMember = focusSet?.members.find((member) => member.ordinal === focusRef?.ordinal);
  const focusMatches = focusRef && focusSet?.setId === focusRef.setId
    && focusSet.inputRevision === focusRef.inputRevision && focusMember?.available
    && (!focusRef.currentInput || focusRef.inputRevision === task.revision);
  const focus: VerificationLine = !focusRef && !s.focusError
    ? { label: "确切焦点", mark: "未开始", detail: "尚未选择集合成员" }
    : !focusMatches || s.focusError
      ? { label: "确切焦点", mark: "待核实", detail: "成员或来源需要重新核对" }
      : { label: "确切焦点", mark: focusRef.currentInput ? "已记录" : "历史",
          detail: `${focusMember!.recordId} · ${focusMember!.version} · ${focusMember!.locator}` };
  const featureRef = work.feature;
  const featureMatches = featureRef && s.confirmation?.featureRevision === work.featureRevision
    && s.confirmation.inputRevision === featureRef.inputRevision
    && featureRef.manifestId === FEATURE_MANIFEST
    && (!featureRef.currentInput || featureRef.inputRevision === task.revision);
  const feature: VerificationLine = !featureRef && !s.featureError
    ? { label: "具名确认", mark: "未开始", detail: "本任务尚无确认版本" }
    : !featureMatches || s.featureError
      ? { label: "具名确认", mark: "待核实", detail: "确认版本需要重新核对" }
      : { label: "具名确认", mark: featureRef.currentInput ? "已记录" : "历史",
          detail: `版本 ${work.featureRevision} · 输入修订 ${featureRef.inputRevision}` };
  const request = s.human?.request;
  const requestMatches = request && request.taskId === task.taskId
    && request.requestSequence === s.human?.requestSequence;
  let human: VerificationLine;
  if (s.humanError || s.opinionError || s.returnError || !request && s.human?.requestSequence
      || request && (!requestMatches || request.currentInput && request.inputRevision !== task.revision)) {
    human = { label: "内部人工", mark: "待核实", detail: "请求或处理记录需要重新核对" };
  } else if (!request) {
    human = { label: "内部人工", mark: "未开始", detail: "没有已保存的内部请求" };
  } else {
    const exact = (row: HumanOpinion | HumanReturn) => row.taskId === task.taskId
      && row.requestId === request.requestId && row.requestSequence === request.requestSequence
      && row.inputRevision === request.inputRevision;
    const mismatch = s.opinion && (!exact(s.opinion) || s.opinion.currentInput !== request.currentInput)
      || s.returned && (!exact(s.returned) || s.returned.currentInput !== request.currentInput)
      || Boolean(s.opinion && s.returned);
    const action = s.opinion ? "内部测试意见已提交" : s.returned ? "已退回" : request.acceptanceState === "ACCEPTED"
      ? "已领取" : "待领取";
    human = mismatch ? { label: "内部人工", mark: "待核实", detail: "处理记录与请求包不一致" }
      : { label: "内部人工", mark: request.currentInput ? "已记录" : "历史",
          detail: `请求序号 ${request.requestSequence} · ${action}` };
  }
  return [result, focus, feature, human];
}
