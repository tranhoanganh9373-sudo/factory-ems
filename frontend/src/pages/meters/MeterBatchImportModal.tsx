import { useState } from 'react';
import { App as AntApp, Button, Modal, Space, Table, Tag, Upload } from 'antd';
import type { UploadFile } from 'antd/es/upload/interface';
import { InboxOutlined } from '@ant-design/icons';
import { AxiosError } from 'axios';
import { useQueryClient } from '@tanstack/react-query';
import { meterApi, type CreateMeterReq } from '@/api/meter';
import { channelApi } from '@/api/channel';

type RowStatus = 'pending' | 'loading' | 'success' | 'skip' | 'fail';

interface ImportMeterInput extends Omit<CreateMeterReq, 'channelId' | 'enabled'> {
  enabled?: boolean;
  channelName?: string;
  channelId?: number | null;
}

interface ImportRow {
  index: number;
  code: string;
  name: string;
  channelLabel: string;
  status: RowStatus;
  message?: string;
  body: ImportMeterInput;
}

interface Props {
  open: boolean;
  onClose: () => void;
}

const STATUS_TAG: Record<RowStatus, { color: string; label: string }> = {
  pending: { color: 'default', label: '待导入' },
  loading: { color: 'processing', label: '导入中' },
  success: { color: 'success', label: '成功' },
  skip: { color: 'warning', label: '已存在' },
  fail: { color: 'error', label: '失败' },
};

function parseMetersFile(text: string): ImportMeterInput[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error('文件不是合法 JSON');
  }
  if (!parsed || typeof parsed !== 'object') {
    throw new Error('JSON 顶层必须是对象');
  }
  const meters = (parsed as { meters?: unknown }).meters;
  if (!Array.isArray(meters) || meters.length === 0) {
    throw new Error('JSON 缺少非空 .meters[] 数组');
  }
  for (const [i, m] of meters.entries()) {
    const v = m as Record<string, unknown>;
    const required = [
      'code',
      'name',
      'energyTypeId',
      'orgNodeId',
      'influxMeasurement',
      'influxTagKey',
      'influxTagValue',
    ];
    for (const k of required) {
      if (v[k] === undefined || v[k] === null || v[k] === '') {
        throw new Error(`第 ${i + 1} 条 meter 缺少字段 ${k}`);
      }
    }
  }
  return meters as ImportMeterInput[];
}

