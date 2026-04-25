import { Modal, Form, Input, Select, InputNumber, message } from 'antd';
import { useEffect } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';

const NODE_TYPES = ['PLANT', 'WORKSHOP', 'LINE', 'DEVICE', 'GROUP', 'OTHER'];

export function EditNodeModal({
  open,
  node,
  onClose,
}: {
  open: boolean;
  node: OrgNodeDTO | null;
  onClose: () => void;
}) {
  const [form] = Form.useForm();
  const qc = useQueryClient();
  useEffect(() => {
    if (node && open) form.setFieldsValue(node);
  }, [node, open, form]);
  const mut = useMutation({
    mutationFn: (v: { name: string; nodeType: string; sortOrder: number }) =>
      orgTreeApi.update(node!.id, v),
    onSuccess: () => {
      message.success('已更新');
      qc.invalidateQueries({ queryKey: ['orgtree'] });
      onClose();
    },
  });

  return (
    <Modal
      title={`编辑 ${node?.name ?? ''}`}
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
        <Form.Item name="nodeType" label="节点类型" rules={[{ required: true }]}>
          <Select options={NODE_TYPES.map((t) => ({ label: t, value: t }))} />
        </Form.Item>
        <Form.Item name="sortOrder" label="排序">
          <InputNumber min={0} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
