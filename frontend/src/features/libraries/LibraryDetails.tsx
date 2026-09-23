import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Alert, Button, Descriptions, Divider, Drawer, Grid, Skeleton, Space, Tag, Typography } from "antd";
import { ExternalLink, RotateCcw } from "lucide-react";
import { apiRequest, ApiError } from "../../shared/api/client";
import { displayCode } from "../../shared/ui/Workbench";
import { LIBRARY_ACTOR, libraryText, type LibraryConfig, type LibraryRecord } from "./libraryConfig";
import type { CaseRecord, LibraryDetailResponse, PatentRecord, TrademarkRecord } from "./types";

export function LibraryError({ error, retry }: { error: unknown; retry: () => void }) {
  const apiError = error instanceof ApiError ? error : null;
  return <Alert type="error" showIcon title={error instanceof Error ? error.message : "资料库加载失败"}
    description={apiError?.code ? `错误码：${apiError.code}${apiError.requestId ? ` · 追踪号：${apiError.requestId}` : ""}` : undefined}
    action={<Button size="small" icon={<RotateCcw size={14} />} onClick={retry}>重试</Button>} />;
}

function SourceLink({ href }: { href?: string }) {
  return href ? <a href={href} target="_blank" rel="noreferrer">打开来源 <ExternalLink size={13} /></a> : <span>未提供</span>;
}

function ExtensionResult({ query }: { query: { isFetching: boolean; isError: boolean; error: unknown; data: unknown; refetch: () => void } }) {
  if (query.isFetching) return <Skeleton className="extension-result" active paragraph={{ rows: 3 }} />;
  if (query.isError) return <div className="extension-result"><LibraryError error={query.error} retry={() => void query.refetch()} /></div>;
  if (!query.data) return null;
  return <StructuredResult data={query.data as Record<string, any>} />;
}

function StateLabel({ value }: { value?: string }) {
  const danger = value?.includes("FAILED") || value?.includes("UNAVAILABLE") || value?.includes("NOT_PROVIDED");
  return <Tag title={value} color={danger ? "orange" : value?.includes("AVAILABLE") || value === "COMPLETE" ? "green" : "default"}>{value ? displayCode(value) : "状态未知"}</Tag>;
}

function StructuredResult({ data }: { data: Record<string, any> }) {
  const labels: Record<string, string> = { abstractSection: "摘要", claimsSection: "权利要求", descriptionSection: "说明书", figuresSection: "附图" };
  if (data.document) {
    const sections = Object.entries(labels).map(([key, label]) => ({ label, value: data.document[key] })).filter((item) => item.value);
    return <div className="extension-result extension-panel"><Typography.Text strong>文献版本 {libraryText(data.document.documentNumber ?? data.document.versionId)}</Typography.Text>
      {sections.map(({ label, value }) => <div className="extension-section" key={label}><div><Typography.Text strong>{label}</Typography.Text><StateLabel value={value.state} /></div>{value.message && <Typography.Paragraph type="secondary">{value.message}</Typography.Paragraph>}{value.texts?.map((item: any, index: number) => <blockquote key={`${item.systemLocator}-${index}`}><span>{libraryText(item.sourceLabel ?? item.systemLocator)}</span>{libraryText(item.text)}</blockquote>)}</div>)}</div>;
  }
  if (data.events) return <div className="extension-result extension-panel"><div className="extension-title"><Typography.Text strong>历史事件</Typography.Text><StateLabel value={data.events.state} /></div>{data.events.message && <Typography.Paragraph type="secondary">{data.events.message}</Typography.Paragraph>}{data.events.records?.map((item: any) => <div className="extension-item" key={item.eventId}><strong>{libraryText(item.type)}</strong><span>{libraryText(item.businessDate)} · {libraryText(item.result)}</span></div>)}</div>;
  if (data.history) return <div className="extension-result extension-panel"><div className="extension-title"><Typography.Text strong>历史信息</Typography.Text><StateLabel value={data.history.state} /></div>{data.history.error && <Typography.Paragraph type="secondary">{data.history.error}</Typography.Paragraph>}{data.history.records?.map((item: any, index: number) => <div className="extension-item" key={`${item.sourceRecordId}-${index}`}><strong>{libraryText(item.matter)}</strong><span>{libraryText(item.businessDate)} · {libraryText(item.status)} · {libraryText(item.result, "尚无决定结果")}</span></div>)}</div>;
  if (data.annotation) {
    const annotation = data.annotation.annotation;
    return <div className="extension-result extension-panel"><div className="extension-title"><Typography.Text strong>发布标注</Typography.Text><StateLabel value={data.annotation.state} /></div>{data.annotation.message && <Typography.Paragraph type="secondary">{data.annotation.message}</Typography.Paragraph>}{annotation && [["事实", annotation.facts], ["争点", annotation.issues], ["结论", annotation.holdings]].map(([label, values]) => <div className="extension-section" key={String(label)}><Typography.Text strong>{label as string}</Typography.Text>{(values as any[])?.map((item, index) => <blockquote key={index}><span>{libraryText(item.sourceLocation)}</span>{libraryText(item.text)}</blockquote>)}</div>)}</div>;
  }
  return <div className="extension-result extension-panel"><Typography.Text type="secondary">该资源暂无可展示的结构化内容。</Typography.Text></div>;
}

