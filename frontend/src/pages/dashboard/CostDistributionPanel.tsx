import { useState } from 'react';
import { Alert, Empty, Skeleton, Typography, DatePicker, Space, Tag, Table } from 'antd';
import { useQuery } from '@tanstack/react-query';
import ReactECharts from 'echarts-for-react';
import dayjs, { type Dayjs } from 'dayjs';
import { billsApi, type CostDistributionDTO, type CostDistributionItem } from '@/api/bills';

const COLORS = [
  '#1677ff',
  '#52c41a',
  '#fa8c16',
  '#cf1322',
  '#722ed1',
  '#13c2c2',
  '#eb2f96',
  '#faad14',
  '#2f54eb',
  '#a0d911',
];

function buildOption(items: CostDistributionItem[]) {
  return {
    tooltip: {
      trigger: 'item',
      formatter: '{b}: ¥{c} ({d}%)',
    },
    legend: { type: 'scroll', bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['50%', '45%'],
        data: items.map((it, i) => ({
          name: it.orgName,
          value: Number(it.amount),
          itemStyle: { color: COLORS[i % COLORS.length] },
        })),
      },
    ],
  };
}

export default function CostDistributionPanel() {
  const [month, setMonth] = useState<Dayjs | null>(null); // null = 任意账期下最近 SUCCESS run

  const { data, isLoading, isError } = useQuery({
    queryKey: ['dashboard', 'cost-distribution', month?.format('YYYY-MM') ?? null],
    queryFn: () => billsApi.costDistribution(month?.format('YYYY-MM')),
    refetchInterval: 60_000,
  });

  return (
    <div>
      <Space style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between' }}>
        <Typography.Text strong>成本分布</Typography.Text>
        <Space>
          <DatePicker
            picker="month"
            allowClear
            value={month}
            onChange={setMonth}
            placeholder="任意账期最新 SUCCESS"
          />
          {data?.runId != null && <Tag color="blue">run #{data.runId}</Tag>}
          {data?.runFinishedAt && <Tag>{dayjs(data.runFinishedAt).format('MM-DD HH:mm')}</Tag>}
        </Space>
      </Space>

      {isLoading ? (
        <Skeleton active paragraph={{ rows: 5 }} />
      ) : isError ? (
        <Alert type="error" message="成本分布加载失败" showIcon />
      ) : !data || !data.items.length ? (
        <Empty description="暂无 SUCCESS 分摊批次" />
      ) : (
        <CostDistributionView data={data} />
      )}
    </div>
  );
}

function CostDistributionView({ data }: { data: CostDistributionDTO }) {
  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start', flexWrap: 'wrap' }}>
      <div style={{ flex: '1 1 320px', minWidth: 280 }}>
        <ReactECharts option={buildOption(data.items)} style={{ height: 320 }} />
      </div>
      <div style={{ flex: '1 1 360px', minWidth: 320 }}>
        <Table<CostDistributionItem>
          rowKey="orgNodeId"
          size="small"
          dataSource={data.items}
          pagination={false}
          scroll={{ y: 280 }}
          columns={[
            { title: '组织', dataIndex: 'orgName' },
            {
              title: '用量',
              dataIndex: 'quantity',
              width: 100,
              align: 'right',
              render: (v: string) => Number(v).toFixed(2),
            },
            {
              title: '金额 (¥)',
              dataIndex: 'amount',
              width: 110,
              align: 'right',
              render: (v: string) => Number(v).toFixed(2),
            },
            {
              title: '占比',
              dataIndex: 'percent',
              width: 80,
              align: 'right',
              render: (v: number) => `${v.toFixed(1)}%`,
            },
          ]}
          summary={() => (
            <Table.Summary.Row>
              <Table.Summary.Cell index={0}>合计</Table.Summary.Cell>
              <Table.Summary.Cell index={1} align="right">
                —
              </Table.Summary.Cell>
              <Table.Summary.Cell index={2} align="right">
                ¥{Number(data.totalAmount).toFixed(2)}
              </Table.Summary.Cell>
              <Table.Summary.Cell index={3} align="right">
                100.0%
              </Table.Summary.Cell>
            </Table.Summary.Row>
          )}
        />
      </div>
    </div>
  );
}
