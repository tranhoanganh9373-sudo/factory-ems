import { useMemo, useState } from 'react';
import {
  Card,
  Table,
  Button,
  Space,
  Tag,
  Modal,
  Form,
  Input,
  Select,
  InputNumber,
  Switch,
  DatePicker,
  TreeSelect,
  message,
  Typography,
  Descriptions,
} from 'antd';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import dayjs, { type Dayjs } from 'dayjs';
import {
  costApi,
  type CostRuleDTO,
  type CreateCostRuleReq,
  type AllocationAlgorithm,
  type EnergyTypeCode,
  type CostLineDTO,
} from '@/api/cost';
import { meterApi } from '@/api/meter';
import { orgTreeApi, type OrgNodeDTO } from '@/api/orgtree';

const ENERGY_OPTIONS: { value: EnergyTypeCode; label: string }[] = [
  { value: 'ELEC', label: '电' },
  { value: 'WATER', label: '水' },
  { value: 'GAS', label: '气' },
  { value: 'STEAM', label: '汽' },
  { value: 'OIL', label: '油' },
];

const ALGORITHM_OPTIONS: { value: AllocationAlgorithm; label: string; hint: string }[] = [
  { value: 'DIRECT', label: 'DIRECT 直接归集', hint: '整张表归 targetOrgIds[0]' },
  {
    value: 'PROPORTIONAL',
    label: 'PROPORTIONAL 比例分摊',
    hint: '按 basis (AREA/HEADCOUNT/PRODUCTION/FIXED) 拆给多个 org',
  },
  { value: 'RESIDUAL', label: 'RESIDUAL 差值分摊', hint: '总表 - 子表 = 残差，再 PROPORTIONAL 拆' },
  { value: 'COMPOSITE', label: 'COMPOSITE 复合', hint: '链式：先 RESIDUAL 再 PROPORTIONAL 等' },
];

const STATUS_COLOR: Record<string, string> = {
  ELEC: 'volcano',
  WATER: 'cyan',
  GAS: 'gold',
  STEAM: 'purple',
  OIL: 'magenta',
};

function buildOrgTreeData(nodes: OrgNodeDTO[]): object[] {
  return nodes.map((n) => ({
    title: `${n.name} (${n.code})`,
    value: n.id,
    key: n.id,
    children: n.children?.length ? buildOrgTreeData(n.children) : undefined,
  }));
}

interface RuleFormValues {
  code: string;
  name: string;
  description?: string;
  energyType: EnergyTypeCode;
  algorithm: AllocationAlgorithm;
  sourceMeterId: number;
  targetOrgIds: number[];
  weightsJson: string;
  priority: number;
  enabled: boolean;
  effectiveRange: [Dayjs, Dayjs | null];
}

