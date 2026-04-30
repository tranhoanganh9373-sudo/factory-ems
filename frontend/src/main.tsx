import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, App as AntdApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import 'antd/dist/reset.css';
import './styles/theme-light.css';
import './styles/theme-dark.css';
import { ErrorBoundary } from './components/ErrorBoundary';
import App from './App';
import { useThemeStore } from './stores/themeStore';
import { buildAntdTheme } from './styles/tokens';

document.documentElement.setAttribute('data-theme', useThemeStore.getState().mode);
useThemeStore.subscribe((state) =>
  document.documentElement.setAttribute('data-theme', state.mode),
);

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, refetchOnWindowFocus: false, retry: 1 },
  },
});

function ThemedConfigProvider({ children }: { children: React.ReactNode }) {
  const mode = useThemeStore((s) => s.mode);
  return (
    <ConfigProvider locale={zhCN} theme={buildAntdTheme(mode)}>
      <AntdApp>{children}</AntdApp>
    </ConfigProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemedConfigProvider>
      <QueryClientProvider client={queryClient}>
        <ErrorBoundary>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </ErrorBoundary>
      </QueryClientProvider>
    </ThemedConfigProvider>
  </React.StrictMode>,
);
