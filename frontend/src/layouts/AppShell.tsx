import { useMemo, useState } from "react";
import { Button, Drawer, Layout, Menu, Tooltip } from "antd";
import { Grid2X2, Menu as MenuIcon, PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import { routeItems, routesForWorkspace, workspaceForPath, type WorkspaceDefinition } from "../app/navigation";

const { Content, Sider } = Layout;

function Navigation({ workspace, onSelect, collapsed = false }: { workspace: WorkspaceDefinition; onSelect?: () => void; collapsed?: boolean }) {
  const location = useLocation();
  const navigate = useNavigate();
  const items = useMemo(() => {
    const routes = routesForWorkspace(workspace.id);
    return [...new Set(routes.map((item) => item.group))].map((group) => ({
      type: "group" as const,
      label: group,
      children: routes.filter((item) => item.group === group).map((item) => ({
        key: item.path,
        icon: item.icon,
        label: item.label
      }))
    }));
  }, [workspace]);

  return (
    <Menu
      mode="inline"
      inlineCollapsed={collapsed}
      selectedKeys={[location.pathname]}
      items={items}
      onClick={({ key }) => { navigate(key); onSelect?.(); }}
    />
  );
}

export function AppShell() {
  const [mobileOpen, setMobileOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();
  const workspace = workspaceForPath(location.pathname);
  const current = routeItems.find((item) => item.path === location.pathname);

  return (
    <Layout className={`app-layout workspace-${workspace.id} ${collapsed ? "navigation-collapsed" : ""}`}>
      <Sider className="desktop-sider" width={240} collapsedWidth={72} collapsed={collapsed} trigger={null} theme="light">
        <button className="brand-block" type="button" onClick={() => navigate(workspace.homePath)} aria-label={`返回${workspace.name}首页`}>
          <span className="brand-mark" aria-hidden="true">{workspace.mark}</span>
          <span className="brand-name">{workspace.name}</span>
        </button>
        <Navigation workspace={workspace} collapsed={collapsed} />
        <div className="sider-footer">
          <Tooltip title="返回智能体工作台" placement="right">
            <Button className="workspace-home-button" type="text" aria-label="返回智能体工作台" icon={<Grid2X2 size={18} />} onClick={() => navigate("/")}>
              {!collapsed && "工作台"}
            </Button>
          </Tooltip>
          <Tooltip title={collapsed ? "展开导航" : "收起导航"} placement="right">
            <Button className="collapse-button" type="text" aria-label={collapsed ? "展开导航" : "收起导航"} icon={collapsed ? <PanelLeftOpen size={18} /> : <PanelLeftClose size={18} />} onClick={() => setCollapsed((value) => !value)} />
          </Tooltip>
        </div>
      </Sider>
      <Layout className="app-main-layout">
        <header className="mobile-app-controls">
          <Button className="mobile-menu-button" type="text" aria-label="打开导航" icon={<MenuIcon size={20} />} onClick={() => setMobileOpen(true)} />
          <span className="mobile-workspace-name">{workspace.name}</span>
          <Button type="text" aria-label="返回智能体工作台" icon={<Grid2X2 size={19} />} onClick={() => navigate("/")} />
        </header>
        <Content className="app-content"><h1 className="sr-only">{current?.label ?? workspace.name}</h1><Outlet /></Content>
      </Layout>
      <Drawer
        className="mobile-navigation"
        placement="left"
        size={320}
        open={mobileOpen}
        onClose={() => setMobileOpen(false)}
        title={<div className="drawer-brand"><span className="brand-mark" aria-hidden="true">{workspace.mark}</span><span>{workspace.name}</span></div>}
      >
        <Navigation workspace={workspace} onSelect={() => setMobileOpen(false)} />
        <Button className="mobile-workspace-home" type="text" icon={<Grid2X2 size={18} />} onClick={() => { navigate("/"); setMobileOpen(false); }}>返回智能体工作台</Button>
      </Drawer>
    </Layout>
  );
}
