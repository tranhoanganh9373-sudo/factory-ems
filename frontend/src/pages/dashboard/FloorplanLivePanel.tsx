import { useEffect, useRef, useState } from 'react';
import { Alert, Empty, Select, Skeleton, Space, Tooltip, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { Stage, Layer, Image as KonvaImage, Circle, Text, Group } from 'react-konva';
import { dashboardApi, type FloorplanLiveDTO, type FloorplanLivePoint } from '@/api/dashboard';
import { floorplanApi, floorplanImageUrl } from '@/api/floorplan';
import { useDashboardFilterStore } from '@/stores/dashboardFilter';
import { useThemeStore } from '@/stores/themeStore';
import { floorplanTokens } from '@/utils/floorplanTokens';

const LEVEL_COLORS: Record<FloorplanLivePoint['level'], string> = {
  HIGH: '#cf1322',
  MEDIUM: '#fa8c16',
  LOW: '#52c41a',
  NONE: '#bfbfbf',
};

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new window.Image();
    img.onload = () => resolve(img);
    img.onerror = reject;
    img.src = url;
  });
}

export default function FloorplanLivePanel() {
  const { range, customFrom, customTo, orgNodeId } = useDashboardFilterStore();
  const mode = useThemeStore((s) => s.mode);
  const tokens = floorplanTokens(mode);
  const isCustomReady = range !== 'CUSTOM' || (!!customFrom && !!customTo);
  const containerRef = useRef<HTMLDivElement>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [img, setImg] = useState<HTMLImageElement | null>(null);
  const [stageSize, setStageSize] = useState({ width: 600, height: 360 });

  const { data: list = [] } = useQuery({
    queryKey: ['floorplans', orgNodeId],
    queryFn: () => floorplanApi.list(orgNodeId),
  });

  // Auto-pick the first plan
  useEffect(() => {
    if (selectedId == null && list.length > 0) setSelectedId(list[0].id);
  }, [list, selectedId]);

  const { data, isLoading, isError } = useQuery<FloorplanLiveDTO>({
    queryKey: [
      'dashboard',
      'floorplan-live',
      selectedId,
      { range, customFrom, customTo, orgNodeId },
    ],
    queryFn: () =>
      dashboardApi.getFloorplanLive(selectedId!, {
        range,
        from: customFrom,
        to: customTo,
        orgNodeId,
      }),
    enabled: !!selectedId && isCustomReady,
    refetchInterval: 60_000,
  });

  useEffect(() => {
    if (!data) return;
    loadImage(floorplanImageUrl(data.floorplan.id))
      .then(setImg)
      .catch(() => setImg(null));
  }, [data]);

  useEffect(() => {
    if (!img || !containerRef.current) return;
    const ro = new ResizeObserver((entries) => {
      const w = entries[0].contentRect.width;
      const h = (img.height / img.width) * w;
      setStageSize({ width: w, height: Math.min(h, 420) });
    });
    ro.observe(containerRef.current);
    return () => ro.disconnect();
  }, [img]);

  if (!isCustomReady)
    return <Alert type="info" message="自定义区间：请选择开始和结束时间" showIcon />;
  if (!list.length) return <Empty description="暂无平面图（请先在 /floorplan 上传）" />;
  if (isLoading) return <Skeleton active paragraph={{ rows: 5 }} />;
  if (isError) return <Alert type="error" message="平面图实时数据加载失败" showIcon />;

  return (
    <div>
      <Space style={{ marginBottom: 8, width: '100%', justifyContent: 'space-between' }}>
        <Typography.Text strong>平面图实时</Typography.Text>
        <Select
          style={{ width: 240 }}
          value={selectedId ?? undefined}
          onChange={setSelectedId}
          options={list.map((f) => ({ label: f.name, value: f.id }))}
        />
      </Space>
      <div ref={containerRef} style={{ width: '100%', minHeight: 200, background: '#fafafa' }}>
        {img && data && (
          <Stage width={stageSize.width} height={stageSize.height}>
            <Layer>
              <KonvaImage image={img} width={stageSize.width} height={stageSize.height} />
              {data.points.map((p) => {
                const x = Number(p.xRatio) * stageSize.width;
                const y = Number(p.yRatio) * stageSize.height;
                const color = LEVEL_COLORS[p.level];
                return (
                  <Group key={p.pointId} x={x} y={y}>
                    <Circle radius={9} fill={color} stroke={tokens.deviceFill} strokeWidth={2} />
                    <Text
                      x={11}
                      y={-7}
                      text={`${p.label ?? p.meterCode}: ${p.value.toFixed(1)} ${p.unit}`}
                      fontSize={11}
                      fill={tokens.labelText}
                      shadowColor="black"
                      shadowBlur={3}
                    />
                  </Group>
                );
              })}
            </Layer>
          </Stage>
        )}
      </div>
      <Space size="small" style={{ marginTop: 8 }}>
        {(['HIGH', 'MEDIUM', 'LOW', 'NONE'] as const).map((lv) => (
          <Tooltip key={lv} title={lv}>
            <span
              style={{
                display: 'inline-block',
                width: 12,
                height: 12,
                borderRadius: 6,
                background: LEVEL_COLORS[lv],
                marginRight: 4,
              }}
            />
          </Tooltip>
        ))}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          高 / 中 / 低 / 无
        </Typography.Text>
      </Space>
    </div>
  );
}
