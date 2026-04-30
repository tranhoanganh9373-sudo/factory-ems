import { useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Table,
  TreeSelect,
  Typography,
  Upload,
} from 'antd';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { UploadOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import {
  productionEntryApi,
  shiftApi,
  uploadProductionCsv,
  type CreateProductionEntryReq,
  type ProductionEntryDTO,
  type BulkImportResult,
} from '@/api/production';
import { orgTreeApi, type OrgNodeDTO } from '@/api/orgtree';

interface EntryFormValues {
  orgNodeId: number;
  shiftId: number;
  entryDate: Dayjs;
  productCode: string;
  quantity: number;
  unit: string;
  remark?: string;
}

function buildTreeData(nodes: OrgNodeDTO[]): object[] {
  return nodes.map((n) => ({
    title: `${n.name} (${n.code})`,
    value: n.id,
    key: n.id,
    children: n.children?.length ? buildTreeData(n.children) : undefined,
  }));
}

export default function ProductionEntryPage() {
  useDocumentTitle('日产量录入');
  const { message } = App.useApp();
  const qc = useQueryClient();
  const [form] = Form.useForm<EntryFormValues>();
  const [importResult, setImportResult] = useState<BulkImportResult | null>(null);
  const [filterOrg, setFilterOrg] = useState<number | undefined>(undefined);
  const [range, setRange] = useState<[Dayjs, Dayjs]>(() => {
    const today = dayjs();
    return [today.startOf('month'), today];
  });
  const [page, setPage] = useState(0);
  const size = 20;

  const { data: shifts = [] } = useQuery({
    queryKey: ['shifts', 'enabled'],
    queryFn: () => shiftApi.list(true),
  });
  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });

  const { data: pageData, isLoading } = useQuery({
    queryKey: [
      'production-entries',
      range[0].format('YYYY-MM-DD'),
      range[1].format('YYYY-MM-DD'),
      filterOrg,
      page,
    ],
    queryFn: () =>
      productionEntryApi.search({
        from: range[0].format('YYYY-MM-DD'),
        to: range[1].format('YYYY-MM-DD'),
        orgNodeId: filterOrg,
        page,
        size,
      }),
  });

  const createMut = useMutation({
    mutationFn: (req: CreateProductionEntryReq) => productionEntryApi.create(req),
    onSuccess: () => {
      message.success('已录入');
      qc.invalidateQueries({ queryKey: ['production-entries'] });
      form.resetFields();
    },
  });
  const deleteMut = useMutation({
    mutationFn: (id: number) => productionEntryApi.delete(id),
    onSuccess: () => {
      message.success('已删除');
      qc.invalidateQueries({ queryKey: ['production-entries'] });
    },
  });
  const importMut = useMutation({
    mutationFn: (file: File) => uploadProductionCsv(file),
    onSuccess: (r) => {
      setImportResult(r);
      message.success(`导入完成：${r.succeeded}/${r.total}`);
      qc.invalidateQueries({ queryKey: ['production-entries'] });
    },
    onError: (e: unknown) => {
      const m = e instanceof Error ? e.message : 'CSV 导入失败';
      message.error(m);
    },
  });

  async function handleSubmit() {
    let v: EntryFormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    createMut.mutate({
      orgNodeId: v.orgNodeId,
      shiftId: v.shiftId,
      entryDate: v.entryDate.format('YYYY-MM-DD'),
      productCode: v.productCode,
      quantity: v.quantity,
      unit: v.unit,
      remark: v.remark,
    });
  }

  const treeData = buildTreeData(tree);
  const shiftMap = new Map(shifts.map((s) => [s.id, s]));

  return (
    <Space direction="vertical" size={16} style={{ display: 'flex' }}>
      <PageHeader title="日产量录入" />
      <Card
        extra={
          <Upload
            accept=".csv"
            showUploadList={false}
            customRequest={({ file, onSuccess }) => {
              importMut.mutate(file as File, {
                onSuccess: () => onSuccess?.('ok'),
              });
            }}
          >
            <Button icon={<UploadOutlined />} loading={importMut.isPending}>
              CSV 批量导入
            </Button>
          </Upload>
        }
      >
        <Form<EntryFormValues>
          form={form}
          layout="inline"
          initialValues={{ entryDate: dayjs(), unit: 'pcs', quantity: 0 }}
        >
          <Form.Item name="entryDate" label="日期" rules={[{ required: true }]}>
            <DatePicker />
          </Form.Item>
          <Form.Item name="orgNodeId" label="组织节点" rules={[{ required: true }]}>
            <TreeSelect
              showSearch
              treeData={treeData}
              treeDefaultExpandAll
              style={{ width: 220 }}
              placeholder="选择车间/产线"
            />
          </Form.Item>
          <Form.Item name="shiftId" label="班次" rules={[{ required: true }]}>
            <Select
              style={{ width: 160 }}
              options={shifts.map((s) => ({ label: `${s.code} ${s.name}`, value: s.id }))}
            />
          </Form.Item>
          <Form.Item name="productCode" label="产品" rules={[{ required: true, max: 64 }]}>
            <Input style={{ width: 140 }} />
          </Form.Item>
          <Form.Item
            name="quantity"
            label="数量"
            rules={[{ required: true, type: 'number', min: 0 }]}
          >
            <InputNumber min={0} step={1} />
          </Form.Item>
          <Form.Item name="unit" label="单位" rules={[{ required: true, max: 16 }]}>
            <Input style={{ width: 80 }} />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input style={{ width: 200 }} />
          </Form.Item>
          <Form.Item>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              loading={createMut.isPending}
              onClick={handleSubmit}
            >
              录入
            </Button>
          </Form.Item>
        </Form>
        {importResult && (
          <Alert
            style={{ marginTop: 12 }}
            type={importResult.errors.length ? 'warning' : 'success'}
            message={`导入结果：成功 ${importResult.succeeded} / 总数 ${importResult.total}`}
            description={
              importResult.errors.length ? (
                <Typography.Paragraph
                  ellipsis={{ rows: 4, expandable: true, symbol: '展开' }}
                  style={{ marginBottom: 0 }}
                >
                  {importResult.errors.map((e, i) => (
                    <div key={i}>
                      行 {e.rowNumber}: {e.message}
                    </div>
                  ))}
                </Typography.Paragraph>
              ) : null
            }
            closable
            onClose={() => setImportResult(null)}
          />
        )}
      </Card>

      <Card
        title="历史记录"
        extra={
          <Space>
            <DatePicker.RangePicker
              value={range}
              onChange={(v) => v && v[0] && v[1] && setRange([v[0], v[1]])}
              allowClear={false}
            />
            <TreeSelect
              allowClear
              treeData={treeData}
              placeholder="按节点过滤"
              style={{ width: 200 }}
              value={filterOrg}
              onChange={(v) => {
                setFilterOrg(v);
                setPage(0);
              }}
            />
          </Space>
        }
      >
        <Table<ProductionEntryDTO>
          rowKey="id"
          loading={isLoading}
          dataSource={pageData?.items ?? []}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: pageData?.total ?? 0,
            onChange: (p) => setPage(p - 1),
          }}
          columns={[
            { title: '日期', dataIndex: 'entryDate', width: 120 },
            { title: '组织节点', dataIndex: 'orgNodeId', width: 90 },
            {
              title: '班次',
              dataIndex: 'shiftId',
              width: 120,
              render: (id: number) => shiftMap.get(id)?.code ?? id,
            },
            { title: '产品', dataIndex: 'productCode' },
            {
              title: '数量',
              render: (_, e) => `${e.quantity} ${e.unit}`,
            },
            { title: '备注', dataIndex: 'remark' },
            {
              title: '操作',
              width: 80,
              render: (_, e) => (
                <Popconfirm title={`删除 #${e.id}？`} onConfirm={() => deleteMut.mutate(e.id)}>
                  <Button size="small" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              ),
            },
          ]}
        />
      </Card>
    </Space>
  );
}
