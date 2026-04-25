import { useRef, useEffect } from 'react';
import * as echarts from 'echarts/core';
import type { EChartsOption } from 'echarts';

/**
 * Returns a ref to attach to a DOM element; initialises an ECharts instance,
 * updates it when `option` changes, and disposes on unmount.
 * Also resizes the chart when the window resizes.
 */
export function useEcharts(option: EChartsOption) {
  const elRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    if (!elRef.current) return;
    const chart = echarts.init(elRef.current);
    chartRef.current = chart;

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
      chartRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (chartRef.current) {
      chartRef.current.setOption(option, { notMerge: false, lazyUpdate: true });
    }
  }, [option]);

  return elRef;
}
