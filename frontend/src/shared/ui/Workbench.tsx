import { Alert, Button, Descriptions, Drawer, Empty, Tag, Typography } from "antd";
import { RotateCcw } from "lucide-react";
import { ApiError } from "../api/client";

export const ACTOR = "v0-authorized-user";
export const REVIEWER = "v0-adjudication-reviewer";
export const ENFORCEMENT_REVIEWER = "v0-enforcement-reviewer";
export const ORGANIZATION = "org-v0";
export const writeHeaders = (actor = ACTOR) => ({
  "X-V0-Actor": actor,
  "Idempotency-Key": crypto.randomUUID()
});
export const requesterHeaders = (write = false) => ({
  "X-V0-Requester": ACTOR,
  ...(write ? { "Idempotency-Key": crypto.randomUUID() } : {})
});

const codeLabels: Record<string, string> = {
  ANSWER_READY: "回答已生成",
  PARTIAL_RESULT: "部分完成",
  NEEDS_CLARIFICATION: "等待补充",
  SUCCEEDED: "已完成",
  NO_RESULT: "无命中结果",
  RESOLVED: "已形成路由建议",
  MULTI_INTENT: "多个诉求",
  UNKNOWN: "无法识别",
  ROUTE_UNAVAILABLE: "能力不可用",
  COMPLETED: "已完成",
  PARTIAL: "部分完成",
  FAILED: "处理失败",
  CANCELLED: "已取消",
  QUEUED: "等待处理",
  PROCESSING: "处理中",
  ACCESS_DENIED: "无权访问",
  SYNTHETIC: "合成开发数据",
  SYNTHETIC_V0: "合成开发数据",
  PUBLIC_PILOT: "公开试验数据",
  PUBLIC_LOCAL: "本地公开数据",
  SOURCE_NOT_CONFIGURED: "来源未配置",
  DEVELOPMENT_MIXED: "开发混合数据",
  NOT_OFFICIAL_DECISION: "非正式决定",
  NOT_LEGAL_CONCLUSION: "非法律结论",
  NOT_CALIBRATED: "结果未校准",
  EVIDENCE_TABLE_ONLY: "仅提供证据表",
  UNREVIEWED: "尚未复核",
  HAS_RECORDS: "有记录",
  NO_RECORDS: "无记录",
  SOURCE_UNAVAILABLE: "来源不可用",
  SOURCE_NOT_PROVIDED: "来源未提供",
  NOT_COLLECTED: "未采集",
  REGISTERED_ACTIVE: "有效注册",
  TEXT: "文字",
  PHONETIC: "读音",
  SEMANTIC: "含义",
  GOODS_SERVICES: "商品/服务",
  ANALYZED: "已分析",
  NOT_ANALYZED: "未分析",
  EXECUTED: "已执行",
  SUSPECTED_SUPPORT: "存在支持线索，待人工复核",
  SUPPORT: "支持关系",
  AVAILABLE: "可用",
  UNSUPPORTED: "当前不支持",
  NOT_PROVIDED: "未提供",
  OCCURRENCE: "发生时间",
  BODY: "正文"
};

export function displayCode(value: unknown): string {
  if (typeof value !== "string") return value === null || value === undefined ? "" : String(value);
  return codeLabels[value] ?? value;
}

export function asText(value: unknown, fallback = "未提供") {
  if (Array.isArray(value)) return value.length ? value.map(displayCode).join("、") : fallback;
  if (value === null || value === undefined || value === "") return fallback;
  if (typeof value === "string") return String(displayCode(value));
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

export function WorkbenchHeader({ eyebrow, title, description, tags = [] }: { eyebrow: string; title: string; description: string; tags?: string[] }) {
  void eyebrow;
  void description;
  void tags;
  return <Typography.Title className="sr-only" level={2}>{title}</Typography.Title>;
}

export function RequestError({ error, retry }: { error: unknown; retry?: () => void }) {
  if (!error) return null;
  const api = error instanceof ApiError ? error : null;
  return <Alert className="workbench-alert" type="error" showIcon title={error instanceof Error ? error.message : "请求未完成"}
    description={[api?.code, api?.requestId && `追踪号 ${api.requestId}`].filter(Boolean).join(" · ") || undefined}
    action={retry ? <Button size="small" icon={<RotateCcw size={14} />} onClick={retry}>重试</Button> : undefined} />;
}

export function StatusTag({ value }: { value?: string }) {
  const color = value === "SUCCEEDED" || value === "ANSWER_READY" || value === "RESOLVED" ? "success" : value === "FAILED" || value === "ACCESS_DENIED" ? "error" : value === "PARTIAL" || value === "NO_RESULT" || value === "NEEDS_CLARIFICATION" || value === "ROUTE_UNAVAILABLE" ? "warning" : "processing";
  return <Tag color={color} title={value}>{value ? displayCode(value) : "等待开始"}</Tag>;
}

export function JsonDrawer({ title, value, onClose }: { title: string; value: unknown; onClose: () => void }) {
  return <Drawer className="detail-drawer" title={title} open={value !== undefined} onClose={onClose} size="min(66.666vw, 1120px)" destroyOnHidden>
    <pre className="json-view">{JSON.stringify(value, null, 2)}</pre>
  </Drawer>;
}

export function SnapshotSummary({ items }: { items: Array<[string, unknown]> }) {
  return <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 3 }} items={items.map(([label, value], index) => ({ key: `${label}-${index}`, label, children: asText(value) }))} />;
}

export function EmptyState({ text = "提交左侧条件后，这里将显示结果。" }: { text?: string }) {
  return <div className="workbench-empty"><Empty description={text} /></div>;
}
