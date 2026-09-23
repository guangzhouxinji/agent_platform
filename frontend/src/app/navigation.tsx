import type { ReactNode } from "react";
import {
  BookOpen,
  BriefcaseBusiness,
  Database,
  FileSearch,
  Files,
  GitCompareArrows,
  Landmark,
  Library,
  MessageSquareText,
  Radar,
  Scale,
  ScanSearch,
  Search,
  Settings2,
  ShieldCheck,
  SlidersHorizontal
} from "lucide-react";

export type WorkspaceId = "consultation" | "enforcement" | "adjudication" | "resources";

export interface RouteItem {
  path: string;
  label: string;
  group: string;
  workspace: WorkspaceId;
  icon: ReactNode;
}

export interface WorkspaceDefinition {
  id: WorkspaceId;
  name: string;
  shortName: string;
  homePath: string;
  mark: string;
}

const icon = (value: ReactNode) => <span className="navigation-icon">{value}</span>;

export const workspaces: Record<WorkspaceId, WorkspaceDefinition> = {
  consultation: { id: "consultation", name: "咨询智能体", shortName: "咨询", homePath: "/consultation", mark: "咨" },
  enforcement: { id: "enforcement", name: "执法智能体", shortName: "执法", homePath: "/enforcement", mark: "执" },
  adjudication: { id: "adjudication", name: "裁决智能体", shortName: "裁决", homePath: "/adjudication", mark: "裁" },
  resources: { id: "resources", name: "公共资料", shortName: "资料", homePath: "/search/patents", mark: "资" }
};

export const routeItems: RouteItem[] = [
  { path: "/consultation", label: "知识产权咨询", group: "咨询服务", workspace: "consultation", icon: icon(<MessageSquareText size={18} />) },
  { path: "/consultation/flow", label: "咨询流程", group: "流程与配置", workspace: "consultation", icon: icon(<GitCompareArrows size={18} />) },
  { path: "/consultation/intents", label: "意图测试", group: "流程与配置", workspace: "consultation", icon: icon(<ScanSearch size={18} />) },
  { path: "/consultation/agent-configuration", label: "模型配置", group: "流程与配置", workspace: "consultation", icon: icon(<Settings2 size={18} />) },
  { path: "/enforcement", label: "执法工作台", group: "发现问题", workspace: "enforcement", icon: icon(<ShieldCheck size={18} />) },
  { path: "/enforcement/discovery", label: "线索发现", group: "发现问题", workspace: "enforcement", icon: icon(<Radar size={18} />) },
  { path: "/enforcement/parsing", label: "线索解析", group: "核实线索", workspace: "enforcement", icon: icon(<ScanSearch size={18} />) },
  { path: "/enforcement/evidence", label: "证据材料", group: "形成材料", workspace: "enforcement", icon: icon(<Files size={18} />) },
  { path: "/enforcement/sources", label: "数据源配置", group: "设置", workspace: "enforcement", icon: icon(<Database size={18} />) },
  { path: "/enforcement/rules", label: "线索规则配置", group: "设置", workspace: "enforcement", icon: icon(<SlidersHorizontal size={18} />) },
  { path: "/adjudication", label: "裁决工作台", group: "裁决业务", workspace: "adjudication", icon: icon(<BriefcaseBusiness size={18} />) },
  { path: "/search/patents", label: "专利检索", group: "检索分析", workspace: "resources", icon: icon(<Search size={18} />) },
  { path: "/compare/patents", label: "专利对照", group: "检索分析", workspace: "resources", icon: icon(<GitCompareArrows size={18} />) },
  { path: "/search/trademarks", label: "商标检索", group: "检索分析", workspace: "resources", icon: icon(<FileSearch size={18} />) },
  { path: "/search/cases", label: "案例检索", group: "检索分析", workspace: "resources", icon: icon(<Landmark size={18} />) },
  { path: "/compare/trademarks", label: "商标比对", group: "检索分析", workspace: "resources", icon: icon(<Scale size={18} />) },
  { path: "/libraries/patents", label: "专利库", group: "资料库", workspace: "resources", icon: icon(<BookOpen size={18} />) },
  { path: "/libraries/trademarks", label: "商标库", group: "资料库", workspace: "resources", icon: icon(<Library size={18} />) },
  { path: "/libraries/cases", label: "案例库", group: "资料库", workspace: "resources", icon: icon(<Landmark size={18} />) }
];

export function workspaceForPath(pathname: string): WorkspaceDefinition {
  const item = routeItems.find((route) => route.path === pathname);
  if (item) return workspaces[item.workspace];
  if (pathname.startsWith("/enforcement")) return workspaces.enforcement;
  if (pathname.startsWith("/adjudication")) return workspaces.adjudication;
  if (pathname.startsWith("/consultation")) return workspaces.consultation;
  return workspaces.resources;
}

export function routesForWorkspace(workspace: WorkspaceId) {
  return routeItems.filter((item) => item.workspace === workspace);
}