function PatentDetail({ data }: { data: LibraryDetailResponse<PatentRecord> }) {
  const record = data.item;
  const [resource, setResource] = useState<string>();
  const extension = useQuery({ queryKey: ["patent-extension", record.sourceId, resource], queryFn: () => apiRequest<any>(resource!, { actor: LIBRARY_ACTOR }), enabled: Boolean(resource) });
  return <><Descriptions column={1} size="small" items={[
    { key: "number", label: "公开 / 公告号", children: libraryText(record.publicationNumber) }, { key: "application", label: "申请号", children: libraryText(record.applicationNumber) },
    { key: "applicant", label: "申请人", children: libraryText(record.applicants) }, { key: "inventor", label: "发明人", children: libraryText(record.inventors) },
    { key: "classification", label: "IPC / CPC", children: libraryText([...record.ipcClassifications, ...record.cpcClassifications]) },
    { key: "status", label: "来源状态", children: <span title={record.sourceLegalStatus}>{displayCode(record.sourceLegalStatus)}</span> }, { key: "source", label: "来源链接", children: <SourceLink href={record.sourceRef} /> }
  ]} /><Divider titlePlacement="start">文献与事件</Divider><Space wrap>
    {record.versions.map((version) => <Button key={version.versionId} onClick={() => setResource(`/api/v0/patent-library/${encodeURIComponent(record.sourceRecordId)}/documents/${encodeURIComponent(version.versionId)}`)}>{version.documentNumber || version.versionId}</Button>)}
    <Button onClick={() => setResource(`/api/v0/patent-library/${encodeURIComponent(record.sourceRecordId)}/events`)}>历史事件</Button>
  </Space><ExtensionResult query={extension} /></>;
}

