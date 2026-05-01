import { useState } from 'react';
import { Button, Table, Typography, Space, App, Empty } from 'antd';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { certApi, type PendingCertificate } from '@/api/collector';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';

export default function CertApprovalPage() {
  useDocumentTitle('系统管理 - 证书审批');
  const queryClient = useQueryClient();
  const { message, modal } = App.useApp();

  const [approvingThumb, setApprovingThumb] = useState<string | null>(null);
  const [rejectingThumb, setRejectingThumb] = useState<string | null>(null);

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
    onSettled: () => {
      setApprovingThumb(null);
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
    onSettled: () => {
      setRejectingThumb(null);
    },
  });

  const columns = [
    { title: '通道 ID', dataIndex: 'channelId', key: 'channelId', width: 100 },
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
      render: (_: unknown, record: PendingCertificate) => {
        const isApproving = approvingThumb === record.thumbprint;
        const isRejecting = rejectingThumb === record.thumbprint;
        const isRowBusy = isApproving || isRejecting;
        return (
          <Space>
            <Button
              type="primary"
              size="small"
              data-testid={`approve-${record.thumbprint}`}
              loading={isApproving}
              disabled={isRowBusy && !isApproving}
              onClick={() => {
                modal.confirm({
                  title: '批准此证书？',
                  content: `Channel #${record.channelId} 将信任 thumbprint ${record.thumbprint.substring(0, 16)}…`,
                  okText: '批准',
                  cancelText: '取消',
                  onOk: () => {
                    setApprovingThumb(record.thumbprint);
                    trustMutation.mutate({
                      channelId: record.channelId,
                      thumbprint: record.thumbprint,
                    });
                  },
                });
              }}
            >
              批准
            </Button>
            <Button
              danger
              size="small"
              data-testid={`reject-${record.thumbprint}`}
              loading={isRejecting}
              disabled={isRowBusy && !isRejecting}
              onClick={() => {
                modal.confirm({
                  title: '拒绝此证书？',
                  content: '证书将移到 rejected 目录，channel 仍无法连接',
                  okText: '拒绝',
                  cancelText: '取消',
                  okButtonProps: { danger: true },
                  onOk: () => {
                    setRejectingThumb(record.thumbprint);
                    rejectMutation.mutate(record.thumbprint);
                  },
                });
              }}
            >
              拒绝
            </Button>
          </Space>
        );
      },
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <PageHeader title="证书审批" />
      <Typography.Paragraph type="secondary">
        OPC UA 服务器证书首次连接时若未在信任列表中，会被记录为 pending。在此批准后 channel
        自动重连成功；拒绝则证书移到 rejected 目录，channel 持续无法连接。
      </Typography.Paragraph>
      {pending.length === 0 && !isLoading && (
        <div data-testid="cert-pending-empty">
          <Empty description="暂无待审批证书" />
        </div>
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
