import { Component, type ErrorInfo, type ReactNode } from "react";
import { Button, Result } from "antd";

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  failed: boolean;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { failed: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("React workspace render failed", error, info.componentStack);
  }

  render() {
    if (this.state.failed) {
      return (
        <Result
          status="error"
          title="页面加载失败"
          subTitle="请重新加载当前 React 工作台；若问题持续存在，请记录操作步骤和追踪信息。"
          extra={<Button onClick={() => window.location.reload()}>重新加载</Button>}
        />
      );
    }
    return this.props.children;
  }
}
