import { Button, Tag } from "antd";
import type { TableColumnsType } from "antd";
import { displayCode } from "../../shared/ui/Workbench";
import type { CaseRecord, PatentRecord, TrademarkRecord } from "./types";

export const LIBRARY_ACTOR = "v0-authorized-user";
export const PAGE_SIZES = [10, 20, 50];

export type LibraryKind = "patents" | "trademarks" | "cases";
export type LibraryRecord = PatentRecord | TrademarkRecord | CaseRecord;
export type FilterValues = Record<string, string | undefined>;

export interface FilterField {
  key: string;
  label: string;
  placeholder?: string;
  options?: Array<{ label: string; value: string }>;
}

export interface LibraryConfig<T extends LibraryRecord> {
  kind: LibraryKind;
  title: string;
  subtitle: string;
  endpoint: string;
  fields: FilterField[];
  columns: (open: (record: T) => void) => TableColumnsType<T>;
  identity: (record: T) => string;
  secondary: (record: T) => string;
  meta: (record: T) => string[];
}

export function libraryText(value: unknown, fallback = "未提供") {
  if (Array.isArray(value)) return value.length ? value.join("、") : fallback;
  return value === null || value === undefined || value === "" ? fallback : String(value);
}

export function libraryModeColor(mode: string) {
  if (mode === "PUBLIC_LOCAL") return "green";
  if (mode === "PUBLIC_PILOT") return "gold";
  if (mode === "SYNTHETIC_V0") return "blue";
  return "default";
}

const actionColumn = <T extends LibraryRecord>(open: (record: T) => void): TableColumnsType<T>[number] => ({
  title: "操作",
  key: "action",
  width: 96,
  fixed: "right",
  render: (_, record) => <Button type="link" onClick={() => open(record)}>查看详情</Button>
});

export const libraryConfigs: { [K in LibraryKind]: LibraryConfig<any> } = {
  patents: {
    kind: "patents", title: "专利资料库", subtitle: "按号码、名称和申请人检索既有专利记录", endpoint: "/api/v0/patent-library",
    fields: [
      { key: "publicationNumber", label: "公开 / 公告号", placeholder: "例如 CN117076952A" },
      { key: "applicationNumber", label: "申请号", placeholder: "完整申请号" },
      { key: "title", label: "专利名称", placeholder: "名称关键词" },
      { key: "applicant", label: "申请人", placeholder: "申请人关键词" },
      { key: "patentType", label: "专利类型", options: ["INVENTION", "UTILITY_MODEL", "DESIGN"].map((value) => ({ label: value, value })) }
    ],
    columns: (open) => [
      { title: "专利名称", dataIndex: "title", ellipsis: true, width: 280 },
      { title: "公开 / 公告号", dataIndex: "publicationNumber", width: 170, render: (value) => libraryText(value) },
      { title: "申请人", dataIndex: "applicants", width: 210, ellipsis: true, render: (value) => libraryText(value) },
      { title: "申请日", dataIndex: "applicationDate", width: 120, render: (value) => libraryText(value) },
      { title: "来源", dataIndex: "dataMode", width: 130, render: (value) => <Tag title={String(value)} color={libraryModeColor(String(value))}>{displayCode(value)}</Tag> },
      actionColumn(open)
    ],
    identity: (record) => record.title,
    secondary: (record) => libraryText(record.publicationNumber ?? record.applicationNumber),
    meta: (record) => [libraryText(record.applicants), libraryText(record.applicationDate), libraryText(record.patentType)]
  },
  trademarks: {
    kind: "trademarks", title: "商标资料库", subtitle: "浏览基础信息、类别及独立历史能力状态", endpoint: "/api/v0/trademark-library",
    fields: [
      { key: "registrationNumber", label: "注册号", placeholder: "完整注册号" },
      { key: "name", label: "商标名称", placeholder: "名称关键词" },
      { key: "applicant", label: "申请人", placeholder: "申请人关键词" },
      { key: "category", label: "类别", placeholder: "例如 35" },
      { key: "status", label: "展示状态", placeholder: "状态值" }
    ],
    columns: (open) => [
      { title: "商标名称", dataIndex: "name", ellipsis: true, width: 250 },
      { title: "注册号", dataIndex: "registrationNumber", width: 150, render: (value) => libraryText(value) },
      { title: "申请人", dataIndex: "applicants", width: 220, ellipsis: true, render: (value) => libraryText(value) },
      { title: "展示状态", dataIndex: "displayStatus", width: 145, render: (value) => libraryText(value) },
      { title: "来源", dataIndex: "dataMode", width: 130, render: (value) => <Tag title={String(value)} color={libraryModeColor(String(value))}>{displayCode(value)}</Tag> },
      actionColumn(open)
    ],
    identity: (record) => record.name,
    secondary: (record) => libraryText(record.registrationNumber),
    meta: (record) => [libraryText(record.applicants), libraryText(record.registrationDate), libraryText(record.displayStatus)]
  },
  cases: {
    kind: "cases", title: "知识产权案例库", subtitle: "区分裁判原文与已发布标注，保留来源定位", endpoint: "/api/v0/case-library",
    fields: [
      { key: "caseNumber", label: "案例编号", placeholder: "来源案例编号" },
      { key: "docketNumber", label: "案号", placeholder: "完整案号" },
      { key: "title", label: "案例标题", placeholder: "标题关键词" },
      { key: "ipType", label: "知识产权类型", placeholder: "例如 PATENT" },
      { key: "procedure", label: "程序", placeholder: "程序类型" }
    ],
    columns: (open) => [
      { title: "案例标题", dataIndex: "title", ellipsis: true, width: 300 },
      { title: "案号", dataIndex: "docketNumber", width: 210, ellipsis: true, render: (value) => libraryText(value) },
      { title: "机构", dataIndex: "institution", width: 180, ellipsis: true, render: (value) => libraryText(value) },
      { title: "决定日", dataIndex: "decisionDate", width: 120, render: (value) => libraryText(value) },
      { title: "来源", dataIndex: "dataMode", width: 130, render: (value) => <Tag title={String(value)} color={libraryModeColor(String(value))}>{displayCode(value)}</Tag> },
      actionColumn(open)
    ],
    identity: (record) => record.title,
    secondary: (record) => libraryText(record.docketNumber ?? record.caseNumber),
    meta: (record) => [libraryText(record.institution), libraryText(record.decisionDate), libraryText(record.procedure)]
  }
};
