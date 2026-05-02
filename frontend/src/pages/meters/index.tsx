import { useState } from 'react';
import {
  Card,
  Table,
  Button,
  Space,
  Tag,
  Popconfirm,
  Select,
  Tabs,
  Tree,
  Empty,
  Spin,
  message,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ApartmentOutlined,
  ImportOutlined,
} from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import { meterApi, MeterDTO, MeterTopologyEdgeDTO } from '@/api/meter';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import { alarmApi } from '@/api/alarm';
import { usePermissions } from '@/hooks/usePermissions';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { StatusTag } from '@/components/StatusTag';
import { METER_STATE_LABEL, translate } from '@/utils/i18n-dict';
import { CreateMeterModal } from './CreateMeterModal';
import { EditMeterModal } from './EditMeterModal';
import { BindParentModal } from './BindParentModal';
import { MeterBatchImportModal } from './MeterBatchImportModal';

// Flatten org tree to a map of id -> name
function buildOrgMap(nodes: OrgNodeDTO[]): Map<number, string> {
  const map = new Map<number, string>();
  function walk(list: OrgNodeDTO[]) {
    for (const n of list) {
      map.set(n.id, n.name);
      if (n.children?.length) walk(n.children);
    }
  }
  walk(nodes);
  return map;
}

// Build a tree structure from topology edges for the topology tab
interface TopologyNode {
  key: string;
  title: string;
  children: TopologyNode[];
}

function buildTopologyTree(meters: MeterDTO[], edges: MeterTopologyEdgeDTO[]): TopologyNode[] {
  const meterMap = new Map(meters.map((m) => [m.id, m]));
  const childIds = new Set(edges.map((e) => e.childMeterId));

  // Root meters: those not appearing as a child in any edge
  const roots = meters.filter((m) => !childIds.has(m.id));

  function buildNode(meter: MeterDTO): TopologyNode {
    const childEdges = edges.filter((e) => e.parentMeterId === meter.id);
    return {
      key: String(meter.id),
      title: `${meter.code} — ${meter.name}`,
      children: childEdges
        .map((e) => meterMap.get(e.childMeterId))
        .filter((m): m is MeterDTO => m != null)
        .map(buildNode),
    };
  }

  return roots.map(buildNode);
}

