import { useRef, useEffect } from 'react';
import { Descriptions, Drawer, Alert, Skeleton, Empty } from 'antd';
import { useQuery } from '@tanstack/react-query';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { dashboardApi, type MeterDetailDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';
import dayjs from 'dayjs';

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

function buildOption(detail: MeterDetailDTO) {
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params: { value: [string, number] }[]) => {
        if (!params.length) return '';
        const [ts, val] = params[0].value;
        return `${dayjs(ts).format('MM-DD HH:mm')}: ${val?.toFixed(2) ?? '—'} ${detail.unit}`;
      },
    },
    grid: { left: 60, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'time', boundaryGap: false },
    yAxis: { type: 'value', name: detail.unit },
    series: [
      {
        name: detail.name,
        type: 'line',
        smooth: true,
        showSymbol: false,
        areaStyle: { opacity: 0.1 },
        data: detail.series.map((p) => [p.ts, p.value]),
      },
    ],
  };
}

interface MeterDetailDrawerProps {
  meterId: number | null;
  onClose: () => void;
}

export default function MeterDetailDrawer({ meterId, onClose }: MeterDetailDrawerProps) {
  const { range, customFrom, customTo } = useDashboardFilterStore();

  const { data, isLoading, isError } = useQuery<MeterDetailDTO>({
    queryKey: ['dashboard', 'meter-detail', meterId, { range, customFrom, customTo }],
    queryFn: () => dashboardApi.getMeterDetail(meterId!, { range, from: customFrom, to: customTo }),
    enabled: meterId != null,
  });

  const elRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    if (!elRef.current || !meterId) return;
    const chart = echarts.init(elRef.current);
    chartRef.current = chart;
    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
      chartRef.current = null;
    };
  }, [meterId]);

  useEffect(() => {
    if (chartRef.current && data) {
      chartRef.current.setOption(buildOption(data), { notMerge: true });
    }
  }, [data]);

  return (
    <Drawer
      title="测点详情"
      placement="right"
      width={640}
      open={meterId != null}
      onClose={onClose}
      destroyOnClose
    >
      {isLoading && <Skeleton active paragraph={{ rows: 6 }} />}
      {isError && <Alert type="error" message="测点详情加载失败" showIcon />}
      {data && (
        <>
          <Descriptions size="small" bordered column={2} style={{ marginBottom: 16 }}>
            <Descriptions.Item label="编码">{data.code}</Descriptions.Item>
            <Descriptions.Item label="名称">{data.name}</Descriptions.Item>
            <Descriptions.Item label="能源类型">{data.energyTypeCode}</Descriptions.Item>
            <Descriptions.Item label="单位">{data.unit}</Descriptions.Item>
            <Descriptions.Item label="合计用量" span={2}>
              {data.total?.toFixed(2)} {data.unit}
            </Descriptions.Item>
          </Descriptions>
          {data.series?.length ? (
            <div ref={elRef} style={{ width: '100%', height: 300 }} />
          ) : (
            <Empty description="暂无时序数据" />
          )}
        </>
      )}
    </Drawer>
  );
}
