import { useRef, useEffect } from 'react';
import { Alert, Skeleton, Tag, Typography } from 'antd';
import { EmptyState } from '@/components/EmptyState';
import { useQuery } from '@tanstack/react-query';
import * as echarts from 'echarts/core';
import { PieChart } from 'echarts/charts';
import { TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { dashboardApi, type EnergyBreakdownDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';

echarts.use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer]);

const RESIDUAL_COLOR = '#bfbfbf';
const NEGATIVE_RESIDUAL_COLOR = '#ff4d4f';

function buildOption(data: EnergyBreakdownDTO) {
  const series = data.items
    .filter((it) => Math.abs(it.value) > 1e-9 || it.isResidual)
    .map((it) => {
      const negative = it.isResidual && it.value < 0;
      const color = it.isResidual
        ? negative
          ? NEGATIVE_RESIDUAL_COLOR
          : RESIDUAL_COLOR
        : undefined;
      return {
        name: it.name,
        // 饼图无法画负值；负残差用 0 占位，旁边告警 Tag 给出具体数字
        value: Math.max(0, it.value),
        itemStyle: color ? { color } : undefined,
      };
    });

  return {
    tooltip: {
      trigger: 'item',
      formatter: (p: { name: string; value: number; percent: number }) =>
        `${p.name}: ${p.value.toFixed(2)} ${data.unit ?? ''} (${p.percent.toFixed(1)}%)`,
    },
    legend: { type: 'scroll', bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['50%', '45%'],
        data: series,
        emphasis: {
          itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.5)' },
        },
      },
    ],
  };
}

export default function EnergyBreakdownPanel() {
  const { range, customFrom, customTo, orgNodeId, energyType } = useDashboardFilterStore();
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);
  const et = energyType || 'ELEC';

  const { data, isLoading, isError } = useQuery<EnergyBreakdownDTO>({
    queryKey: [
      'dashboard',
      'energy-breakdown',
      { range, customFrom, customTo, orgNodeId, et },
    ],
    queryFn: () =>
      dashboardApi.getEnergyBreakdown({
        range,
        from: customFrom,
        to: customTo,
        orgNodeId,
        energyType: et,
      }),
    enabled: isCustomReady,
    refetchInterval: 60_000,
    refetchIntervalInBackground: false,
  });

  const elRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

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
  if (isError) return <Alert type="error" message="用电细分加载失败" showIcon />;
  if (!data || !data.items.length || data.rootTotal === 0)
    return <EmptyState kind="no-pie" description="暂无用电细分数据（请先配置拓扑）" compact />;

  const residualItem = data.items.find((it) => it.isResidual);
  const negativeResidual = residualItem && residualItem.value < 0;

  return (
    <div>
      <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
        用电细分（按测点）
        <Tag color="blue" style={{ marginLeft: 8 }}>
          {et}
        </Tag>
        {negativeResidual ? (
          <Tag color="red" style={{ marginLeft: 8 }}>
            子表合计超过根表 {Math.abs(residualItem!.value).toFixed(2)} {data.unit}（请检查 CT
            倍率 / 拓扑配置）
          </Tag>
        ) : null}
      </Typography.Text>
      <div ref={elRef} style={{ width: '100%', height: 300 }} />
    </div>
  );
}
