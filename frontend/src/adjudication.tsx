import React from "react";
import ReactDOM from "react-dom/client";
import { ConfigProvider } from "antd";
import zhCN from "antd/locale/zh_CN";
import "antd/dist/reset.css";
import "./styles/global.css";
import { theme } from "./app/theme";
import { ErrorBoundary } from "./shared/ui/ErrorBoundary";
import AdjudicationWorkspace from "./features/adjudication/AdjudicationWorkspace";
// Preserve the approved workspace, callback and case query; no generic cross-domain router.
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode><ErrorBoundary><ConfigProvider locale={zhCN} theme={theme}>
    <AdjudicationWorkspace />
  </ConfigProvider></ErrorBoundary></React.StrictMode>
);
