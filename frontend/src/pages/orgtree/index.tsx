import { useState } from 'react';
import { Card, Tree, Button, Space, Typography, Popconfirm, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, SwapOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import { CreateNodeModal } from './CreateNodeModal';
import { EditNodeModal } from './EditNodeModal';
import { MoveNodeModal } from './MoveNodeModal';
import { usePermissions } from '@/hooks/usePermissions';

interface DisplayNode { title: React.ReactNode; key: string; children?: DisplayNode[]; raw: OrgNodeDTO; }

export default function OrgTreePage() {
  const { isAdmin } = usePermissions();
  const qc = useQueryClient();
  const { data: tree = [] } = useQuery({ queryKey: ['orgtree'], queryFn: () => orgTreeApi.getTree() });

  const [selected, setSelected] = useState<OrgNodeDTO | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [moveOpen, setMoveOpen] = useState(false);

  const del = useMutation({
    mutationFn: (id: number) => orgTreeApi.delete(id),
    onSuccess: () => {
      message.success('已删除'); setSelected(null);
      qc.invalidateQueries({ queryKey: ['orgtree'] });
    },
  });

  const toTreeData = (nodes: OrgNodeDTO[]): DisplayNode[] =>
    nodes.map((n) => ({
      key: String(n.id),
      title: `${n.name} (${n.code}) [${n.nodeType}]`,
      children: toTreeData(n.children),
      raw: n,
    }));

  return (
    <Card title="组织树" extra={isAdmin && (
      <Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建节点
        </Button>
        <Button icon={<EditOutlined />} disabled={!selected} onClick={() => setEditOpen(true)}>编辑</Button>
        <Button icon={<SwapOutlined />} disabled={!selected} onClick={() => setMoveOpen(true)}>移动</Button>
        <Popconfirm title="确认删除？" disabled={!selected} onConfirm={() => selected && del.mutate(selected.id)}>
          <Button danger icon={<DeleteOutlined />} disabled={!selected}>删除</Button>
        </Popconfirm>
      </Space>
    )}>
      <Tree
        treeData={toTreeData(tree)}
        defaultExpandAll
        onSelect={(_, info) => setSelected((info.node as unknown as DisplayNode).raw)}
        selectedKeys={selected ? [String(selected.id)] : []}
      />
      {selected && (
        <Typography.Paragraph style={{ marginTop: 16 }}>
          当前选中：<b>{selected.name}</b> ({selected.code})
        </Typography.Paragraph>
      )}
      <CreateNodeModal open={createOpen} parent={selected} onClose={() => setCreateOpen(false)} />
      <EditNodeModal open={editOpen} node={selected} onClose={() => setEditOpen(false)} />
      <MoveNodeModal open={moveOpen} node={selected} tree={tree} onClose={() => setMoveOpen(false)} />
    </Card>
  );
}
