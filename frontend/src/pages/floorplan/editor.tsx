import { useEffect, useMemo, useRef, useState } from 'react';
import {
  App,
  Button,
  Card,
  Input,
  Popconfirm,
  Select,
  Skeleton,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { ArrowLeftOutlined, SaveOutlined, DeleteOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stage, Layer, Image as KonvaImage, Circle, Text, Group } from 'react-konva';
import type { KonvaEventObject } from 'konva/lib/Node';
import {
  floorplanApi,
  floorplanImageUrl,
  type FloorplanWithPointsDTO,
  type SetPointEntry,
} from '@/api/floorplan';
import { meterApi, type MeterDTO } from '@/api/meter';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { HELP_FLOORPLAN_EDITOR } from '@/components/pageHelp';
import { useThemeStore } from '@/stores/themeStore';
import { floorplanTokens } from '@/utils/floorplanTokens';

interface PointDraft {
  meterId: number;
  xRatio: number;
  yRatio: number;
  label?: string | null;
}

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new window.Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => resolve(img);
    img.onerror = reject;
    img.src = url;
  });
}

export default function FloorplanEditorPage() {
  useDocumentTitle('设备分布图 - 编辑');
  const { message } = App.useApp();
  const { id } = useParams();
  const mode = useThemeStore((s) => s.mode);
  const tokens = floorplanTokens(mode);
  const fpId = Number(id);
  const nav = useNavigate();
  const qc = useQueryClient();

  const containerRef = useRef<HTMLDivElement>(null);
  const [stageSize, setStageSize] = useState({ width: 800, height: 600 });
  const [img, setImg] = useState<HTMLImageElement | null>(null);
  const [points, setPoints] = useState<PointDraft[]>([]);
  const [selectedIdx, setSelectedIdx] = useState<number | null>(null);
  const [pickMeter, setPickMeter] = useState<number | undefined>();

  const { data, isLoading } = useQuery<FloorplanWithPointsDTO>({
    queryKey: ['floorplan', fpId],
    queryFn: () => floorplanApi.getById(fpId),
    enabled: !!fpId,
  });
  const { data: meters = [] } = useQuery({
    queryKey: ['meters'],
    queryFn: () => meterApi.listMeters(),
  });
  const meterMap = useMemo(() => {
    const m = new Map<number, MeterDTO>();
    meters.forEach((mt) => m.set(mt.id, mt));
    return m;
  }, [meters]);

  // Load image once data arrives
  useEffect(() => {
    if (!data) return;
    loadImage(floorplanImageUrl(data.floorplan.id))
      .then(setImg)
      .catch(() => message.error('图片加载失败'));
    setPoints(
      data.points.map((p) => ({
        meterId: p.meterId,
        xRatio: Number(p.xRatio),
        yRatio: Number(p.yRatio),
        label: p.label,
      }))
    );
  }, [data, message]);

  // Resize stage to container width, preserving image aspect ratio
  useEffect(() => {
    if (!img || !containerRef.current) return;
    const ro = new ResizeObserver((entries) => {
      const w = entries[0].contentRect.width;
      const h = (img.height / img.width) * w;
      setStageSize({ width: w, height: h });
    });
    ro.observe(containerRef.current);
    return () => ro.disconnect();
  }, [img]);

  const setPointsMut = useMutation({
    mutationFn: (req: SetPointEntry[]) => floorplanApi.setPoints(fpId, req),
    onSuccess: () => {
      message.success('已保存');
      qc.invalidateQueries({ queryKey: ['floorplan', fpId] });
    },
  });

  function handleStageClick(e: KonvaEventObject<MouseEvent>) {
    if (!pickMeter) {
      message.warning('请先选择测点再点击画布');
      return;
    }
    const stage = e.target.getStage?.();
    if (!stage) return;
    const pos = stage.getPointerPosition();
    if (!pos) return;
    const xRatio = pos.x / stageSize.width;
    const yRatio = pos.y / stageSize.height;
    const meter = meterMap.get(pickMeter);
    setPoints((prev) => [
      ...prev,
      {
        meterId: pickMeter,
        xRatio,
        yRatio,
        // 缺省用 name 做用户可读的显示；想自定义可在右侧表格里改
        label: meter ? meter.name : null,
      },
    ]);
    setPickMeter(undefined);
  }

  function handlePointDrag(idx: number, x: number, y: number) {
    const xRatio = Math.min(1, Math.max(0, x / stageSize.width));
    const yRatio = Math.min(1, Math.max(0, y / stageSize.height));
    setPoints((prev) => prev.map((p, i) => (i === idx ? { ...p, xRatio, yRatio } : p)));
  }

  function handleSave() {
    setPointsMut.mutate(
      points.map((p) => ({
        meterId: p.meterId,
        xRatio: Number(p.xRatio.toFixed(4)),
        yRatio: Number(p.yRatio.toFixed(4)),
        label: p.label ?? null,
      }))
    );
  }

  if (isLoading) return <Skeleton active paragraph={{ rows: 8 }} />;
  if (!data) return null;

  return (
    <>
      <PageHeader title="编辑设备分布图" helpContent={HELP_FLOORPLAN_EDITOR} />
      <Card
        title={
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => nav('/floorplan')}>
              返回列表
            </Button>
            <Typography.Text strong>编辑：{data.floorplan.name}</Typography.Text>
          </Space>
        }
        extra={
          <Space>
            <Select
              allowClear
              showSearch
              placeholder="选择测点放置"
              style={{ width: 280 }}
              value={pickMeter}
              onChange={setPickMeter}
              options={meters
                .filter((m) => !points.find((p) => p.meterId === m.id))
                .map((m) => ({ label: `${m.code} — ${m.name}`, value: m.id }))}
              filterOption={(input, option) =>
                String(option?.label ?? '')
                  .toLowerCase()
                  .includes(input.toLowerCase())
              }
            />
            <Button
              type="primary"
              icon={<SaveOutlined />}
              onClick={handleSave}
              loading={setPointsMut.isPending}
            >
              保存测点
            </Button>
          </Space>
        }
      >
        <div ref={containerRef} style={{ width: '100%', background: '#fafafa' }}>
          {img && (
            <Stage
              width={stageSize.width}
              height={stageSize.height}
              onClick={handleStageClick}
              style={{ cursor: pickMeter ? 'crosshair' : 'default' }}
            >
              <Layer>
                <KonvaImage image={img} width={stageSize.width} height={stageSize.height} />
                {points.map((p, idx) => {
                  const x = p.xRatio * stageSize.width;
                  const y = p.yRatio * stageSize.height;
                  const meter = meterMap.get(p.meterId);
                  return (
                    <Group
                      key={idx}
                      x={x}
                      y={y}
                      draggable
                      onDragEnd={(e) => handlePointDrag(idx, e.target.x(), e.target.y())}
                      onClick={(e) => {
                        e.cancelBubble = true;
                        setSelectedIdx(idx);
                      }}
                    >
                      <Circle
                        radius={10}
                        fill={selectedIdx === idx ? tokens.deviceStrokeAlarm : tokens.deviceStroke}
                        stroke={tokens.deviceFill}
                        strokeWidth={2}
                      />
                      <Text
                        x={12}
                        y={-6}
                        text={
                          p.label && p.label !== meter?.code
                            ? p.label
                            : meter?.name ?? meter?.code ?? `M${p.meterId}`
                        }
                        fontSize={12}
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

        <Card
          type="inner"
          title={`已放置测点 (${points.length})`}
          style={{ marginTop: 16 }}
          size="small"
        >
          <Table<PointDraft & { idx: number }>
            rowKey="idx"
            size="small"
            pagination={false}
            dataSource={points.map((p, idx) => ({ ...p, idx }))}
            columns={[
              {
                title: '测点',
                dataIndex: 'meterId',
                render: (mid: number) => {
                  const m = meterMap.get(mid);
                  return m ? `${m.code} — ${m.name}` : `#${mid}`;
                },
              },
              {
                title: '位置 (x, y)',
                render: (_, p) => `(${p.xRatio.toFixed(3)}, ${p.yRatio.toFixed(3)})`,
              },
              {
                title: '标签',
                dataIndex: 'label',
                render: (_, p) => (
                  <Input
                    size="small"
                    value={p.label ?? ''}
                    style={{ width: 160 }}
                    onChange={(e) =>
                      setPoints((prev) =>
                        prev.map((pt, i) => (i === p.idx ? { ...pt, label: e.target.value } : pt))
                      )
                    }
                  />
                ),
              },
              {
                title: '状态',
                render: (_, p) => (selectedIdx === p.idx ? <Tag color="orange">选中</Tag> : null),
              },
              {
                title: '操作',
                width: 80,
                render: (_, p) => (
                  <Popconfirm
                    title="移除该测点？"
                    onConfirm={() => setPoints((prev) => prev.filter((_, i) => i !== p.idx))}
                  >
                    <Button size="small" danger icon={<DeleteOutlined />}>
                      移除
                    </Button>
                  </Popconfirm>
                ),
              },
            ]}
          />
        </Card>
      </Card>
    </>
  );
}
