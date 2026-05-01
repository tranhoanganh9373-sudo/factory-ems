import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import 'antd/dist/reset.css';
import './styles/theme-light.css';
import './styles/theme-dark.css';
import { ErrorBoundary } from './components/ErrorBoundary';
import App from './App';
import { useThemeStore } from './stores/themeStore';
import { ensureEchartsThemes } from './styles/echarts';
import { ThemedConfigProvider } from './styles/ThemedConfigProvider';

ensureEchartsThemes();

document.documentElement.setAttribute('data-theme', useThemeStore.getState().mode);
useThemeStore.subscribe((state) => document.documentElement.setAttribute('data-theme', state.mode));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, refetchOnWindowFocus: false, retry: 1 },
  },
});

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
  </React.StrictMode>
);
