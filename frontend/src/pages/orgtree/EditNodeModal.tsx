import { Modal, Form, Input, Select, InputNumber, message } from 'antd';
import { useEffect } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import { allowedChildTypes, NodeType } from './nodeTypeRules';

export function EditNodeModal({
  open,
  node,
  parentNodeType,
  onClose,
}: {
  open: boolean;
  node: OrgNodeDTO | null;
  /** 当前节点在树中的父节点 nodeType；根节点传 null。用来软约束可选 type 列表。 */
  parentNodeType: string | null;
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

  // 编辑时按"父节点是谁"过滤可选 type；如果当前节点已是不合规组合（旧脏数据），
  // 把它原本的 type 兜底进选项里，避免下拉里看不到自己导致 UX 抓瞎。
  const allowed = allowedChildTypes(parentNodeType);
  const typeOptions: NodeType[] =
    node && !allowed.includes(node.nodeType as NodeType)
      ? [...allowed, node.nodeType as NodeType]
      : allowed;

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
        <Form.Item
          name="nodeType"
          label="节点类型"
          rules={[{ required: true }]}
          tooltip={
            parentNodeType
              ? `父节点是 ${parentNodeType}，仅允许同级或更细粒度的类型`
              : '根节点可任选类型'
          }
        >
          <Select options={typeOptions.map((t) => ({ label: t, value: t }))} />
        </Form.Item>
        <Form.Item name="sortOrder" label="排序">
          <InputNumber min={0} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
