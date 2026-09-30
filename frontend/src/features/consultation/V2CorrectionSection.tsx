import { Alert, Button, Popconfirm } from "antd";
import { CORRECTED_SUPPLEMENT, CORRECTION_MESSAGE, type Task } from "./v2DevApi";

type Correction = NonNullable<Task["corrections"]>[number];
export const correctionPayload = { expectedVersion: 2, sourceRevision: 2,
  newText: CORRECTED_SUPPLEMENT, userMessage: CORRECTION_MESSAGE };

export default function V2CorrectionSection({ correction, busy, onCorrect }: {
  correction?: Correction; busy?: boolean; onCorrect?: () => void;
}) {
  return correction ? <div className="v2-dev-correction-history">
    <strong>具名更正 · 版本 {correction.newRevision}</strong><p>{correction.utterance}</p>
    <Alert type="warning" showIcon title="旧结果与确认仅作为历史保留"
      description="更正后输入尚无获准检索或技术对照结果。" />
  </div> : <div className="v2-dev-action-row"><span>阀门 → 电磁阀</span>
    <Popconfirm title="提交事实更正？" description="原输入及已产生的引用保留为历史。"
      okText="确认更正" cancelText="返回" onConfirm={onCorrect}>
      <Button disabled={busy}>更正 F3</Button>
    </Popconfirm>
  </div>;
}
