import { App as AntApp, Button, Card, Progress, Space, Table, Tag, Tooltip } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import 'dayjs/locale/zh-cn';
import { useState } from 'react';
import { collectorDiagApi, type ChannelRuntimeState } from '@/api/collectorDiag';
import { channelApi, type ChannelDTO } from '@/api/channel';
import { translate, COLLECTOR_PROTOCOL_LABEL, CONNECTION_STATE_LABEL } from '@/utils/i18n-dict';
import { ChannelDetailDrawer } from './ChannelDetailDrawer';
import { ChannelEditor } from './ChannelEditor';
import { PageHeader } from '@/components/PageHeader';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';

dayjs.extend(relativeTime);
dayjs.locale('zh-cn');

const STATE_COLORS: Record<string, string> = {
  CONNECTED: 'green',
  CONNECTING: 'gold',
  DISCONNECTED: 'red',
  ERROR: 'red',
};

export default function CollectorPage() {
  useDocumentTitle('数据采集');
  const { message, modal } = AntApp.useApp();
  const qc = useQueryClient();
  const [detailId, setDetailId] = useState<number | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<ChannelDTO | undefined>(undefined);
  const [deletingId, setDeletingId] = useState<number | null>(null);

  const { data: states = [], isLoading } = useQuery({
    queryKey: ['collector', 'state'],
    queryFn: collectorDiagApi.list,
    refetchInterval: 5000,
  });

  const test = useMutation({
    mutationFn: (id: number) => collectorDiagApi.test(id),
    onSuccess: (res) => {
      if (res.success) message.success(`测试成功 (${res.latencyMs ?? 0} ms)`);
      else message.error(`测试失败：${res.message}`);
    },
  });

  const reconnect = useMutation({
    mutationFn: (id: number) => collectorDiagApi.reconnect(id),
    onSuccess: () => {
      message.success('已触发重连');
      qc.invalidateQueries({ queryKey: ['collector'] });
    },
  });

  const remove = useMutation({
    mutationFn: (id: number) => channelApi.delete(id),
    onSuccess: () => {
      message.success('通道已删除');
      qc.invalidateQueries({ queryKey: ['collector'] });
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : '删除失败';
      message.error(msg);
    },
    onSettled: () => setDeletingId(null),
  });

  const confirmDelete = (channelId: number) => {
    modal.confirm({
      title: '删除通道',
      content: `确认删除通道 #${channelId}？此操作不可撤销，正在采集的数据会停止。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => {
        setDeletingId(channelId);
        return new Promise<void>((resolve, reject) => {
          remove.mutate(channelId, {
            onSuccess: () => resolve(),
            onError: () => reject(),
          });
        });
      },
    });
  };

  const openEditor = async (channelId?: number) => {
    if (channelId !== undefined) {
      const ch = await channelApi.get(channelId);
      setEditing(ch);
    } else {
      setEditing(undefined);
    }
    setEditorOpen(true);
  };

  return (
    <>
      <PageHeader
        title="数据采集"
        extra={
          <Button type="primary" onClick={() => openEditor()}>
            新增通道
          </Button>
        }
      />
      <Card>
        <Table<ChannelRuntimeState>
          rowKey="channelId"
          loading={isLoading}
          dataSource={states}
          columns={[
            {
              title: '协议',
              dataIndex: 'protocol',
              render: (p: string) => <Tag>{translate(COLLECTOR_PROTOCOL_LABEL, p)}</Tag>,
            },
            { title: '通道 ID', dataIndex: 'channelId', width: 80 },
            {
              title: '状态',
              dataIndex: 'connState',
              render: (s: string) => (
                <Tag color={STATE_COLORS[s] ?? 'default'}>
                  {translate(CONNECTION_STATE_LABEL, s)}
                </Tag>
              ),
            },
            {
              title: '最近成功',
              dataIndex: 'lastSuccessAt',
              render: (t?: string) =>
                t ? (
                  <Tooltip title={dayjs(t).format('YYYY-MM-DD HH:mm:ss')}>
                    {dayjs(t).fromNow()}
                  </Tooltip>
                ) : (
                  '-'
                ),
            },
            {
              title: '24h 成功率',
              render: (_, r) => {
                const tot = r.successCount24h + r.failureCount24h;
                const rate = tot ? (r.successCount24h / tot) * 100 : 100;
                return (
                  <Progress
                    percent={Math.round(rate)}
                    size="small"
                    strokeColor={rate < 95 ? '#cf1322' : '#52c41a'}
                  />
                );
              },
            },
            {
              title: '平均延迟',
              dataIndex: 'avgLatencyMs',
              render: (v: number) => `${v} ms`,
            },
            {
              title: '最后错误',
              dataIndex: 'lastErrorMessage',
              render: (m?: string) =>
                m ? <Tooltip title={m}>{m.length > 30 ? `${m.slice(0, 30)}…` : m}</Tooltip> : '-',
            },
            {
              title: '操作',
              render: (_, r) => (
                <Space>
                  <Button
                    size="small"
                    onClick={() => test.mutate(r.channelId)}
                    loading={test.isPending}
                  >
                    测试
                  </Button>
                  <Button
                    size="small"
                    onClick={() => reconnect.mutate(r.channelId)}
                    loading={reconnect.isPending}
                  >
                    重连
                  </Button>
                  <Button size="small" type="link" onClick={() => openEditor(r.channelId)}>
                    编辑
                  </Button>
                  <Button size="small" type="link" onClick={() => setDetailId(r.channelId)}>
                    详情
                  </Button>
                  <Button
                    size="small"
                    type="link"
                    danger
                    data-testid={`channel-delete-${r.channelId}`}
                    loading={deletingId === r.channelId}
                    disabled={deletingId !== null && deletingId !== r.channelId}
                    onClick={() => confirmDelete(r.channelId)}
                  >
                    删除
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>
      <ChannelDetailDrawer channelId={detailId} onClose={() => setDetailId(null)} />
      <ChannelEditor
        channel={editing}
        open={editorOpen}
        onClose={() => {
          setEditorOpen(false);
          setEditing(undefined);
        }}
      />
    </>
  );
}
