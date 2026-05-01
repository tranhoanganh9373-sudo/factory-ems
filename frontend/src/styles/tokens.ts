import { theme, type ThemeConfig } from 'antd';
import type { ThemeMode } from '@/stores/themeStore';

const SHARED = {
  fontFamily:
    '"PingFang SC", "Microsoft YaHei", "Helvetica Neue", "Segoe UI", system-ui, sans-serif',
  borderRadius: 4,
  borderRadiusLG: 6,
  borderRadiusSM: 2,
  fontSize: 14,
};

const LIGHT_TOKEN = {
  ...SHARED,
  colorPrimary: '#007D8A',
  colorInfo: '#0E6BB8',
  colorSuccess: '#1B7A3E',
  colorWarning: '#E37A00',
  colorError: '#C8201F',
  colorBgBase: '#FFFFFF',
  colorBgLayout: '#F4F5F7',
  colorBgContainer: '#FFFFFF',
  colorTextBase: '#16181D',
  colorBorder: '#E2E5E9',
};

const DARK_TOKEN = {
  ...SHARED,
  colorPrimary: '#00C2CC',
  colorInfo: '#4FA8FF',
  colorSuccess: '#3DCB6E',
  colorWarning: '#FFA940',
  colorError: '#FF6464',
  colorBgBase: '#141C28',
  colorBgLayout: '#0A1018',
  colorBgContainer: '#141C28',
  colorTextBase: '#E8ECF1',
  colorBorder: '#252F3E',
};

export function buildAntdTheme(mode: ThemeMode): ThemeConfig {
  return {
    algorithm: mode === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: mode === 'dark' ? DARK_TOKEN : LIGHT_TOKEN,
    components: {
      Layout: {
        headerBg: mode === 'dark' ? '#06090F' : '#0E1A2B',
        headerColor: '#FFFFFF',
        siderBg: mode === 'dark' ? '#0F1722' : '#FFFFFF',
        bodyBg: mode === 'dark' ? '#0A1018' : '#F4F5F7',
      },
      Menu: {
        itemSelectedBg: mode === 'dark' ? '#1A2536' : '#E6F4F5',
        itemSelectedColor: mode === 'dark' ? '#00C2CC' : '#007D8A',
      },
      Card: { borderRadiusLG: 4 },
      Button: { borderRadius: 4 },
      Table: { headerBg: mode === 'dark' ? '#1A2536' : '#FAFBFC' },
    },
  };
}
