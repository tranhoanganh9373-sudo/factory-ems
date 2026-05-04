import { Button, Col, Drawer, Form, Input, Row, Select, Space, App as AntApp } from 'antd';
import { DurationInput } from '@/components/DurationInput';
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

const DEFAULT_DRAWER_WIDTH = 960;
const MIN_DRAWER_WIDTH = 480;

export function ChannelEditor({ channel, open, onClose }: Props) {
  const [form] = Form.useForm();
  const [protocol, setProtocol] = useState<Protocol>(channel?.protocol ?? 'MODBUS_TCP');
  const [drawerWidth, setDrawerWidth] = useState<number>(DEFAULT_DRAWER_WIDTH);
  const { message } = AntApp.useApp();
  const qc = useQueryClient();

  // 抽屉关闭时重置宽度——下次打开还是默认 720，避免上次拖宽的状态泄漏到不同 channel。
  useEffect(() => {
    if (!open) setDrawerWidth(DEFAULT_DRAWER_WIDTH);
  }, [open]);

  const startResize = (e: React.MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startWidth = drawerWidth;
    const maxWidth = window.innerWidth * 0.95;
    const onMove = (mv: MouseEvent) => {
      // 抽屉右锚定，向左拖（clientX 减小）= 宽度增加
      const delta = startX - mv.clientX;
      setDrawerWidth(Math.max(MIN_DRAWER_WIDTH, Math.min(maxWidth, startWidth + delta)));
    };
    const onUp = () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      document.body.style.userSelect = '';
      document.body.style.cursor = '';
    };
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'ew-resize';
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  };

  useEffect(() => {
    if (open) {
      setProtocol(channel?.protocol ?? 'MODBUS_TCP');
      form.resetFields();
      if (channel) {
        const value: ChannelDTO = { ...channel };
        const cfg = channel.protocolConfig as Record<string, unknown> | undefined;
        const points = cfg?.points;
        if (channel.protocol === 'VIRTUAL' && Array.isArray(points)) {
          value.protocolConfig = {
            ...cfg,
            points: points.map((p: Record<string, unknown>) => ({
              ...p,
              params:
                p.params && typeof p.params === 'object' ? JSON.stringify(p.params) : p.params,
            })),
          };
        }
        form.setFieldsValue(value);
      }
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
    <>
      {open && (
        <div
          onMouseDown={startResize}
          aria-label="拖动调整宽度"
          role="separator"
          style={{
            position: 'fixed',
            top: 0,
            bottom: 0,
            right: drawerWidth - 3,
            width: 6,
            cursor: 'ew-resize',
            // 抽屉默认 zIndex 1000；放 1100 保证 handle 始终在抽屉边上可点
            zIndex: 1100,
          }}
        />
      )}
      <Drawer
        title={channel ? `编辑：${channel.name}` : '新增通道'}
        open={open}
        onClose={onClose}
        width={drawerWidth}
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
                  let protocolConfig: Record<string, unknown> = {
                    ...(v.protocolConfig ?? {}),
                    protocol,
                  };
                  if (protocol === 'VIRTUAL' && v.protocolConfig?.points) {
                    try {
                      protocolConfig = {
                        ...protocolConfig,
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
                  const payload: Partial<ChannelDTO> = {
                    ...v,
                    protocol,
                    isVirtual: protocol === 'VIRTUAL',
                    protocolConfig,
                  };
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
          <Row gutter={16}>
            <Col span={protocol === 'MQTT' ? 24 : 12}>
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
            </Col>
            {protocol !== 'MQTT' && (
              <Col span={12}>
                {/* key={protocol} 让用户在新建通道切换协议时重新应用对应默认值（VIRTUAL=PT1S，其他=PT5S） */}
                <Form.Item
                  key={`pollInterval-${protocol}`}
                  name={['protocolConfig', 'pollInterval']}
                  label="轮询间隔"
                  rules={[{ required: true }]}
                  initialValue={protocol === 'VIRTUAL' ? 'PT1S' : 'PT5S'}
                >
                  <DurationInput />
                </Form.Item>
              </Col>
            )}
          </Row>
          {protocol === 'MODBUS_TCP' && <ModbusTcpForm />}
          {protocol === 'MODBUS_RTU' && <ModbusRtuForm />}
          {protocol === 'OPC_UA' && <OpcUaForm />}
          {protocol === 'MQTT' && <MqttForm />}
          {protocol === 'VIRTUAL' && <VirtualForm />}
        </Form>
      </Drawer>
    </>
  );
}
