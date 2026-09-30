import { useState } from "react";
import { Alert, Button, Form, Input, Space, Typography } from "antd";
import { RefreshCw } from "lucide-react";

type Receipt = { requestId: string; operation: string; resultId: string; resultVersion: number };
export type Rule = { id: string; revision: number; state: string; basisRef: string; sourceId: string };
type Trial = { ruleId: string; observationId: string; sourceVersion: string; textSha256: string; outcome: string };
type Props = {
  rule: Rule | null;
  setRule: (rule: Rule | null) => void;
  busy: boolean;
  request: <T>(path: string, body?: unknown) => Promise<T>;
  run: (action: () => Promise<void>) => Promise<void>;
  report: (receipt: Receipt) => void;
};

const outcomeLabel: Record<string, string> = {
  MATCH: "命中", NO_MATCH: "未命中", NOT_APPLICABLE: "不适用", UNKNOWN: "字段不足"
};

export default function EnforcementRuleTab({ rule, setRule, busy, request, run, report }: Props) {
  const [trial, setTrial] = useState<Trial | null>(null);
  async function refresh(id: string) {
    setTrial(null);
    setRule(await request<Rule>(`/discovery-rules/${encodeURIComponent(id)}`));
  }
  return <div className="enf3-columns">
    <section><Typography.Title level={4}>规则草稿</Typography.Title>
      <Form layout="vertical" onFinish={(values) => void run(async () => {
        const receipt = await request<Receipt>("/discovery-rules", values); report(receipt);
        await refresh(receipt.resultId);
      })}>
        <Form.Item name="id" label="规则 ID" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="sourceId" label="适用来源" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="literal" label="字面条件" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="basisRef" label="依据定位" rules={[{ required: true }]}><Input /></Form.Item>
        <Button type="primary" htmlType="submit" loading={busy}>保存草稿</Button>
      </Form>
    </section>
    <section><Typography.Title level={4}>版本</Typography.Title>
      <Form layout="inline" onFinish={(values) => void run(() => refresh(values.id))}>
        <Form.Item name="id" label="规则 ID" rules={[{ required: true }]}><Input /></Form.Item>
        <Button htmlType="submit" icon={<RefreshCw size={16} />} aria-label="读取规则" />
      </Form>
      {rule && <div className="enf3-detail"><strong>{rule.id}</strong><span>{rule.state} · v{rule.revision}</span>
        <span>{rule.basisRef}</span>
        <Form layout="inline" onFinish={(values) => void run(async () => {
          setTrial(await request<Trial>(`/discovery-rules/${encodeURIComponent(rule.id)}/trial/${encodeURIComponent(values.observationId)}`));
        })}>
          <Form.Item name="observationId" label="观察 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Button htmlType="submit" loading={busy}>试跑</Button>
        </Form>
        {trial?.ruleId === rule.id && <Alert type="info" showIcon
          message={`${outcomeLabel[trial.outcome] ?? trial.outcome} · ${trial.sourceVersion}`}
          description={<code>{trial.textSha256}</code>} />}
        {rule.state === "DRAFT" && <Form layout="vertical" onFinish={(values) => void run(async () => {
          report(await request<Receipt>(`/discovery-rules/${encodeURIComponent(rule.id)}/publish`,
            { revision: rule.revision, ...values }));
          await refresh(rule.id);
        })}>
          <Form.Item name="positiveObservationId" label="命中观察 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="negativeObservationId" label="未命中观察 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Button htmlType="submit" loading={busy}>核验并发布</Button>
        </Form>}
        <Space><Button disabled={rule.state !== "ACTIVE"} loading={busy} danger onClick={() => void run(async () => {
          report(await request<Receipt>(`/discovery-rules/${encodeURIComponent(rule.id)}/disable`, { revision: rule.revision }));
          await refresh(rule.id);
        })}>停用</Button></Space>
      </div>}
    </section>
  </div>;
}
