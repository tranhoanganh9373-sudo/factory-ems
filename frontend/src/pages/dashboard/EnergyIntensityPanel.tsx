import { Alert, Empty, Skeleton, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import ReactECharts from 'echarts-for-react';
import { dashboardApi, type EnergyIntensityDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';

function buildOption(data: EnergyIntensityDTO) {
  const dates = data.points.map((p) => p.date);
  return {
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { top: 30, left: 50, right: 60, bottom: 40 },
    xAxis: { type: 'category', data: dates },
    yAxis: [
      { type: 'value', name: data.electricityUnit, position: 'left' },
      { type: 'value', name: '能耗强度', position: 'right' },
    ],
    series: [
      {
        name: '电量',
        type: 'bar',
        data: data.points.map((p) => p.electricity),
        itemStyle: { color: '#1677ff' },
      },
      {
        name: '产量',
        type: 'bar',
        data: data.points.map((p) => p.production),
        itemStyle: { color: '#52c41a' },
      },
      {
        name: '单位产量能耗',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: data.points.map((p) => p.intensity),
        itemStyle: { color: '#fa8c16' },
      },
    ],
  };
}

export default function EnergyIntensityPanel() {
  const { range, customFrom, customTo, orgNodeId } = useDashboardFilterStore();
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);

  const { data, isLoading, isError } = useQuery({
    queryKey: ['dashboard', 'energy-intensity', { range, customFrom, customTo, orgNodeId }],
    queryFn: () =>
      dashboardApi.getEnergyIntensity({ range, from: customFrom, to: customTo, orgNodeId }),
    enabled: isCustomReady,
    refetchInterval: 60_000,
  });

  if (!isCustomReady)
    return <Alert type="info" message="自定义区间：请选择开始和结束时间" showIcon />;
  if (isLoading) return <Skeleton active paragraph={{ rows: 5 }} />;
  if (isError) return <Alert type="error" message="单位产量能耗加载失败" showIcon />;
  if (!data?.points?.length) return <Empty description="暂无数据" />;

  return (
    <div>
      <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
        单位产量能耗（kWh / {data.productionUnit}）
      </Typography.Text>
      <ReactECharts option={buildOption(data)} style={{ height: 300 }} />
    </div>
  );
}
