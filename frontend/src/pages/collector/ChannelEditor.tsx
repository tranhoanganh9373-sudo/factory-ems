import { Button, Drawer, Form, Input, Select, Space, App as AntApp } from 'antd';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { channelApi, type ChannelDTO, type Protocol } from '@/api/channel';
import { COLLECTOR_PROTOCOL_LABEL } from '@/utils/i18n-dict';
import { ModbusTcpForm } from './forms/ModbusTcpForm';
import { ModbusRtuForm } from './forms/ModbusRtuForm';
import { OpcUaForm } from './forms/OpcUaForm';
import { MqttForm } from './forms/MqttForm';
import { VirtualForm } from './forms/VirtualForm';

interface Props {
  channel?: ChannelDTO;
  open: boolean;
  onClose: () => void;
}

export function ChannelEditor({ channel, open, onClose }: Props) {
  const [form] = Form.useForm();
  const [protocol, setProtocol] = useState<Protocol>(channel?.protocol ?? 'MODBUS_TCP');
  const { message } = AntApp.useApp();
  const qc = useQueryClient();

  useEffect(() => {
    if (open) {
      setProtocol(channel?.protocol ?? 'MODBUS_TCP');
      form.resetFields();
      if (channel) form.setFieldsValue(channel);
    }
  }, [channel, open, form]);

  const save = useMutation({
    mutationFn: (body: Partial<ChannelDTO>) =>
      channel ? channelApi.update(channel.id, body) : channelApi.create(body),
    onSuccess: () => {
      message.success('已保存');
      qc.invalidateQueries({ queryKey: ['channel'] });
      qc.invalidateQueries({ queryKey: ['collector'] });
      onClose();
    },
    onError: (e: Error) => message.error(`保存失败：${e.message}`),
  });

  const test = useMutation({
    mutationFn: () => (channel ? channelApi.test(channel.id) : Promise.resolve(null)),
    onSuccess: (res) => {
      if (!res) return;
      if (res.success) message.success(`连接成功 (${res.latencyMs ?? 0} ms)`);
      else message.error(`连接失败：${res.message}`);
    },
  });

  return (
    <Drawer
      title={channel ? `编辑：${channel.name}` : '新增通道'}
      open={open}
      onClose={onClose}
      width={720}
      destroyOnClose
      extra={
        <Space>
          {channel && (
            <Button onClick={() => test.mutate()} loading={test.isPending}>
              测试连接
            </Button>
          )}
          <Button
            type="primary"
            loading={save.isPending}
            onClick={async () => {
              try {
                const v = await form.validateFields();
                const payload: Partial<ChannelDTO> = {
                  ...v,
                  protocol,
                  isVirtual: protocol === 'VIRTUAL',
                };
                // VIRTUAL points.params 在表单里是 JSON 字符串 → 后端要 Map<String,Double>
                if (protocol === 'VIRTUAL' && v.protocolConfig?.points) {
                  try {
                    payload.protocolConfig = {
                      ...v.protocolConfig,
                      points: v.protocolConfig.points.map((p: Record<string, unknown>) => ({
                        ...p,
                        params: typeof p.params === 'string' ? JSON.parse(p.params) : p.params,
                      })),
                    };
                  } catch {
                    message.error('测点 params 不是合法 JSON 对象');
                    return;
                  }
                }
                save.mutate(payload);
              } catch {
                // validation error: AntD 已显示
              }
            }}
          >
            保存
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" initialValues={channel ?? { enabled: true }}>
        <Form.Item name="name" label="通道名称" rules={[{ required: true, max: 128 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="protocol" label="协议" initialValue={protocol}>
          <Select
            disabled={!!channel}
            value={protocol}
            onChange={setProtocol}
            options={Object.entries(COLLECTOR_PROTOCOL_LABEL).map(([v, l]) => ({
              value: v,
              label: l,
            }))}
          />
        </Form.Item>
        <Form.Item name="description" label="描述">
          <Input.TextArea rows={2} />
        </Form.Item>

        {protocol === 'MODBUS_TCP' && <ModbusTcpForm />}
        {protocol === 'MODBUS_RTU' && <ModbusRtuForm />}
        {protocol === 'OPC_UA' && <OpcUaForm />}
        {protocol === 'MQTT' && <MqttForm />}
        {protocol === 'VIRTUAL' && <VirtualForm />}
      </Form>
    </Drawer>
  );
}
