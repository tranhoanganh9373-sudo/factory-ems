import { Modal, Form, Input, Select, Switch, message, Typography } from 'antd';
import { useEffect } from 'react';
import { useMutation, useQueryClient, useQuery } from '@tanstack/react-query';
import {
  meterApi,
  MeterDTO,
  UpdateMeterReq,
  ValueKind,
  MeterRole,
  EnergySource,
  FlowDirection,
} from '@/api/meter';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import { channelApi } from '@/api/channel';

const VALUE_KIND_OPTIONS: { value: ValueKind; label: string }[] = [
  { value: 'INTERVAL_DELTA', label: '周期增量 (collector 上报已是增量)' },
  { value: 'CUMULATIVE_ENERGY', label: '累积电量 (如安科瑞 0x003F 寄存器)' },
  { value: 'INSTANT_POWER', label: '瞬时功率 (暂不支持区间合计)' },
];

const VALUE_KIND_TOOLTIP = (
  <div style={{ fontSize: 12, lineHeight: 1.65 }}>
    <div style={{ marginBottom: 4, fontWeight: 600 }}>周期增量 (INTERVAL_DELTA)</div>
    <ul style={{ margin: 0, paddingLeft: 18 }}>
      <li>含义：每次上报的就是该周期内的能耗增量</li>
      <li>合计算法：区间内所有点直接相加</li>
      <li>典型场景：脉冲计数、流量计、collector 已做过差分的协议</li>
    </ul>
    <div style={{ marginTop: 10, marginBottom: 4, fontWeight: 600 }}>
      累积电量 (CUMULATIVE_ENERGY)
    </div>
    <ul style={{ margin: 0, paddingLeft: 18 }}>
      <li>含义：寄存器是单调递增的总电量底数（odometer 式）</li>
      <li>合计算法：末点值 − 初点值</li>
      <li>典型场景：安科瑞 ACR 系列 0x003F (UINT32 总电能 kWh)、电表"电度"寄存器</li>
      <li style={{ color: '#faad14' }}>注意：区间内换表/计数器归零会得到负数或异常值</li>
    </ul>
    <div style={{ marginTop: 10, marginBottom: 4, fontWeight: 600 }}>瞬时功率 (INSTANT_POWER)</div>
    <ul style={{ margin: 0, paddingLeft: 18 }}>
      <li>含义：寄存器是当前瞬时值（W / kW）</li>
      <li style={{ color: '#ff4d4f' }}>暂不支持区间合计（需要时间积分）</li>
      <li>典型场景：安科瑞 0x0031 (INT16 瞬时功率)</li>
      <li>推荐：安科瑞电表请改读 0x003F (CUMULATIVE_ENERGY) 替代</li>
    </ul>
  </div>
);

interface PointInConfig {
  key: string;
  unit?: string;
}

function pointsOf(channel: { protocolConfig?: unknown } | undefined): PointInConfig[] {
  const cfg = channel?.protocolConfig as { points?: unknown[] } | undefined;
  if (!Array.isArray(cfg?.points)) return [];
  return cfg.points
    .map((p) => p as Record<string, unknown>)
    .filter((p) => typeof p.key === 'string' && p.key.length > 0)
    .map((p) => ({ key: p.key as string, unit: typeof p.unit === 'string' ? p.unit : undefined }));
}

function flattenTree(nodes: OrgNodeDTO[]): OrgNodeDTO[] {
  const result: OrgNodeDTO[] = [];
  function walk(list: OrgNodeDTO[]) {
    for (const n of list) {
      result.push(n);
      if (n.children?.length) walk(n.children);
    }
  }
  walk(nodes);
  return result;
}

interface FormValues {
  name: string;
  energyTypeId: number;
  orgNodeId: number;
  channelId?: number | null;
  channelPointKey?: string | null;
  enabled?: boolean;
  valueKind?: ValueKind;
  role?: MeterRole;
  energySource?: EnergySource;
  flowDirection?: FlowDirection;
}

