import {
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { webhookApi, type DeliveryLogDTO, type WebhookConfigRequest } from '@/api/alarm';
import dayjs from 'dayjs';

interface FormValues {
  enabled: boolean;
  url: string;
  secret: string;
  adapterType: string;
  timeoutMs: number;
}

export default function AlarmWebhookPage() {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const [form] = Form.useForm<FormValues>();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);

  const { data: cfg, isLoading: loadingCfg } = useQuery({
    queryKey: ['webhook', 'config'],
    queryFn: webhookApi.get,
    refetchOnWindowFocus: false,
  });

  const { data: deliveries, isFetching: loadingDeliveries } = useQuery({
    queryKey: ['webhook', 'deliveries', page, size],
    queryFn: () => webhookApi.listDeliveries({ page, size }),
    refetchOnWindowFocus: false,
  });

  // 表单初值同步（cfg.secret 为 "***" 时清空表单 secret 字段，让用户明确知道留空=保持原值）
  useEffect(() => {
    if (cfg) {
      form.setFieldsValue({
        enabled: cfg.enabled,
        url: cfg.url,
        secret: '', // 始终空：留空=保持原值
        adapterType: cfg.adapterType || 'GENERIC_JSON',
        timeoutMs: cfg.timeoutMs > 0 ? cfg.timeoutMs : 5000,
      });
    }
  }, [cfg, form]);

  const update = useMutation({
    mutationFn: (req: WebhookConfigRequest) => webhookApi.update(req),
    onSuccess: () => {
      message.success('已保存');
      qc.invalidateQueries({ queryKey: ['webhook'] });
    },
    onError: (e: unknown) => {
      message.error(e instanceof Error ? e.message : '保存失败');
    },
  });

  const test = useMutation({
    mutationFn: (req: WebhookConfigRequest) => webhookApi.test(req),
    onSuccess: (res) => {
      if (res.error) {
        message.error(`测试失败：${res.error}`);
      } else if (res.statusCode >= 200 && res.statusCode < 300) {
        message.success(`测试成功：HTTP ${res.statusCode}（耗时 ${res.durationMs}ms）`);
      } else {
        message.warning(`测试响应非 2xx：HTTP ${res.statusCode}（耗时 ${res.durationMs}ms）`);
      }
    },
    onError: (e: unknown) => {
      message.error(e instanceof Error ? e.message : '测试失败');
    },
  });

  const retry = useMutation({
    mutationFn: webhookApi.retry,
    onSuccess: () => {
      message.success('已触发重发，请稍后刷新查看新流水');
      setTimeout(() => qc.invalidateQueries({ queryKey: ['webhook', 'deliveries'] }), 1000);
    },
    onError: (e: unknown) => {
      message.error(e instanceof Error ? e.message : '重发失败');
    },
  });

  const onSave = async () => {
    const v = await form.validateFields();
    update.mutate({
      enabled: v.enabled,
      url: v.url,
      secret: v.secret || '', // 空字符串 = 保持原值（后端约定）
      adapterType: v.adapterType,
      timeoutMs: v.timeoutMs,
    });
  };

  const onTest = async () => {
    const v = await form.validateFields();
    test.mutate({
      enabled: v.enabled,
      url: v.url,
      secret: v.secret || '',
      adapterType: v.adapterType,
      timeoutMs: v.timeoutMs,
    });
  };

  const statusTag = (s: 'SUCCESS' | 'FAILED') =>
    s === 'SUCCESS' ? <Tag color="success">成功</Tag> : <Tag color="error">失败</Tag>;

  return (
    <div>
      <h2>Webhook 配置</h2>

      <Row gutter={16}>
        <Col xs={24} lg={14}>
          <Card title="配置" loading={loadingCfg}>
            <Form<FormValues> form={form} layout="vertical">
              <Form.Item label="启用" name="enabled" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item
                label="接收方 URL"
                name="url"
                rules={[
                  { required: true, message: '必填' },
                  { max: 512, message: '最多 512 字符' },
                  {
                    pattern: /^https?:\/\//,
                    message: '必须以 http:// 或 https:// 开头',
                  },
                ]}
              >
                <Input placeholder="https://your-receiver.example.com/webhook" />
              </Form.Item>
              <Form.Item
                label="签名密钥（HMAC-SHA256）"
                name="secret"
                extra={
                  cfg?.secret === '***'
                    ? '当前已设置（留空保持原值，输入新值则覆盖）'
                    : '尚未设置（建议至少 32 字符随机串）'
                }
                rules={[{ max: 255, message: '最多 255 字符' }]}
              >
                <Input.Password
                  placeholder={
                    cfg?.secret === '***' ? '*** 已设置 ***' : '建议 openssl rand -hex 32'
                  }
                />
              </Form.Item>
              <Form.Item label="适配器类型" name="adapterType" rules={[{ required: true }]}>
                <Select
                  options={[{ value: 'GENERIC_JSON', label: 'GENERIC_JSON（首版唯一支持）' }]}
                />
              </Form.Item>
              <Form.Item
                label="超时（毫秒）"
                name="timeoutMs"
                rules={[
                  { required: true, message: '必填' },
                  { type: 'number', min: 1000, max: 30000, message: '范围 1000-30000' },
                ]}
              >
                <InputNumber min={1000} max={30000} step={500} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item>
                <Space>
                  <Button type="primary" onClick={onSave} loading={update.isPending}>
                    保存
                  </Button>
                  <Button onClick={onTest} loading={test.isPending}>
                    测试发送
                  </Button>
                </Space>
              </Form.Item>
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card title="当前状态" loading={loadingCfg}>
            <Descriptions column={1} size="small">
              <Descriptions.Item label="启用">
                {cfg?.enabled ? <Tag color="success">已启用</Tag> : <Tag>未启用</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="URL">{cfg?.url || '—'}</Descriptions.Item>
              <Descriptions.Item label="密钥">
                {cfg?.secret === '***' ? (
                  <Tag color="success">已设置</Tag>
                ) : (
                  <Tag color="warning">未设置</Tag>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="适配器">{cfg?.adapterType || '—'}</Descriptions.Item>
              <Descriptions.Item label="超时（ms）">{cfg?.timeoutMs || '—'}</Descriptions.Item>
              <Descriptions.Item label="更新时间">
                {cfg?.updatedAt ? dayjs(cfg.updatedAt).format('YYYY-MM-DD HH:mm:ss') : '—'}
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
      </Row>

      <Card title="发送流水" style={{ marginTop: 16 }}>
        <Table<DeliveryLogDTO>
          rowKey="id"
          loading={loadingDeliveries}
          dataSource={deliveries?.items ?? []}
          pagination={{
            current: page,
            pageSize: size,
            total: deliveries?.total ?? 0,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, s) => {
              setPage(p);
              setSize(s);
            },
          }}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 80 },
            { title: '告警 ID', dataIndex: 'alarmId', width: 100 },
            {
              title: '尝试次数',
              dataIndex: 'attempts',
              width: 100,
              align: 'right',
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 100,
              render: statusTag,
            },
            {
              title: 'HTTP 状态',
              dataIndex: 'responseStatus',
              width: 110,
              render: (v: number | null) => v ?? '—',
            },
            {
              title: '耗时（ms）',
              dataIndex: 'responseMs',
              width: 110,
              align: 'right',
              render: (v: number | null) => v ?? '—',
            },
            { title: '错误', dataIndex: 'lastError', ellipsis: true },
            {
              title: '时间',
              dataIndex: 'createdAt',
              width: 180,
              render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm:ss'),
            },
            {
              title: '操作',
              width: 100,
              render: (_, row) =>
                row.status === 'FAILED' ? (
                  <Popconfirm title="重发该告警的 webhook？" onConfirm={() => retry.mutate(row.id)}>
                    <Button size="small" loading={retry.isPending && retry.variables === row.id}>
                      重发
                    </Button>
                  </Popconfirm>
                ) : null,
            },
          ]}
        />
      </Card>
    </div>
  );
}
