import { App, Button, Card, Empty, Modal, Popconfirm, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { collectorApi, type DeviceState, type DeviceStatusDTO } from '@/api/collector';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { StatusTag } from '@/components/StatusTag';

dayjs.extend(relativeTime);

const STATE_MAP: Record<DeviceState, { tone: 'success' | 'warning' | 'error'; label: string }> = {
  HEALTHY: { tone: 'success', label: '正常' },
  DEGRADED: { tone: 'warning', label: '降级' },
  UNREACHABLE: { tone: 'error', label: '不可达' },
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
  useDocumentTitle('数据采集');
  const { message } = App.useApp();
  const qc = useQueryClient();
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

  const reloadMu = useMutation({
    mutationFn: collectorApi.reload,
    onSuccess: (r) => {
      const summary =
        `+${r.added.length} ~${r.modified.length} -${r.removed.length} ` +
        `(unchanged ${r.unchanged})`;
      Modal.success({
        title: '重新加载完成',
        content: (
          <div>
            <p style={{ marginBottom: 8 }}>{summary}</p>
            {r.added.length > 0 && <p>新增：{r.added.join(', ')}</p>}
            {r.modified.length > 0 && <p>修改：{r.modified.join(', ')}</p>}
            {r.removed.length > 0 && <p>移除：{r.removed.join(', ')}</p>}
          </div>
        ),
      });
      qc.invalidateQueries({ queryKey: ['collector'] });
    },
    onError: (e: unknown) => {
      const msg = e instanceof Error ? e.message : 'reload 失败';
      message.error(msg);
    },
  });

  return (
    <>
      <PageHeader title="数据采集" />
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
      extra={
        <Popconfirm
          title="重新加载 collector.yml"
          description="服务端会从磁盘重读配置；不变的 device 不受影响。"
          onConfirm={() => reloadMu.mutate()}
          okText="确认"
        >
          <Button icon={<ReloadOutlined />} loading={reloadMu.isPending} type="primary">
            重新加载
          </Button>
        </Popconfirm>
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
              render: (s: DeviceState) => {
                const c = STATE_MAP[s] ?? { tone: 'default' as const, label: s };
                return <StatusTag tone={c.tone}>{c.label}</StatusTag>;
              },
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
    </>
  );
}
