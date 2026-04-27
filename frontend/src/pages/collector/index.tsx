import { Card, Empty, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { collectorApi, type DeviceState, type DeviceStatusDTO } from '@/api/collector';

dayjs.extend(relativeTime);

const STATE_COLOR: Record<DeviceState, string> = {
  HEALTHY: 'success',
  DEGRADED: 'warning',
  UNREACHABLE: 'error',
};

const STATE_LABEL: Record<DeviceState, string> = {
  HEALTHY: '正常',
  DEGRADED: '降级',
  UNREACHABLE: '不可达',
};

function fmtRelative(iso: string | null): string {
  if (!iso) return '—';
  return dayjs(iso).fromNow();
}

function fmtAbsolute(iso: string | null): string {
  if (!iso) return '—';
  return dayjs(iso).format('YYYY-MM-DD HH:mm:ss');
}

export default function CollectorStatusPage() {
  const { data: running } = useQuery({
    queryKey: ['collector', 'running'],
    queryFn: collectorApi.running,
    refetchInterval: 5_000,
  });
  const { data = [], isLoading } = useQuery({
    queryKey: ['collector', 'status'],
    queryFn: collectorApi.status,
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  });

  return (
    <Card
      title={
        <Space>
          <Typography.Text strong>采集器状态</Typography.Text>
          {running && (
            <Tag color={running.running ? 'green' : 'default'}>
              {running.running ? `运行中 · ${running.deviceCount} 台设备` : '未运行'}
            </Tag>
          )}
        </Space>
      }
    >
      {data.length === 0 && !isLoading ? (
        <Empty description="无采集设备配置（ems.collector.devices 为空或 enabled=false）" />
      ) : (
        <Table<DeviceStatusDTO>
          rowKey="deviceId"
          dataSource={data}
          loading={isLoading}
          pagination={{ pageSize: 50 }}
          columns={[
            { title: '设备 ID', dataIndex: 'deviceId', width: 160 },
            { title: '关联测点', dataIndex: 'meterCode', width: 200 },
            {
              title: '状态',
              dataIndex: 'state',
              width: 100,
              render: (s: DeviceState) => <Tag color={STATE_COLOR[s]}>{STATE_LABEL[s]}</Tag>,
            },
            {
              title: '上次读取',
              dataIndex: 'lastReadAt',
              width: 180,
              render: (v: string | null) => (
                <Tooltip title={fmtAbsolute(v)}>{fmtRelative(v)}</Tooltip>
              ),
            },
            {
              title: '上次状态切换',
              dataIndex: 'lastTransitionAt',
              width: 180,
              render: (v: string | null) => (
                <Tooltip title={fmtAbsolute(v)}>{fmtRelative(v)}</Tooltip>
              ),
            },
            { title: '成功', dataIndex: 'successCount', width: 90 },
            { title: '失败', dataIndex: 'failureCount', width: 90 },
            {
              title: '连错',
              dataIndex: 'consecutiveErrors',
              width: 80,
              render: (n: number) => (n > 0 ? <Tag color="red">{n}</Tag> : <span>0</span>),
            },
            {
              title: '最近错误',
              dataIndex: 'lastError',
              ellipsis: true,
              render: (v: string | null) =>
                v ? (
                  <Tooltip title={v}>
                    <Typography.Text type="danger" ellipsis style={{ maxWidth: 280 }}>
                      {v}
                    </Typography.Text>
                  </Tooltip>
                ) : (
                  <span style={{ color: '#999' }}>—</span>
                ),
            },
          ]}
        />
      )}
    </Card>
  );
}
