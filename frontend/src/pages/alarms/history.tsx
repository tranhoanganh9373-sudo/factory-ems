import {
  App,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Form,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { alarmApi, type AlarmListItemDTO, type AlarmStatus, type AlarmType } from '@/api/alarm';
import { useAuthStore } from '@/stores/authStore';
import dayjs, { type Dayjs } from 'dayjs';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { StatusTag } from '@/components/StatusTag';

interface FilterValues {
  status?: AlarmStatus;
  alarmType?: AlarmType;
  range?: [Dayjs, Dayjs];
}

export default function AlarmHistoryPage() {
  useDocumentTitle('告警 - 历史');
  const { message } = App.useApp();
  const qc = useQueryClient();
  const { hasRole } = useAuthStore();
  const isAdmin = hasRole('ADMIN');

  const [searchParams, setSearchParams] = useSearchParams();
  const idFromQuery = searchParams.get('id');

  const [filters, setFilters] = useState<FilterValues>({});
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [openId, setOpenId] = useState<number | null>(null);

  // 跟随 URL 参数自动打开详情
  useEffect(() => {
    if (idFromQuery) {
      const id = Number(idFromQuery);
      if (!Number.isNaN(id)) setOpenId(id);
    }
  }, [idFromQuery]);

  const { data, isFetching } = useQuery({
    queryKey: ['alarms', 'list', filters, page, size],
    queryFn: () =>
      alarmApi.list({
        status: filters.status,
        alarmType: filters.alarmType,
        from: filters.range?.[0]?.toISOString(),
        to: filters.range?.[1]?.toISOString(),
        page,
        size,
      }),
    refetchOnWindowFocus: false,
  });

  const { data: detail } = useQuery({
    queryKey: ['alarms', 'detail', openId],
    queryFn: () => alarmApi.getById(openId as number),
    enabled: openId !== null,
  });

  const ack = useMutation({
    mutationFn: alarmApi.ack,
    onSuccess: () => {
      message.success('已确认');
      qc.invalidateQueries({ queryKey: ['alarms'] });
    },
    onError: (e: unknown) => {
      const msg = e instanceof Error ? e.message : '确认失败';
      message.error(msg);
    },
  });

  const resolve = useMutation({
    mutationFn: alarmApi.resolve,
    onSuccess: () => {
      message.success('已恢复');
      qc.invalidateQueries({ queryKey: ['alarms'] });
    },
    onError: (e: unknown) => {
      const msg = e instanceof Error ? e.message : '恢复失败';
      message.error(msg);
    },
  });

  const closeDetail = () => {
    setOpenId(null);
    if (idFromQuery) {
      const next = new URLSearchParams(searchParams);
      next.delete('id');
      setSearchParams(next, { replace: true });
    }
  };

  const statusTag = (s: AlarmStatus) => {
    const tone = s === 'ACTIVE' ? 'error' : s === 'ACKED' ? 'warning' : 'success';
    const label = s === 'ACTIVE' ? '告警中' : s === 'ACKED' ? '已确认' : '已恢复';
    return <StatusTag tone={tone}>{label}</StatusTag>;
  };

  const typeText = (t: AlarmType) => (t === 'SILENT_TIMEOUT' ? '采集中断' : '连续失败');

  const durationOf = (a: AlarmListItemDTO) => {
    const start = dayjs(a.triggeredAt);
    const end = a.ackedAt ? dayjs(a.ackedAt) : dayjs();
    const minutes = end.diff(start, 'minute');
    if (minutes < 60) return `${minutes} 分钟`;
    if (minutes < 60 * 24) return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`;
    return `${Math.floor(minutes / (60 * 24))} 天`;
  };

  return (
    <div>
      <PageHeader title="告警历史" />

      <Card style={{ marginBottom: 16 }}>
        <Form<FilterValues>
          layout="inline"
          onFinish={(v) => {
            setFilters(v);
            setPage(1);
          }}
          initialValues={filters}
        >
          <Form.Item label="状态" name="status">
            <Select
              allowClear
              style={{ width: 140 }}
              options={[
                { value: 'ACTIVE', label: '告警中' },
                { value: 'ACKED', label: '已确认' },
                { value: 'RESOLVED', label: '已恢复' },
              ]}
              placeholder="全部"
            />
          </Form.Item>
          <Form.Item label="类型" name="alarmType">
            <Select
              allowClear
              style={{ width: 160 }}
              options={[
                { value: 'SILENT_TIMEOUT', label: '采集中断' },
                { value: 'CONSECUTIVE_FAIL', label: '连续失败' },
              ]}
              placeholder="全部"
            />
          </Form.Item>
          <Form.Item label="时间范围" name="range">
            <DatePicker.RangePicker showTime />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                查询
              </Button>
              <Button
                onClick={() => {
                  setFilters({});
                  setPage(1);
                }}
              >
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Table<AlarmListItemDTO>
        rowKey="id"
        loading={isFetching}
        dataSource={data?.items ?? []}
        pagination={{
          current: page,
          pageSize: size,
          total: data?.total ?? 0,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(p);
            setSize(s);
          },
        }}
        columns={[
          {
            title: '触发时间',
            dataIndex: 'triggeredAt',
            width: 200,
            render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm:ss'),
          },
          { title: '设备编码', dataIndex: 'deviceCode', width: 180 },
          { title: '设备名称', dataIndex: 'deviceName' },
          {
            title: '类型',
            dataIndex: 'alarmType',
            width: 120,
            render: typeText,
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            render: statusTag,
          },
          {
            title: '持续时长',
            width: 130,
            render: (_, a) => durationOf(a),
          },
          {
            title: '操作',
            width: 240,
            render: (_, a) => (
              <Space>
                <Button size="small" onClick={() => setOpenId(a.id)}>
                  详情
                </Button>
                {isAdmin && a.status === 'ACTIVE' && (
                  <Popconfirm title="确认该告警？" onConfirm={() => ack.mutate(a.id)}>
                    <Button
                      size="small"
                      type="primary"
                      loading={ack.isPending && ack.variables === a.id}
                    >
                      确认
                    </Button>
                  </Popconfirm>
                )}
                {isAdmin && (a.status === 'ACTIVE' || a.status === 'ACKED') && (
                  <Popconfirm title="手动标记为已恢复？" onConfirm={() => resolve.mutate(a.id)}>
                    <Button size="small" loading={resolve.isPending && resolve.variables === a.id}>
                      手动恢复
                    </Button>
                  </Popconfirm>
                )}
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title={detail ? `告警详情 #${detail.id}` : '告警详情'}
        open={openId !== null}
        onCancel={closeDetail}
        footer={null}
        width={720}
      >
        {detail && (
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="ID">{detail.id}</Descriptions.Item>
            <Descriptions.Item label="状态">{statusTag(detail.status)}</Descriptions.Item>
            <Descriptions.Item label="设备编码">{detail.deviceCode}</Descriptions.Item>
            <Descriptions.Item label="设备名称">{detail.deviceName}</Descriptions.Item>
            <Descriptions.Item label="类型">{typeText(detail.alarmType)}</Descriptions.Item>
            <Descriptions.Item label="严重性">{detail.severity}</Descriptions.Item>
            <Descriptions.Item label="触发时间">
              {dayjs(detail.triggeredAt).format('YYYY-MM-DD HH:mm:ss')}
            </Descriptions.Item>
            <Descriptions.Item label="最后数据时间">
              {detail.lastSeenAt ? dayjs(detail.lastSeenAt).format('YYYY-MM-DD HH:mm:ss') : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="确认时间">
              {detail.ackedAt ? dayjs(detail.ackedAt).format('YYYY-MM-DD HH:mm:ss') : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="确认人">
              {detail.ackedBy !== null ? String(detail.ackedBy) : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="恢复时间">
              {detail.resolvedAt ? dayjs(detail.resolvedAt).format('YYYY-MM-DD HH:mm:ss') : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="恢复方式">
              {detail.resolvedReason === 'AUTO'
                ? '自动'
                : detail.resolvedReason === 'MANUAL'
                  ? '手动'
                  : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="详情" span={2}>
              <pre style={{ margin: 0, maxHeight: 240, overflow: 'auto' }}>
                {detail.detail ? JSON.stringify(detail.detail, null, 2) : '—'}
              </pre>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </div>
  );
}
