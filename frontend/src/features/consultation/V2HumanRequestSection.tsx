import { Alert, Button, Tag } from "antd";
import { CircleHelp } from "lucide-react";
import type { HumanOpinion, HumanReturn, HumanRequestCurrent } from "./v2DevApi";

type Props = { current?: HumanRequestCurrent; error?: string; active: boolean; busy: boolean;
  opinion?: HumanOpinion; opinionError?: string; opinionMissing?: boolean;
  returned?: HumanReturn; returnError?: string; returnMissing?: boolean;
  disabled: boolean; onRequest: (sequence: number) => void };

export default function V2HumanRequestSection({ current, error, opinion, opinionError, opinionMissing,
  returned, returnError, returnMissing,
  active, busy, disabled, onRequest }: Props) {
  const request = current?.request;
  return <section className="v2-dev-section"><div className="v2-dev-section-head"><h2>人工帮助</h2>
    {current && <span>请求序号 {current.requestSequence}</span>}</div>
    {error ? <Alert type="warning" showIcon title={error} /> : request ? <div className="v2-dev-human-request">
      <Tag color={request.currentInput ? "processing" : "default"}>
        {request.acceptanceState === "ACCEPTED"
          ? request.currentInput ? "请求已领取" : "历史请求，已领取"
          : request.currentInput ? "请求已保存，尚未受理" : "历史输入的请求，尚未受理"}</Tag>
      <p className="v2-dev-muted">输入版本 {request.inputRevision} ·
        {new Date(request.createdAt).toLocaleString("zh-CN")}</p>
      {request.acceptanceState === "ACCEPTED" && request.acceptedBy &&
        <p className="v2-dev-muted">领取人 {request.acceptedBy}</p>}
      <p>{request.requestText}</p>
      {opinionError && <Alert type="warning" showIcon title={opinionError} />}
      {returnError && <Alert type="warning" showIcon title={returnError} />}
      {returned && <p role="status"><Tag color={returned.currentInput ? "warning" : "default"}>
        {returned.currentInput ? "已退回，等待原任务处理" : "历史请求已退回"}</Tag>
        <span className="v2-dev-muted">{returned.returnedBy} ·
          {new Date(returned.returnedAt).toLocaleString("zh-CN")}</span></p>}
      {opinionMissing && returnMissing && <p className="v2-dev-muted">尚无内部处理结果</p>}
      {opinion && <div className="v2-dev-opinion"><Tag color={opinion.currentInput ? "success" : "default"}>
        {opinion.currentInput ? "内部意见已提交" : "历史输入的内部意见"}</Tag>
        <p className="v2-dev-muted">{opinion.submittedBy} ·
          {new Date(opinion.submittedAt).toLocaleString("zh-CN")}</p>
        <p>{opinion.opinionText}</p></div>}
    </div> : <p className="v2-dev-muted">尚无人工请求</p>}
    {active && current && (!request?.currentInput || Boolean(returned?.currentInput)) &&
      <Button icon={<CircleHelp size={16} aria-hidden="true" />} disabled={disabled}
        loading={busy} onClick={() => onRequest(current.requestSequence)}>
        {returned?.currentInput ? "重新请求人工帮助" : "请求人工帮助"}</Button>}
  </section>;
}
