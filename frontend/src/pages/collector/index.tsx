import { App as AntApp, Button, Card, Progress, Space, Table, Tag, Tooltip } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import 'dayjs/locale/zh-cn';
import { useState } from 'react';
import { collectorDiagApi, type ChannelRuntimeState } from '@/api/collectorDiag';
import { channelApi, type ChannelDTO } from '@/api/channel';
import { translate, COLLECTOR_PROTOCOL_LABEL, CONNECTION_STATE_LABEL } from '@/utils/i18n-dict';
import { BatchImportModal } from './BatchImportModal';
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
  // 用 Set 跟踪每个 channelId 是否在飞行中——同列多通道操作互不阻塞。
  const [deletingIds, setDeletingIds] = useState<Set<number>>(new Set());
  const [reconnectingIds, setReconnectingIds] = useState<Set<number>>(new Set());
  const [testingIds, setTestingIds] = useState<Set<number>>(new Set());

  const addId = (set: Set<number>, id: number) => new Set(set).add(id);
  const removeId = (set: Set<number>, id: number) => {
    const next = new Set(set);
    next.delete(id);
    return next;
  };
  const [batchOpen, setBatchOpen] = useState(false);

  const { data: states = [], isLoading } = useQuery({
    queryKey: ['collector', 'state'],
    queryFn: collectorDiagApi.list,
    refetchInterval: 5000,
  });

  const { data: channels = [] } = useQuery({
    queryKey: ['collector', 'channels'],
    queryFn: channelApi.list,
  });
  const channelNameById = new Map<number, string>(channels.map((c) => [c.id, c.name]));

  const test = useMutation({
    mutationFn: (id: number) => collectorDiagApi.test(id),
    onSuccess: (res) => {
      if (res.success) message.success(`测试成功 (${res.latencyMs ?? 0} ms)`);
      else message.error(`测试失败：${res.message}`);
    },
    onSettled: (_, __, id) => setTestingIds((s) => removeId(s, id)),
  });

  const reconnect = useMutation({
    mutationFn: (id: number) => collectorDiagApi.reconnect(id),
    onSuccess: (res) => {
      if (res.success) {
        // 后端 restart() 对 Modbus / OPC UA / MQTT 都是非阻塞的：start() 把 transport 起到
        // 后台线程就返回。9ms 是指令下发耗时，不是连接建立耗时——别冒充"已连接"。
        message.success('重连请求已下发，连接结果以状态列为准');
        qc.invalidateQueries({ queryKey: ['collector'] });
      } else {
        message.error(`重连失败：${res.message}`);
      }
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : '重连失败';
      message.error(`重连失败：${msg}`);
    },
    onSettled: (_, __, id) => setReconnectingIds((s) => removeId(s, id)),
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
    onSettled: (_, __, id) => setDeletingIds((s) => removeId(s, id)),
  });

  const confirmDelete = (channelId: number) => {
    modal.confirm({
      title: '删除通道',
      content: `确认删除通道 #${channelId}？此操作不可撤销，正在采集的数据会停止。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => {
        setDeletingIds((s) => addId(s, channelId));
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
          <Space>
            <Button onClick={() => setBatchOpen(true)}>批量导入</Button>
            <Button type="primary" onClick={() => openEditor()}>
              新增通道
            </Button>
          </Space>
        }
      />
      <Card>
        <Table<ChannelRuntimeState>
          rowKey="channelId"
          loading={isLoading}
          dataSource={states}
          columns={[
            {
              title: '通道名称',
              dataIndex: 'channelId',
              render: (id: number) => channelNameById.get(id) ?? '-',
            },
            {
              title: '协议',
              dataIndex: 'protocol',
              render: (p: string) => <Tag>{translate(COLLECTOR_PROTOCOL_LABEL, p)}</Tag>,
            },
            { title: '通道 ID', dataIndex: 'channelId', width: 80 },
            {
              title: '状态',
              dataIndex: 'connState',
              render: (s: string, r) => {
                const effective = reconnectingIds.has(r.channelId) ? 'CONNECTING' : s;
                return (
                  <Tag color={STATE_COLORS[effective] ?? 'default'}>
                    {translate(CONNECTION_STATE_LABEL, effective)}
                  </Tag>
                );
              },
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
                if (tot === 0) return '-';
                const rate = (r.successCount24h / tot) * 100;
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
              render: (_, r) => {
                const tot = r.successCount24h + r.failureCount24h;
                return tot === 0 ? '-' : `${r.avgLatencyMs} ms`;
              },
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
                    onClick={() => {
                      setTestingIds((s) => addId(s, r.channelId));
                      test.mutate(r.channelId);
                    }}
                    loading={testingIds.has(r.channelId)}
                  >
                    测试
                  </Button>
                  <Button
                    size="small"
                    onClick={() => {
                      setReconnectingIds((s) => addId(s, r.channelId));
                      reconnect.mutate(r.channelId);
                    }}
                    loading={reconnectingIds.has(r.channelId)}
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
                    loading={deletingIds.has(r.channelId)}
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
      <BatchImportModal open={batchOpen} onClose={() => setBatchOpen(false)} />
    </>
  );
}
