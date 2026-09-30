import { Button } from "antd";
import { SUPPLEMENT, type Task } from "./v2DevApi";
import V2CorrectionSection from "./V2CorrectionSection";

export default function V2TaskInputSection({ task, active, busy, onAppend, onCorrect }: {
  task: Task; active: boolean; busy: boolean; onAppend: () => void; onCorrect: () => void;
}) {
  const correction = task.corrections?.[0];
  const hasSupplement = task.messages.some((message) => message.body === SUPPLEMENT);
  return <section className="v2-dev-section"><div className="v2-dev-section-head"><h2>目标与补充</h2></div>
    {task.messages.map((message) => <div className="v2-dev-message" key={message.revision}>
      <span>输入版本 {message.revision} {correction && message.revision === correction.oldRevision && "· 历史"}
        {correction && message.revision === correction.newRevision && "· 更正后"}</span>
      <p>{message.body}</p></div>)}
    {correction && <V2CorrectionSection correction={correction} />}
    {active && !hasSupplement && <div className="v2-dev-action-row"><span>{SUPPLEMENT}</span>
      <Button onClick={onAppend} disabled={busy}>提交补充</Button></div>}
    {active && task.revision === 2 && hasSupplement && !correction
      && <V2CorrectionSection busy={busy} onCorrect={onCorrect} />}
  </section>;
}
