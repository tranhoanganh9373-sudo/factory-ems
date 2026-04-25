import { Button, DatePicker, Select, Space, TreeSelect } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { orgTreeApi, type OrgNodeDTO } from '@/api/orgtree';
import { meterApi } from '@/api/meter';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';
import type { RangeType } from '@/api/dashboard';

const RANGE_OPTIONS: { label: string; value: RangeType }[] = [
  { label: '今日', value: 'TODAY' },
  { label: '昨日', value: 'YESTERDAY' },
  { label: '本月', value: 'THIS_MONTH' },
  { label: '近24小时', value: 'LAST_24H' },
  { label: '自定义', value: 'CUSTOM' },
];

function buildTreeData(nodes: OrgNodeDTO[]): object[] {
  return nodes.map((n) => ({
    title: n.name,
    value: n.id,
    key: n.id,
    children: n.children?.length ? buildTreeData(n.children) : undefined,
  }));
}

export default function FilterBar() {
  const qc = useQueryClient();
  const {
    range,
    customFrom,
    customTo,
    orgNodeId,
    energyType,
    setRange,
    setCustomRange,
    setOrgNodeId,
    setEnergyType,
    reset,
  } = useDashboardFilterStore();

  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
    staleTime: 60_000,
  });

  const { data: energyTypes = [] } = useQuery({
    queryKey: ['energyTypes'],
    queryFn: () => meterApi.listEnergyTypes(),
    staleTime: 60_000,
  });

  const handleRangeChange = (val: RangeType) => {
    setRange(val);
    if (val !== 'CUSTOM') setCustomRange(undefined, undefined);
  };

  const handleDateRange = (dates: [Dayjs | null, Dayjs | null] | null) => {
    if (dates && dates[0] && dates[1]) {
      setCustomRange(dates[0].toISOString(), dates[1].toISOString());
    } else {
      setCustomRange(undefined, undefined);
    }
  };

  const handleRefresh = () => {
    qc.invalidateQueries({ queryKey: ['dashboard'] });
  };

  const dateValue: [Dayjs, Dayjs] | null =
    customFrom && customTo ? [dayjs(customFrom), dayjs(customTo)] : null;

  return (
    <Space wrap style={{ marginBottom: 16 }}>
      <Select
        style={{ width: 130 }}
        options={RANGE_OPTIONS}
        value={range}
        onChange={handleRangeChange}
      />
      {range === 'CUSTOM' && (
        <DatePicker.RangePicker
          showTime
          value={dateValue}
          onChange={handleDateRange}
        />
      )}
      <TreeSelect
        allowClear
        placeholder="组织节点"
        style={{ width: 180 }}
        treeData={buildTreeData(tree)}
        value={orgNodeId}
        onChange={(v: number | undefined) => setOrgNodeId(v)}
        treeDefaultExpandAll
      />
      <Select
        allowClear
        placeholder="能源类型"
        style={{ width: 140 }}
        options={energyTypes.map((e) => ({ label: e.name, value: e.code }))}
        value={energyType}
        onChange={(v) => setEnergyType(v)}
      />
      <Button onClick={reset}>重置</Button>
      <Button type="primary" icon={<ReloadOutlined />} onClick={handleRefresh}>
        刷新
      </Button>
    </Space>
  );
}
