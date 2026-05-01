import { useState } from 'react';
import { Card, Table, Space, Input, Select, DatePicker, Button, Tag } from 'antd';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { auditApi, AuditLogDTO, AuditQuery } from '@/api/audit';
import { DetailModal } from './DetailModal';
import { translate, AUDIT_ACTION_LABEL, RESOURCE_TYPE_LABEL } from '@/utils/i18n-dict';

const { RangePicker } = DatePicker;
const ACTIONS = [
  'LOGIN',
  'LOGOUT',
  'LOGIN_FAIL',
  'CREATE',
  'UPDATE',
  'DELETE',
  'MOVE',
  'CONFIG_CHANGE',
];
const RES_TYPES = ['AUTH', 'USER', 'ORG_NODE', 'NODE_PERMISSION'];

export default function AuditListPage() {
  useDocumentTitle('系统管理 - 审计日志');
  const [q, setQ] = useState<AuditQuery>({ page: 1, size: 20 });
  const [detail, setDetail] = useState<AuditLogDTO | null>(null);
  const { data, isLoading } = useQuery({
    queryKey: ['audit', q],
    queryFn: () => auditApi.search(q),
    placeholderData: (p) => p,
  });

  return (
    <>
      <PageHeader title="审计日志" />
      <Card>
        <Space style={{ marginBottom: 16 }} wrap>
          <Select
            placeholder="动作"
            allowClear
            style={{ width: 160 }}
            options={ACTIONS.map((a) => ({ label: translate(AUDIT_ACTION_LABEL, a), value: a }))}
            onChange={(v) => setQ({ ...q, action: v, page: 1 })}
          />
          <Select
            placeholder="资源类型"
            allowClear
            style={{ width: 160 }}
            options={RES_TYPES.map((r) => ({ label: translate(RESOURCE_TYPE_LABEL, r), value: r }))}
            onChange={(v) => setQ({ ...q, resourceType: v, page: 1 })}
          />
          <Input
            placeholder="操作者 User ID"
            type="number"
            style={{ width: 160 }}
            onBlur={(e) =>
              setQ({
                ...q,
                actorUserId: e.target.value ? Number(e.target.value) : undefined,
                page: 1,
              })
            }
          />
          <RangePicker
            showTime
            onChange={(range) =>
              setQ({
                ...q,
                page: 1,
                from: range?.[0] ? range[0].toISOString() : undefined,
                to: range?.[1] ? range[1].toISOString() : undefined,
              })
            }
          />
          <Button onClick={() => setQ({ page: 1, size: 20 })}>重置</Button>
        </Space>
        <Table<AuditLogDTO>
          rowKey="id"
          loading={isLoading}
          dataSource={data?.items ?? []}
          pagination={{
            current: q.page,
            pageSize: q.size,
            total: data?.total ?? 0,
            onChange: (p, s) => setQ({ ...q, page: p, size: s }),
          }}
          columns={[
            {
              title: '时间',
              dataIndex: 'occurredAt',
              render: (v) => dayjs(v).format('YYYY-MM-DD HH:mm:ss'),
            },
            { title: '操作者', dataIndex: 'actorUsername' },
            {
              title: '动作',
              dataIndex: 'action',
              render: (a) => (
                <Tag color={a.includes('FAIL') ? 'red' : 'blue'}>
                  {translate(AUDIT_ACTION_LABEL, a)}
                </Tag>
              ),
            },
            {
              title: '资源',
              render: (_, r) =>
                `${r.resourceType ? translate(RESOURCE_TYPE_LABEL, r.resourceType) : '-'} / ${r.resourceId ?? '-'}`,
            },
            { title: '概述', dataIndex: 'summary', ellipsis: true },
            { title: 'IP', dataIndex: 'ip' },
            {
              title: '操作',
              key: 'ops',
              render: (_, r) => (
                <Button type="link" onClick={() => setDetail(r)}>
                  详情
                </Button>
              ),
            },
          ]}
        />
        <DetailModal log={detail} onClose={() => setDetail(null)} />
      </Card>
    </>
  );
}
