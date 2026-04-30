import {
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Space,
  Switch,
  Table,
  Tag,
  Input,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { alarmRuleApi, type AlarmRuleOverrideDTO, type OverrideRequest } from '@/api/alarm';
import { useAuthStore } from '@/stores/authStore';

interface FormValues {
  deviceId: number;
  silentTimeoutSeconds: number | null;
  consecutiveFailCount: number | null;
  maintenanceMode: boolean;
  maintenanceNote: string | null;
}

export default function AlarmRulesPage() {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const { hasRole } = useAuthStore();
  const isAdmin = hasRole('ADMIN');

  const { data: defaults, isLoading: loadingDefaults } = useQuery({
    queryKey: ['alarm-rules', 'defaults'],
    queryFn: alarmRuleApi.getDefaults,
    refetchOnWindowFocus: false,
  });

  const { data: overrides = [], isFetching } = useQuery({
    queryKey: ['alarm-rules', 'overrides'],
    queryFn: alarmRuleApi.listOverrides,
    refetchOnWindowFocus: false,
  });

  const [editing, setEditing] = useState<AlarmRuleOverrideDTO | null>(null);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<FormValues>();

  const save = useMutation({
    mutationFn: ({ deviceId, req }: { deviceId: number; req: OverrideRequest }) =>
      alarmRuleApi.setOverride(deviceId, req),
    onSuccess: () => {
      message.success('保存成功，最长 60s 内生效');
      qc.invalidateQueries({ queryKey: ['alarm-rules'] });
      setEditing(null);
      setCreating(false);
      form.resetFields();
    },
    onError: (e: unknown) => {
      message.error(e instanceof Error ? e.message : '保存失败');
    },
  });

  const clear = useMutation({
    mutationFn: alarmRuleApi.clearOverride,
    onSuccess: () => {
      message.success('已清除覆盖，沿用全局默认');
      qc.invalidateQueries({ queryKey: ['alarm-rules'] });
    },
    onError: (e: unknown) => {
      message.error(e instanceof Error ? e.message : '清除失败');
    },
  });

  useEffect(() => {
    if (editing) {
      form.setFieldsValue({
        deviceId: editing.deviceId,
        silentTimeoutSeconds: editing.silentTimeoutSeconds,
        consecutiveFailCount: editing.consecutiveFailCount,
        maintenanceMode: editing.maintenanceMode,
        maintenanceNote: editing.maintenanceNote,
      });
    } else if (creating) {
      form.resetFields();
      form.setFieldsValue({ maintenanceMode: false });
    }
  }, [editing, creating, form]);

  const closeModal = () => {
    setEditing(null);
    setCreating(false);
    form.resetFields();
  };

  const onSubmit = async () => {
    const v = await form.validateFields();
    save.mutate({
      deviceId: v.deviceId,
      req: {
        silentTimeoutSeconds: v.silentTimeoutSeconds ?? null,
        consecutiveFailCount: v.consecutiveFailCount ?? null,
        maintenanceMode: v.maintenanceMode,
        maintenanceNote: v.maintenanceNote ?? null,
      },
    });
  };

  return (
    <div>
      <h2>阈值规则</h2>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={24}>
          <Card title="全局默认（只读，修改需重启 ems-app）" loading={loadingDefaults}>
            <Descriptions column={3} bordered size="small">
              <Descriptions.Item label="静默超时（秒）">
                {defaults?.silentTimeoutSeconds ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="连续失败次数">
                {defaults?.consecutiveFailCount ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="抑制窗口（秒）">
                {defaults?.suppressionWindowSeconds ?? '—'}
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
      </Row>

      <Card
        title="设备级覆盖"
        extra={
          isAdmin && (
            <Button type="primary" onClick={() => setCreating(true)}>
              新增覆盖
            </Button>
          )
        }
      >
        <Table<AlarmRuleOverrideDTO>
          rowKey="deviceId"
          loading={isFetching}
          dataSource={overrides}
          pagination={{ pageSize: 20 }}
          locale={{ emptyText: '暂无设备级覆盖（全部沿用全局默认）' }}
          columns={[
            { title: '设备 ID', dataIndex: 'deviceId', width: 120 },
            {
              title: '静默超时（秒）',
              dataIndex: 'silentTimeoutSeconds',
              width: 160,
              render: (v: number | null) => v ?? <Tag>沿用默认</Tag>,
            },
            {
              title: '连续失败次数',
              dataIndex: 'consecutiveFailCount',
              width: 160,
              render: (v: number | null) => v ?? <Tag>沿用默认</Tag>,
            },
            {
              title: '维护模式',
              dataIndex: 'maintenanceMode',
              width: 120,
              render: (v: boolean) => (v ? <Tag color="warning">维护中</Tag> : <Tag>关闭</Tag>),
            },
            {
              title: '备注',
              dataIndex: 'maintenanceNote',
              ellipsis: true,
            },
            {
              title: '更新时间',
              dataIndex: 'updatedAt',
              width: 200,
            },
            {
              title: '操作',
              width: 200,
              render: (_, row) =>
                isAdmin ? (
                  <Space>
                    <Button size="small" onClick={() => setEditing(row)}>
                      编辑
                    </Button>
                    <Popconfirm
                      title="清除覆盖？"
                      description="清除后该设备沿用全局默认"
                      onConfirm={() => clear.mutate(row.deviceId)}
                    >
                      <Button
                        size="small"
                        danger
                        loading={clear.isPending && clear.variables === row.deviceId}
                      >
                        清除
                      </Button>
                    </Popconfirm>
                  </Space>
                ) : (
                  <span style={{ color: '#999' }}>仅 ADMIN 可编辑</span>
                ),
            },
          ]}
        />
      </Card>

      <Modal
        title={editing ? `编辑覆盖 — 设备 ${editing.deviceId}` : '新增设备覆盖'}
        open={editing !== null || creating}
        onCancel={closeModal}
        onOk={onSubmit}
        confirmLoading={save.isPending}
        destroyOnClose
      >
        <Form<FormValues> form={form} layout="vertical" requiredMark="optional">
          <Form.Item
            label="设备 ID"
            name="deviceId"
            rules={[{ required: true, message: '必填' }]}
            extra="对应 meters.id"
          >
            <InputNumber min={1} disabled={editing !== null} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="静默超时（秒）" name="silentTimeoutSeconds" extra="留空则沿用全局默认">
            <InputNumber min={1} placeholder="如 120" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="连续失败次数" name="consecutiveFailCount" extra="留空则沿用全局默认">
            <InputNumber min={1} placeholder="如 5" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="维护模式" name="maintenanceMode" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item
            label="维护备注"
            name="maintenanceNote"
            extra="建议格式：原因 + 计划恢复时间 + 联系人"
          >
            <Input.TextArea rows={3} maxLength={255} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
