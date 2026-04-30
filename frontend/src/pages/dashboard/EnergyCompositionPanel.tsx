import { useRef, useEffect } from 'react';
import { Alert, Empty, Skeleton, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import * as echarts from 'echarts/core';
import { PieChart } from 'echarts/charts';
import { TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { dashboardApi, type CompositionDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';
import { translate, ENERGY_TYPE_LABEL } from '@/utils/i18n-dict';

echarts.use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer]);

function buildOption(data: CompositionDTO[]) {
  return {
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} ({d}%)',
    },
    legend: { type: 'scroll', bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['50%', '45%'],
        data: data.map((d) => ({
          name: translate(ENERGY_TYPE_LABEL, d.energyType),
          value: d.total,
          label: {
            formatter: `{b}\n${(d.share * 100).toFixed(1)}%`,
          },
        })),
        emphasis: {
          itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.5)' },
        },
      },
    ],
  };
}

export default function EnergyCompositionPanel() {
  const { range, customFrom, customTo, orgNodeId } = useDashboardFilterStore();
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);

  const { data, isLoading, isError } = useQuery<CompositionDTO[]>({
    queryKey: ['dashboard', 'composition', { range, customFrom, customTo, orgNodeId }],
    queryFn: () =>
      dashboardApi.getEnergyComposition({ range, from: customFrom, to: customTo, orgNodeId }),
    enabled: isCustomReady,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  });

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
    if (chartRef.current && data) {
      chartRef.current.setOption(buildOption(data), { notMerge: true });
    }
  }, [data]);

  if (!isCustomReady)
    return <Alert type="info" message="自定义区间：请选择开始和结束时间" showIcon />;
  if (isLoading) return <Skeleton active paragraph={{ rows: 5 }} />;
  if (isError) return <Alert type="error" message="能耗构成加载失败" showIcon />;
  if (!data?.length) return <Empty description="暂无能耗构成数据" />;

  return (
    <div>
      <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
        能耗构成
      </Typography.Text>
      <div ref={elRef} style={{ width: '100%', height: 300 }} />
    </div>
  );
}
