import { Alert, Button, Tag } from "antd";
import { Check } from "lucide-react";
import type { PurposeSelection } from "./v2DevApi";

type Props = { value?: PurposeSelection; error?: string; ready: boolean; active: boolean;
  busy: boolean; onSelect: () => void };

export default function V2PurposeSection({ value, error, ready, active, busy, onSelect }: Props) {
  const selected = value?.currentInput && value.analysisPurpose === "TECHNICAL_SIMILARITY";
  return <section className="v2-dev-section" aria-label="分析目的">
    <div className="v2-dev-section-head"><h2>分析目的</h2><span>版本 {value?.purposeSequence ?? 0}</span></div>
    {error && <Alert type="warning" showIcon title={error} />}
    {selected ? <Tag color="success" icon={<Check size={14} aria-hidden="true" />}>技术相似已选择</Tag>
      : <><p className="v2-dev-muted">{value && value.purposeSequence > 0 ? "当前输入未选择" : "未选择"}</p>
        <Button disabled={!ready || !active || busy || !!error} onClick={onSelect}>选择技术相似</Button></>}
  </section>;
}
