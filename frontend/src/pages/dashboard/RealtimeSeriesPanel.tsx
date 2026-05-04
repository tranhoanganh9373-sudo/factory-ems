import { useRef, useEffect } from 'react';
import { Alert, Skeleton, Typography } from 'antd';
import { EmptyState } from '@/components/EmptyState';
import { useQuery } from '@tanstack/react-query';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  ToolboxComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { dashboardApi, type SeriesDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';
import { translate, ENERGY_TYPE_LABEL } from '@/utils/i18n-dict';
import dayjs from 'dayjs';

echarts.use([
  LineChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  ToolboxComponent,
  CanvasRenderer,
]);

function buildOption(series: SeriesDTO[]) {
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params: { seriesName: string; value: [string, number]; marker: string }[]) => {
        if (!params.length) return '';
        const time = dayjs(params[0].value[0]).format('MM-DD HH:mm');
        const lines = params.map((p) => {
          const s = series.find((s) => translate(ENERGY_TYPE_LABEL, s.energyType) === p.seriesName);
          return `${p.marker}${p.seriesName}: ${p.value[1]?.toFixed(2) ?? '—'} ${s?.unit ?? ''}`;
        });
        return [time, ...lines].join('<br/>');
      },
    },
    legend: { type: 'scroll', bottom: 0 },
    grid: { left: 60, right: 20, top: 20, bottom: 40 },
    xAxis: { type: 'time', boundaryGap: false },
    yAxis: { type: 'value' },
    series: series.map((s) => ({
      name: translate(ENERGY_TYPE_LABEL, s.energyType),
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: s.points.map((p) => [p.ts, p.value]),
    })),
  };
}

export default function RealtimeSeriesPanel() {
  const { range, customFrom, customTo, orgNodeId, energyType } = useDashboardFilterStore();
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);

  const { data, isLoading, isError } = useQuery<SeriesDTO[]>({
    queryKey: ['dashboard', 'series', { range, customFrom, customTo, orgNodeId, energyType }],
    queryFn: () =>
      dashboardApi.getRealtimeSeries({
        range,
        from: customFrom,
        to: customTo,
        orgNodeId,
        energyType,
      }),
    enabled: isCustomReady,
    refetchInterval: 60_000,
    refetchIntervalInBackground: false,
  });

  const elRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

  // 懒初始化 + DOM 漂移检测：
  //  ① data 未到时组件返回 Skeleton，div 不在 DOM；必须等 data 到、div 进 DOM 后再 init。
  //  ② 切换过滤器时 queryKey 变 → 又走一次 Skeleton → div 被卸载、再 mount 出新节点；
  //     若复用旧 chart 实例（仍绑在已脱离的 DOM 节点上），setOption 不会显示。
  //  通过 getDom() 检查 chart 绑的节点是否还是当前 elRef，不一致就 dispose 重建。
  useEffect(() => {
    if (!elRef.current) return;
    if (chartRef.current && chartRef.current.getDom() !== elRef.current) {
      chartRef.current.dispose();
      chartRef.current = null;
    }
    if (!chartRef.current) {
      chartRef.current = echarts.init(elRef.current);
    }
    if (data) {
      chartRef.current.setOption(buildOption(data), { notMerge: true });
    }
  }, [data]);

  useEffect(() => {
    const onResize = () => chartRef.current?.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chartRef.current?.dispose();
      chartRef.current = null;
    };
  }, []);

  if (!isCustomReady)
    return <Alert type="info" message="自定义区间：请选择开始和结束时间" showIcon />;
  if (isLoading) return <Skeleton active paragraph={{ rows: 5 }} />;
  if (isError) return <Alert type="error" message="实时曲线加载失败" showIcon />;
  if (!data?.length) return <EmptyState kind="no-chart" description="暂无实时曲线数据" compact />;

  return (
    <div>
      <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
        实时能耗曲线
      </Typography.Text>
      <div ref={elRef} style={{ width: '100%', height: 300 }} />
    </div>
  );
}
