import { useRef, useEffect } from 'react';
import * as echarts from 'echarts/core';
import type { EChartsOption } from 'echarts';
import { useEchartsTheme } from './useEchartsTheme';
import { useReducedMotion } from './useReducedMotion';
import { applyReducedMotion } from '@/styles/echarts/reduced-motion';

export function useEcharts(option: EChartsOption) {
  const elRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);
  const themeName = useEchartsTheme();
  const reduced = useReducedMotion();

  useEffect(() => {
    if (!elRef.current) return;
    const chart = echarts.init(elRef.current, themeName);
    chartRef.current = chart;
    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
      chartRef.current = null;
    };
  }, [themeName]);

  useEffect(() => {
    if (chartRef.current) {
      chartRef.current.setOption(applyReducedMotion(option, reduced), {
        notMerge: false,
        lazyUpdate: true,
      });
    }
  }, [option, reduced]);

  return elRef;
}
