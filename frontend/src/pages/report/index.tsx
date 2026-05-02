import { useState } from 'react';
import { App, Button, Card, Col, DatePicker, Divider, Form, Radio, Row, Select, Space } from 'antd';
import { FileTextOutlined } from '@ant-design/icons';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { useQuery } from '@tanstack/react-query';
import type { Dayjs } from 'dayjs';
import { meterApi } from '@/api/meter';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';
import {
  downloadAdHocSync,
  submitAdHocAsync,
  triggerDownload,
  Granularity,
  ReportRequest,
} from '@/api/report';
import { useReportTasksStore } from '@/stores/reportTasks';
import AsyncTaskList from './AsyncTaskList';

const { RangePicker } = DatePicker;

type ExportMode = 'sync' | 'async';

interface FormValues {
  timeRange: [Dayjs, Dayjs];
  granularity: Granularity;
  orgNodeId?: number;
  energyTypes?: string[];
  meterIds?: number[];
  mode: ExportMode;
}

/** Recursively flatten an org tree to TreeSelect-compatible nodes */
function flattenOrgTree(
  nodes: OrgNodeDTO[]
): { title: string; value: number; children?: ReturnType<typeof flattenOrgTree> }[] {
  return nodes.map((n) => ({
    title: `${n.name} (${n.code})`,
    value: n.id,
    children: n.children?.length ? flattenOrgTree(n.children) : undefined,
  }));
}

// Note: /report endpoints are NOT ADMIN-gated — all authenticated roles may access.
export default function ReportPage() {
  useDocumentTitle('报表 - 即席查询');
  const { message } = App.useApp();
  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);
  const addTask = useReportTasksStore((s) => s.addTask);
  const pruneExpired = useReportTasksStore((s) => s.pruneExpired);

  // Watch form fields for dependent selects
  const orgNodeId = Form.useWatch('orgNodeId', form);
  const energyTypes = Form.useWatch('energyTypes', form);

  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });

  const { data: energyTypeList = [] } = useQuery({
    queryKey: ['energyTypes'],
    queryFn: () => meterApi.listEnergyTypes(),
  });

  const { data: meters = [] } = useQuery({
    queryKey: ['meters', { orgNodeId, energyTypes }],
    queryFn: () =>
      meterApi.listMeters({
        orgNodeId,
        // energyTypeId filter not supported by listMeters directly; filter client-side by code
      }),
  });

  // Filter meters by selected energyType codes
  const filteredMeters = energyTypes?.length
    ? meters.filter((m) => energyTypes.includes(m.energyTypeCode))
    : meters;

  const orgTreeData = flattenOrgTree(tree);

  async function handleExport() {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }

    pruneExpired();

    const [fromDayjs, toDayjs] = values.timeRange;
    const req: ReportRequest = {
      from: fromDayjs.toISOString(),
      to: toDayjs.toISOString(),
      granularity: values.granularity,
      orgNodeId: values.orgNodeId ?? null,
      energyTypes: values.energyTypes?.length ? values.energyTypes : null,
      meterIds: values.meterIds?.length ? values.meterIds : null,
    };

    setSubmitting(true);
    try {
      if (values.mode === 'sync') {
        const blob = await downloadAdHocSync(req);
        const from = fromDayjs.format('YYYYMMDD');
        const to = toDayjs.format('YYYYMMDD');
        triggerDownload(blob, `ad-hoc-${from}_${to}.csv`);
        message.success('导出成功');
      } else {
        const dto = await submitAdHocAsync(req);
        addTask({
          token: dto.token,
          filename: dto.filename,
          status: dto.status,
          createdAt: dto.createdAt,
          bytes: dto.bytes,
          error: dto.error,
        });
        message.success('异步任务已提交，请在下方列表查看进度');
      }
    } catch (err: unknown) {
      const msg =
        (err as { response?: { data?: { message?: string } } })?.response?.data?.message ??
        '导出失败，请检查参数或稍后重试';
      message.error(msg);
    } finally {
      setSubmitting(false);
    }
  }

  function handleReset() {
    form.resetFields();
  }

  return (
    <>
      <PageHeader title="即席查询" />
      <Card>
        <Form<FormValues>
          form={form}
          layout="vertical"
          initialValues={{ granularity: 'DAY', mode: 'sync' }}
        >
          <Row gutter={16}>
            <Col xs={24} md={12} lg={8}>
              <Form.Item
                name="timeRange"
                label="时间范围"
                rules={[{ required: true, message: '请选择时间范围' }]}
              >
                <RangePicker showTime style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={6} lg={4}>
              <Form.Item name="granularity" label="聚合粒度" rules={[{ required: true }]}>
                <Select
                  options={[
                    { label: '小时', value: 'HOUR' },
                    { label: '天', value: 'DAY' },
                    { label: '月', value: 'MONTH' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={6} lg={4}>
              <Form.Item name="orgNodeId" label="组织节点">
                <Select
                  allowClear
                  showSearch
                  placeholder="全部节点"
                  style={{ width: '100%' }}
                  options={orgTreeData.map((n) => ({ label: n.title, value: n.value }))}
                  filterOption={(input, option) =>
                    String(option?.label ?? '')
                      .toLowerCase()
                      .includes(input.toLowerCase())
                  }
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12} lg={8}>
              <Form.Item name="energyTypes" label="能源类型">
                <Select
                  mode="multiple"
                  allowClear
                  placeholder="全部类型"
                  options={energyTypeList.map((e) => ({
                    label: `${e.name} (${e.unit})`,
                    value: e.code,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12} lg={8}>
              <Form.Item name="meterIds" label="测点">
                <Select
                  mode="multiple"
                  allowClear
                  placeholder="全部测点"
                  options={filteredMeters.map((m) => ({
                    label: `${m.code} — ${m.name}`,
                    value: m.id,
                  }))}
                  filterOption={(input, option) =>
                    String(option?.label ?? '')
                      .toLowerCase()
                      .includes(input.toLowerCase())
                  }
                  showSearch
                />
              </Form.Item>
            </Col>
            <Col xs={24}>
              <Form.Item name="mode" label="导出模式">
                <Radio.Group>
                  <Radio value="sync">同步导出</Radio>
                  <Radio value="async">异步任务</Radio>
                </Radio.Group>
              </Form.Item>
            </Col>
          </Row>

          <Form.Item>
            <Space size="middle">
              <Button
                type="primary"
                onClick={handleExport}
                loading={submitting}
                icon={<FileTextOutlined />}
              >
                {submitting ? '导出中…' : '导出'}
              </Button>
              <Button onClick={handleReset} disabled={submitting}>
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>

        <Divider />

        <AsyncTaskList />
      </Card>
    </>
  );
}
