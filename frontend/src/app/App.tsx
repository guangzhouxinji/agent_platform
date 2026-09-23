import { ConfigProvider } from "antd";
import zhCN from "antd/locale/zh_CN";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter } from "react-router-dom";
import { ErrorBoundary } from "../shared/ui/ErrorBoundary";
import { AppRoutes } from "./AppRoutes";
import { theme } from "./theme";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 30_000
    }
  }
});

export function App() {
  return (
    <ErrorBoundary>
      <ConfigProvider locale={zhCN} theme={theme}>
        <QueryClientProvider client={queryClient}>
          <BrowserRouter basename="/app">
            <AppRoutes />
          </BrowserRouter>
        </QueryClientProvider>
      </ConfigProvider>
    </ErrorBoundary>
  );
}
