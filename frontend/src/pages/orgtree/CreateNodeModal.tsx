import { Modal, Form, Input, Select, InputNumber, message } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';

const NODE_TYPES = ['PLANT', 'WORKSHOP', 'LINE', 'DEVICE', 'GROUP', 'OTHER'];

export function CreateNodeModal({ open, parent, onClose }: {
  open: boolean; parent: OrgNodeDTO | null; onClose: () => void;
}) {
  const [form] = Form.useForm();
  const qc = useQueryClient();
  const mut = useMutation({
    mutationFn: orgTreeApi.create,
    onSuccess: () => {
      message.success('已创建');
      qc.invalidateQueries({ queryKey: ['orgtree'] });
      form.resetFields();
      onClose();
    },
  });

  return (
    <Modal
      title={`新建节点${parent ? ' (父: ' + parent.name + ')' : ' (根节点)'}`}
      open={open} onCancel={onClose}
      onOk={() => form.validateFields().then((v) => mut.mutate({
        parentId: parent?.id ?? null, ...v,
      }))}
      confirmLoading={mut.isPending} destroyOnClose
    >
      <Form form={form} layout="vertical">
        <Form.Item name="name" label="名称" rules={[{ required: true, max: 128 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="code" label="编码" rules={[
          { required: true, max: 64 },
          { pattern: /^[A-Za-z0-9_-]+$/, message: '只允许字母数字下划线横线' },
        ]}>
          <Input />
        </Form.Item>
        <Form.Item name="nodeType" label="节点类型" rules={[{ required: true }]}>
          <Select options={NODE_TYPES.map((t) => ({ label: t, value: t }))} />
        </Form.Item>
        <Form.Item name="sortOrder" label="排序" initialValue={0}>
          <InputNumber min={0} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
