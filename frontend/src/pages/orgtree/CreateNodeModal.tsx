import { Alert, Modal, Form, Input, Select, InputNumber, message } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import { allowedChildTypes, isTerminal } from './nodeTypeRules';

export function CreateNodeModal({
  open,
  parent,
  onClose,
}: {
  open: boolean;
  parent: OrgNodeDTO | null;
  onClose: () => void;
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

  const parentBlocked = isTerminal(parent?.nodeType);
  const typeOptions = allowedChildTypes(parent?.nodeType ?? null);

  return (
    <Modal
      title={`新建节点${parent ? ' (父: ' + parent.name + ')' : ' (根节点)'}`}
      open={open}
      onCancel={onClose}
      onOk={() =>
        form.validateFields().then((v) =>
          mut.mutate({
            parentId: parent?.id ?? null,
            ...v,
          })
        )
      }
      confirmLoading={mut.isPending}
      okButtonProps={{ disabled: parentBlocked }}
      destroyOnClose
    >
      {parentBlocked && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={`"${parent?.name}" 是 OTHER 类型（终结叶子），不允许再添加子节点`}
        />
      )}
      <Form form={form} layout="vertical" disabled={parentBlocked}>
        <Form.Item name="name" label="名称" rules={[{ required: true, max: 128 }]}>
          <Input />
        </Form.Item>
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
        <Form.Item
          name="nodeType"
          label="节点类型"
          rules={[{ required: true }]}
          tooltip={
            parent ? `父节点是 ${parent.nodeType}，仅允许同级或更细粒度的类型` : '根节点可任选类型'
          }
        >
          <Select options={typeOptions.map((t) => ({ label: t, value: t }))} />
        </Form.Item>
        <Form.Item name="sortOrder" label="排序" initialValue={0}>
          <InputNumber min={0} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
