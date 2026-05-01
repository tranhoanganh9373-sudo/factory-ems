import * as echarts from 'echarts/core';
import { emsLightTheme } from './ems-light';
import { emsDarkTheme } from './ems-dark';

export const EMS_LIGHT = 'ems-light';
export const EMS_DARK = 'ems-dark';

let registered = false;
export function ensureEchartsThemes(): void {
  if (registered) return;
  echarts.registerTheme(EMS_LIGHT, emsLightTheme as Record<string, unknown>);
  echarts.registerTheme(EMS_DARK, emsDarkTheme as Record<string, unknown>);
  registered = true;
}