export default function MetersPage() {
  useDocumentTitle('表计管理');
  const { isAdmin } = usePermissions();
  const qc = useQueryClient();

  const [orgNodeId, setOrgNodeId] = useState<number | undefined>(undefined);
  const [energyTypeId, setEnergyTypeId] = useState<number | undefined>(undefined);
  const [enabledFilter, setEnabledFilter] = useState<boolean | undefined>(undefined);

  const [createOpen, setCreateOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [bindOpen, setBindOpen] = useState(false);
  const [batchOpen, setBatchOpen] = useState(false);
  const [selectedMeter, setSelectedMeter] = useState<MeterDTO | null>(null);

  const {
    data: meters = [],
    isLoading: metersLoading,
    isError: metersError,
  } = useQuery({
    queryKey: ['meters', { orgNodeId, energyTypeId, enabled: enabledFilter }],
    queryFn: () =>
      meterApi.listMeters({
        orgNodeId,
        energyTypeId,
        enabled: enabledFilter,
      }),
  });

  const { data: topology = [] } = useQuery({
    queryKey: ['topology'],
    queryFn: () => meterApi.listTopology(),
  });

  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });

  const { data: energyTypes = [] } = useQuery({
    queryKey: ['energyTypes'],
    queryFn: () => meterApi.listEnergyTypes(),
  });

  const { data: activeAlarmsPage } = useQuery({
    queryKey: ['alarms', 'active', 'all'],
    queryFn: () => alarmApi.list({ status: 'ACTIVE', page: 1, size: 500 }),
    refetchInterval: 30_000,
  });

  const activeAlarmDeviceIds = new Set<number>(
    (activeAlarmsPage?.items ?? []).map((a) => a.deviceId)
  );

  const orgMap = buildOrgMap(tree);

  const delMut = useMutation({
    mutationFn: (id: number) => meterApi.deleteMeter(id),
    onSuccess: () => {
      message.success('已删除');
      qc.invalidateQueries({ queryKey: ['meters'] });
      qc.invalidateQueries({ queryKey: ['topology'] });
    },
  });

  // Flatten org tree for filter select
  const orgOptions: { label: string; value: number }[] = [];
  function collectOrg(nodes: OrgNodeDTO[]) {
    for (const n of nodes) {
      orgOptions.push({ label: `${n.name} (${n.code})`, value: n.id });
      if (n.children?.length) collectOrg(n.children);
    }
  }
  collectOrg(tree);

  const columns: ColumnsType<MeterDTO> = [
    { title: '编码', dataIndex: 'code', key: 'code', width: 140 },
    { title: '名称', dataIndex: 'name', key: 'name', width: 160 },
    {
      title: '能源类型',
      key: 'energyType',
      width: 140,
      render: (_, r) => `${r.energyTypeCode} (${r.unit})`,
    },
    {
      title: '组织节点',
      key: 'orgNode',
      width: 160,
      render: (_, r) => orgMap.get(r.orgNodeId) ?? String(r.orgNodeId),
    },
    {
      title: '状态',
      key: 'enabled',
      width: 80,
      render: (_, r) => {
        const s = r.enabled ? 'ACTIVE' : 'INACTIVE';
        return (
          <StatusTag tone={r.enabled ? 'success' : 'default'}>
            {translate(METER_STATE_LABEL, s)}
          </StatusTag>
        );
      },
    },
    {
      title: '告警',
      key: 'alarmStatus',
      width: 90,
      render: (_, r) => {
        if (!r.enabled) return <Tag color="default">未启用</Tag>;
        if (activeAlarmDeviceIds.has(r.id)) return <Tag color="error">告警中</Tag>;
        return <Tag color="success">正常</Tag>;
      },
    },
    {
      title: '父测点',
      key: 'parentMeter',
      width: 120,
      render: (_, r) => {
        if (!r.parentMeterId) return '—';
        const edge = topology.find((e) => e.childMeterId === r.id);
        return edge ? edge.parentMeterCode : String(r.parentMeterId);
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 180,
      render: (_, r) => (
        <Space>
          {isAdmin && (
            <>
              <Button
                size="small"
                icon={<EditOutlined />}
                onClick={() => {
                  setSelectedMeter(r);
                  setEditOpen(true);
                }}
              >
                编辑
              </Button>
              <Button
                size="small"
                icon={<ApartmentOutlined />}
                onClick={() => {
                  setSelectedMeter(r);
                  setBindOpen(true);
                }}
              >
                绑父
              </Button>
              <Popconfirm title="确认删除？" onConfirm={() => delMut.mutate(r.id)}>
                <Button size="small" danger icon={<DeleteOutlined />}>
                  删除
                </Button>
              </Popconfirm>
            </>
          )}
        </Space>
      ),
    },
  ];

  const topologyTree = buildTopologyTree(meters, topology);

  const listTab = (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <Select
          allowClear
          placeholder="组织节点"
          style={{ width: 200 }}
          options={orgOptions}
          value={orgNodeId}
          onChange={setOrgNodeId}
          showSearch
          filterOption={(input, option) =>
            String(option?.label ?? '')
              .toLowerCase()
              .includes(input.toLowerCase())
          }
        />
        <Select
          allowClear
          placeholder="能源类型"
          style={{ width: 160 }}
          options={energyTypes.map((e) => ({ label: e.name, value: e.id }))}
          value={energyTypeId}
          onChange={setEnergyTypeId}
        />
        <Select
          allowClear
          placeholder="启用状态"
          style={{ width: 120 }}
          options={[
            { label: '启用', value: true },
            { label: '禁用', value: false },
          ]}
          value={enabledFilter}
          onChange={setEnabledFilter}
        />
      </Space>
      {metersError ? (
        <Empty description="加载失败" />
      ) : (
        <Spin spinning={metersLoading}>
          <Table
            rowKey="id"
            columns={columns}
            dataSource={meters}
            pagination={{ pageSize: 20, showSizeChanger: false }}
            size="middle"
          />
        </Spin>
      )}
    </>
  );

  const topologyTab = (
    <>
      {topologyTree.length === 0 ? (
        <Empty description="暂无拓扑数据" />
      ) : (
        <Tree treeData={topologyTree} defaultExpandAll />
      )}
    </>
  );

  return (
    <Card>
      <PageHeader
        title="表计管理"
        extra={
          isAdmin && (
            <Space>
              <Button icon={<ImportOutlined />} onClick={() => setBatchOpen(true)}>
                批量导入
              </Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
                新建测点
              </Button>
            </Space>
          )
        }
      />
      <Tabs
        items={[
          { key: 'list', label: '列表', children: listTab },
          { key: 'topology', label: '拓扑', children: topologyTab },
        ]}
      />
      <CreateMeterModal open={createOpen} onClose={() => setCreateOpen(false)} />
      <EditMeterModal open={editOpen} meter={selectedMeter} onClose={() => setEditOpen(false)} />
      <BindParentModal
        open={bindOpen}
        meter={selectedMeter}
        meters={meters}
        topology={topology}
        onClose={() => setBindOpen(false)}
      />
      <MeterBatchImportModal open={batchOpen} onClose={() => setBatchOpen(false)} />
    </Card>
  );
}
