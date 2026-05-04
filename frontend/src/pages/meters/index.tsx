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
  Tooltip,
  message,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ApartmentOutlined,
  ImportOutlined,
  ExportOutlined,
} from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import Papa from 'papaparse';
import { meterApi, MeterDTO, MeterTopologyEdgeDTO, MeterRole, EnergySource, FlowDirection } from '@/api/meter';
import { channelApi } from '@/api/channel';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import { alarmApi, type MeterOnlineState } from '@/api/alarm';
import { usePermissions } from '@/hooks/usePermissions';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { HELP_METERS } from '@/components/pageHelp';
import { StatusTag } from '@/components/StatusTag';
import { CreateMeterModal } from './CreateMeterModal';
import { EditMeterModal } from './EditMeterModal';
import { BindParentModal } from './BindParentModal';
import { MeterBatchImportModal } from './MeterBatchImportModal';
import { showTotal } from '@/utils/format';

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

const ROLE_LABEL: Record<MeterRole, string> = {
  CONSUME: '纯耗电',
  GENERATE: '光伏发电',
  GRID_TIE: '并网点',
};
const SOURCE_LABEL: Record<EnergySource, string> = {
  GRID: '电网',
  SOLAR: '光伏',
  WIND: '风电',
  STORAGE: '储能',
};
const DIR_LABEL: Record<FlowDirection, string> = {
  IMPORT: '进口',
  EXPORT: '出口',
};

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
    // 后端 size 上限 200（C2+C3 校验加固后）；活动报警一般 << 200，超出的边界场景看板分页打开。
    queryFn: () => alarmApi.list({ status: 'ACTIVE', page: 1, size: 200 }),
    refetchInterval: 30_000,
  });

  // 真实在线状态：与「报警健康」同口径，按 meter id 维度返回 ONLINE/OFFLINE/MAINTENANCE。
  // 后端基于 ring.lastGoodSampleAt + freshness 5min 计算；维护中独立分支。
  // 30s 刷新与 health 卡同步，避免两处数字"瞬时不一致"造成误解。
  const { data: meterStatusMap } = useQuery({
    queryKey: ['alarms', 'meter-status'],
    queryFn: () => alarmApi.meterStatus(),
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

  // 批量导出：用 papaparse 在浏览器构建 CSV，避免后端跨模块去 channel 表查 name 映射。
  // 列与 backend MeterCsvParser 必填表头保持一致，导出文件可直接喂回批量导入。
  const handleExport = async () => {
    if (meters.length === 0) {
      message.info('暂无测点可导出');
      return;
    }
    let channelNameById = new Map<number, string>();
    try {
      const channels = await channelApi.list();
      channelNameById = new Map(channels.map((c) => [c.id, c.name]));
    } catch {
      // channel 列表取不到不致命——channelName 列留空，导入时会绑不上 channel
      message.warning('未能获取通道列表，channelName 列将为空');
    }
    const csv = Papa.unparse({
      fields: [
        'code',
        'name',
        'energyTypeId',
        'orgNodeId',
        'enabled',
        'channelName',
        'channelPointKey',
        'role',
        'energySource',
        'flowDirection',
      ],
      data: meters.map((m) => [
        m.code,
        m.name,
        m.energyTypeId,
        m.orgNodeId,
        m.enabled ? 'true' : 'false',
        m.channelId != null ? (channelNameById.get(m.channelId) ?? '') : '',
        m.channelPointKey ?? '',
        m.role ?? '',
        m.energySource ?? '',
        m.flowDirection ?? '',
      ]),
    });
    // Excel 打开 UTF-8 CSV 默认按 GBK 解码会乱码，加 BOM 提示编码
    const blob = new Blob(['﻿', csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `meters-export-${dayjs().format('YYYYMMDD-HHmmss')}.csv`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    message.success(`已导出 ${meters.length} 条测点`);
  };

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
      title: '量值类型',
      key: 'valueKind',
      width: 110,
      render: (_, r) => {
        const label =
          r.valueKind === 'CUMULATIVE_ENERGY'
            ? '累积电量'
            : r.valueKind === 'INSTANT_POWER'
              ? '瞬时功率'
              : '周期增量';
        const color =
          r.valueKind === 'CUMULATIVE_ENERGY'
            ? 'blue'
            : r.valueKind === 'INSTANT_POWER'
              ? 'orange'
              : 'default';
        return <Tag color={color}>{label}</Tag>;
      },
    },
    {
      title: '角色',
      dataIndex: 'role',
      key: 'role',
      width: 90,
      render: (v: MeterRole) => ROLE_LABEL[v] ?? v,
    },
    {
      title: '来源',
      dataIndex: 'energySource',
      key: 'energySource',
      width: 80,
      render: (v: EnergySource) => SOURCE_LABEL[v] ?? v,
    },
    {
      title: '方向',
      dataIndex: 'flowDirection',
      key: 'flowDirection',
      width: 70,
      render: (v: FlowDirection) => DIR_LABEL[v] ?? v,
    },
    {
      title: '组织节点',
      key: 'orgNode',
      width: 160,
      render: (_, r) => orgMap.get(r.orgNodeId) ?? String(r.orgNodeId),
    },
    {
      title: '采集状态',
      key: 'collectionState',
      width: 100,
      render: (_, r) => {
        if (!r.enabled) return <StatusTag tone="default">未启用</StatusTag>;
        const state: MeterOnlineState | undefined = meterStatusMap?.[r.id];
        if (state === 'MAINTENANCE') return <StatusTag tone="warning">维护中</StatusTag>;
        if (state === 'OFFLINE') return <StatusTag tone="error">离线</StatusTag>;
        if (state === 'ONLINE') return <StatusTag tone="success">在线</StatusTag>;
        // 后端首拉未到达：先按"加载中"显示，避免误判为离线。
        return <StatusTag tone="default">—</StatusTag>;
      },
    },
    {
      title: '报警',
      key: 'alarmStatus',
      width: 90,
      render: (_, r) => {
        if (!r.enabled) return <Tag color="default">未启用</Tag>;
        if (activeAlarmDeviceIds.has(r.id)) return <Tag color="error">报警中</Tag>;
        return <Tag color="success">正常</Tag>;
      },
    },
    {
      title: '父测点',
      key: 'parentMeter',
      width: 120,
      render: (_, r) => {
        if (!r.parentMeterId) return '—';
        // 后端 /meter-topology 只回 (childId, parentId)，不带 code；直接用 meters 列表按 id 查
        const parent = meters.find((m) => m.id === r.parentMeterId);
        return parent?.code ?? String(r.parentMeterId);
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, r) => (
        <Space size={4}>
          {isAdmin && (
            <>
              <Tooltip title="编辑">
                <Button
                  type="text"
                  size="small"
                  aria-label="编辑测点"
                  icon={<EditOutlined />}
                  onClick={() => {
                    setSelectedMeter(r);
                    setEditOpen(true);
                  }}
                />
              </Tooltip>
              <Tooltip title="绑定父测点">
                <Button
                  type="text"
                  size="small"
                  aria-label="绑定父测点"
                  icon={<ApartmentOutlined />}
                  onClick={() => {
                    setSelectedMeter(r);
                    setBindOpen(true);
                  }}
                />
              </Tooltip>
              <Popconfirm title="确认删除？" onConfirm={() => delMut.mutate(r.id)}>
                <Tooltip title="删除">
                  <Button
                    type="text"
                    size="small"
                    danger
                    aria-label="删除测点"
                    icon={<DeleteOutlined />}
                  />
                </Tooltip>
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
        <Table
          rowKey="id"
          columns={columns}
          dataSource={meters}
          loading={metersLoading}
          pagination={{ pageSize: 20, showSizeChanger: false, showTotal }}
          size="small"
          scroll={{ x: 'max-content' }}
        />
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
        helpContent={HELP_METERS}
        extra={
          isAdmin && (
            <Space>
              <Button
                icon={<ExportOutlined />}
                onClick={handleExport}
                disabled={meters.length === 0}
              >
                批量导出
              </Button>
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
