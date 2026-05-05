import { Drawer, Descriptions, Tag, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import { collectorDiagApi, type RecentSample } from '@/api/collectorDiag';
import { channelApi } from '@/api/channel';
import { translate, CONNECTION_STATE_LABEL, COLLECTOR_PROTOCOL_LABEL } from '@/utils/i18n-dict';

interface ConfiguredPoint {
  key: string;
  address?: number | string;
  unit?: string;
}

interface PointStatus {
  key: string;
  address?: number | string;
  unit?: string;
  lastValue: unknown;
  lastTimestamp: string | null;
  lastQuality: string | null;
  sampleCount: number;
}

function extractPoints(cfg: Record<string, unknown> | undefined): ConfiguredPoint[] {
  if (!cfg || !Array.isArray(cfg.points)) return [];
  return (cfg.points as unknown[])
    .map((p) => p as Record<string, unknown>)
    .filter((p) => typeof p.key === 'string' && p.key.length > 0)
    .map((p) => ({
      key: p.key as string,
      address:
        typeof p.address === 'number' || typeof p.address === 'string' ? p.address : undefined,
      unit: typeof p.unit === 'string' ? p.unit : undefined,
    }));
}

/** 把通道配置里的 points 与最近 samples join，按 pointKey 聚合最新值/时间/质量。 */
function aggregatePointStatus(
  configuredPoints: ConfiguredPoint[],
  samples: RecentSample[] | undefined
): PointStatus[] {
  const byKey = new Map<string, RecentSample[]>();
  for (const s of samples ?? []) {
    const list = byKey.get(s.pointKey) ?? [];
    list.push(s);
    byKey.set(s.pointKey, list);
  }
  return configuredPoints.map((p) => {
    const list = byKey.get(p.key) ?? [];
    // recentSamples 由后端按时间倒序返回；取第 0 条即最新
    const latest = list[0];
    return {
      key: p.key,
      address: p.address,
      unit: p.unit,
      lastValue: latest?.value ?? null,
      lastTimestamp: latest?.timestamp ?? null,
      lastQuality: latest?.quality ?? null,
      sampleCount: list.length,
    };
  });
}

interface Props {
  channelId: number | null;
  onClose: () => void;
}

const DRAWER_WIDTH_KEY = 'channelDrawerWidth';
const DEFAULT_DRAWER_WIDTH = 520;
const MIN_DRAWER_WIDTH = 360;

function readStoredWidth(): number {
  if (typeof window === 'undefined') return DEFAULT_DRAWER_WIDTH;
  const raw = window.localStorage.getItem(DRAWER_WIDTH_KEY);
  const parsed = raw ? Number(raw) : NaN;
  return Number.isFinite(parsed) && parsed >= MIN_DRAWER_WIDTH ? parsed : DEFAULT_DRAWER_WIDTH;
}

const QUALITY_COLOR: Record<string, string> = {
  GOOD: 'green',
  BAD: 'red',
  UNCERTAIN: 'orange',
};

const SAMPLE_COLUMNS: ColumnsType<RecentSample> = [
  {
    title: '时间',
    dataIndex: 'timestamp',
    key: 'timestamp',
    width: 120,
    render: (t: string) => dayjs(t).format('HH:mm:ss.SSS'),
  },
  {
    title: '点位',
    dataIndex: 'pointKey',
    key: 'pointKey',
  },
  {
    title: '值',
    dataIndex: 'value',
    key: 'value',
    render: (v: unknown) => (v === null || v === undefined ? '-' : String(v)),
  },
  {
    title: '质量',
    dataIndex: 'quality',
    key: 'quality',
    width: 100,
    render: (q: string) => <Tag color={QUALITY_COLOR[q] ?? 'default'}>{q}</Tag>,
  },
];

export function ChannelDetailDrawer({ channelId, onClose }: Props) {
  const [drawerWidth, setDrawerWidth] = useState<number>(() => readStoredWidth());
  const dragStateRef = useRef<{ startX: number; startWidth: number } | null>(null);

  const handleResizeMouseDown = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      e.preventDefault();
      dragStateRef.current = { startX: e.clientX, startWidth: drawerWidth };
      document.body.style.cursor = 'ew-resize';
      document.body.style.userSelect = 'none';
    },
    [drawerWidth]
  );

  useEffect(() => {
    function onMove(ev: MouseEvent) {
      const drag = dragStateRef.current;
      if (!drag) return;
      // 抽屉锚定右侧：鼠标向左移动 → 宽度增大
      const delta = drag.startX - ev.clientX;
      const maxWidth = Math.max(MIN_DRAWER_WIDTH, window.innerWidth - 80);
      const next = Math.min(maxWidth, Math.max(MIN_DRAWER_WIDTH, drag.startWidth + delta));
      setDrawerWidth(next);
    }
    function onUp() {
      if (!dragStateRef.current) return;
      dragStateRef.current = null;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      window.localStorage.setItem(DRAWER_WIDTH_KEY, String(Math.round(drawerWidth)));
    }
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [drawerWidth]);

  const { data } = useQuery({
    queryKey: ['collector', 'state', channelId],
    queryFn: () => (channelId !== null ? collectorDiagApi.get(channelId) : null),
    enabled: channelId !== null,
    refetchInterval: 3000,
  });

  const { data: samples } = useQuery({
    queryKey: ['collector', 'recent-samples', channelId],
    queryFn: () => (channelId !== null ? collectorDiagApi.recentSamples(channelId, 100) : []),
    enabled: channelId !== null,
    refetchInterval: 5000,
  });

  // 拉通道配置以拿到 points 列表，与 samples join 得到每点的最新状态
  const { data: channel } = useQuery({
    queryKey: ['collector', 'channel-config', channelId],
    queryFn: () => (channelId !== null ? channelApi.get(channelId) : null),
    enabled: channelId !== null,
  });

  const configuredPoints = extractPoints(channel?.protocolConfig);
  const pointStatus = aggregatePointStatus(configuredPoints, samples);

  return (
    <Drawer
      open={channelId !== null}
      onClose={onClose}
      width={drawerWidth}
      title={data ? `通道 #${data.channelId} 详情` : '详情'}
      destroyOnClose
    >
      <div
        onMouseDown={handleResizeMouseDown}
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          bottom: 0,
          width: 6,
          cursor: 'ew-resize',
          zIndex: 10,
          background: 'transparent',
        }}
        title="拖动以调整宽度"
      />
      {data && (
        <Descriptions column={1} bordered size="small">
          <Descriptions.Item label="协议">
            <Tag>{translate(COLLECTOR_PROTOCOL_LABEL, data.protocol)}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            {translate(CONNECTION_STATE_LABEL, data.connState)}
          </Descriptions.Item>
          <Descriptions.Item label="最近连接">
            {data.lastConnectAt ? dayjs(data.lastConnectAt).format('YYYY-MM-DD HH:mm:ss') : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="最近成功">
            {data.lastSuccessAt ? dayjs(data.lastSuccessAt).format('YYYY-MM-DD HH:mm:ss') : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="最近失败">
            {data.lastFailureAt ? dayjs(data.lastFailureAt).format('YYYY-MM-DD HH:mm:ss') : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="24h 成功">{data.successCount24h}</Descriptions.Item>
          <Descriptions.Item label="24h 失败">{data.failureCount24h}</Descriptions.Item>
          <Descriptions.Item label="平均延迟">{data.avgLatencyMs} ms</Descriptions.Item>
          <Descriptions.Item label="最后错误">{data.lastErrorMessage ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="协议元信息">
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
              {JSON.stringify(data.protocolMeta, null, 2)}
            </pre>
          </Descriptions.Item>
        </Descriptions>
      )}
      {data && configuredPoints.length > 0 && (
        <>
          <h4 style={{ marginTop: 16 }}>测点状态（{pointStatus.length} 个测点）</h4>
          <Table<PointStatus>
            size="small"
            pagination={false}
            rowKey="key"
            dataSource={pointStatus}
            locale={{ emptyText: '通道未配置任何测点' }}
            columns={[
              { title: '测点 key', dataIndex: 'key', key: 'key', width: 120 },
              {
                title: '地址',
                dataIndex: 'address',
                key: 'address',
                width: 80,
                render: (v) => (v == null ? '—' : String(v)),
              },
              {
                title: '最新值',
                dataIndex: 'lastValue',
                key: 'lastValue',
                render: (v: unknown, r) =>
                  v === null || v === undefined ? (
                    <Tag color="default">暂无数据</Tag>
                  ) : (
                    <span>
                      {String(v)}
                      {r.unit ? ` ${r.unit}` : ''}
                    </span>
                  ),
              },
              {
                title: '最新时间',
                dataIndex: 'lastTimestamp',
                key: 'lastTimestamp',
                width: 120,
                render: (t: string | null) => (t ? dayjs(t).format('HH:mm:ss.SSS') : '—'),
              },
              {
                title: '质量',
                dataIndex: 'lastQuality',
                key: 'lastQuality',
                width: 80,
                render: (q: string | null) =>
                  q ? <Tag color={QUALITY_COLOR[q] ?? 'default'}>{q}</Tag> : '—',
              },
            ]}
          />
        </>
      )}
      {data && (
        <>
          <h4 style={{ marginTop: 16 }}>最近样本（最多 100 条）</h4>
          <Table<RecentSample>
            size="small"
            pagination={false}
            rowKey={(r, idx) => `${r.timestamp}-${r.pointKey}-${idx ?? 0}`}
            columns={SAMPLE_COLUMNS}
            dataSource={samples ?? []}
            locale={{ emptyText: '暂无样本（生产环境无 ring buffer）' }}
          />
        </>
      )}
    </Drawer>
  );
}
