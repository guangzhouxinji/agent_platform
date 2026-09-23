import { Button, Tag, Typography } from "antd";
import { asText, displayCode } from "../../shared/ui/Workbench";

export function ValueList({ values }: { values?: unknown[] }) {
  if (!values?.length) return <Typography.Text type="secondary">无</Typography.Text>;
  return <ul className="value-list">{values.map((value, index) => <li key={index}>{typeof value === "object" ? <StructuredValue value={value as Record<string, any>} /> : asText(value)}</li>)}</ul>;
}

function StructuredValue({ value }: { value: Record<string, any> }) {
  const title = value.issue ?? value.roleAsStated ?? value.kind ?? value.action ?? value.code ?? value.contentKind ?? value.source?.sourceId ?? value.id;
  const detail = value.content ?? value.name ?? value.value ?? value.reason ?? value.originalValue ?? value.location ?? value.source?.location;
  return <span className="structured-value"><strong>{asText(title, "记录")}</strong>{detail && <span>{asText(detail)}</span>}</span>;
}

export function WorkflowSection({ title, meta, children }: { title: string; meta?: React.ReactNode; children: React.ReactNode }) {
  return <section className="workflow-section"><div className="section-title"><Typography.Title level={4}>{title}</Typography.Title>{meta}</div>{children}</section>;
}

export function VersionButtons({ items, onOpen }: { items: Array<{ key: string; label: string; resource: string; id: string; version: number }>; onOpen: (item: { resource: string; id: string; version: number }) => void }) {
  if (!items.length) return <Typography.Text type="secondary">暂无历史版本</Typography.Text>;
  return <div className="version-list">{items.map((item) => <Button key={item.key} onClick={() => onOpen(item)}>{item.label}</Button>)}</div>;
}

export function ScopeTags() {
  return <><Tag color="gold">{displayCode("SYNTHETIC_V0")}</Tag><Tag>{displayCode("NOT_OFFICIAL_DECISION")}</Tag></>;
}
