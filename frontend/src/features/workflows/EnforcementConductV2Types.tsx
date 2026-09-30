import { Table, Tag } from "antd";

export type Link = { elementId: string; factRef: string; evidenceRef: string; locator: string; relation: "SUPPORT" | "CONTRADICT" };
export type Finding = { elementId: string; state: string; support: Link[]; contradiction: Link[]; gaps: string[] };
export type Rule = { id: string; version: number; organization: string; behavior: string; state: string;
  basis: { ref: string; version: string; locator: string; effectStatus: string; scope: string; jurisdiction: string; procedure: string };
  elements: { id: string; description: string; required: boolean }[]; defenses: string[];
  noDefensesConfirmed: boolean; noDefensesSource: string; reviewRef: string; author: string };
export type Report = { id: string; version: number; ruleId: string; ruleVersion: number; behavior: string;
  caseRef: string; caseVersion: string; rightRef: string; rightVersion: string; rightStatus: string;
  facts: { ref: string; kind: string; sourceRef: string; locator: string }[];
  elements: Finding[]; gaps: string[]; outcome: string; reviewState: string; reviewedBy: string;
  reviewReason: string; previousVersion: number };
export type Receipt = { operation: string; resultId: string; version: number };
export type Trial = { elements: Finding[]; gaps: string[]; outcome: string };

export function ConductFindings({ findings }: { findings: Finding[] }) {
  return <div className="enf-next-table"><Table size="small" rowKey="elementId" pagination={false}
    dataSource={findings} locale={{ emptyText: "暂无逐项结果" }} columns={[
      { title: "要件", dataIndex: "elementId", key: "elementId" },
      { title: "状态", dataIndex: "state", key: "state", render: (value: string) => <Tag>{value}</Tag> },
      { title: "支持", key: "support", render: (_, row) => row.support?.map(link => `${link.evidenceRef} · ${link.locator}`).join("；") || "—" },
      { title: "反证", key: "contradiction", render: (_, row) => row.contradiction?.map(link => `${link.evidenceRef} · ${link.locator}`).join("；") || "—" },
      { title: "缺口", key: "gaps", render: (_, row) => row.gaps?.join("；") || "—" }
    ]} /></div>;
}

export function ConductFacts({ facts }: { facts: Report["facts"] }) {
  return <div className="enf-next-table"><Table size="small" rowKey="ref" pagination={false}
    dataSource={facts || []} locale={{ emptyText: "暂无事实输入" }} columns={[
      { title: "事实 Ref", dataIndex: "ref", key: "ref" },
      { title: "事实性质", dataIndex: "kind", key: "kind", render: (value: string) => <Tag>{value}</Tag> },
      { title: "来源", dataIndex: "sourceRef", key: "sourceRef" },
      { title: "定位", dataIndex: "locator", key: "locator" }
    ]} /></div>;
}
