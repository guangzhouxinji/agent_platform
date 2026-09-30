import { Tag } from "antd";
import { verificationLines, type VerificationSnapshot } from "./v2Verification";

export default function V2VerificationSection({ snapshot }: { snapshot: VerificationSnapshot }) {
  const lines = verificationLines(snapshot);
  const corrected = Boolean(snapshot.task.corrections?.length);
  return <section className="v2-dev-verification" aria-label="核查进度">
    <div className="v2-dev-section-head"><h2>核查进度</h2><Tag>开发期 · 未发布</Tag></div>
    <p className="v2-dev-muted">读取时任务版本 {snapshot.task.revision}</p>
    <div className="v2-dev-verification-grid">{lines.map((line) =>
      <div key={line.label}><div className="v2-dev-verification-label">{line.label}<Tag
        color={line.mark === "已记录" ? "success" : line.mark === "待核实" ? "warning" : "default"}>
        {line.mark}</Tag></div><p>{line.detail}</p></div>)}</div>
    <div className="v2-dev-verification-gaps"><strong>待核查</strong>
      <span>{corrected ? "更正后输入尚无获准新检索及确认。" : "逐项技术对应尚未形成。"}
        分析目的、专业方法和证据充分性尚未确认。</span></div>
  </section>;
}
