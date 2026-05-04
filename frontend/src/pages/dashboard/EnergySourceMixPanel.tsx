import { useRef, useEffect } from 'react';
import { Alert, Skeleton, Typography } from 'antd';
import { EmptyState } from '@/components/EmptyState';
import { useQuery } from '@tanstack/react-query';
import * as echarts from 'echarts/core';
import { PieChart } from 'echarts/charts';
import { TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { dashboardApi, type EnergySourceMixDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';

echarts.use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer]);

const SOURCE_LABEL: Record<string, string> = {
  GRID: '电网',
  SOLAR: '光伏',
  WIND: '风电',
  STORAGE: '储能',
};

function buildOption(data: EnergySourceMixDTO[]) {
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
          name: SOURCE_LABEL[d.energySource] ?? d.energySource,
          value: d.value,
          label: {
            formatter:
              d.share != null
                ? `{b}\n${(d.share * 100).toFixed(1)}%`
                : '{b}',
          },
        })),
        emphasis: {
          itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.5)' },
        },
      },
    ],
  };
}

export default function EnergySourceMixPanel() {
  const { range, customFrom, customTo, orgNodeId } = useDashboardFilterStore();
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);

  const { data, isLoading, isError } = useQuery<EnergySourceMixDTO[]>({
    queryKey: ['dashboard', 'energy-source-mix', { range, customFrom, customTo, orgNodeId }],
    queryFn: () =>
      dashboardApi.getEnergySourceMix({ range, from: customFrom, to: customTo, orgNodeId }),
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
  if (isError) return <Alert type="error" message="能源来源构成加载失败" showIcon />;
  if (!data?.length) return <EmptyState kind="no-pie" description="暂无能源来源构成数据" compact />;

  return (
    <div>
      <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
        能源来源构成
      </Typography.Text>
      <div ref={elRef} style={{ width: '100%', height: 300 }} />
    </div>
  );
}
