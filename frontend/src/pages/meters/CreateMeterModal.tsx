import { Modal, Form, Input, Select, Switch, message } from 'antd';
import { useMutation, useQueryClient, useQuery } from '@tanstack/react-query';
import { meterApi, CreateMeterReq } from '@/api/meter';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';

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

export function CreateMeterModal({ open, onClose }: { open: boolean; onClose: () => void }) {
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

  const mut = useMutation({
    mutationFn: (req: CreateMeterReq) => meterApi.createMeter(req),
    onSuccess: () => {
      message.success('已创建');
      qc.invalidateQueries({ queryKey: ['meters'] });
      form.resetFields();
      onClose();
    },
  });

  return (
    <Modal
      title="新增表计"
      open={open}
      onCancel={onClose}
      onOk={() =>
        form.validateFields().then((v) => mut.mutate({ ...v, enabled: v.enabled ?? true }))
      }
      confirmLoading={mut.isPending}
      destroyOnClose
    >
      <Form form={form} layout="vertical" initialValues={{ enabled: true }}>
        <Form.Item
          name="code"
          label="编码"
          rules={[
            { required: true, max: 64 },
            { pattern: /^[A-Za-z0-9_-]+$/, message: '只允许字母数字下划线横线' },
          ]}
        >
          <Input />
        </Form.Item>
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
        <Form.Item name="enabled" label="启用" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}
