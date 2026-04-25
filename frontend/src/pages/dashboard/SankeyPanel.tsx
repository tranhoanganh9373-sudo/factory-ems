import { Alert, Empty, Skeleton, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import ReactECharts from 'echarts-for-react';
import { dashboardApi, type SankeyDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';

const ENERGY_COLORS: Record<string, string> = {
  ELEC: '#1677ff',
  WATER: '#13c2c2',
  GAS: '#fa8c16',
  STEAM: '#722ed1',
};

function buildOption(data: SankeyDTO) {
  return {
    tooltip: { trigger: 'item' },
    series: [
      {
        type: 'sankey',
        layout: 'none',
        emphasis: { focus: 'adjacency' },
        nodeAlign: 'left',
        data: data.nodes.map((n) => ({
          name: n.id,
          itemStyle: { color: ENERGY_COLORS[n.energyType] ?? '#888' },
          label: { formatter: n.name || n.id },
        })),
        links: data.links.map((l) => ({
          source: l.source,
          target: l.target,
          value: l.value,
        })),
        lineStyle: { color: 'gradient', curveness: 0.5 },
      },
    ],
  };
}

export default function SankeyPanel() {
  const { range, customFrom, customTo, orgNodeId, energyType } = useDashboardFilterStore();
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);

  const { data, isLoading, isError } = useQuery({
    queryKey: ['dashboard', 'sankey', { range, customFrom, customTo, orgNodeId, energyType }],
    queryFn: () =>
      dashboardApi.getSankey({ range, from: customFrom, to: customTo, orgNodeId, energyType }),
    enabled: isCustomReady,
    refetchInterval: 60_000,
  });

  if (!isCustomReady)
    return <Alert type="info" message="自定义区间：请选择开始和结束时间" showIcon />;
  if (isLoading) return <Skeleton active paragraph={{ rows: 5 }} />;
  if (isError) return <Alert type="error" message="能流图加载失败" showIcon />;
  if (!data?.nodes?.length) return <Empty description="暂无能流数据" />;

  return (
    <div>
      <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
        能流 Sankey
      </Typography.Text>
      <ReactECharts option={buildOption(data)} style={{ height: 360 }} />
    </div>
  );
}
