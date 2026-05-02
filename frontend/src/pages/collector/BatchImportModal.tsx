import { useState } from 'react';
import { App as AntApp, Button, Modal, Space, Table, Tag, Upload } from 'antd';
import type { UploadFile } from 'antd/es/upload/interface';
import { InboxOutlined } from '@ant-design/icons';
import { AxiosError } from 'axios';
import { useQueryClient } from '@tanstack/react-query';
import { channelApi, type ChannelDTO } from '@/api/channel';

type RowStatus = 'pending' | 'loading' | 'success' | 'skip' | 'fail';

interface ImportRow {
  index: number;
  name: string;
  protocol: string;
  pointCount: number;
  status: RowStatus;
  message?: string;
  body: Partial<ChannelDTO>;
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

function parseChannelsFile(text: string): Partial<ChannelDTO>[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error('文件不是合法 JSON');
  }
  if (!parsed || typeof parsed !== 'object') {
    throw new Error('JSON 顶层必须是对象');
  }
  const channels = (parsed as { channels?: unknown }).channels;
  if (!Array.isArray(channels) || channels.length === 0) {
    throw new Error('JSON 缺少非空 .channels[] 数组');
  }
  return channels as Partial<ChannelDTO>[];
}

function pointCountOf(c: Partial<ChannelDTO>): number {
  const cfg = c.protocolConfig as { points?: unknown[] } | undefined;
  return Array.isArray(cfg?.points) ? cfg.points.length : 0;
}

export function BatchImportModal({ open, onClose }: Props) {
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
      const text = await obj.text();
      const channels = parseChannelsFile(text);
      setRows(
        channels.map((c, i) => ({
          index: i,
          name: c.name ?? `(未命名 #${i + 1})`,
          protocol: c.protocol ?? '?',
          pointCount: pointCountOf(c),
          status: 'pending',
          body: c,
        })),
      );
      setFileName(obj.name);
      message.success(`已读取 ${channels.length} 条通道`);
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
    let ok = 0;
    let fail = 0;
    let skip = 0;
    for (const row of rows) {
      if (row.status === 'success' || row.status === 'skip') continue;
      updateRow(row.index, { status: 'loading', message: undefined });
      try {
        await channelApi.create(row.body);
        updateRow(row.index, { status: 'success' });
        ok += 1;
      } catch (e) {
        const ax = e as AxiosError<{ message?: string }>;
        const status = ax.response?.status;
        if (status === 409) {
          updateRow(row.index, { status: 'skip', message: '同名通道已存在' });
          skip += 1;
        } else {
          const msg = ax.response?.data?.message ?? ax.message ?? '未知错误';
          updateRow(row.index, { status: 'fail', message: msg });
          fail += 1;
        }
      }
    }
    setRunning(false);
    qc.invalidateQueries({ queryKey: ['collector'] });
    qc.invalidateQueries({ queryKey: ['channel'] });
    message.info(`导入完成：成功 ${ok}，跳过 ${skip}，失败 ${fail}`);
  };

  const allDone =
    rows.length > 0 && rows.every((r) => r.status === 'success' || r.status === 'skip');

  return (
    <Modal
      title="批量导入通道"
      open={open}
      width={760}
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
          {fileName ? `已选择：${fileName}` : '点击或拖入 channels JSON 文件'}
        </p>
        <p className="ant-upload-hint">
          schema 见 docs/install/channel-config-import.json；CSV 请先用
          scripts/csv-to-channels.py 转成 JSON
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
            { title: '通道名', dataIndex: 'name' },
            {
              title: '协议',
              dataIndex: 'protocol',
              width: 110,
              render: (p: string) => <Tag>{p}</Tag>,
            },
            { title: '测点数', dataIndex: 'pointCount', width: 80 },
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
