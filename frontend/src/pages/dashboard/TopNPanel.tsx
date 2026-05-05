import { useState } from 'react';
import { Alert, Select, Skeleton, Space, Table, Typography } from 'antd';
import { EmptyState } from '@/components/EmptyState';
import { useQuery } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import { dashboardApi, type TopNItemDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';

const LIMIT_OPTIONS = [
  { label: 'Top 5', value: 5 },
  { label: 'Top 10', value: 10 },
  { label: 'Top 20', value: 20 },
];

const SCOPE_OPTIONS = [
  { label: '仅叶子表', value: 'LEAVES' as const },
  { label: '仅根表', value: 'ROOTS' as const },
  { label: '全部表', value: 'ALL' as const },
];

type Scope = 'LEAVES' | 'ROOTS' | 'ALL';

interface TopNPanelProps {
  onMeterClick?: (meterId: number) => void;
}

export default function TopNPanel({ onMeterClick }: TopNPanelProps) {
  const { range, customFrom, customTo, orgNodeId, energyType } = useDashboardFilterStore();
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);
  const [limit, setLimit] = useState(10);
  const [scope, setScope] = useState<Scope>('LEAVES');

  const { data, isLoading, isError } = useQuery<TopNItemDTO[]>({
    queryKey: [
      'dashboard',
      'topn',
      { range, customFrom, customTo, orgNodeId, energyType, limit, scope },
    ],
    queryFn: () =>
      dashboardApi.getTopN(
        { range, from: customFrom, to: customTo, orgNodeId, energyType },
        limit,
        scope,
      ),
    enabled: isCustomReady,
    refetchInterval: 60_000,
    refetchIntervalInBackground: false,
  });

  const columns: ColumnsType<TopNItemDTO> = [
    {
      title: '排名',
      key: 'rank',
      width: 60,
      render: (_, __, idx) => idx + 1,
    },
    {
      title: '测点',
      dataIndex: 'name',
      key: 'name',
      render: (name, record) => {
        const primary = name || record.code;
        const node = onMeterClick ? (
          <a onClick={() => onMeterClick(record.meterId)}>{primary}</a>
        ) : (
          primary
        );
        return (
          <span>
            {node}
            {name && record.code ? (
              <Typography.Text
                type="secondary"
                style={{ fontSize: 12, marginLeft: 8 }}
              >
                {record.code}
              </Typography.Text>
            ) : null}
          </span>
        );
      },
    },
    {
      title: '能源类型',
      dataIndex: 'energyTypeCode',
      key: 'energyTypeCode',
      width: 100,
    },
    {
      title: '用量',
      key: 'total',
      width: 120,
      render: (_, r) => `${r.total?.toFixed(2) ?? '—'} ${r.unit}`,
    },
  ];

  if (!isCustomReady)
    return <Alert type="info" message="自定义区间：请选择开始和结束时间" showIcon />;

  return (
    <div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 8,
        }}
      >
        <Typography.Text strong>综合排名</Typography.Text>
        <Space size="small">
          <Select
            options={SCOPE_OPTIONS}
            value={scope}
            onChange={setScope}
            style={{ width: 110 }}
          />
          <Select
            options={LIMIT_OPTIONS}
            value={limit}
            onChange={setLimit}
            style={{ width: 100 }}
          />
        </Space>
      </div>
      {isLoading ? (
        <Skeleton active paragraph={{ rows: 5 }} />
      ) : isError ? (
        <Alert type="error" message="综合排名数据加载失败" showIcon />
      ) : !data?.length ? (
        <EmptyState kind="no-data" description="暂无综合排名数据" compact />
      ) : (
        <Table
          rowKey="meterId"
          columns={columns}
          dataSource={data}
          pagination={false}
          size="small"
          onRow={(r) =>
            onMeterClick
              ? { style: { cursor: 'pointer' }, onClick: () => onMeterClick(r.meterId) }
              : {}
          }
        />
      )}
    </div>
  );
}
