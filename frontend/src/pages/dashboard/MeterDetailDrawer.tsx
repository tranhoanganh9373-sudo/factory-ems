import { useRef, useEffect } from 'react';
import { Descriptions, Drawer, Alert, Skeleton, Empty, Tag, Timeline, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { dashboardApi, type MeterDetailDTO } from '@/api/dashboard';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';
import { alarmApi } from '@/api/alarm';
import type { AlarmStatus } from '@/api/alarm';
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

function computeStatusInfo(statuses: AlarmStatus[]): { color: string; label: string } {
  if (statuses.includes('ACTIVE')) return { color: 'error', label: '告警中' };
  if (statuses.includes('ACKED')) return { color: 'warning', label: '已确认' };
  return { color: 'success', label: '正常' };
}

function alarmTypeLabel(alarmType: string): string {
  if (alarmType === 'SILENT_TIMEOUT') return '静默超时';
  if (alarmType === 'CONSECUTIVE_FAIL') return '连续失败';
  return alarmType;
}

function alarmStatusLabel(status: AlarmStatus): string {
  if (status === 'ACTIVE') return '告警中';
  if (status === 'ACKED') return '已确认';
  return '已恢复';
}

export default function MeterDetailDrawer({ meterId, onClose }: MeterDetailDrawerProps) {
  const { range, customFrom, customTo } = useDashboardFilterStore();

  const { data, isLoading, isError } = useQuery<MeterDetailDTO>({
    queryKey: ['dashboard', 'meter-detail', meterId, { range, customFrom, customTo }],
    queryFn: () => dashboardApi.getMeterDetail(meterId!, { range, from: customFrom, to: customTo }),
    enabled: meterId != null,
  });

  const { data: alarmsPage, isLoading: alarmsLoading } = useQuery({
    queryKey: ['dashboard', 'meter-alarms', meterId],
    queryFn: () => alarmApi.list({ deviceId: meterId!, page: 1, size: 5 }),
    enabled: meterId != null,
    refetchInterval: 30_000,
  });

  const alarms = alarmsPage?.items ?? [];
  const statusInfo = computeStatusInfo(alarms.map((a) => a.status));

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

  const statusExtra =
    meterId != null && !alarmsLoading ? (
      <Tag color={statusInfo.color}>{statusInfo.label}</Tag>
    ) : null;

  return (
    <Drawer
      title="测点详情"
      placement="right"
      width={640}
      open={meterId != null}
      onClose={onClose}
      destroyOnClose
      extra={statusExtra}
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
          <div style={{ marginTop: 24 }}>
            <Typography.Title level={5}>最近告警</Typography.Title>
            {alarms.length === 0 ? (
              <Empty description="暂无告警" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <Timeline
                items={alarms.map((alarm) => ({
                  color:
                    alarm.status === 'ACTIVE'
                      ? 'red'
                      : alarm.status === 'ACKED'
                        ? 'orange'
                        : 'green',
                  children: (
                    <div>
                      <div>
                        {dayjs(alarm.triggeredAt).format('MM-DD HH:mm')}{' '}
                        {alarmTypeLabel(alarm.alarmType)}
                      </div>
                      <div style={{ color: '#8c8c8c', fontSize: 12 }}>
                        {alarmStatusLabel(alarm.status)}
                      </div>
                    </div>
                  ),
                }))}
              />
            )}
          </div>
        </>
      )}
    </Drawer>
  );
}
