import { useState } from 'react';
import { Alert, Card, DatePicker, Empty, Select, Skeleton, Space, TreeSelect } from 'antd';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { HELP_REPORT_DAILY } from '@/components/pageHelp';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { presetReportApi } from '@/api/reportPreset';
import { orgTreeApi, type OrgNodeDTO } from '@/api/orgtree';
import { meterApi } from '@/api/meter';
import MatrixTable from './MatrixTable';
import ExportButtons from './ExportButtons';

function buildTreeData(nodes: OrgNodeDTO[]): object[] {
  return nodes.map((n) => ({
    title: `${n.name} (${n.code})`,
    value: n.id,
    key: n.id,
    children: n.children?.length ? buildTreeData(n.children) : undefined,
  }));
}

export default function DailyReportPage() {
  useDocumentTitle('报表 - 日报');
  const [date, setDate] = useState<Dayjs>(dayjs());
  const [orgNodeId, setOrgNodeId] = useState<number | undefined>();
  const [energyTypes, setEnergyTypes] = useState<string[] | undefined>();

  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });
  const { data: ets = [] } = useQuery({
    queryKey: ['energyTypes'],
    queryFn: meterApi.listEnergyTypes,
  });

  const { data, isLoading, error } = useQuery({
    queryKey: ['report', 'daily', date.format('YYYY-MM-DD'), orgNodeId, energyTypes],
    queryFn: () =>
      presetReportApi.daily({
        date: date.format('YYYY-MM-DD'),
        orgNodeId,
        energyTypes,
      }),
  });

  return (
    <>
      <PageHeader title="日报" helpContent={HELP_REPORT_DAILY} />
      <Card
        extra={
          <ExportButtons
            defaultFilename={`daily-${date.format('YYYYMMDD')}`}
            buildRequest={(fmt) => ({
              preset: 'daily',
              format: fmt,
              date: date.format('YYYY-MM-DD'),
              orgNodeId,
              energyTypes,
            })}
          />
        }
      >
        <Space wrap style={{ marginBottom: 16 }}>
          <DatePicker value={date} onChange={(v) => v && setDate(v)} allowClear={false} />
          <TreeSelect
            allowClear
            placeholder="组织节点"
            treeData={buildTreeData(tree)}
            treeDefaultExpandAll
            style={{ width: 200 }}
            value={orgNodeId}
            onChange={setOrgNodeId}
          />
          <Select
            mode="multiple"
            allowClear
            placeholder="能源类型"
            style={{ width: 240 }}
            value={energyTypes}
            onChange={setEnergyTypes}
            options={ets.map((e) => ({ label: `${e.name} (${e.unit})`, value: e.code }))}
          />
        </Space>

        {isLoading ? (
          <Skeleton active paragraph={{ rows: 8 }} />
        ) : error ? (
          <Alert type="error" message="加载失败" showIcon />
        ) : !data ? (
          <Alert
            type="info"
            message="日报后端控制器尚未上线（Phase L 服务已就绪，等待 controller wiring）"
            showIcon
          />
        ) : data.rowLabels.length === 0 ? (
          <Empty description="暂无数据" />
        ) : (
          <MatrixTable matrix={data} />
        )}
      </Card>
    </>
  );
}
