import { Card, Col, Row, Statistic, Table } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { alarmApi } from '@/api/alarm';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';

export default function AlarmHealthPage() {
  useDocumentTitle('告警 - 健康');
  const { data: summary, isLoading } = useQuery({
    queryKey: ['alarms', 'health'],
    queryFn: alarmApi.healthSummary,
    refetchInterval: 30_000,
    refetchOnWindowFocus: false,
  });

  return (
    <div>
      <PageHeader title="告警健康" />
      <Row gutter={16}>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={isLoading}>
            <Statistic
              title="在线设备"
              value={summary?.onlineCount ?? 0}
              valueStyle={{ color: '#52c41a' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={isLoading}>
            <Statistic
              title="离线设备"
              value={summary?.offlineCount ?? 0}
              valueStyle={{ color: '#999' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={isLoading}>
            <Statistic
              title="告警中"
              value={summary?.alarmCount ?? 0}
              valueStyle={{ color: '#ff4d4f' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={isLoading}>
            <Statistic
              title="维护中"
              value={summary?.maintenanceCount ?? 0}
              valueStyle={{ color: '#faad14' }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card title="Top 异常设备（按活动告警数）">
            <Table
              rowKey="deviceId"
              loading={isLoading}
              dataSource={summary?.topOffenders ?? []}
              pagination={false}
              columns={[
                { title: '设备 ID', dataIndex: 'deviceId', width: 120 },
                { title: '设备编码', dataIndex: 'deviceCode' },
                {
                  title: '活动告警数',
                  dataIndex: 'activeAlarmCount',
                  align: 'right',
                  width: 140,
                },
              ]}
              locale={{ emptyText: '当前无异常设备' }}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
}
