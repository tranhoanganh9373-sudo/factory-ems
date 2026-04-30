import { useState } from 'react';
import { Alert, Card, DatePicker, Empty, Select, Space, Spin, TreeSelect } from 'antd';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
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

export default function YearlyReportPage() {
  useDocumentTitle('报表 - 年报');
  const [year, setYear] = useState<Dayjs>(dayjs().startOf('year'));
  const [orgNodeId, setOrgNodeId] = useState<number | undefined>();
  const [energyTypes, setEnergyTypes] = useState<string[] | undefined>();

  const { data: tree = [] } = useQuery({ queryKey: ['orgtree'], queryFn: () => orgTreeApi.getTree() });
  const { data: ets = [] } = useQuery({ queryKey: ['energyTypes'], queryFn: meterApi.listEnergyTypes });

  const yr = year.year();
  const { data, isLoading, error } = useQuery({
    queryKey: ['report', 'yearly', yr, orgNodeId, energyTypes],
    queryFn: () => presetReportApi.yearly({ year: yr, orgNodeId, energyTypes }),
  });

  return (
    <>
      <PageHeader title="年报" />
      <Card
        extra={
          <ExportButtons
            defaultFilename={`yearly-${yr}`}
            buildRequest={(fmt) => ({
              preset: 'yearly',
              format: fmt,
              year: yr,
              orgNodeId,
              energyTypes,
            })}
          />
        }
      >
        <Space wrap style={{ marginBottom: 16 }}>
          <DatePicker.YearPicker value={year} onChange={(v) => v && setYear(v)} allowClear={false} />
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
          <Spin />
        ) : error ? (
          <Alert type="error" message="加载失败" showIcon />
        ) : !data ? (
          <Alert
            type="info"
            message="年报后端控制器尚未上线（Phase L 服务已就绪，等待 controller wiring）"
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
