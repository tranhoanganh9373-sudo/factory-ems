import { Skeleton, Alert, Empty } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { dashboardApi, type KpiDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';
import { KpiCard } from '@/components/KpiCard';
import { translate, ENERGY_TYPE_LABEL } from '@/utils/i18n-dict';

export default function KpiPanel() {
  const { range, customFrom, customTo, orgNodeId, energyType } = useDashboardFilterStore();
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);

  const { data, isLoading, isError } = useQuery<KpiDTO[]>({
    queryKey: ['dashboard', 'kpi', { range, customFrom, customTo, orgNodeId, energyType }],
    queryFn: () =>
      dashboardApi.getKpi({ range, from: customFrom, to: customTo, orgNodeId, energyType }),
    enabled: isCustomReady,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  });

  if (!isCustomReady) {
    return <Alert type="info" message="自定义区间：请选择开始和结束时间" showIcon />;
  }
  if (isLoading) return <Skeleton active paragraph={{ rows: 2 }} />;
  if (isError) return <Alert type="error" message="KPI 数据加载失败" showIcon />;
  if (!data?.length) return <Empty description="暂无 KPI 数据" />;

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 16 }}>
      {data.map((item) => (
        <KpiCard
          key={item.energyType}
          label={translate(ENERGY_TYPE_LABEL, item.energyType)}
          value={item.total != null ? item.total.toFixed(2) : '0'}
          unit={item.unit}
          delta={item.mom != null ? item.mom * 100 : undefined}
        />
      ))}
    </div>
  );
}
