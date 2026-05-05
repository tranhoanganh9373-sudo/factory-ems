import { useState } from 'react';
import { Alert, App, Button, Card, DatePicker, Form, Radio, Select, Space, TreeSelect } from 'antd';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { HELP_REPORT_EXPORT } from '@/components/pageHelp';
import type { Dayjs } from 'dayjs';
import { useQuery } from '@tanstack/react-query';
import {
  submitExport,
  pollExport,
  downloadBlob,
  type ExportFormat,
  type ExportRequest,
  type ExportTokenDTO,
  type PresetKind,
} from '@/api/reportPreset';
import { orgTreeApi, type OrgNodeDTO } from '@/api/orgtree';
import { meterApi } from '@/api/meter';
import { shiftApi } from '@/api/production';
import { DownloadOutlined } from '@ant-design/icons';

function buildTreeData(nodes: OrgNodeDTO[]): object[] {
  return nodes.map((n) => ({
    title: `${n.name} (${n.code})`,
    value: n.id,
    key: n.id,
    children: n.children?.length ? buildTreeData(n.children) : undefined,
  }));
}

interface FormValues {
  preset: PresetKind;
  format: ExportFormat;
  date?: Dayjs;
  month?: Dayjs;
  year?: Dayjs;
  shiftId?: number;
  orgNodeId?: number;
  energyTypes?: string[];
}

const POLL_MS = 1500;
const MAX_POLLS = 80;

export default function ExportReportPage() {
  useDocumentTitle('报表 - 异步导出');
  const { message } = App.useApp();
  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);
  const preset = Form.useWatch('preset', form);

  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });
  const { data: ets = [] } = useQuery({
    queryKey: ['energyTypes'],
    queryFn: meterApi.listEnergyTypes,
  });
  const { data: shifts = [] } = useQuery({
    queryKey: ['shifts', 'enabled'],
    queryFn: () => shiftApi.list(true),
  });

  async function handleSubmit() {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const req: ExportRequest = {
      preset: v.preset,
      format: v.format,
      orgNodeId: v.orgNodeId,
      energyTypes: v.energyTypes,
    };
    if (v.preset === 'daily' || v.preset === 'shift') req.date = v.date?.format('YYYY-MM-DD');
    if (v.preset === 'monthly') req.month = v.month?.format('YYYY-MM');
    if (v.preset === 'yearly') req.year = v.year?.year();
    if (v.preset === 'shift') req.shiftId = v.shiftId;

    setSubmitting(true);
    try {
      const submitted = await submitExport(req);
      if (!submitted) {
        message.warning('导出端点尚未上线（Phase M 进行中），请稍后重试');
        return;
      }
      message.success(`已提交导出任务：${submitted.token}`);
      let dto: ExportTokenDTO | null = submitted;
      const filename = submitted.filename || `${v.preset}.${v.format.toLowerCase()}`;
      for (let i = 0; i < MAX_POLLS; i++) {
        await new Promise((r) => setTimeout(r, POLL_MS));
        const r = await pollExport(submitted.token);
        if (r == null) {
          message.error('token 已过期或不存在');
          return;
        }
        if (r instanceof Blob) {
          downloadBlob(r, filename);
          message.success('导出完成');
          return;
        }
        dto = r;
        if (dto.status === 'FAILED') {
          message.error(`导出失败：${dto.error ?? 'unknown'}`);
          return;
        }
      }
      message.warning('任务仍在执行，可稍后再试');
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '导出失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <PageHeader title="异步导出" helpContent={HELP_REPORT_EXPORT} />
      <Card>
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="该页面调用 POST /api/v1/reports/export 异步任务接口，自动轮询直到返回二进制文件。Phase M 上线前会显示『端点尚未上线』。"
        />
        <Form<FormValues>
          form={form}
          layout="vertical"
          initialValues={{ preset: 'daily', format: 'EXCEL' }}
        >
          <Space wrap>
            <Form.Item name="preset" label="报表类型" rules={[{ required: true }]}>
              <Radio.Group>
                <Radio.Button value="daily">日报</Radio.Button>
                <Radio.Button value="monthly">月报</Radio.Button>
                <Radio.Button value="yearly">年报</Radio.Button>
                <Radio.Button value="shift">班次</Radio.Button>
              </Radio.Group>
            </Form.Item>
            <Form.Item name="format" label="格式" rules={[{ required: true }]}>
              <Radio.Group>
                <Radio.Button value="EXCEL">Excel</Radio.Button>
                <Radio.Button value="PDF">PDF</Radio.Button>
                <Radio.Button value="CSV">CSV</Radio.Button>
              </Radio.Group>
            </Form.Item>
          </Space>

          <Space wrap>
            {(preset === 'daily' || preset === 'shift') && (
              <Form.Item name="date" label="日期" rules={[{ required: true }]}>
                <DatePicker />
              </Form.Item>
            )}
            {preset === 'monthly' && (
              <Form.Item name="month" label="月份" rules={[{ required: true }]}>
                <DatePicker.MonthPicker />
              </Form.Item>
            )}
            {preset === 'yearly' && (
              <Form.Item name="year" label="年份" rules={[{ required: true }]}>
                <DatePicker.YearPicker />
              </Form.Item>
            )}
            {preset === 'shift' && (
              <Form.Item name="shiftId" label="班次" rules={[{ required: true }]}>
                <Select
                  style={{ width: 220 }}
                  options={shifts.map((s) => ({ label: `${s.code} ${s.name}`, value: s.id }))}
                />
              </Form.Item>
            )}
          </Space>

          <Space wrap>
            <Form.Item name="orgNodeId" label="组织节点">
              <TreeSelect
                allowClear
                treeData={buildTreeData(tree)}
                treeDefaultExpandAll
                style={{ width: 240 }}
              />
            </Form.Item>
            <Form.Item name="energyTypes" label="能源类型">
              <Select
                mode="multiple"
                allowClear
                style={{ width: 240 }}
                options={ets.map((e) => ({ label: `${e.name} (${e.unit})`, value: e.code }))}
              />
            </Form.Item>
          </Space>

          <Form.Item>
            <Button
              type="primary"
              icon={<DownloadOutlined />}
              loading={submitting}
              onClick={handleSubmit}
            >
              提交导出任务
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </>
  );
}
