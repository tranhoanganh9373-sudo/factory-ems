import { useState } from 'react';
import { Card, Table, Button, Modal, Form, DatePicker, Select, message } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import dayjs, { type Dayjs } from 'dayjs';
import { costApi, type CostRunDTO, type RunStatus } from '@/api/cost';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { StatusTag, type StatusTone } from '@/components/StatusTag';

const BATCH_STATE: Record<RunStatus, { tone: StatusTone; label: string }> = {
  PENDING: { tone: 'default', label: '待运行' },
  RUNNING: { tone: 'info', label: '运行中' },
  SUCCESS: { tone: 'success', label: '成功' },
  FAILED: { tone: 'error', label: '失败' },
  SUPERSEDED: { tone: 'warning', label: '已覆盖' },
};

interface SubmitFormValues {
  range: [Dayjs, Dayjs];
  ruleIds?: number[];
}

export default function CostRunsPage() {
  useDocumentTitle('成本核算 - 分摊批次');
  const qc = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm<SubmitFormValues>();

  const { data: rules = [] } = useQuery({
    queryKey: ['cost', 'rules'],
    queryFn: costApi.listRules,
    staleTime: 30_000,
  });

  // 简化：服务端目前没有"按 period 列表"的端点；前端持有客户端添加的 runId 列表，
  // 通过 useQuery 单条轮询每个 PENDING/RUNNING 状态。这里以 sessionStorage 持久化最近 50 个 runId。
  const [runIds, setRunIds] = useState<number[]>(() => loadRunIds());

  const runsQuery = useQuery({
    queryKey: ['cost', 'runs', runIds.join(',')],
    queryFn: () => Promise.all(runIds.map((id) => costApi.getRun(id))),
    enabled: runIds.length > 0,
    refetchInterval: (q) => {
      const data = q.state.data as CostRunDTO[] | undefined;
      const pending = data?.some((r) => r.status === 'PENDING' || r.status === 'RUNNING');
      return pending ? 1000 : false;
    },
  });

  const onSubmit = async () => {
    const v = await form.validateFields();
    try {
      const { runId } = await costApi.submitRun({
        periodStart: v.range[0].toISOString(),
        periodEnd: v.range[1].toISOString(),
        ruleIds: v.ruleIds && v.ruleIds.length > 0 ? v.ruleIds : null,
      });
      message.success(`已提交分摊批次 #${runId}`);
      const next = [runId, ...runIds].slice(0, 50);
      setRunIds(next);
      saveRunIds(next);
      setCreateOpen(false);
      form.resetFields();
      qc.invalidateQueries({ queryKey: ['cost', 'runs'] });
    } catch {
      // interceptor toast
    }
  };

  const runs = (runsQuery.data ?? []).slice().sort((a, b) => b.id - a.id);

  return (
    <>
      <PageHeader
        title="分摊批次"
        extra={
          <Button type="primary" onClick={() => setCreateOpen(true)}>
            新建批次
          </Button>
        }
      />
      <Card>
      {runIds.length === 0 ? (
        <div style={{ padding: 32, textAlign: 'center', color: '#888' }}>
          本会话尚无批次。点"新建批次"触发一次分摊；列表展示<strong>当前会话</strong>提交的最近 50
          条 run。
        </div>
      ) : (
        <Table<CostRunDTO>
          rowKey="id"
          loading={runsQuery.isLoading}
          dataSource={runs}
          pagination={{ pageSize: 20 }}
          columns={[
            {
              title: 'ID',
              dataIndex: 'id',
              width: 90,
              render: (id: number) => <Link to={`/cost/runs/${id}`}>#{id}</Link>,
            },
            {
              title: '账期',
              width: 220,
              render: (_, r) =>
                `${dayjs(r.periodStart).format('YYYY-MM-DD HH:mm')} ~ ${dayjs(r.periodEnd).format('MM-DD HH:mm')}`,
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 110,
              render: (s: RunStatus) => {
                const cfg = BATCH_STATE[s] ?? { tone: 'default' as StatusTone, label: s };
                return <StatusTag tone={cfg.tone}>{cfg.label}</StatusTag>;
              },
            },
            { title: '总金额', dataIndex: 'totalAmount', width: 120 },
            {
              title: '提交时间',
              dataIndex: 'createdAt',
              width: 160,
              render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm:ss'),
            },
            {
              title: '完成时间',
              dataIndex: 'finishedAt',
              width: 160,
              render: (v: string | null) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '—'),
            },
            { title: '错误', dataIndex: 'errorMessage', ellipsis: true },
          ]}
        />
      )}

      <Modal
        title="新建分摊批次"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={onSubmit}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="range"
            label="账期 (period)"
            rules={[{ required: true, message: '必须选区间' }]}
          >
            <DatePicker.RangePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="ruleIds" label="规则 (可选；不选 = 所有启用规则)">
            <Select
              mode="multiple"
              placeholder="留空 = 所有启用且 effective 的规则"
              options={rules
                .filter((r) => r.enabled)
                .map((r) => ({
                  value: r.id,
                  label: `${r.code} (${r.algorithm} · ${r.energyType})`,
                }))}
            />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
    </>
  );
}

const STORAGE_KEY = 'ems-cost-runs';

function loadRunIds(): number[] {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr.filter((x) => typeof x === 'number') : [];
  } catch {
    return [];
  }
}

function saveRunIds(ids: number[]) {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(ids));
  } catch {
    /* ignore */
  }
}
