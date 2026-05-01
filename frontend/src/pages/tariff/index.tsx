import { useState, useMemo } from 'react';
import {
  App,
  Button,
  Card,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  TimePicker,
} from 'antd';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { translate, TARIFF_PERIOD_LABEL } from '@/utils/i18n-dict';
import { PlusOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import {
  tariffApi,
  type TariffPlanDTO,
  type TariffPeriodDTO,
  type CreateTariffPlanReq,
  type UpdateTariffPlanReq,
} from '@/api/tariff';
import { meterApi } from '@/api/meter';
import TariffTimeline from './TariffTimeline';

const PERIOD_TYPES = ['SHARP', 'PEAK', 'FLAT', 'VALLEY'] as const;

interface PeriodFormValue {
  periodType: string;
  timeStart: Dayjs;
  timeEnd: Dayjs;
  pricePerUnit: number;
}

interface PlanFormValues {
  name: string;
  energyTypeId: number;
  effectiveFrom: Dayjs;
  effectiveTo?: Dayjs | null;
  enabled: boolean;
  periods: PeriodFormValue[];
}

function defaultPeriods(): PeriodFormValue[] {
  return PERIOD_TYPES.map((pt, i) => ({
    periodType: pt,
    timeStart: dayjs()
      .hour(i * 6)
      .minute(0)
      .second(0),
    timeEnd: dayjs()
      .hour(((i + 1) * 6) % 24)
      .minute(0)
      .second(0),
    pricePerUnit: 1,
  }));
}

function periodsToWire(ps: PeriodFormValue[]): TariffPeriodDTO[] {
  return ps.map((p) => ({
    periodType: p.periodType,
    timeStart: p.timeStart.format('HH:mm:ss'),
    timeEnd: p.timeEnd.format('HH:mm:ss'),
    pricePerUnit: p.pricePerUnit,
  }));
}

function wireToPeriods(ps: TariffPeriodDTO[]): PeriodFormValue[] {
  return ps.map((p) => ({
    periodType: p.periodType,
    timeStart: dayjs(p.timeStart, 'HH:mm:ss'),
    timeEnd: dayjs(p.timeEnd, 'HH:mm:ss'),
    pricePerUnit: Number(p.pricePerUnit),
  }));
}

export default function TariffPage() {
  useDocumentTitle('电价方案');
  const { message } = App.useApp();
  const qc = useQueryClient();
  const [editing, setEditing] = useState<TariffPlanDTO | null>(null);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<PlanFormValues>();

  const { data: plans = [], isLoading } = useQuery({
    queryKey: ['tariff', 'plans'],
    queryFn: tariffApi.list,
  });
  const { data: energyTypes = [] } = useQuery({
    queryKey: ['energyTypes'],
    queryFn: meterApi.listEnergyTypes,
  });

  const energyTypeName = useMemo(() => {
    const m = new Map<number, string>();
    energyTypes.forEach((e) => m.set(e.id, `${e.name} (${e.unit})`));
    return m;
  }, [energyTypes]);

  const createMut = useMutation({
    mutationFn: (req: CreateTariffPlanReq) => tariffApi.create(req),
    onSuccess: () => {
      message.success('已创建');
      qc.invalidateQueries({ queryKey: ['tariff', 'plans'] });
      setCreating(false);
      form.resetFields();
    },
  });
  const updateMut = useMutation({
    mutationFn: ({ id, req }: { id: number; req: UpdateTariffPlanReq }) =>
      tariffApi.update(id, req),
    onSuccess: () => {
      message.success('已更新');
      qc.invalidateQueries({ queryKey: ['tariff', 'plans'] });
      setEditing(null);
      form.resetFields();
    },
  });
  const deleteMut = useMutation({
    mutationFn: (id: number) => tariffApi.delete(id),
    onSuccess: () => {
      message.success('已删除');
      qc.invalidateQueries({ queryKey: ['tariff', 'plans'] });
    },
  });

  function openCreate() {
    setCreating(true);
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      name: '',
      energyTypeId: energyTypes[0]?.id,
      effectiveFrom: dayjs(),
      effectiveTo: null,
      enabled: true,
      periods: defaultPeriods(),
    });
  }

  function openEdit(p: TariffPlanDTO) {
    setEditing(p);
    setCreating(false);
    form.setFieldsValue({
      name: p.name,
      energyTypeId: p.energyTypeId,
      effectiveFrom: dayjs(p.effectiveFrom),
      effectiveTo: p.effectiveTo ? dayjs(p.effectiveTo) : null,
      enabled: p.enabled,
      periods: wireToPeriods(p.periods),
    });
  }

  async function handleSubmit() {
    let v: PlanFormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    if (creating) {
      createMut.mutate({
        name: v.name,
        energyTypeId: v.energyTypeId,
        effectiveFrom: v.effectiveFrom.format('YYYY-MM-DD'),
        effectiveTo: v.effectiveTo ? v.effectiveTo.format('YYYY-MM-DD') : null,
        periods: periodsToWire(v.periods),
      });
    } else if (editing) {
      updateMut.mutate({
        id: editing.id,
        req: {
          name: v.name,
          effectiveTo: v.effectiveTo ? v.effectiveTo.format('YYYY-MM-DD') : null,
          enabled: v.enabled,
          periods: periodsToWire(v.periods),
        },
      });
    }
  }

  const open = creating || editing != null;

  return (
    <>
      <PageHeader title="电价方案" />
      <Card
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建电价方案
          </Button>
        }
      >
        <Table<TariffPlanDTO>
          rowKey="id"
          loading={isLoading}
          dataSource={plans}
          pagination={false}
          columns={[
            { title: '名称', dataIndex: 'name', width: 200 },
            {
              title: '能源类型',
              dataIndex: 'energyTypeId',
              width: 140,
              render: (id: number) => energyTypeName.get(id) ?? id,
            },
            {
              title: '生效期',
              width: 220,
              render: (_, p) => `${p.effectiveFrom} ~ ${p.effectiveTo || '∞'}`,
            },
            {
              title: '24h 时段覆盖',
              render: (_, p) => <TariffTimeline periods={p.periods} />,
            },
            {
              title: '启用',
              dataIndex: 'enabled',
              width: 70,
              render: (e: boolean) => (e ? '✓' : '—'),
            },
            {
              title: '操作',
              width: 160,
              render: (_, p) => (
                <Space>
                  <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(p)}>
                    编辑
                  </Button>
                  <Popconfirm
                    title={`确认删除 ${p.name}？`}
                    onConfirm={() => deleteMut.mutate(p.id)}
                  >
                    <Button size="small" danger icon={<DeleteOutlined />}>
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />

        <Modal
          title={creating ? '新建电价方案' : `编辑：${editing?.name}`}
          open={open}
          width={760}
          onOk={handleSubmit}
          onCancel={() => {
            setCreating(false);
            setEditing(null);
          }}
          confirmLoading={createMut.isPending || updateMut.isPending}
          destroyOnClose
        >
          <Form<PlanFormValues> form={form} layout="vertical">
            <Space style={{ display: 'flex' }}>
              <Form.Item
                name="name"
                label="名称"
                style={{ flex: 1 }}
                rules={[{ required: true, max: 64 }]}
              >
                <Input />
              </Form.Item>
              <Form.Item name="energyTypeId" label="能源类型" rules={[{ required: true }]}>
                <Select
                  disabled={!creating}
                  style={{ width: 200 }}
                  options={energyTypes.map((e) => ({
                    label: `${e.name} (${e.unit})`,
                    value: e.id,
                  }))}
                />
              </Form.Item>
            </Space>
            <Space>
              <Form.Item name="effectiveFrom" label="生效起" rules={[{ required: true }]}>
                <DatePicker disabled={!creating} />
              </Form.Item>
              <Form.Item name="effectiveTo" label="生效止（可空）">
                <DatePicker />
              </Form.Item>
              {!creating && (
                <Form.Item name="enabled" label="启用" valuePropName="checked">
                  <Switch />
                </Form.Item>
              )}
            </Space>

            <Form.List
              name="periods"
              rules={[
                {
                  validator: async (_, periods: PeriodFormValue[]) => {
                    if (!periods?.length) throw new Error('至少需要 1 个时段');
                  },
                },
              ]}
            >
              {(fields, { add, remove }) => (
                <>
                  <div style={{ marginBottom: 8, fontWeight: 500 }}>
                    时段列表（尖峰/高峰/平段/低谷，时段可跨零点）
                  </div>
                  {fields.map((f) => (
                    <Space
                      key={f.key}
                      style={{ display: 'flex', marginBottom: 8 }}
                      align="baseline"
                    >
                      <Form.Item name={[f.name, 'periodType']} rules={[{ required: true }]}>
                        <Select
                          style={{ width: 100 }}
                          options={PERIOD_TYPES.map((pt) => ({
                            label: translate(TARIFF_PERIOD_LABEL, pt),
                            value: pt,
                          }))}
                        />
                      </Form.Item>
                      <Form.Item name={[f.name, 'timeStart']} rules={[{ required: true }]}>
                        <TimePicker format="HH:mm" minuteStep={15} />
                      </Form.Item>
                      <span>→</span>
                      <Form.Item name={[f.name, 'timeEnd']} rules={[{ required: true }]}>
                        <TimePicker format="HH:mm" minuteStep={15} />
                      </Form.Item>
                      <Form.Item
                        name={[f.name, 'pricePerUnit']}
                        rules={[{ required: true, type: 'number', min: 0 }]}
                      >
                        <InputNumber addonBefore="¥" min={0} step={0.01} style={{ width: 140 }} />
                      </Form.Item>
                      <Button danger type="text" onClick={() => remove(f.name)}>
                        移除
                      </Button>
                    </Space>
                  ))}
                  <Button
                    type="dashed"
                    onClick={() =>
                      add({
                        periodType: 'FLAT',
                        timeStart: dayjs().hour(0).minute(0),
                        timeEnd: dayjs().hour(0).minute(0),
                        pricePerUnit: 1,
                      })
                    }
                    block
                    icon={<PlusOutlined />}
                  >
                    新增时段
                  </Button>
                </>
              )}
            </Form.List>
          </Form>
        </Modal>
      </Card>
    </>
  );
}
