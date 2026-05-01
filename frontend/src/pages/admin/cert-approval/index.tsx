import { Button, Modal, Table, Typography, Space, App, Empty } from 'antd';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { certApi, type PendingCertificate } from '@/api/collector';

export default function CertApprovalPage() {
  const queryClient = useQueryClient();
  const { message } = App.useApp();

  const { data: pending = [], isLoading } = useQuery({
    queryKey: ['cert-pending'],
    queryFn: certApi.listPending,
    refetchInterval: 10_000,
  });

  const trustMutation = useMutation({
    mutationFn: ({ channelId, thumbprint }: { channelId: number; thumbprint: string }) =>
      certApi.trust(channelId, thumbprint),
    onSuccess: () => {
      message.success('证书已批准');
      queryClient.invalidateQueries({ queryKey: ['cert-pending'] });
    },
    onError: (e: unknown) => {
      const msg = e instanceof Error ? e.message : String(e);
      message.error(`批准失败: ${msg}`);
    },
  });

  const rejectMutation = useMutation({
    mutationFn: (thumbprint: string) => certApi.reject(thumbprint),
    onSuccess: () => {
      message.success('证书已拒绝');
      queryClient.invalidateQueries({ queryKey: ['cert-pending'] });
    },
    onError: (e: unknown) => {
      const msg = e instanceof Error ? e.message : String(e);
      message.error(`拒绝失败: ${msg}`);
    },
  });

  const columns = [
    { title: 'Channel ID', dataIndex: 'channelId', key: 'channelId', width: 100 },
    { title: '端点 URL', dataIndex: 'endpointUrl', key: 'endpointUrl', ellipsis: true },
    {
      title: '指纹 (SHA-256)',
      dataIndex: 'thumbprint',
      key: 'thumbprint',
      width: 200,
      render: (t: string) => (
        <Typography.Text code copyable={{ text: t }}>
          {t.substring(0, 16)}…
        </Typography.Text>
      ),
    },
    { title: '主题', dataIndex: 'subjectDn', key: 'subjectDn', ellipsis: true },
    {
      title: '首次见到',
      dataIndex: 'firstSeenAt',
      key: 'firstSeenAt',
      width: 180,
      render: (t: string) => new Date(t).toLocaleString('zh-CN'),
    },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_: unknown, record: PendingCertificate) => (
        <Space>
          <Button
            type="primary"
            size="small"
            data-testid={`approve-${record.thumbprint}`}
            loading={trustMutation.isPending}
            onClick={() => {
              Modal.confirm({
                title: '批准此证书？',
                content: `Channel #${record.channelId} 将信任 thumbprint ${record.thumbprint.substring(0, 16)}…`,
                okText: '批准',
                cancelText: '取消',
                onOk: () =>
                  trustMutation.mutate({
                    channelId: record.channelId,
                    thumbprint: record.thumbprint,
                  }),
              });
            }}
          >
            批准
          </Button>
          <Button
            danger
            size="small"
            data-testid={`reject-${record.thumbprint}`}
            loading={rejectMutation.isPending}
            onClick={() => {
              Modal.confirm({
                title: '拒绝此证书？',
                content: '证书将移到 rejected 目录，channel 仍无法连接',
                okText: '拒绝',
                cancelText: '取消',
                okButtonProps: { danger: true },
                onOk: () => rejectMutation.mutate(record.thumbprint),
              });
            }}
          >
            拒绝
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Typography.Title level={3}>OPC UA 证书审批</Typography.Title>
      <Typography.Paragraph type="secondary">
        OPC UA 服务器证书首次连接时若未在信任列表中，会被记录为 pending。在此批准后 channel
        自动重连成功；拒绝则证书移到 rejected 目录，channel 持续无法连接。
      </Typography.Paragraph>
      {pending.length === 0 && !isLoading && (
        <Empty description="暂无待审批证书" data-testid="cert-pending-empty" />
      )}
      <Table
        rowKey="thumbprint"
        dataSource={pending}
        columns={columns}
        loading={isLoading}
        pagination={false}
        data-testid="cert-pending-table"
      />
    </div>
  );
}
