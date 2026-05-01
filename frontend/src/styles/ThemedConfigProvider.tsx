import type { ReactNode } from 'react';
import { ConfigProvider, App as AntdApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { useThemeStore } from '@/stores/themeStore';
import { buildAntdTheme } from '@/styles/tokens';

interface Props {
  children: ReactNode;
}

export function ThemedConfigProvider({ children }: Props) {
  const mode = useThemeStore((s) => s.mode);
  return (
    <ConfigProvider locale={zhCN} theme={buildAntdTheme(mode)}>
      <AntdApp>{children}</AntdApp>
    </ConfigProvider>
  );
}
