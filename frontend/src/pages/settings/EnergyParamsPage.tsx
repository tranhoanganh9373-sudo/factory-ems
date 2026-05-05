import { useState } from 'react';
import {
  Card,
  Tabs,
  Table,
  Button,
  Space,
  Tag,
  Modal,
  Form,
  Input,
  InputNumber,
  Select,
  DatePicker,
  Alert,
  message,
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import dayjs, { Dayjs } from 'dayjs';
import {
  energyParamsApi,
  type FeedInTariffDTO,
  type CarbonFactorDTO,
  type FeedInPeriodType,
  type CreateFeedInTariffReq,
  type CreateCarbonFactorReq,
} from '@/api/energyParams';
import type { EnergySource } from '@/api/meter';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { showTotal } from '@/utils/format';

const SOURCE_LABEL: Record<EnergySource, string> = {
  GRID: '电网',
  SOLAR: '光伏',
  WIND: '风电',
  STORAGE: '储能',
};

const PERIOD_LABEL: Record<FeedInPeriodType, string> = {
  PEAK: '峰',
  FLAT: '平',
  VALLEY: '谷',
  TIP: '尖',
  SHARP: '深谷',
};

const PERIOD_TONE: Record<FeedInPeriodType, string> = {
  PEAK: 'red',
  FLAT: 'blue',
  VALLEY: 'green',
  TIP: 'volcano',
  SHARP: 'cyan',
};

/**
 * 当前生效行 = 在它所属 (region, energy_source, [period_type]) 分组里 effective_from 最大的一行。
 * 列表已由后端按这一顺序倒序返回，因此每组第一条就是 current。
 */
function markCurrentTariff(rows: FeedInTariffDTO[]): Set<number> {
  const seen = new Set<string>();
  const current = new Set<number>();
  for (const r of rows) {
    const key = `${r.region}|${r.energySource}|${r.periodType}`;
    if (!seen.has(key)) {
      seen.add(key);
      current.add(r.id);
    }
  }
  return current;
}

function markCurrentFactor(rows: CarbonFactorDTO[]): Set<number> {
  const seen = new Set<string>();
  const current = new Set<number>();
  for (const r of rows) {
    const key = `${r.region}|${r.energySource}`;
    if (!seen.has(key)) {
      seen.add(key);
      current.add(r.id);
    }
  }
  return current;
}

// ─── Tariff tab ────────────────────────────────────────────────────────

function FeedInTariffTab() {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const { data: rows = [], isLoading } = useQuery({
    queryKey: ['feed-in-tariff'],
    queryFn: () => energyParamsApi.listFeedInTariff(),
  });
  const currentIds = markCurrentTariff(rows);

  const columns: ColumnsType<FeedInTariffDTO> = [
    { title: '区域', dataIndex: 'region', key: 'region', width: 100 },
    {
      title: '能源',
      dataIndex: 'energySource',
      key: 'energySource',
      width: 90,
      render: (v: EnergySource) => SOURCE_LABEL[v] ?? v,
    },
    {
      title: '时段',
      dataIndex: 'periodType',
      key: 'periodType',
      width: 90,
      render: (v: FeedInPeriodType) => (
        <Tag color={PERIOD_TONE[v] ?? 'default'}>{PERIOD_LABEL[v] ?? v}</Tag>
      ),
    },
    { title: '生效日 (YYYY-MM-DD)', dataIndex: 'effectiveFrom', key: 'effectiveFrom', width: 160 },
    {
      title: '电价 (元/kWh)',
      dataIndex: 'price',
      key: 'price',
      width: 130,
      align: 'right',
      render: (v: number) => Number(v).toFixed(4),
    },
    {
      title: '状态',
      key: 'status',
      width: 100,
      render: (_, r) =>
        currentIds.has(r.id) ? (
          <Tag color="success">当前生效</Tag>
        ) : (
          <Tag color="default">历史</Tag>
        ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm:ss'),
    },
  ];

  return (
    <>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="Append-only：历史行不可编辑或删除。如需调价，请使用「新增下一周期」按钮插入一条更晚生效日的新记录。"
      />
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
          新增下一周期
        </Button>
      </Space>
      <Table<FeedInTariffDTO>
        rowKey="id"
        columns={columns}
        dataSource={rows}
        loading={isLoading}
        size="small"
        pagination={{ pageSize: 20, showSizeChanger: false, showTotal }}
        rowClassName={(r) => (currentIds.has(r.id) ? 'eparams-row-current' : 'eparams-row-history')}
      />
      <CreateFeedInTariffModal
        open={open}
        onClose={() => setOpen(false)}
        onCreated={() => qc.invalidateQueries({ queryKey: ['feed-in-tariff'] })}
      />
      <style>{`
        .eparams-row-current td { font-weight: 600; }
        .eparams-row-history td { color: rgba(0, 0, 0, 0.45); }
        :where(.dark) .eparams-row-history td { color: rgba(255, 255, 255, 0.45); }
      `}</style>
    </>
  );
}

interface FeedInForm {
  region: string;
  energySource: EnergySource;
  periodType: FeedInPeriodType;
  effectiveFrom: Dayjs;
  price: number;
}

function CreateFeedInTariffModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [form] = Form.useForm<FeedInForm>();
  const mut = useMutation({
    mutationFn: (req: CreateFeedInTariffReq) => energyParamsApi.createFeedInTariff(req),
    onSuccess: () => {
      message.success('已新增电价记录');
      form.resetFields();
      onCreated();
      onClose();
    },
  });

  return (
    <Modal
      title="新增上网电价（下一生效周期）"
      open={open}
      onCancel={onClose}
      onOk={() =>
        form.validateFields().then((v) =>
          mut.mutate({
            region: v.region,
            energySource: v.energySource,
            periodType: v.periodType,
            effectiveFrom: v.effectiveFrom.format('YYYY-MM-DD'),
            price: v.price,
          })
        )
      }
      confirmLoading={mut.isPending}
      destroyOnClose
    >
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message="提交后该记录不可修改或删除。请确认生效日。"
      />
      <Form
        form={form}
        layout="vertical"
        initialValues={{ region: 'CN', energySource: 'SOLAR', periodType: 'FLAT' }}
      >
        <Form.Item name="region" label="区域" rules={[{ required: true, max: 64 }]}>
          <Input placeholder="例如 CN（全国）/ JS（江苏）" />
        </Form.Item>
        <Form.Item name="energySource" label="能源类型" rules={[{ required: true }]}>
          <Select
            options={(Object.keys(SOURCE_LABEL) as EnergySource[]).map((v) => ({
              value: v,
              label: SOURCE_LABEL[v],
            }))}
          />
        </Form.Item>
        <Form.Item name="periodType" label="时段" rules={[{ required: true }]}>
          <Select
            options={(Object.keys(PERIOD_LABEL) as FeedInPeriodType[]).map((v) => ({
              value: v,
              label: PERIOD_LABEL[v],
            }))}
          />
        </Form.Item>
        <Form.Item name="effectiveFrom" label="生效日" rules={[{ required: true }]}>
          <DatePicker style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="price" label="电价 (元/kWh)" rules={[{ required: true, type: 'number', min: 0 }]}>
          <InputNumber style={{ width: '100%' }} min={0} step={0.0001} precision={4} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

// ─── Carbon Factor tab ────────────────────────────────────────────────

function CarbonFactorTab() {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const { data: rows = [], isLoading } = useQuery({
    queryKey: ['carbon-factor'],
    queryFn: () => energyParamsApi.listCarbonFactor(),
  });
  const currentIds = markCurrentFactor(rows);

  const columns: ColumnsType<CarbonFactorDTO> = [
    { title: '区域', dataIndex: 'region', key: 'region', width: 100 },
    {
      title: '能源',
      dataIndex: 'energySource',
      key: 'energySource',
      width: 90,
      render: (v: EnergySource) => SOURCE_LABEL[v] ?? v,
    },
    { title: '生效日 (YYYY-MM-DD)', dataIndex: 'effectiveFrom', key: 'effectiveFrom', width: 160 },
    {
      title: '排放因子 (kg CO₂e / kWh)',
      dataIndex: 'factorKgPerKwh',
      key: 'factorKgPerKwh',
      width: 200,
      align: 'right',
      render: (v: number) => Number(v).toFixed(4),
    },
    {
      title: '状态',
      key: 'status',
      width: 100,
      render: (_, r) =>
        currentIds.has(r.id) ? (
          <Tag color="success">当前生效</Tag>
        ) : (
          <Tag color="default">历史</Tag>
        ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm:ss'),
    },
  ];

  return (
    <>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="Append-only：历史行不可编辑或删除。如需调整因子，请使用「新增下一周期」按钮插入一条更晚生效日的新记录。"
      />
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
          新增下一周期
        </Button>
      </Space>
      <Table<CarbonFactorDTO>
        rowKey="id"
        columns={columns}
        dataSource={rows}
        loading={isLoading}
        size="small"
        pagination={{ pageSize: 20, showSizeChanger: false, showTotal }}
        rowClassName={(r) => (currentIds.has(r.id) ? 'eparams-row-current' : 'eparams-row-history')}
      />
      <CreateCarbonFactorModal
        open={open}
        onClose={() => setOpen(false)}
        onCreated={() => qc.invalidateQueries({ queryKey: ['carbon-factor'] })}
      />
    </>
  );
}

interface CarbonForm {
  region: string;
  energySource: EnergySource;
  effectiveFrom: Dayjs;
  factorKgPerKwh: number;
}

function CreateCarbonFactorModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [form] = Form.useForm<CarbonForm>();
  const mut = useMutation({
    mutationFn: (req: CreateCarbonFactorReq) => energyParamsApi.createCarbonFactor(req),
    onSuccess: () => {
      message.success('已新增碳排因子记录');
      form.resetFields();
      onCreated();
      onClose();
    },
  });

  return (
    <Modal
      title="新增碳排因子（下一生效周期）"
      open={open}
      onCancel={onClose}
      onOk={() =>
        form.validateFields().then((v) =>
          mut.mutate({
            region: v.region,
            energySource: v.energySource,
            effectiveFrom: v.effectiveFrom.format('YYYY-MM-DD'),
            factorKgPerKwh: v.factorKgPerKwh,
          })
        )
      }
      confirmLoading={mut.isPending}
      destroyOnClose
    >
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message="提交后该记录不可修改或删除。请确认生效日。"
      />
      <Form
        form={form}
        layout="vertical"
        initialValues={{ region: 'CN', energySource: 'GRID' }}
      >
        <Form.Item name="region" label="区域" rules={[{ required: true, max: 64 }]}>
          <Input placeholder="例如 CN（全国）/ JS（江苏）" />
        </Form.Item>
        <Form.Item name="energySource" label="能源类型" rules={[{ required: true }]}>
          <Select
            options={(Object.keys(SOURCE_LABEL) as EnergySource[]).map((v) => ({
              value: v,
              label: SOURCE_LABEL[v],
            }))}
          />
        </Form.Item>
        <Form.Item name="effectiveFrom" label="生效日" rules={[{ required: true }]}>
          <DatePicker style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="factorKgPerKwh"
          label="排放因子 (kg CO₂e / kWh)"
          rules={[{ required: true, type: 'number', min: 0 }]}
        >
          <InputNumber style={{ width: '100%' }} min={0} step={0.0001} precision={4} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

// ─── Page shell ───────────────────────────────────────────────────────

export default function EnergyParamsPage() {
  useDocumentTitle('上网电价 / 碳排因子');
  return (
    <Card>
      <PageHeader
        title="上网电价 / 碳排因子"
        subtitle="光伏并网售电的种子参数。append-only：调价或调因子时新增一条更晚生效日的记录，历史行保留供回放。"
      />
      <Tabs
        items={[
          { key: 'tariff', label: '上网电价', children: <FeedInTariffTab /> },
          { key: 'factor', label: '碳排因子', children: <CarbonFactorTab /> },
        ]}
      />
    </Card>
  );
}
