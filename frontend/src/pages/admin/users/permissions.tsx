import {
  Card,
  Table,
  Button,
  Space,
  Popconfirm,
  message,
  Modal,
  TreeSelect,
  Select,
  Form,
} from 'antd';
import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { PlusOutlined } from '@ant-design/icons';
import { permissionApi, NodePermissionDTO } from '@/api/permission';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import { userApi } from '@/api/user';

export default function UserPermissionPage() {
  const { id } = useParams();
  const userId = Number(id);
  const nav = useNavigate();
  const qc = useQueryClient();
  const [addOpen, setAddOpen] = useState(false);
  const [form] = Form.useForm();

  const { data: user } = useQuery({
    queryKey: ['user', userId],
    queryFn: () => userApi.getById(userId),
  });
  const { data: perms = [] } = useQuery({
    queryKey: ['perms', userId],
    queryFn: () => permissionApi.listByUser(userId),
  });
  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });

  const assignMut = useMutation({
    mutationFn: (v: { orgNodeId: number; scope: 'SUBTREE' | 'NODE_ONLY' }) =>
      permissionApi.assign(userId, v.orgNodeId, v.scope),
    onSuccess: () => {
      message.success('已授权');
      qc.invalidateQueries({ queryKey: ['perms', userId] });
      setAddOpen(false);
      form.resetFields();
    },
  });
  const revokeMut = useMutation({
    mutationFn: (permId: number) => permissionApi.revoke(userId, permId),
    onSuccess: () => {
      message.success('已撤销');
      qc.invalidateQueries({ queryKey: ['perms', userId] });
    },
  });

  const nodeById = (nid: number, nodes: OrgNodeDTO[]): OrgNodeDTO | null => {
    for (const n of nodes) {
      if (n.id === nid) return n;
      const r = nodeById(nid, n.children);
      if (r) return r;
    }
    return null;
  };
  interface SelectNode {
    title: string;
    value: number;
    children: SelectNode[];
  }
  const toSelectData = (nodes: OrgNodeDTO[]): SelectNode[] =>
    nodes.map((n) => ({ title: n.name, value: n.id, children: toSelectData(n.children) }));

  return (
    <Card
      title={`${user?.username ?? ''} 的节点权限`}
      extra={
        <Space>
          <Button onClick={() => nav(-1)}>返回</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setAddOpen(true)}>
            授权节点
          </Button>
        </Space>
      }
    >
      <Table<NodePermissionDTO>
        rowKey="id"
        dataSource={perms}
        columns={[
          {
            title: '组织节点',
            dataIndex: 'orgNodeId',
            render: (nid) => {
              const n = nodeById(nid, tree);
              return n ? `${n.name} (${n.code})` : nid;
            },
          },
          {
            title: '范围',
            dataIndex: 'scope',
            render: (s) => (s === 'SUBTREE' ? '子树（含后代）' : '仅此节点'),
          },
          { title: '授予时间', dataIndex: 'createdAt' },
          {
            title: '操作',
            key: 'ops',
            render: (_, p) => (
              <Popconfirm title="确认撤销？" onConfirm={() => revokeMut.mutate(p.id)}>
                <Button danger size="small">
                  撤销
                </Button>
              </Popconfirm>
            ),
          },
        ]}
      />

      <Modal
        title="授权节点"
        open={addOpen}
        onCancel={() => setAddOpen(false)}
        destroyOnClose
        onOk={() => form.validateFields().then((v) => assignMut.mutate(v))}
        confirmLoading={assignMut.isPending}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="orgNodeId" label="组织节点" rules={[{ required: true }]}>
            <TreeSelect
              treeData={toSelectData(tree)}
              treeDefaultExpandAll
              showSearch
              treeNodeFilterProp="title"
              allowClear
            />
          </Form.Item>
          <Form.Item name="scope" label="范围" rules={[{ required: true }]} initialValue="SUBTREE">
            <Select
              options={[
                { label: '子树（含所有后代）', value: 'SUBTREE' },
                { label: '仅此节点', value: 'NODE_ONLY' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
