import { Card, Statistic, Row, Col } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { fetchCarbon } from '@/api/report';

interface Props {
  orgNodeId: number;
  from: string;
  to: string;
}

export function CarbonPanel({ orgNodeId, from, to }: Props) {
  const { data } = useQuery({
    queryKey: ['carbon', orgNodeId, from, to],
    queryFn: () => fetchCarbon({ orgNodeId, from, to }),
  });
  return (
    <Card title="碳排报表（v1.2.0）" style={{ marginBottom: 16 }}>
      <Row gutter={16}>
        <Col span={8}>
          <Statistic title="自发自用 (kWh)" value={data?.selfConsumptionKwh ?? 0} precision={1} />
        </Col>
        <Col span={8}>
          <Statistic title="电网因子 (kg/kWh)" value={data?.gridFactor ?? 0} precision={4} />
        </Col>
        <Col span={8}>
          <Statistic
            title="减排当量 (kg)"
            value={data?.reductionKg ?? 0}
            precision={2}
            valueStyle={{ color: '#3f8600' }}
          />
        </Col>
      </Row>
    </Card>
  );
}
