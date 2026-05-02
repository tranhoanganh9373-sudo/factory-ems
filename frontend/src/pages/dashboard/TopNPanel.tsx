import { useState } from 'react';
import { Alert, Select, Skeleton, Table, Typography } from 'antd';
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

interface TopNPanelProps {
  onMeterClick?: (meterId: number) => void;
}

export default function TopNPanel({ onMeterClick }: TopNPanelProps) {
  const { range, customFrom, customTo, orgNodeId, energyType } = useDashboardFilterStore();
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);
  const [limit, setLimit] = useState(10);

  const { data, isLoading, isError } = useQuery<TopNItemDTO[]>({
    queryKey: ['dashboard', 'topn', { range, customFrom, customTo, orgNodeId, energyType, limit }],
    queryFn: () =>
      dashboardApi.getTopN({ range, from: customFrom, to: customTo, orgNodeId, energyType }, limit),
    enabled: isCustomReady,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  });

  const columns: ColumnsType<TopNItemDTO> = [
    {
      title: '排名',
      key: 'rank',
      width: 60,
      render: (_, __, idx) => idx + 1,
    },
    { title: '编码', dataIndex: 'code', key: 'code', width: 120 },
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      render: (name, record) =>
        onMeterClick ? <a onClick={() => onMeterClick(record.meterId)}>{name}</a> : name,
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
        <Select options={LIMIT_OPTIONS} value={limit} onChange={setLimit} style={{ width: 100 }} />
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
