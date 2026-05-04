import { Card, Col, Row, Statistic, Table, Tag, Tooltip } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { alarmApi } from '@/api/alarm';
import {
  dashboardApi,
  type TopologyConsistencyDTO,
  type TopologyConsistencySeverity,
} from '@/api/dashboard';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { HELP_ALARM_HEALTH } from '@/components/pageHelp';

const SEVERITY_COLOR: Record<TopologyConsistencySeverity, string> = {
  OK: 'default',
  INFO: 'blue',
  WARN: 'orange',
  WARN_NEGATIVE: 'volcano',
  ALARM: 'red',
};

const SEVERITY_LABEL: Record<TopologyConsistencySeverity, string> = {
  OK: '正常',
  INFO: '正残差 (未分摊)',
  WARN: '正残差异常',
  WARN_NEGATIVE: '负残差 (轻)',
  ALARM: '负残差 (严重)',
};

export default function AlarmHealthPage() {
  useDocumentTitle('报警 - 健康');
  const { data: summary, isLoading } = useQuery({
    queryKey: ['alarms', 'health'],
    queryFn: alarmApi.healthSummary,
    refetchInterval: 30_000,
    refetchOnWindowFocus: false,
  });

  const { data: topoIssues = [], isLoading: topoLoading } = useQuery<TopologyConsistencyDTO[]>({
    queryKey: ['dashboard', 'topology-consistency', 'TODAY'],
    queryFn: () => dashboardApi.getTopologyConsistency({ range: 'TODAY' }),
    refetchInterval: 60_000,
    refetchOnWindowFocus: false,
  });

  return (
    <div>
      <PageHeader title="报警健康" helpContent={HELP_ALARM_HEALTH} />
      <Row gutter={16}>
        <Col xs={24} sm={12} lg={5}>
          <Card loading={isLoading}>
            <Statistic
              title="测点总数"
              value={summary?.totalCount ?? 0}
              valueStyle={{ color: '#1677ff' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={5}>
          <Card loading={isLoading}>
            <Statistic
              title="在线测点"
              value={summary?.onlineCount ?? 0}
              valueStyle={{ color: '#52c41a' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={5}>
          <Card loading={isLoading}>
            <Statistic
              title="离线测点"
              value={summary?.offlineCount ?? 0}
              valueStyle={{ color: '#999' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={5}>
          <Card loading={isLoading}>
            <Statistic
              title="报警中"
              value={summary?.alarmCount ?? 0}
              valueStyle={{ color: '#ff4d4f' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={4}>
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
          <Card
            title={
              <span>
                拓扑一致性自检（今日）
                <Tooltip title="父表读数 vs 直接子表合计；负残差 > 15% 通常意味着 CT 倍率错 / 拓扑配错 / 同回路重复测量">
                  <Tag color="default" style={{ marginLeft: 8 }}>
                    ?
                  </Tag>
                </Tooltip>
              </span>
            }
          >
            <Table<TopologyConsistencyDTO>
              rowKey="parentMeterId"
              loading={topoLoading}
              dataSource={topoIssues}
              pagination={false}
              size="small"
              columns={[
                {
                  title: '严重度',
                  dataIndex: 'severity',
                  width: 130,
                  render: (s: TopologyConsistencySeverity) => (
                    <Tag color={SEVERITY_COLOR[s]}>{SEVERITY_LABEL[s]}</Tag>
                  ),
                },
                {
                  title: '父表',
                  render: (_, r) => r.parentName || r.parentCode,
                },
                { title: '能源', dataIndex: 'energyType', width: 80 },
                {
                  title: '父表读数',
                  width: 130,
                  align: 'right',
                  render: (_, r) => `${r.parentReading.toFixed(2)} ${r.unit}`,
                },
                {
                  title: '子表合计',
                  width: 130,
                  align: 'right',
                  render: (_, r) =>
                    `${r.childrenSum.toFixed(2)} ${r.unit} (${r.childrenCount} 子)`,
                },
                {
                  title: '残差',
                  width: 130,
                  align: 'right',
                  render: (_, r) => (
                    <span style={{ color: r.residual < 0 ? '#ff4d4f' : undefined }}>
                      {r.residual >= 0 ? '+' : ''}
                      {r.residual.toFixed(2)} {r.unit}
                    </span>
                  ),
                },
                {
                  title: '残差比例',
                  width: 110,
                  align: 'right',
                  render: (_, r) =>
                    r.residualRatio == null
                      ? '—'
                      : `${(r.residualRatio * 100).toFixed(1)}%`,
                },
              ]}
              locale={{ emptyText: '今日所有父子拓扑读数一致（偏差 ≤ 5%）' }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card title="Top 异常设备（按活动报警数）">
            <Table
              rowKey="deviceId"
              loading={isLoading}
              dataSource={summary?.topOffenders ?? []}
              pagination={false}
              columns={[
                { title: '设备 ID', dataIndex: 'deviceId', width: 120 },
                { title: '设备编码', dataIndex: 'deviceCode' },
                {
                  title: '活动报警数',
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
