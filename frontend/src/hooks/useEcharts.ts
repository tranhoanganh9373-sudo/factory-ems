import { useRef, useEffect } from 'react';
import * as echarts from 'echarts/core';
import type { EChartsOption } from 'echarts';
import { useEchartsTheme } from './useEchartsTheme';

export function useEcharts(option: EChartsOption) {
  const elRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);
  const themeName = useEchartsTheme();

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
      chartRef.current.setOption(option, { notMerge: false, lazyUpdate: true });
    }
  }, [option]);

  return elRef;
}