export function MeterBatchImportModal({ open, onClose }: Props) {
  const { message } = AntApp.useApp();
  const qc = useQueryClient();
  const [rows, setRows] = useState<ImportRow[]>([]);
  const [running, setRunning] = useState(false);
  const [fileName, setFileName] = useState<string | null>(null);

  const reset = () => {
    setRows([]);
    setFileName(null);
  };

  const handleFile = async (file: UploadFile) => {
    const obj = file as unknown as File;
    try {
      // 防灌爆浏览器：5 MB 上限（约 50000 条 meter，远超实际场景）
      const MAX_BYTES = 5_000_000;
      if (obj.size > MAX_BYTES) {
        throw new Error(`文件过大（${(obj.size / 1024 / 1024).toFixed(1)} MB），上限 5 MB`);
      }
      const text = await obj.text();
      const meters = parseMetersFile(text);
      setRows(
        meters.map((m, i) => ({
          index: i,
          code: m.code,
          name: m.name,
          channelLabel: m.channelName ?? (m.channelId != null ? `#${m.channelId}` : '—'),
          status: 'pending',
          body: m,
        }))
      );
      setFileName(obj.name);
      message.success(`已读取 ${meters.length} 条 meter`);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '读取失败');
      reset();
    }
    return false; // 阻止 antd 自动上传
  };

  const updateRow = (index: number, patch: Partial<ImportRow>) =>
    setRows((prev) => prev.map((r) => (r.index === index ? { ...r, ...patch } : r)));

  const startImport = async () => {
    if (rows.length === 0) return;
    setRunning(true);

    // 1) 若有 channelName，先 fetch 全量 channel 建本地 name→id 映射
    let nameToId = new Map<string, number>();
    const needsResolve = rows.some(
      (r) => r.status !== 'success' && r.status !== 'skip' && r.body.channelName
    );
    if (needsResolve) {
      try {
        const channels = await channelApi.list();
        nameToId = new Map(channels.map((c) => [c.name, c.id]));
      } catch (e) {
        const ax = e as AxiosError<{ message?: string }>;
        message.error(`获取 channel 列表失败：${ax.message ?? '未知错误'}`);
        setRunning(false);
        return;
      }
    }

    let ok = 0;
    let fail = 0;
    let skip = 0;
    for (const row of rows) {
      if (row.status === 'success' || row.status === 'skip') continue;
      updateRow(row.index, { status: 'loading', message: undefined });

      const { channelName, channelId: bodyChannelId, ...rest } = row.body;
      let channelId: number | null = bodyChannelId ?? null;
      if (channelName) {
        const resolved = nameToId.get(channelName);
        if (resolved == null) {
          updateRow(row.index, {
            status: 'fail',
            message: `channelName "${channelName}" 在 EMS 中不存在`,
          });
          fail += 1;
          continue;
        }
        channelId = resolved;
      }

      const payload: CreateMeterReq = {
        ...rest,
        enabled: row.body.enabled ?? true,
        channelId,
      };

      try {
        await meterApi.createMeter(payload);
        updateRow(row.index, { status: 'success' });
        ok += 1;
      } catch (e) {
        const ax = e as AxiosError<{ message?: string }>;
        const status = ax.response?.status;
        if (status === 409) {
          updateRow(row.index, { status: 'skip', message: '同 code 已存在' });
          skip += 1;
        } else {
          const msg = ax.response?.data?.message ?? ax.message ?? '未知错误';
          updateRow(row.index, { status: 'fail', message: msg });
          fail += 1;
        }
      }
    }
    setRunning(false);
    qc.invalidateQueries({ queryKey: ['meters'] });
    qc.invalidateQueries({ queryKey: ['topology'] });
    message.info(`导入完成：成功 ${ok}，跳过 ${skip}，失败 ${fail}`);
  };

  const allDone =
    rows.length > 0 && rows.every((r) => r.status === 'success' || r.status === 'skip');

  return (
    <Modal
      title="批量导入测点"
      open={open}
      width={820}
      onCancel={() => {
        if (running) return;
        reset();
        onClose();
      }}
      footer={
        <Space>
          <Button
            onClick={() => {
              reset();
              onClose();
            }}
            disabled={running}
          >
            关闭
          </Button>
          <Button onClick={reset} disabled={running || rows.length === 0}>
            清空
          </Button>
          <Button
            type="primary"
            onClick={startImport}
            loading={running}
            disabled={rows.length === 0 || allDone}
          >
            开始导入
          </Button>
        </Space>
      }
    >
      <Upload.Dragger
        accept=".json,application/json"
        multiple={false}
        showUploadList={false}
        beforeUpload={handleFile}
        disabled={running}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">
          {fileName ? `已选择：${fileName}` : '点击或拖入 meters JSON 文件'}
        </p>
        <p className="ant-upload-hint">
          schema 与 scripts/csv-to-meters.py 输出一致；含 channelName 字段时 导入前会自动解析为
          channelId
        </p>
      </Upload.Dragger>

      {rows.length > 0 && (
        <Table<ImportRow>
          style={{ marginTop: 16 }}
          rowKey="index"
          size="small"
          dataSource={rows}
          pagination={false}
          columns={[
            { title: '#', dataIndex: 'index', width: 50, render: (i: number) => i + 1 },
            { title: 'code', dataIndex: 'code', width: 200 },
            { title: '名称', dataIndex: 'name', ellipsis: true },
            { title: '通道', dataIndex: 'channelLabel', width: 140 },
            {
              title: '状态',
              dataIndex: 'status',
              width: 90,
              render: (s: RowStatus) => (
                <Tag color={STATUS_TAG[s].color}>{STATUS_TAG[s].label}</Tag>
              ),
            },
            { title: '消息', dataIndex: 'message', ellipsis: true },
          ]}
        />
      )}
    </Modal>
  );
}
