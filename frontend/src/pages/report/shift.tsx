import { useState } from 'react';
import { Alert, Card, DatePicker, Empty, Select, Space, Spin, TreeSelect } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { presetReportApi } from '@/api/reportPreset';
import { orgTreeApi, type OrgNodeDTO } from '@/api/orgtree';
import { meterApi } from '@/api/meter';
import { shiftApi, isCrossMidnight } from '@/api/production';
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

export default function ShiftReportPage() {
  const [date, setDate] = useState<Dayjs>(dayjs());
  const [shiftId, setShiftId] = useState<number | undefined>();
  const [orgNodeId, setOrgNodeId] = useState<number | undefined>();
  const [energyTypes, setEnergyTypes] = useState<string[] | undefined>();

  const { data: tree = [] } = useQuery({ queryKey: ['orgtree'], queryFn: () => orgTreeApi.getTree() });
  const { data: ets = [] } = useQuery({ queryKey: ['energyTypes'], queryFn: meterApi.listEnergyTypes });
  const { data: shifts = [] } = useQuery({
    queryKey: ['shifts', 'enabled'],
    queryFn: () => shiftApi.list(true),
  });

  const { data, isLoading, error } = useQuery({
    queryKey: ['report', 'shift', date.format('YYYY-MM-DD'), shiftId, orgNodeId, energyTypes],
    queryFn: () =>
      shiftId
        ? presetReportApi.shift({
            date: date.format('YYYY-MM-DD'),
            shiftId,
            orgNodeId,
            energyTypes,
          })
        : Promise.resolve(null),
    enabled: !!shiftId,
  });

  return (
    <Card
      title="班次报表"
      extra={
        <ExportButtons
          defaultFilename={`shift-${date.format('YYYYMMDD')}-${shiftId ?? ''}`}
          buildRequest={(fmt) => ({
            preset: 'shift',
            format: fmt,
            date: date.format('YYYY-MM-DD'),
            shiftId: shiftId!,
            orgNodeId,
            energyTypes,
          })}
        />
      }
    >
      <Space wrap style={{ marginBottom: 16 }}>
        <DatePicker value={date} onChange={(v) => v && setDate(v)} allowClear={false} />
        <Select
          placeholder="选择班次"
          style={{ width: 240 }}
          value={shiftId}
          onChange={setShiftId}
          options={shifts.map((s) => ({
            label: (
              <span>
                {s.code} {s.name} ({s.timeStart}~{s.timeEnd})
                {isCrossMidnight(s.timeStart, s.timeEnd) ? ' [跨零点]' : ''}
              </span>
            ),
            value: s.id,
          }))}
        />
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

      {!shiftId ? (
        <Alert type="info" message="请选择班次" showIcon />
      ) : isLoading ? (
        <Spin />
      ) : error ? (
        <Alert type="error" message="加载失败" showIcon />
      ) : !data ? (
        <Alert
          type="info"
          message="班次报表后端控制器尚未上线（Phase L 服务已就绪，等待 controller wiring）"
          showIcon
        />
      ) : data.rowLabels.length === 0 ? (
        <Empty description="暂无数据" />
      ) : (
        <MatrixTable matrix={data} />
      )}
    </Card>
  );
}