function TrademarkDetail({ data }: { data: LibraryDetailResponse<TrademarkRecord> }) {
  const record = data.item;
  const [type, setType] = useState<string>();
  const history = useQuery({ queryKey: ["trademark-history", record.sourceId, type], queryFn: () => apiRequest<any>(`/api/v0/trademark-library/${encodeURIComponent(record.sourceRecordId)}/history/${type}`, { actor: LIBRARY_ACTOR }), enabled: Boolean(type) });
  return <><Descriptions column={1} size="small" items={[
    { key: "number", label: "注册号", children: libraryText(record.registrationNumber) }, { key: "applicant", label: "申请人", children: libraryText(record.applicants) },
    { key: "classes", label: "类别", children: libraryText(record.classes.map((item) => `第 ${item.code} 类${item.name ? ` ${item.name}` : ""}`)) },
    { key: "date", label: "注册日 / 有效期", children: `${libraryText(record.registrationDate)} / ${libraryText(record.validUntil)}` },
    { key: "status", label: "来源状态", children: <span title={record.sourceStatus}>{displayCode(record.sourceStatus)}</span> }, { key: "source", label: "来源链接", children: <SourceLink href={record.sourceRef} /> }
  ]} /><Divider titlePlacement="start">历史能力</Divider><Space wrap>{[["changes", "变更信息"], ["oppositions", "异议信息"], ["invalidations", "无效信息"]].map(([value, label]) => { const state = data.historyCapabilities?.[value] ?? "未知"; return <Button key={value} title={state} onClick={() => setType(value)}>{label} · {displayCode(state)}</Button>; })}</Space><ExtensionResult query={history} /></>;
}

function CaseDetail({ data }: { data: LibraryDetailResponse<CaseRecord> }) {
  const record = data.item;
  const [resource, setResource] = useState<string>();
  const extension = useQuery({ queryKey: ["case-extension", record.sourceId, resource], queryFn: () => apiRequest<any>(resource!, { actor: LIBRARY_ACTOR }), enabled: Boolean(resource) });
  return <><Descriptions column={1} size="small" items={[
    { key: "number", label: "案号", children: libraryText(record.docketNumber) }, { key: "type", label: "案例 / 知产类型", children: `${libraryText(record.caseType)} / ${libraryText(record.intellectualPropertyType)}` },
    { key: "institution", label: "机构", children: libraryText(record.institution) }, { key: "procedure", label: "程序", children: libraryText(record.procedure) },
    { key: "date", label: "决定日", children: libraryText(record.decisionDate) }, { key: "outcome", label: "结果摘录", children: libraryText(record.outcomeExcerpt) }, { key: "source", label: "来源链接", children: <SourceLink href={record.sourceRef} /> }
  ]} /><Divider titlePlacement="start">原文与标注</Divider><Space wrap>
    {record.documents.map((document) => <Button key={document.versionId} onClick={() => setResource(`/api/v0/case-library/${encodeURIComponent(record.sourceRecordId)}/documents/${encodeURIComponent(document.versionId)}`)}>原文 · {document.versionId}</Button>)}
    <Button onClick={() => setResource(`/api/v0/case-library/${encodeURIComponent(record.sourceRecordId)}/published-annotation`)}>发布标注</Button>
  </Space><ExtensionResult query={extension} /></>;
}

export function LibraryDetailDrawer<T extends LibraryRecord>({ config, sourceId, close }: { config: LibraryConfig<T>; sourceId?: string; close: () => void }) {
  const screens = Grid.useBreakpoint();
  const detail = useQuery({ queryKey: [config.kind, "detail", sourceId], queryFn: () => apiRequest<LibraryDetailResponse<T>>(`${config.endpoint}/${encodeURIComponent(sourceId!)}`, { actor: LIBRARY_ACTOR }), enabled: Boolean(sourceId) });
  const item = detail.data?.item;
  return <Drawer className="detail-drawer" title={item ? config.identity(item) : "记录详情"} open={Boolean(sourceId)} onClose={close} size={screens.md ? "min(66.666vw, 1120px)" : "100%"} destroyOnHidden>
    {detail.isLoading && <Skeleton active paragraph={{ rows: 8 }} />}{detail.isError && <LibraryError error={detail.error} retry={() => void detail.refetch()} />}
    {detail.data && <div className="detail-content">
      {config.kind === "patents" && <PatentDetail data={detail.data as LibraryDetailResponse<PatentRecord>} />}
      {config.kind === "trademarks" && <TrademarkDetail data={detail.data as LibraryDetailResponse<TrademarkRecord>} />}
      {config.kind === "cases" && <CaseDetail data={detail.data as LibraryDetailResponse<CaseRecord>} />}</div>}
  </Drawer>;
}
