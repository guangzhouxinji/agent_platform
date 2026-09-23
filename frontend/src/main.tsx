import React from "react";
import ReactDOM from "react-dom/client";
import "antd/dist/reset.css";
import "./styles/global.css";
import "./styles/search-workbench.css";
import "./styles/consultation-flow.css";
import "./styles/enforcement-source.css";
import "./styles/enforcement-rules.css";
import "./styles/enforcement-discovery.css";
import "./styles/enforcement-parsing.css";
import "./styles/enforcement-evidence.css";
import "./styles/responsive.css";
import "./styles/chat-workbench.css";
import "./styles/agent-workspace.css";
import "./styles/agent-workspace-responsive.css";
import { App } from "./app/App";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
