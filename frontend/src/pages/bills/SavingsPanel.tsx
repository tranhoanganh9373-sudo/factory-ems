import { Card, Statistic, Row, Col } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { fetchSavings } from '@/api/cost';

interface Props {
  orgNodeId: number;
  from: string;
  to: string;
}

export function SavingsPanel({ orgNodeId, from, to }: Props) {
  const { data } = useQuery({
    queryKey: ['savings', orgNodeId, from, to],
    queryFn: () => fetchSavings({ orgNodeId, from, to }),
  });
  return (
    <Card title="节费报表（v1.2.0）" style={{ marginBottom: 16 }}>
      <Row gutter={16}>
        <Col span={8}>
          <Statistic title="月度电费" value={data?.amount ?? 0} prefix="¥" precision={2} />
        </Col>
        <Col span={8}>
          <Statistic
            title="上网卖电收入"
            value={data?.feedInRevenue ?? 0}
            prefix="¥"
            precision={2}
            valueStyle={{ color: '#3f8600' }}
          />
        </Col>
        <Col span={8}>
          <Statistic title="电费净额" value={data?.netAmount ?? 0} prefix="¥" precision={2} />
        </Col>
      </Row>
    </Card>
  );
}