export function EditMeterModal({
  open,
  meter,
  onClose,
}: {
  open: boolean;
  meter: MeterDTO | null;
  onClose: () => void;
}) {
  const [form] = Form.useForm<FormValues>();
  const qc = useQueryClient();

  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });

  const { data: energyTypes = [] } = useQuery({
    queryKey: ['energyTypes'],
    queryFn: () => meterApi.listEnergyTypes(),
  });

  const { data: channels = [] } = useQuery({
    queryKey: ['channel', 'list'],
    queryFn: () => channelApi.list(),
  });

  const channelId = Form.useWatch('channelId', form) as number | undefined;
  const { data: selectedChannel } = useQuery({
    enabled: channelId != null,
    queryKey: ['channel', 'detail', channelId],
    queryFn: () => channelApi.get(channelId as number),
  });
  const channelPoints = pointsOf(selectedChannel);

  useEffect(() => {
    if (meter && open) {
      form.setFieldsValue({
        name: meter.name,
        energyTypeId: meter.energyTypeId,
        orgNodeId: meter.orgNodeId,
        enabled: meter.enabled,
        channelId: meter.channelId,
        channelPointKey: meter.channelPointKey,
        valueKind: meter.valueKind ?? 'INTERVAL_DELTA',
        role: meter.role ?? 'CONSUME',
        energySource: meter.energySource ?? 'GRID',
        flowDirection: meter.flowDirection ?? 'IMPORT',
      });
    }
  }, [meter, open, form]);

  const mut = useMutation({
    mutationFn: (v: UpdateMeterReq) => meterApi.updateMeter(meter!.id, v),
    onSuccess: () => {
      message.success('已更新');
      qc.invalidateQueries({ queryKey: ['meters'] });
      onClose();
    },
  });

  return (
    <Modal
      title="编辑表计"
      open={open}
      onCancel={onClose}
      onOk={() =>
        form.validateFields().then((v) => {
          if (!meter) return;
          // 编辑时保留原 code，不再让用户改动；channelPointKey 在解绑通道时同步清空
          const channelPointKey = v.channelId != null ? (v.channelPointKey ?? null) : null;
          mut.mutate({
            code: meter.code,
            name: v.name,
            energyTypeId: v.energyTypeId,
            orgNodeId: v.orgNodeId,
            enabled: v.enabled ?? true,
            channelId: v.channelId ?? null,
            channelPointKey,
            valueKind: v.valueKind ?? 'INTERVAL_DELTA',
            role: v.role ?? 'CONSUME',
            energySource: v.energySource ?? 'GRID',
            flowDirection: v.flowDirection ?? 'IMPORT',
          });
        })
      }
      confirmLoading={mut.isPending}
      destroyOnClose
    >
      <Form form={form} layout="vertical">
        {meter && (
          <Form.Item label="编码">
            <Typography.Text type="secondary" copyable>
              {meter.code}
            </Typography.Text>
          </Form.Item>
        )}
        <Form.Item name="name" label="名称" rules={[{ required: true, max: 128 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="energyTypeId" label="能源类型" rules={[{ required: true }]}>
          <Select
            options={energyTypes.map((e) => ({
              label: `${e.name} (${e.code}, ${e.unit})`,
              value: e.id,
            }))}
          />
        </Form.Item>
        <Form.Item
          name="valueKind"
          label="量值类型"
          rules={[{ required: true }]}
          tooltip={{ title: VALUE_KIND_TOOLTIP, overlayStyle: { maxWidth: 420 } }}
        >
          <Select options={VALUE_KIND_OPTIONS} />
        </Form.Item>
        <Form.Item name="role" label="角色">
          <Select>
            <Select.Option value="CONSUME">纯耗电</Select.Option>
            <Select.Option value="GENERATE">光伏发电</Select.Option>
            <Select.Option value="GRID_TIE">并网点</Select.Option>
          </Select>
        </Form.Item>
        <Form.Item name="energySource" label="来源">
          <Select>
            <Select.Option value="GRID">电网</Select.Option>
            <Select.Option value="SOLAR">光伏</Select.Option>
            <Select.Option value="WIND">风电</Select.Option>
            <Select.Option value="STORAGE">储能</Select.Option>
          </Select>
        </Form.Item>
        <Form.Item name="flowDirection" label="方向">
          <Select>
            <Select.Option value="IMPORT">进口</Select.Option>
            <Select.Option value="EXPORT">出口</Select.Option>
          </Select>
        </Form.Item>
        <Form.Item name="orgNodeId" label="组织节点" rules={[{ required: true }]}>
          <Select
            showSearch
            filterOption={(input, option) =>
              String(option?.label ?? '')
                .toLowerCase()
                .includes(input.toLowerCase())
            }
            options={flattenTree(tree).map((n) => ({
              label: `${n.name} (${n.code})`,
              value: n.id,
            }))}
          />
        </Form.Item>
        <Form.Item name="channelId" label="关联通道">
          <Select
            allowClear
            placeholder="可选；不绑则该测点不接收 collector 数据"
            options={channels
              .filter((c) => c.enabled)
              .map((c) => ({ value: c.id, label: `${c.name} (${c.protocol})` }))}
            onChange={() => form.setFieldValue('channelPointKey', undefined)}
          />
        </Form.Item>
        {channelId != null && (
          <Form.Item
            name="channelPointKey"
            label="关联测点"
            rules={[{ required: true, message: '已选关联通道时，必须选择测点' }]}
            tooltip="采集器按 (通道, 测点) 把上报数据写入对应表计，与编码解耦"
          >
            <Select
              showSearch
              placeholder={
                channelPoints.length === 0
                  ? '该通道没有测点；请先去采集器补测点'
                  : '从通道点位中选一个'
              }
              options={channelPoints.map((p) => ({
                value: p.key,
                label: p.unit ? `${p.key}（${p.unit}）` : p.key,
              }))}
              disabled={channelPoints.length === 0}
            />
          </Form.Item>
        )}
        <Form.Item name="enabled" label="启用" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}
