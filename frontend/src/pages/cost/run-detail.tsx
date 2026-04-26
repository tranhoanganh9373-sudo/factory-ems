import { useMemo, useState } from 'react';
import { Card, Table, Tag, Descriptions, Space, Select, TreeSelect, Empty } from 'antd';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { costApi, type CostLineDTO, type EnergyTypeCode, type RunStatus } from '@/api/cost';
import { orgTreeApi, type OrgNodeDTO } from '@/api/orgtree';

const STATUS_COLOR: Record<RunStatus, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  SUCCESS: 'success',
  FAILED: 'error',
  SUPERSEDED: 'warning',
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

export default function CostRunDetailPage() {
  const { id } = useParams<{ id: string }>();
  const runId = Number(id);
  const [filterOrg, setFilterOrg] = useState<number | undefined>();
  const [filterEnergy, setFilterEnergy] = useState<EnergyTypeCode | undefined>();

  const { data: run } = useQuery({
    queryKey: ['cost', 'run', runId],
    queryFn: () => costApi.getRun(runId),
    enabled: !Number.isNaN(runId),
  });

  const { data: lines = [], isLoading } = useQuery({
    queryKey: ['cost', 'run', runId, 'lines', filterOrg],
    queryFn: () => costApi.getRunLines(runId, filterOrg),
    enabled: !Number.isNaN(runId),
  });

  const { data: orgTree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });

  const orgNameById = useMemo(() => flattenOrgs(orgTree), [orgTree]);

  const filteredLines = useMemo(
    () => (filterEnergy ? lines.filter((l) => l.energyType === filterEnergy) : lines),
    [lines, filterEnergy]
  );

  return (
    <Card title={`分摊批次 #${runId}`}>
      {run && (
        <Descriptions size="small" column={3} bordered style={{ marginBottom: 16 }}>
          <Descriptions.Item label="状态">
            <Tag color={STATUS_COLOR[run.status]}>{run.status}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="账期">
            {dayjs(run.periodStart).format('YYYY-MM-DD HH:mm')} ~{' '}
            {dayjs(run.periodEnd).format('MM-DD HH:mm')}
          </Descriptions.Item>
          <Descriptions.Item label="总金额">{run.totalAmount ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="提交时间">
            {dayjs(run.createdAt).format('YYYY-MM-DD HH:mm:ss')}
          </Descriptions.Item>
          <Descriptions.Item label="完成时间">
            {run.finishedAt ? dayjs(run.finishedAt).format('YYYY-MM-DD HH:mm:ss') : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="错误">{run.errorMessage ?? '—'}</Descriptions.Item>
        </Descriptions>
      )}

      <Space style={{ marginBottom: 16 }}>
        <TreeSelect
          allowClear
          placeholder="按组织过滤"
          treeData={buildTreeData(orgTree)}
          treeDefaultExpandAll
          style={{ width: 220 }}
          value={filterOrg}
          onChange={setFilterOrg}
        />
        <Select
          allowClear
          placeholder="按能源过滤"
          style={{ width: 180 }}
          value={filterEnergy}
          onChange={setFilterEnergy}
          options={[
            { value: 'ELEC', label: '电' },
            { value: 'WATER', label: '水' },
            { value: 'GAS', label: '气' },
            { value: 'STEAM', label: '汽' },
            { value: 'OIL', label: '油' },
          ]}
        />
      </Space>

      {filteredLines.length === 0 && !isLoading ? (
        <Empty description="暂无明细" />
      ) : (
        <Table<CostLineDTO>
          rowKey="id"
          loading={isLoading}
          dataSource={filteredLines}
          pagination={{ pageSize: 50 }}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: '组织',
              dataIndex: 'targetOrgId',
              width: 180,
              render: (id: number) => orgNameById.get(id) ?? `#${id}`,
            },
            { title: '能源', dataIndex: 'energyType', width: 80 },
            { title: '用量', dataIndex: 'quantity', width: 110 },
            { title: '尖电费', dataIndex: 'sharpAmount', width: 100 },
            { title: '峰电费', dataIndex: 'peakAmount', width: 100 },
            { title: '平电费', dataIndex: 'flatAmount', width: 100 },
            { title: '谷电费', dataIndex: 'valleyAmount', width: 100 },
            { title: '合计', dataIndex: 'amount', width: 100 },
            { title: '规则', dataIndex: 'ruleId', width: 80, render: (v) => `#${v}` },
          ]}
        />
      )}
    </Card>
  );
}
