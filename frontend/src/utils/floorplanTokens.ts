import type { ThemeMode } from '@/stores/themeStore';

export interface FloorplanTokens {
  bgFill: string;
  gridStroke: string;
  deviceFill: string;
  deviceStroke: string;
  deviceStrokeAlarm: string;
  labelText: string;
}

export function floorplanTokens(mode: ThemeMode): FloorplanTokens {
  if (mode === 'dark') {
    return {
      bgFill: '#0A1018',
      gridStroke: '#252F3E',
      deviceFill: '#141C28',
      deviceStroke: '#00C2CC',
      deviceStrokeAlarm: '#FF6464',
      labelText: '#E8ECF1',
    };
  }
  return {
    bgFill: '#F4F5F7',
    gridStroke: '#E2E5E9',
    deviceFill: '#FFFFFF',
    deviceStroke: '#007D8A',
    deviceStrokeAlarm: '#C8201F',
    labelText: '#16181D',
  };
}
