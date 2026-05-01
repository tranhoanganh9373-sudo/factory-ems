import { Modal, Form, Input, Select, Switch, message } from 'antd';
import { useEffect } from 'react';
import { useMutation, useQueryClient, useQuery } from '@tanstack/react-query';
import { meterApi, MeterDTO, UpdateMeterReq } from '@/api/meter';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import { channelApi } from '@/api/channel';

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

export function EditMeterModal({
  open,
  meter,
  onClose,
}: {
  open: boolean;
  meter: MeterDTO | null;
  onClose: () => void;
}) {
  const [form] = Form.useForm();
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

  useEffect(() => {
    if (meter && open) {
      form.setFieldsValue({
        name: meter.name,
        energyTypeId: meter.energyTypeId,
        orgNodeId: meter.orgNodeId,
        influxMeasurement: meter.influxMeasurement,
        influxTagKey: meter.influxTagKey,
        influxTagValue: meter.influxTagValue,
        enabled: meter.enabled,
        channelId: meter.channelId,
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
      onOk={() => form.validateFields().then((v) => mut.mutate(v))}
      confirmLoading={mut.isPending}
      destroyOnClose
    >
      <Form form={form} layout="vertical">
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
        <Form.Item name="influxMeasurement" label="测量名称" rules={[{ required: true, max: 128 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="influxTagKey" label="标签键" rules={[{ required: true, max: 64 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="influxTagValue" label="标签值" rules={[{ required: true, max: 128 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="channelId" label="关联通道">
          <Select
            allowClear
            placeholder="可选；不绑则该测点不接收 collector 数据"
            options={channels
              .filter((c) => c.enabled)
              .map((c) => ({ value: c.id, label: `${c.name} (${c.protocol})` }))}
          />
        </Form.Item>
        <Form.Item name="enabled" label="启用" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}
