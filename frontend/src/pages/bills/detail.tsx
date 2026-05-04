import { Card, Descriptions, Table, Tag, Empty, Skeleton } from 'antd';
import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { billsApi, type BillLineDTO } from '@/api/bills';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { HELP_BILL_DETAIL } from '@/components/pageHelp';
import { ENERGY_TYPE_LABEL, translate } from '@/utils/i18n-dict';

function fmt(v: string | null): string {
  if (v == null) return '—';
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(4) : v;
}

export default function BillDetailPage() {
  useDocumentTitle('账单 - 详情');
  const { id } = useParams<{ id: string }>();
  const billId = Number(id);

  const { data: bill, isLoading } = useQuery({
    queryKey: ['bill', billId],
    queryFn: () => billsApi.getBill(billId),
    enabled: !Number.isNaN(billId),
  });

  const { data: lines = [] } = useQuery({
    queryKey: ['bill', billId, 'lines'],
    queryFn: () => billsApi.getBillLines(billId),
    enabled: !Number.isNaN(billId),
  });

  if (isLoading) return <Skeleton active paragraph={{ rows: 6 }} />;
  if (!bill) return <Empty description="账单不存在" />;

  return (
    <>
      <PageHeader title="账单详情" helpContent={HELP_BILL_DETAIL} />
      <Card
        title={
          <span>
            账单 #{bill.id} <Tag color="blue">{translate(ENERGY_TYPE_LABEL, bill.energyType)}</Tag>
          </span>
        }
        extra={<Link to={`/bills?periodId=${bill.periodId}`}>← 返回列表</Link>}
      >
        <Descriptions size="small" column={3} bordered style={{ marginBottom: 24 }}>
          <Descriptions.Item label="账期 ID">{bill.periodId}</Descriptions.Item>
          <Descriptions.Item label="核算批次">#{bill.runId}</Descriptions.Item>
          <Descriptions.Item label="组织 ID">{bill.orgNodeId}</Descriptions.Item>

          <Descriptions.Item label="用量">{fmt(bill.quantity)}</Descriptions.Item>
          <Descriptions.Item label="金额合计">{fmt(bill.amount)}</Descriptions.Item>
          <Descriptions.Item label="能源">
            {translate(ENERGY_TYPE_LABEL, bill.energyType)}
          </Descriptions.Item>

          <Descriptions.Item label="尖电费">{fmt(bill.sharpAmount)}</Descriptions.Item>
          <Descriptions.Item label="峰电费">{fmt(bill.peakAmount)}</Descriptions.Item>
          <Descriptions.Item label="平电费">{fmt(bill.flatAmount)}</Descriptions.Item>

          <Descriptions.Item label="谷电费">{fmt(bill.valleyAmount)}</Descriptions.Item>
          <Descriptions.Item label="产量">
            {bill.productionQty == null ? '—' : fmt(bill.productionQty)}
          </Descriptions.Item>
          <Descriptions.Item label="单位成本">
            {bill.unitCost == null ? '—' : Number(bill.unitCost).toFixed(6)}
          </Descriptions.Item>

          <Descriptions.Item label="单位强度">
            {bill.unitIntensity == null ? '—' : Number(bill.unitIntensity).toFixed(6)}
          </Descriptions.Item>
          <Descriptions.Item label="创建">
            {dayjs(bill.createdAt).format('YYYY-MM-DD HH:mm:ss')}
          </Descriptions.Item>
          <Descriptions.Item label="更新">
            {dayjs(bill.updatedAt).format('YYYY-MM-DD HH:mm:ss')}
          </Descriptions.Item>
        </Descriptions>

        <h3 style={{ marginBottom: 8 }}>来源链 (bill_line)</h3>
        <Table<BillLineDTO>
          rowKey="id"
          dataSource={lines}
          pagination={false}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 80 },
            { title: '规则 ID', dataIndex: 'ruleId', width: 100 },
            { title: '来源标签', dataIndex: 'sourceLabel' },
            { title: '用量', dataIndex: 'quantity', width: 120, render: fmt },
            { title: '金额', dataIndex: 'amount', width: 120, render: fmt },
          ]}
        />
      </Card>
    </>
  );
}
