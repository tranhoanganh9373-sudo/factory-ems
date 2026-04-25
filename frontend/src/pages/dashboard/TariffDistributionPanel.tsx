import { Alert, Empty, Skeleton, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import ReactECharts from 'echarts-for-react';
import { dashboardApi, type TariffDistributionDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';

const PERIOD_COLORS: Record<string, string> = {
  SHARP: '#cf1322',
  PEAK: '#fa8c16',
  FLAT: '#1677ff',
  VALLEY: '#52c41a',
};

function buildOption(data: TariffDistributionDTO) {
  return {
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} ' + data.unit + ' ({d}%)',
    },
    legend: { type: 'scroll', bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['50%', '45%'],
        data: data.slices.map((s) => ({
          name: s.periodType,
          value: s.value,
          itemStyle: { color: PERIOD_COLORS[s.periodType] },
        })),
      },
    ],
  };
}

export default function TariffDistributionPanel() {
  const { range, customFrom, customTo, orgNodeId } = useDashboardFilterStore();
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);

  const { data, isLoading, isError } = useQuery({
    queryKey: ['dashboard', 'tariff-distribution', { range, customFrom, customTo, orgNodeId }],
    queryFn: () =>
      dashboardApi.getTariffDistribution({ range, from: customFrom, to: customTo, orgNodeId }),
    enabled: isCustomReady,
    refetchInterval: 60_000,
  });

  if (!isCustomReady)
    return <Alert type="info" message="自定义区间：请选择开始和结束时间" showIcon />;
  if (isLoading) return <Skeleton active paragraph={{ rows: 5 }} />;
  if (isError) return <Alert type="error" message="尖峰平谷分布加载失败" showIcon />;
  if (!data?.slices?.length) return <Empty description="暂无数据" />;

  return (
    <div>
      <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
        尖峰平谷分布
      </Typography.Text>
      <ReactECharts option={buildOption(data)} style={{ height: 300 }} />
    </div>
  );
}
