import { Card, Col, Row, Skeleton, Alert, Empty, Statistic, Typography } from 'antd';
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { dashboardApi, type KpiDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';

function DeltaBadge({ value, label }: { value: number | null; label: string }) {
  if (value == null) return <span style={{ color: '#999' }}>{label}: —</span>;
  const pct = (value * 100).toFixed(1);
  const isUp = value >= 0;
  return (
    <span style={{ color: isUp ? '#3f8600' : '#cf1322', fontSize: 12, marginRight: 8 }}>
      {isUp ? <ArrowUpOutlined /> : <ArrowDownOutlined />} {label} {Math.abs(Number(pct))}%
    </span>
  );
}

function KpiCard({ item }: { item: KpiDTO }) {
  return (
    <Card size="small" style={{ minWidth: 160 }}>
      <Statistic
        title={item.energyType}
        value={item.total ?? 0}
        precision={2}
        suffix={item.unit}
      />
      <div style={{ marginTop: 4 }}>
        <DeltaBadge value={item.mom} label="环比" />
        <DeltaBadge value={item.yoy} label="同比" />
      </div>
    </Card>
  );
}

export default function KpiPanel() {
  const { range, customFrom, customTo, orgNodeId, energyType } = useDashboardFilterStore();
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);

  const { data, isLoading, isError } = useQuery<KpiDTO[]>({
    queryKey: ['dashboard', 'kpi', { range, customFrom, customTo, orgNodeId, energyType }],
    queryFn: () => dashboardApi.getKpi({ range, from: customFrom, to: customTo, orgNodeId, energyType }),
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
    <div>
      <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
        能耗 KPI
      </Typography.Text>
      <Row gutter={[12, 12]}>
        {data.map((item) => (
          <Col key={item.energyType} xs={24} sm={12} md={8} lg={6}>
            <KpiCard item={item} />
          </Col>
        ))}
      </Row>
    </div>
  );
}
