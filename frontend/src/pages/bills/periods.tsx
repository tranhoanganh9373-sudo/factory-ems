import { useState } from 'react';
import { Card, Table, Button, Space, Modal, Form, Input, message, App, DatePicker } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import dayjs, { type Dayjs } from 'dayjs';
import { billsApi, type BillPeriodDTO, type BillPeriodStatus } from '@/api/bills';
import { usePermissions } from '@/hooks/usePermissions';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { StatusTag, type StatusTone } from '@/components/StatusTag';
import { showTotal } from '@/utils/format';

const PERIOD_STATUS: Record<BillPeriodStatus, { tone: StatusTone; label: string }> = {
  OPEN: { tone: 'info', label: '开放' },
  CLOSED: { tone: 'success', label: '已关闭' },
  LOCKED: { tone: 'error', label: '已锁定' },
};

export default function BillPeriodsPage() {
  useDocumentTitle('账单 - 账期管理');
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { canUnlockPeriod } = usePermissions();
  const { modal } = App.useApp();

  const { data: periods = [], isLoading } = useQuery({
    queryKey: ['bill', 'periods'],
    queryFn: billsApi.listPeriods,
  });

  const [createOpen, setCreateOpen] = useState(false);
  const [createMonth, setCreateMonth] = useState<Dayjs | null>(dayjs().startOf('month'));

  const ensureMu = useMutation({
    mutationFn: (ym: string) => billsApi.ensurePeriod(ym),
    onSuccess: () => {
      message.success('账期已创建');
      setCreateOpen(false);
      qc.invalidateQueries({ queryKey: ['bill', 'periods'] });
    },
  });

  const closeMu = useMutation({
    mutationFn: (id: number) => billsApi.closePeriod(id),
    onSuccess: (p) => {
      message.success(`账期 ${p.yearMonth} 已关闭，账单已生成`);
      qc.invalidateQueries({ queryKey: ['bill', 'periods'] });
      // 不自动跳转 — 留在账期管理页让用户继续 lock/unlock，
      // 想看账单时点行内"查看账单"链接（见 columns.操作）。
    },
  });

  const lockMu = useMutation({
    mutationFn: (id: number) => billsApi.lockPeriod(id),
    onSuccess: () => {
      message.success('账期已锁定');
      qc.invalidateQueries({ queryKey: ['bill', 'periods'] });
    },
  });

  const unlockMu = useMutation({
    mutationFn: (id: number) => billsApi.unlockPeriod(id),
    onSuccess: () => {
      message.success('账期已解锁');
      qc.invalidateQueries({ queryKey: ['bill', 'periods'] });
    },
  });

  const confirmLock = (p: BillPeriodDTO) => {
    let typed = '';
    const want = `我确认锁定 ${p.yearMonth}`;
    modal.confirm({
      title: '锁定账期',
      icon: null,
      content: (
        <div>
          <p>锁定后将禁止任何对该账期的写操作（包括重跑分摊）。审计日志会记录本次锁定。</p>
          <p>
            请输入 <code>{want}</code> 以确认：
          </p>
          <Input onChange={(e) => (typed = e.target.value)} placeholder={want} />
        </div>
      ),
      okText: '锁定',
      okButtonProps: { danger: true },
      onOk: () =>
        new Promise<void>((resolve, reject) => {
          if (typed.trim() !== want) {
            message.error('确认文本不匹配');
            reject(new Error('mismatch'));
            return;
          }
          lockMu.mutate(p.id);
          resolve();
        }),
    });
  };

  const confirmUnlock = (p: BillPeriodDTO) => {
    let typed = '';
    const want = `我确认解锁 ${p.yearMonth}`;
    modal.confirm({
      title: '解锁账期 (仅 ADMIN)',
      icon: null,
      content: (
        <div>
          <p>解锁后该账期可被再次重写。本次解锁将记入审计日志。</p>
          <p>
            请输入 <code>{want}</code> 以确认：
          </p>
          <Input onChange={(e) => (typed = e.target.value)} placeholder={want} />
        </div>
      ),
      okText: '解锁',
      onOk: () =>
        new Promise<void>((resolve, reject) => {
          if (typed.trim() !== want) {
            message.error('确认文本不匹配');
            reject(new Error('mismatch'));
            return;
          }
          unlockMu.mutate(p.id);
          resolve();
        }),
    });
  };

  return (
    <>
      <PageHeader title="账期管理" />
      <Card
        extra={
          <Button type="primary" onClick={() => setCreateOpen(true)}>
            创建账期
          </Button>
        }
      >
        <Table<BillPeriodDTO>
          rowKey="id"
          loading={isLoading}
          dataSource={periods}
          pagination={{ pageSize: 50, showTotal }}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 80 },
            { title: '账期', dataIndex: 'yearMonth', width: 100 },
            {
              title: '状态',
              dataIndex: 'status',
              width: 100,
              render: (s: BillPeriodStatus) => {
                const cfg = PERIOD_STATUS[s] ?? { tone: 'default' as StatusTone, label: s };
                return <StatusTag tone={cfg.tone}>{cfg.label}</StatusTag>;
              },
            },
            {
              title: '关闭',
              dataIndex: 'closedAt',
              width: 160,
              render: (v: string | null) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—'),
            },
            { title: '关闭人', dataIndex: 'closedBy', width: 90 },
            {
              title: '锁定',
              dataIndex: 'lockedAt',
              width: 160,
              render: (v: string | null) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—'),
            },
            { title: '锁定人', dataIndex: 'lockedBy', width: 90 },
            {
              title: '操作',
              width: 360,
              fixed: 'right',
              render: (_, p) => (
                <Space>
                  {p.status !== 'OPEN' && (
                    <Button
                      size="small"
                      type="link"
                      onClick={() => navigate(`/bills?periodId=${p.id}`)}
                    >
                      查看账单
                    </Button>
                  )}
                  {p.status === 'OPEN' && (
                    <Button
                      size="small"
                      type="primary"
                      loading={closeMu.isPending}
                      onClick={() => closeMu.mutate(p.id)}
                    >
                      关账期 + 生成账单
                    </Button>
                  )}
                  {p.status === 'CLOSED' && (
                    <>
                      <Button
                        size="small"
                        loading={closeMu.isPending}
                        onClick={() => closeMu.mutate(p.id)}
                      >
                        重新生成
                      </Button>
                      <Button
                        size="small"
                        danger
                        loading={lockMu.isPending}
                        onClick={() => confirmLock(p)}
                      >
                        锁定
                      </Button>
                    </>
                  )}
                  {p.status === 'LOCKED' && (
                    <Button
                      size="small"
                      disabled={!canUnlockPeriod}
                      loading={unlockMu.isPending}
                      onClick={() => confirmUnlock(p)}
                    >
                      {canUnlockPeriod ? '解锁 (ADMIN)' : '已锁 (仅 ADMIN 可解)'}
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />

        <Modal
          title="创建账期"
          open={createOpen}
          onCancel={() => setCreateOpen(false)}
          onOk={() => {
            if (!createMonth) {
              message.error('请选择月份');
              return;
            }
            ensureMu.mutate(createMonth.format('YYYY-MM'));
          }}
          confirmLoading={ensureMu.isPending}
          destroyOnClose
        >
          <Form layout="vertical">
            <Form.Item label="账期月份">
              <DatePicker
                picker="month"
                value={createMonth}
                onChange={setCreateMonth}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Form>
        </Modal>
      </Card>
    </>
  );
}
