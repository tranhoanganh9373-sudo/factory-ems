import { Modal, Select, Button, Space, message } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { meterApi, MeterDTO, MeterTopologyEdgeDTO } from '@/api/meter';
import { useState, useEffect } from 'react';

/**
 * Computes the set of descendant meter IDs for a given meter using BFS over topology edges.
 * This prevents creating cycles when binding a parent.
 */
function getDescendantIds(meterId: number, edges: MeterTopologyEdgeDTO[]): Set<number> {
  const descendants = new Set<number>();
  const queue = [meterId];
  while (queue.length > 0) {
    const current = queue.shift()!;
    for (const edge of edges) {
      if (edge.parentMeterId === current && !descendants.has(edge.childMeterId)) {
        descendants.add(edge.childMeterId);
        queue.push(edge.childMeterId);
      }
    }
  }
  return descendants;
}

export function BindParentModal({
  open,
  meter,
  meters,
  topology,
  onClose,
}: {
  open: boolean;
  meter: MeterDTO | null;
  meters: MeterDTO[];
  topology: MeterTopologyEdgeDTO[];
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const [selectedParentId, setSelectedParentId] = useState<number | undefined>(undefined);

  useEffect(() => {
    if (open && meter) {
      setSelectedParentId(meter.parentMeterId ?? undefined);
    }
  }, [open, meter]);

  const bindMut = useMutation({
    mutationFn: ({ childId, parentId }: { childId: number; parentId: number }) =>
      meterApi.bindParent(childId, parentId),
    onSuccess: () => {
      message.success('已绑定父测点');
      qc.invalidateQueries({ queryKey: ['meters'] });
      qc.invalidateQueries({ queryKey: ['topology'] });
      onClose();
    },
  });

  const unbindMut = useMutation({
    mutationFn: (childId: number) => meterApi.unbindParent(childId),
    onSuccess: () => {
      message.success('已解绑父测点');
      qc.invalidateQueries({ queryKey: ['meters'] });
      qc.invalidateQueries({ queryKey: ['topology'] });
      onClose();
    },
  });

  if (!meter) return null;

  // Exclude self and all descendants to prevent cycles
  const excludeIds = getDescendantIds(meter.id, topology);
  excludeIds.add(meter.id);

  const options = meters
    .filter((m) => !excludeIds.has(m.id))
    .map((m) => ({ label: `${m.code} - ${m.name}`, value: m.id }));

  const hasExistingParent = meter.parentMeterId != null;

  return (
    <Modal
      title="绑定上级"
      open={open}
      onCancel={onClose}
      footer={
        <Space>
          <Button onClick={onClose}>取消</Button>
          {hasExistingParent && (
            <Button danger loading={unbindMut.isPending} onClick={() => unbindMut.mutate(meter.id)}>
              解绑
            </Button>
          )}
          <Button
            type="primary"
            disabled={selectedParentId == null}
            loading={bindMut.isPending}
            onClick={() => {
              if (selectedParentId != null) {
                bindMut.mutate({ childId: meter.id, parentId: selectedParentId });
              }
            }}
          >
            绑定
          </Button>
        </Space>
      }
      destroyOnClose
    >
      <Select
        style={{ width: '100%' }}
        placeholder="选择父测点"
        value={selectedParentId}
        onChange={setSelectedParentId}
        options={options}
        showSearch
        filterOption={(input, option) =>
          String(option?.label ?? '')
            .toLowerCase()
            .includes(input.toLowerCase())
        }
        allowClear
      />
    </Modal>
  );
}
