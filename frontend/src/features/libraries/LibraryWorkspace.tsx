import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button, Empty, Form, Grid, Input, Pagination, Select, Skeleton, Table, Tag, Typography } from "antd";
import { Search, SlidersHorizontal } from "lucide-react";
import { useSearchParams } from "react-router-dom";
import { apiRequest } from "../../shared/api/client";
import { displayCode } from "../../shared/ui/Workbench";
import { FlowHandoff } from "../consultation/FlowHandoff";
import { LibraryDetailDrawer, LibraryError } from "./LibraryDetails";
import { LIBRARY_ACTOR, PAGE_SIZES, libraryConfigs, libraryModeColor, type FilterField, type FilterValues, type LibraryConfig, type LibraryKind, type LibraryRecord } from "./libraryConfig";
import type { CaseRecord, LibraryPageResponse, PatentRecord, TrademarkRecord } from "./types";

function queryFrom(params: URLSearchParams, fields: FilterField[]): FilterValues {
  return Object.fromEntries(fields.map(({ key }) => [key, params.get(key) ?? undefined]));
}

function buildRequest(endpoint: string, params: URLSearchParams) {
  const request = new URLSearchParams(params);
  request.delete("flowId");
  request.set("page", params.get("page") ?? "1");
  request.set("pageSize", params.get("pageSize") ?? "20");
  return `${endpoint}?${request.toString()}`;
}

function RecordCards<T extends LibraryRecord>({ records, config, open }: { records: T[]; config: LibraryConfig<T>; open: (record: T) => void }) {
  return <div className="record-card-list">{records.map((record) => (
    <article className="record-card" key={`${record.sourceId}:${record.sourceRecordId}:${record.sourceVersion}`}>
      <div className="record-card-heading"><div><Typography.Text strong>{config.identity(record)}</Typography.Text><Typography.Text type="secondary">{config.secondary(record)}</Typography.Text></div>
        <Tag title={record.dataMode} color={libraryModeColor(record.dataMode)}>{displayCode(record.dataMode)}</Tag></div>
      <div className="record-card-meta">{config.meta(record).map((value, index) => <span key={`${value}-${index}`}>{value}</span>)}</div>
      <Button type="link" onClick={() => open(record)}>查看详情</Button>
    </article>
  ))}</div>;
}

export function LibraryWorkspace<T extends LibraryRecord>({ kind }: { kind: LibraryKind }) {
  const config = libraryConfigs[kind] as LibraryConfig<T>;
  const [params, setParams] = useSearchParams();
  const [form] = Form.useForm<FilterValues>();
  const [selectedSourceId, setSelectedSourceId] = useState<string>();
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const screens = Grid.useBreakpoint();
  const stableSearch = params.toString();
  const flowId = params.get("flowId");
  const page = Math.max(1, Number(params.get("page") ?? 1));
  const requestedSize = Number(params.get("pageSize") ?? 20);
  const pageSize = PAGE_SIZES.includes(requestedSize) ? requestedSize : 20;

  useEffect(() => form.setFieldsValue(queryFrom(params, config.fields)), [config.fields, form, stableSearch]);
  const list = useQuery({ queryKey: [kind, "list", stableSearch], queryFn: () => apiRequest<LibraryPageResponse<T>>(buildRequest(config.endpoint, params), { actor: LIBRARY_ACTOR }) });
  const columns = useMemo(() => config.columns((record) => setSelectedSourceId(record.sourceRecordId)), [config]);

  function submit(values: FilterValues) {
    const next = new URLSearchParams();
    for (const field of config.fields) {
      const value = values[field.key]?.trim();
      if (value) next.set(field.key, value);
    }
    next.set("page", "1");
    next.set("pageSize", String(pageSize));
    if (flowId) next.set("flowId", flowId);
    setParams(next);
  }

  function changePage(nextPage: number, nextSize: number) {
    const next = new URLSearchParams(params);
    next.set("page", String(nextSize === pageSize ? nextPage : 1));
    next.set("pageSize", String(nextSize));
    setParams(next);
  }

  return <section className="library-workspace" aria-label={config.title}>
    <Typography.Title className="sr-only" level={2}>{config.title}</Typography.Title>
    <FlowHandoff flowId={flowId} />
    <Form form={form} layout="vertical" className="library-filter" onFinish={submit} initialValues={queryFrom(params, config.fields)}>
      <div className="filter-grid">{config.fields.map((field, index) => (
        <Form.Item key={field.key} name={field.key} label={field.label} className={!screens.md && !advancedOpen && index > 1 ? "mobile-filter-hidden" : undefined}>
          {field.options ? <Select allowClear options={field.options} placeholder="全部" /> : <Input allowClear placeholder={field.placeholder} />}
        </Form.Item>
      ))}</div>
      <div className="filter-actions">
        {!screens.md && <Button htmlType="button" type="text" icon={<SlidersHorizontal size={15} />} onClick={() => setAdvancedOpen((value) => !value)}>{advancedOpen ? "收起筛选" : "更多筛选"}</Button>}
        <Button htmlType="button" onClick={() => { form.resetFields(); setParams({ page: "1", pageSize: String(pageSize), ...(flowId ? { flowId } : {}) }); }}>重置</Button>
        <Button type="primary" htmlType="submit" icon={<Search size={16} />}>查询</Button>
      </div>
    </Form>
    {list.isError && <LibraryError error={list.error} retry={() => void list.refetch()} />}
    <div className="library-results">
      <div className="results-heading"><Typography.Text strong>检索结果</Typography.Text><Typography.Text type="secondary">共 {list.data?.total ?? 0} 条</Typography.Text></div>
      {list.isLoading ? <Skeleton active paragraph={{ rows: 7 }} /> : list.data?.items.length ? (screens.md
        ? <Table<T> columns={columns} dataSource={list.data.items} rowKey={(record) => `${record.sourceId}:${record.sourceRecordId}:${record.sourceVersion}`} loading={list.isFetching} pagination={false} scroll={{ x: 980 }} />
        : <RecordCards records={list.data.items} config={config} open={(record) => setSelectedSourceId(record.sourceRecordId)} />
      ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合条件的记录" />}
      <Pagination current={page} pageSize={pageSize} total={list.data?.total ?? 0} showSizeChanger pageSizeOptions={PAGE_SIZES} onChange={changePage} showTotal={(total) => `共 ${total} 条`} />
    </div>
    <LibraryDetailDrawer config={config} sourceId={selectedSourceId} close={() => setSelectedSourceId(undefined)} />
  </section>;
}

export function PatentLibraryPage() { return <LibraryWorkspace<PatentRecord> kind="patents" />; }
export function TrademarkLibraryPage() { return <LibraryWorkspace<TrademarkRecord> kind="trademarks" />; }
export function CaseLibraryPage() { return <LibraryWorkspace<CaseRecord> kind="cases" />; }
