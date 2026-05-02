import { useState } from 'react';
import { Card, Tree, Button, Space, Typography, Tooltip, Tag, Popconfirm, message } from 'antd';

const NODE_TYPE_COLOR: Record<string, string> = {
  PLANT: 'blue',
  WORKSHOP: 'cyan',
  LINE: 'green',
  EQUIPMENT: 'default',
};

function shortenCode(code: string, max = 16): string {
  if (code.length <= max) return code;
  return `${code.slice(0, max - 3)}…`;
}
import { PlusOutlined, EditOutlined, DeleteOutlined, SwapOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import { CreateNodeModal } from './CreateNodeModal';
import { EditNodeModal } from './EditNodeModal';
import { MoveNodeModal } from './MoveNodeModal';
import { usePermissions } from '@/hooks/usePermissions';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';

interface DisplayNode {
  title: React.ReactNode;
  key: string;
  children?: DisplayNode[];
  raw: OrgNodeDTO;
}

export default function OrgTreePage() {
  useDocumentTitle('组织树');
  const { isAdmin } = usePermissions();
  const qc = useQueryClient();
  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });

  const [selected, setSelected] = useState<OrgNodeDTO | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [moveOpen, setMoveOpen] = useState(false);

  const del = useMutation({
    mutationFn: (id: number) => orgTreeApi.delete(id),
    onSuccess: () => {
      message.success('已删除');
      setSelected(null);
      qc.invalidateQueries({ queryKey: ['orgtree'] });
    },
  });

  const toTreeData = (nodes: OrgNodeDTO[]): DisplayNode[] =>
    nodes.map((n) => {
      const codeShort = shortenCode(n.code);
      const tagColor = NODE_TYPE_COLOR[n.nodeType] ?? 'default';
      return {
        key: String(n.id),
        title: (
          <Space size={6}>
            <span>{n.name}</span>
            <Tooltip title={n.code}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {codeShort}
              </Typography.Text>
            </Tooltip>
            <Tag color={tagColor} style={{ margin: 0, fontSize: 11, lineHeight: '18px' }}>
              {n.nodeType}
            </Tag>
          </Space>
        ),
        children: toTreeData(n.children),
        raw: n,
      };
    });

  return (
    <>
      <PageHeader title="组织树" />
      <Card
        extra={
          isAdmin && (
            <Space>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
                新建节点
              </Button>
              <Button
                icon={<EditOutlined />}
                disabled={!selected}
                onClick={() => setEditOpen(true)}
              >
                编辑
              </Button>
              <Button
                icon={<SwapOutlined />}
                disabled={!selected}
                onClick={() => setMoveOpen(true)}
              >
                移动
              </Button>
              <Popconfirm
                title="确认删除？"
                disabled={!selected}
                onConfirm={() => selected && del.mutate(selected.id)}
              >
                <Button danger icon={<DeleteOutlined />} disabled={!selected}>
                  删除
                </Button>
              </Popconfirm>
            </Space>
          )
        }
      >
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
        <MoveNodeModal
          open={moveOpen}
          node={selected}
          tree={tree}
          onClose={() => setMoveOpen(false)}
        />
      </Card>
    </>
  );
}
