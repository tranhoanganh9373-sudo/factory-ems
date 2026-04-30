import { useThemeStore } from '@/stores/themeStore';
import { EMS_LIGHT, EMS_DARK } from '@/styles/echarts';

export function useEchartsTheme(): typeof EMS_LIGHT | typeof EMS_DARK {
  const mode = useThemeStore((s) => s.mode);
  return mode === 'dark' ? EMS_DARK : EMS_LIGHT;
}