export default function CostRulesPage() {
  useDocumentTitle('成本核算 - 分摊规则');
  const qc = useQueryClient();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<CostRuleDTO | null>(null);
  const [dryRunRule, setDryRunRule] = useState<CostRuleDTO | null>(null);
  const [form] = Form.useForm<RuleFormValues>();
  const watchedAlgorithm = Form.useWatch('algorithm', form);

  const { data: rules = [], isLoading } = useQuery({
    queryKey: ['cost', 'rules'],
    queryFn: costApi.listRules,
    staleTime: 30_000,
  });
  const { data: meters = [] } = useQuery({
    queryKey: ['meters', 'all'],
    queryFn: () => meterApi.listMeters(),
  });
  const { data: orgTree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });

  const meterById = useMemo(() => {
    const m = new Map<number, (typeof meters)[number]>();
    meters.forEach((x) => m.set(x.id, x));
    return m;
  }, [meters]);

  const createMu = useMutation({
    mutationFn: (req: CreateCostRuleReq) => costApi.createRule(req),
    onSuccess: () => {
      message.success('规则创建成功');
      setModalOpen(false);
      qc.invalidateQueries({ queryKey: ['cost', 'rules'] });
    },
  });

  const updateMu = useMutation({
    mutationFn: ({ id, req }: { id: number; req: CreateCostRuleReq }) =>
      costApi.updateRule(id, req),
    onSuccess: () => {
      message.success('规则已更新');
      setModalOpen(false);
      qc.invalidateQueries({ queryKey: ['cost', 'rules'] });
    },
  });

  const deleteMu = useMutation({
    mutationFn: (id: number) => costApi.deleteRule(id),
    onSuccess: () => {
      message.success('规则已删除');
      qc.invalidateQueries({ queryKey: ['cost', 'rules'] });
    },
  });

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      algorithm: 'PROPORTIONAL',
      energyType: 'ELEC',
      priority: 100,
      enabled: true,
      weightsJson: JSON.stringify({ basis: 'FIXED', values: {} }, null, 2),
      effectiveRange: [dayjs(), null],
    } as Partial<RuleFormValues>);
    setModalOpen(true);
  };

  const openEdit = (r: CostRuleDTO) => {
    setEditing(r);
    form.setFieldsValue({
      code: r.code,
      name: r.name,
      description: r.description ?? '',
      energyType: r.energyType,
      algorithm: r.algorithm,
      sourceMeterId: r.sourceMeterId,
      targetOrgIds: r.targetOrgIds,
      weightsJson: JSON.stringify(r.weights ?? {}, null, 2),
      priority: r.priority,
      enabled: r.enabled,
      effectiveRange: [dayjs(r.effectiveFrom), r.effectiveTo ? dayjs(r.effectiveTo) : null],
    });
    setModalOpen(true);
  };

  const onSubmit = async () => {
    const v = await form.validateFields();
    let weights: Record<string, unknown>;
    try {
      weights = JSON.parse(v.weightsJson);
    } catch {
      message.error('weights 必须是合法 JSON');
      return;
    }
    const req: CreateCostRuleReq = {
      code: v.code,
      name: v.name,
      description: v.description ?? null,
      energyType: v.energyType,
      algorithm: v.algorithm,
      sourceMeterId: v.sourceMeterId,
      targetOrgIds: v.targetOrgIds,
      weights,
      priority: v.priority,
      enabled: v.enabled,
      effectiveFrom: v.effectiveRange[0].format('YYYY-MM-DD'),
      effectiveTo: v.effectiveRange[1]?.format('YYYY-MM-DD') ?? null,
    };
    if (editing) {
      updateMu.mutate({ id: editing.id, req });
    } else {
      createMu.mutate(req);
    }
  };

  const algoHint = ALGORITHM_OPTIONS.find((a) => a.value === watchedAlgorithm)?.hint;

  return (
    <>
      <PageHeader
        title="分摊规则"
        extra={
          <Button type="primary" onClick={openCreate}>
            新建规则
          </Button>
        }
      />
      <Card>
      <Table<CostRuleDTO>
        rowKey="id"
        loading={isLoading}
        dataSource={rules}
        pagination={{ pageSize: 20 }}
        columns={[
          { title: '编码', dataIndex: 'code', width: 140 },
          { title: '名称', dataIndex: 'name' },
          {
            title: '能源',
            dataIndex: 'energyType',
            width: 80,
            render: (v: EnergyTypeCode) => <Tag color={STATUS_COLOR[v]}>{v}</Tag>,
          },
          { title: '算法', dataIndex: 'algorithm', width: 130 },
          {
            title: '主表',
            dataIndex: 'sourceMeterId',
            width: 160,
            render: (id: number) => meterById.get(id)?.code ?? `#${id}`,
          },
          {
            title: '目标 org 数',
            dataIndex: 'targetOrgIds',
            width: 110,
            render: (ids: number[]) => ids.length,
          },
          { title: '优先级', dataIndex: 'priority', width: 80 },
          {
            title: '状态',
            dataIndex: 'enabled',
            width: 80,
            render: (e: boolean) =>
              e ? <Tag color="green">启用</Tag> : <Tag color="default">停用</Tag>,
          },
          {
            title: '操作',
            width: 240,
            fixed: 'right',
            render: (_, r) => (
              <Space>
                <Button size="small" onClick={() => openEdit(r)}>
                  编辑
                </Button>
                <Button size="small" onClick={() => setDryRunRule(r)}>
                  Dry-run
                </Button>
                <Button
                  size="small"
                  danger
                  onClick={() =>
                    Modal.confirm({
                      title: `删除规则 ${r.code}？`,
                      content: '不可撤销。',
                      onOk: () => deleteMu.mutateAsync(r.id),
                    })
                  }
                >
                  删除
                </Button>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title={editing ? `编辑规则 ${editing.code}` : '新建规则'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={onSubmit}
        width={720}
        confirmLoading={createMu.isPending || updateMu.isPending}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="code" label="编码" rules={[{ required: true, message: '编码必填' }]}>
            <Input disabled={!!editing} placeholder="例如 R-PLANT-RESID" />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>

          <Space size="large" style={{ display: 'flex', flexWrap: 'wrap' }}>
            <Form.Item
              name="energyType"
              label="能源"
              rules={[{ required: true }]}
              style={{ minWidth: 160 }}
            >
              <Select options={ENERGY_OPTIONS} />
            </Form.Item>
            <Form.Item
              name="algorithm"
              label="算法"
              rules={[{ required: true }]}
              style={{ minWidth: 240 }}
            >
              <Select
                options={ALGORITHM_OPTIONS}
                onChange={(v: AllocationAlgorithm) => {
                  // 算法切换时重置 weights 到合理初值
                  const init =
                    v === 'DIRECT'
                      ? {}
                      : v === 'PROPORTIONAL'
                        ? { basis: 'FIXED', values: {} }
                        : v === 'RESIDUAL'
                          ? { deductMeterIds: [], values: {} }
                          : [];
                  form.setFieldValue('weightsJson', JSON.stringify(init, null, 2));
                }}
              />
            </Form.Item>
            <Form.Item name="priority" label="优先级" style={{ minWidth: 120 }}>
              <InputNumber min={0} max={9999} />
            </Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>

          {algoHint && (
            <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
              {algoHint}
            </Typography.Text>
          )}

          <Form.Item name="sourceMeterId" label="主表 (source meter)" rules={[{ required: true }]}>
            <Select
              showSearch
              optionFilterProp="label"
              options={meters.map((m) => ({
                value: m.id,
                label: `${m.code} · ${m.name} (${m.energyTypeCode})`,
              }))}
              placeholder="选择主表"
            />
          </Form.Item>

          <Form.Item
            name="targetOrgIds"
            label="目标组织节点"
            rules={[{ required: true, message: '至少选 1 个目标 org' }]}
          >
            <TreeSelect
              multiple
              treeData={buildOrgTreeData(orgTree)}
              treeDefaultExpandAll
              placeholder="选择目标组织"
              showSearch
              treeNodeFilterProp="title"
              style={{ width: '100%' }}
            />
          </Form.Item>

          <Form.Item
            name="weightsJson"
            label="权重 (weights, JSON)"
            rules={[{ required: true }]}
            tooltip={
              <>
                算法对应的 JSON 结构：
                <br />
                PROPORTIONAL: {'{ "basis": "FIXED", "values": { "orgId": weight } }'}
                <br />
                RESIDUAL: {'{ "deductMeterIds": [..], "values": {..} }'}
                <br />
                COMPOSITE: 子规则数组
              </>
            }
          >
            <Input.TextArea rows={6} style={{ fontFamily: 'monospace' }} />
          </Form.Item>

          <Form.Item name="effectiveRange" label="生效区间" rules={[{ required: true }]}>
            <DatePicker.RangePicker allowEmpty={[false, true]} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <DryRunModal rule={dryRunRule} onClose={() => setDryRunRule(null)} />
    </Card>
    </>
  );
}

function DryRunModal({ rule, onClose }: { rule: CostRuleDTO | null; onClose: () => void }) {
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [lines, setLines] = useState<CostLineDTO[] | null>(null);
  const [loading, setLoading] = useState(false);

  const close = () => {
    setRange(null);
    setLines(null);
    onClose();
  };

  const run = async () => {
    if (!rule || !range) return;
    setLoading(true);
    try {
      const res = await costApi.dryRunRule(rule.id, {
        periodStart: range[0].toISOString(),
        periodEnd: range[1].toISOString(),
      });
      setLines(res);
    } catch {
      // interceptor 已 toast
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title={rule ? `Dry-run · ${rule.code}` : 'Dry-run'}
      open={!!rule}
      onCancel={close}
      onOk={close}
      cancelButtonProps={{ style: { display: 'none' } }}
      okText="关闭"
      width={900}
      destroyOnClose
    >
      {rule && (
        <>
          <Descriptions size="small" column={2} style={{ marginBottom: 12 }}>
            <Descriptions.Item label="算法">{rule.algorithm}</Descriptions.Item>
            <Descriptions.Item label="能源">{rule.energyType}</Descriptions.Item>
            <Descriptions.Item label="主表">#{rule.sourceMeterId}</Descriptions.Item>
            <Descriptions.Item label="目标 org">{rule.targetOrgIds.length} 个</Descriptions.Item>
          </Descriptions>
          <Space style={{ marginBottom: 12 }}>
            <DatePicker.RangePicker
              showTime
              value={range}
              onChange={(v) => setRange(v as [Dayjs, Dayjs] | null)}
            />
            <Button type="primary" loading={loading} disabled={!range} onClick={run}>
              预览
            </Button>
          </Space>
          {lines && (
            <Table<CostLineDTO>
              size="small"
              rowKey="id"
              dataSource={lines.map((l, i) => ({ ...l, id: l.id || i }))}
              pagination={false}
              columns={[
                { title: 'org', dataIndex: 'targetOrgId', width: 100 },
                { title: 'energy', dataIndex: 'energyType', width: 80 },
                { title: 'qty', dataIndex: 'quantity', width: 100 },
                { title: 'amount', dataIndex: 'amount', width: 100 },
                { title: '尖', dataIndex: 'sharpAmount', width: 80 },
                { title: '峰', dataIndex: 'peakAmount', width: 80 },
                { title: '平', dataIndex: 'flatAmount', width: 80 },
                { title: '谷', dataIndex: 'valleyAmount', width: 80 },
              ]}
              scroll={{ x: 'max-content' }}
            />
          )}
        </>
      )}
    </Modal>
  );
}
