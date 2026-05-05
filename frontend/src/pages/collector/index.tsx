import { App as AntApp, Button, Card, Progress, Space, Table, Tag, Tooltip } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import 'dayjs/locale/zh-cn';
import Papa from 'papaparse';
import { useState } from 'react';
import { collectorDiagApi, type ChannelRuntimeState } from '@/api/collectorDiag';
import { channelApi, type ChannelDTO } from '@/api/channel';
import { translate, COLLECTOR_PROTOCOL_LABEL, CONNECTION_STATE_LABEL } from '@/utils/i18n-dict';
import { humanizeChannelError } from '@/utils/channelErrorHumanize';
import { BatchImportModal } from './BatchImportModal';
import { ChannelDetailDrawer } from './ChannelDetailDrawer';
import { ChannelEditor } from './ChannelEditor';
import { PageHeader } from '@/components/PageHeader';
import { HELP_COLLECTOR } from '@/components/pageHelp';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';

dayjs.extend(relativeTime);
dayjs.locale('zh-cn');

const STATE_COLORS: Record<string, string> = {
  CONNECTED: 'green',
  CONNECTING: 'gold',
  DISCONNECTED: 'red',
  ERROR: 'red',
};

// channels.csv 列：5 协议字段并集；某行只填它对应协议需要的列，其它留空。
const CHANNEL_CSV_FIELDS = [
  'name',
  'protocol',
  'enabled',
  'isVirtual',
  'description',
  'host',
  'port',
  'serialPort',
  'baudRate',
  'dataBits',
  'stopBits',
  'parity',
  'unitId',
  'pollInterval',
  'timeout',
  'endpointUrl',
  'securityMode',
  'certRef',
  'certPasswordRef',
  'usernameRef',
  'passwordRef',
  'brokerUrl',
  'clientId',
  'qos',
  'cleanSession',
  'keepAlive',
  'tlsCaCertRef',
  'lastWillTopic',
  'lastWillPayload',
  'lastWillQos',
  'lastWillRetained',
];

// points.csv 列：4 协议测点字段并集 + 通用 channelName/key/unit。
const POINT_CSV_FIELDS = [
  'channelName',
  'key',
  'unit',
  'registerKind',
  'address',
  'quantity',
  'dataType',
  'byteOrder',
  'scale',
  'nodeId',
  'mode',
  'samplingIntervalMs',
  'topic',
  'jsonPath',
  'timestampJsonPath',
  'virtualMode',
  'virtualParams',
];

type CfgRecord = Record<string, unknown> & { points?: unknown[] };

function buildChannelsCsv(channels: ChannelDTO[]): string {
  const rows = channels.map((c) => {
    const cfg = (c.protocolConfig ?? {}) as CfgRecord;
    return CHANNEL_CSV_FIELDS.map((f) => {
      switch (f) {
        case 'name':
          return c.name;
        case 'protocol':
          return c.protocol;
        case 'enabled':
          return c.enabled ? 'true' : 'false';
        case 'isVirtual':
          return c.isVirtual ? 'true' : 'false';
        case 'description':
          return c.description ?? '';
        default: {
          const v = cfg[f];
          if (v == null) return '';
          if (typeof v === 'boolean') return v ? 'true' : 'false';
          return String(v);
        }
      }
    });
  });
  return Papa.unparse({ fields: CHANNEL_CSV_FIELDS, data: rows });
}

function buildPointsCsv(channels: ChannelDTO[]): string {
  const rows: (string | number)[][] = [];
  for (const c of channels) {
    const cfg = (c.protocolConfig ?? {}) as CfgRecord;
    const points = Array.isArray(cfg.points) ? cfg.points : [];
    for (const pUnknown of points) {
      const p = pUnknown as Record<string, unknown>;
      rows.push(
        POINT_CSV_FIELDS.map((f) => {
          if (f === 'channelName') return c.name;
          // VirtualPoint.mode 在 JSON 里是 mode 字段，需映射到 virtualMode 列
          if (f === 'virtualMode') return c.protocol === 'VIRTUAL' ? String(p.mode ?? '') : '';
          // VirtualPoint.params 是嵌套 Map，序列化成 JSON 串塞进单列
          if (f === 'virtualParams') {
            return c.protocol === 'VIRTUAL' && p.params ? JSON.stringify(p.params) : '';
          }
          // mode 列只用于 OPC UA（READ/SUBSCRIBE）；Virtual 的 mode 已被上一分支吃掉
          if (f === 'mode') return c.protocol === 'OPC_UA' ? String(p.mode ?? '') : '';
          const v = p[f];
          if (v == null) return '';
          if (typeof v === 'boolean') return v ? 'true' : 'false';
          return String(v);
        })
      );
    }
  }
  return Papa.unparse({ fields: POINT_CSV_FIELDS, data: rows });
}

function downloadCsv(filename: string, csv: string): void {
  // Excel 打开 UTF-8 CSV 默认按 GBK 解码会乱码，加 BOM 提示编码
  const blob = new Blob(['﻿', csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

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

  // 批量导出：拍平 protocolConfig 到两份 CSV——channels.csv（一行一通道）+ points.csv（一行一测点）。
  // 列结构与后端 ChannelCsvParser 必填表头一一对齐，导出文件可直接喂回批量导入。
  // VirtualPoint.params 是嵌套 Map，单列存 JSON 字符串（virtualParams 列）。
  const handleExport = () => {
    if (channels.length === 0) {
      message.info('暂无通道可导出');
      return;
    }
    const ts = dayjs().format('YYYYMMDD-HHmmss');
    downloadCsv(`channels-export-${ts}.csv`, buildChannelsCsv(channels));
    downloadCsv(`points-export-${ts}.csv`, buildPointsCsv(channels));
    message.success(`已导出 ${channels.length} 条通道及其测点（2 个 CSV 文件）`);
  };

  return (
    <>
      <PageHeader
        title="数据采集"
        helpContent={HELP_COLLECTOR}
        extra={
          <Space>
            <Button onClick={handleExport} disabled={channels.length === 0}>
              批量导出
            </Button>
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
              render: (m?: string) => {
                if (!m) return '-';
                // 主显示翻译成中文人话；hover 仍可看原始错误供 debug。
                return <Tooltip title={m}>{humanizeChannelError(m)}</Tooltip>;
              },
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
