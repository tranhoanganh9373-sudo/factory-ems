import { useMemo, useState } from 'react';
import { Card, Table, Select, TreeSelect, Space, Empty, Tag, Button, App } from 'antd';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { DownloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { billsApi, type BillDTO, type BillPeriodStatus } from '@/api/bills';
import { type EnergyTypeCode } from '@/api/cost';
import { orgTreeApi, type OrgNodeDTO } from '@/api/orgtree';
import { submitExport, pollExport, downloadBlob } from '@/api/reportPreset';

const PERIOD_STATUS_COLOR: Record<BillPeriodStatus, string> = {
  OPEN: 'default',
  CLOSED: 'success',
  LOCKED: 'red',
};

function buildTreeData(nodes: OrgNodeDTO[]): object[] {
  return nodes.map((n) => ({
    title: `${n.name} (${n.code})`,
    value: n.id,
    key: n.id,
    children: n.children?.length ? buildTreeData(n.children) : undefined,
  }));
}

function flattenOrgs(nodes: OrgNodeDTO[]): Map<number, string> {
  const map = new Map<number, string>();
  const walk = (ns: OrgNodeDTO[]) => {
    for (const n of ns) {
      map.set(n.id, n.name);
      if (n.children?.length) walk(n.children);
    }
  };
  walk(nodes);
  return map;
}

function fmtNum(v: string | null): string {
  if (v == null) return '—';
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(2) : v;
}

export default function BillsListPage() {
  const { message } = App.useApp();
  const [periodId, setPeriodId] = useState<number | undefined>();
  const [orgNodeId, setOrgNodeId] = useState<number | undefined>();
  const [energyType, setEnergyType] = useState<EnergyTypeCode | undefined>();
  const [exporting, setExporting] = useState(false);

  const { data: periods = [] } = useQuery({
    queryKey: ['bill', 'periods'],
    queryFn: billsApi.listPeriods,
  });

  const { data: orgTree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });

  const orgNameById = useMemo(() => flattenOrgs(orgTree), [orgTree]);

  const { data: bills = [], isLoading } = useQuery({
    queryKey: ['bills', periodId, orgNodeId, energyType],
    queryFn: () =>
      billsApi.listBills({
        periodId: periodId!,
        orgNodeId,
        energyType,
      }),
    enabled: periodId != null,
  });

  const selectedPeriod = periods.find((p) => p.id === periodId);

  async function handleExport() {
    if (!selectedPeriod) {
      message.warning('请先选择账期');
      return;
    }
    setExporting(true);
    try {
      const submitted = await submitExport({
        preset: 'COST_MONTHLY',
        format: 'EXCEL',
        yearMonth: selectedPeriod.yearMonth,
        orgNodeId,
      });
      if (!submitted) {
        message.error('导出端点 404');
        return;
      }
      const token = submitted.token;
      const filename = submitted.filename || `cost-monthly-${selectedPeriod.yearMonth}.xlsx`;
      for (let i = 0; i < 80; i++) {
        await new Promise((r) => setTimeout(r, 1500));
        const r = await pollExport(token);
        if (!r) {
          message.error('导出失败：token 失效');
          return;
        }
        if (r instanceof Blob) {
          downloadBlob(r, filename);
          message.success('导出完成');
          return;
        }
        if (r.status === 'FAILED') {
          message.error(`导出失败：${r.error ?? 'unknown'}`);
          return;
        }
      }
      message.warning('导出超时');
    } finally {
      setExporting(false);
    }
  }

  return (
    <Card
      title="账单"
      extra={
        <Button
          type="primary"
          icon={<DownloadOutlined />}
          loading={exporting}
          disabled={!periodId}
          onClick={handleExport}
        >
          导出 Excel (COST_MONTHLY)
        </Button>
      }
    >
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          placeholder="选择账期"
          style={{ width: 240 }}
          value={periodId}
          onChange={setPeriodId}
          options={periods.map((p) => ({
            value: p.id,
            label: (
              <span>
                {p.yearMonth} <Tag color={PERIOD_STATUS_COLOR[p.status]}>{p.status}</Tag>
              </span>
            ),
          }))}
        />
        <TreeSelect
          allowClear
          placeholder="组织过滤"
          treeData={buildTreeData(orgTree)}
          treeDefaultExpandAll
          style={{ width: 220 }}
          value={orgNodeId}
          onChange={setOrgNodeId}
        />
        <Select
          allowClear
          placeholder="能源过滤"
          style={{ width: 160 }}
          value={energyType}
          onChange={setEnergyType}
          options={[
            { value: 'ELEC', label: '电' },
            { value: 'WATER', label: '水' },
            { value: 'GAS', label: '气' },
            { value: 'STEAM', label: '汽' },
            { value: 'OIL', label: '油' },
          ]}
        />
      </Space>

      {!periodId ? (
        <Empty description="选一个账期" />
      ) : bills.length === 0 && !isLoading ? (
        <Empty description="该账期暂无账单（账期未关或无数据）" />
      ) : (
        <Table<BillDTO>
          rowKey="id"
          loading={isLoading}
          dataSource={bills}
          pagination={{ pageSize: 50 }}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: 'ID',
              dataIndex: 'id',
              width: 80,
              render: (id: number) => <Link to={`/bills/${id}`}>#{id}</Link>,
            },
            {
              title: '组织',
              dataIndex: 'orgNodeId',
              width: 180,
              render: (id: number) => orgNameById.get(id) ?? `#${id}`,
            },
            { title: '能源', dataIndex: 'energyType', width: 80 },
            {
              title: '用量',
              dataIndex: 'quantity',
              width: 100,
              render: fmtNum,
            },
            { title: '尖', dataIndex: 'sharpAmount', width: 90, render: fmtNum },
            { title: '峰', dataIndex: 'peakAmount', width: 90, render: fmtNum },
            { title: '平', dataIndex: 'flatAmount', width: 90, render: fmtNum },
            { title: '谷', dataIndex: 'valleyAmount', width: 90, render: fmtNum },
            { title: '金额', dataIndex: 'amount', width: 100, render: fmtNum },
            {
              title: '产量',
              dataIndex: 'productionQty',
              width: 100,
              render: (v: string | null) => (v == null ? '—' : fmtNum(v)),
            },
            {
              title: '单位成本',
              dataIndex: 'unitCost',
              width: 110,
              render: (v: string | null) => (v == null ? '—' : Number(v).toFixed(4)),
            },
            {
              title: '更新',
              dataIndex: 'updatedAt',
              width: 160,
              render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
            },
          ]}
        />
      )}
    </Card>
  );
}
