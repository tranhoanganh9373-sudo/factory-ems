import type { EChartsOption } from 'echarts';

/**
 * Returns a copy of the ECharts option with all animations disabled when
 * `reduced` is true. Honors `prefers-reduced-motion: reduce` for
 * shop-floor displays and accessibility users.
 */
export function applyReducedMotion<T extends EChartsOption>(option: T, reduced: boolean): T {
  if (!reduced) return option;
  return {
    ...option,
    animation: false,
    animationDuration: 0,
    animationDurationUpdate: 0,
    animationDelay: 0,
    animationDelayUpdate: 0,
  };
}
