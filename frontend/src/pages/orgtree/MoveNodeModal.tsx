import { Modal, TreeSelect, Alert, message } from 'antd';
import { useState, useMemo } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';

export function MoveNodeModal({ open, node, tree, onClose }: {
  open: boolean; node: OrgNodeDTO | null; tree: OrgNodeDTO[]; onClose: () => void;
}) {
  const [newParentId, setNewParentId] = useState<number | null>(null);
  const qc = useQueryClient();
  const mut = useMutation({
    mutationFn: () => orgTreeApi.move(node!.id, newParentId),
    onSuccess: () => {
      message.success('已移动');
      qc.invalidateQueries({ queryKey: ['orgtree'] });
      setNewParentId(null); onClose();
    },
  });

  // 排除节点自身及其子树
  const excludedIds = useMemo(() => {
    if (!node) return new Set<number>();
    const ids = new Set<number>();
    const walk = (n: OrgNodeDTO) => { ids.add(n.id); n.children.forEach(walk); };
    const findSelf = (arr: OrgNodeDTO[]): OrgNodeDTO | null => {
      for (const n of arr) {
        if (n.id === node.id) return n;
        const r = findSelf(n.children); if (r) return r;
      }
      return null;
    };
    const self = findSelf(tree);
    if (self) walk(self);
    return ids;
  }, [node, tree]);

  const toSelectData = (nodes: OrgNodeDTO[]): any[] =>
    nodes.map((n) => ({
      title: n.name, value: n.id,
      disabled: excludedIds.has(n.id),
      children: toSelectData(n.children),
    }));

  return (
    <Modal title={`移动 ${node?.name ?? ''}`} open={open} onCancel={onClose}
      onOk={() => mut.mutate()} confirmLoading={mut.isPending} destroyOnClose>
      <Alert type="info" showIcon message="选择新的父节点；选择"根"将成为顶级节点。" style={{ marginBottom: 16 }} />
      <TreeSelect style={{ width: '100%' }}
        placeholder="选择新父节点（空=根）"
        allowClear treeDefaultExpandAll
        treeData={toSelectData(tree)}
        value={newParentId ?? undefined}
        onChange={(v) => setNewParentId(v ?? null)}
      />
    </Modal>
  );
}
