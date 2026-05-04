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
  body: ChannelDTO;
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

function pointCountOf(c: ChannelDTO): number {
  const cfg = c.protocolConfig as { points?: unknown[] } | undefined;
  return Array.isArray(cfg?.points) ? cfg.points.length : 0;
}

export function BatchImportModal({ open, onClose }: Props) {
  const { message } = AntApp.useApp();
  const qc = useQueryClient();
  const [rows, setRows] = useState<ImportRow[]>([]);
  const [running, setRunning] = useState(false);
  const [parsing, setParsing] = useState(false);
  const [channelsFile, setChannelsFile] = useState<File | null>(null);
  const [pointsFile, setPointsFile] = useState<File | null>(null);

  const reset = () => {
    setRows([]);
    setChannelsFile(null);
    setPointsFile(null);
  };

  const pickChannels = (file: UploadFile) => {
    setChannelsFile(file as unknown as File);
    setRows([]);
    return false;
  };

  const pickPoints = (file: UploadFile) => {
    setPointsFile(file as unknown as File);
    setRows([]);
    return false;
  };

  const parseFiles = async () => {
    if (!channelsFile || !pointsFile) return;
    setParsing(true);
    try {
      const channels = await channelApi.parseCsv(channelsFile, pointsFile);
      if (channels.length === 0) {
        throw new Error('CSV 中未找到任何有效通道');
      }
      setRows(
        channels.map((c, i) => ({
          index: i,
          name: c.name,
          protocol: c.protocol,
          pointCount: pointCountOf(c),
          status: 'pending',
          body: c,
        }))
      );
      message.success(`已解析 ${channels.length} 条通道`);
    } catch (e) {
      const ax = e as AxiosError<{ message?: string }>;
      const backendMsg = ax.response?.data?.message;
      message.error(backendMsg ?? (e instanceof Error ? e.message : '解析失败'));
    } finally {
      setParsing(false);
    }
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
        // body 是后端 parse 完整 ChannelDTO（id 占位）；create 时 backend 忽略 id/createdAt/updatedAt
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
  const canParse = channelsFile != null && pointsFile != null && !parsing && !running;

  return (
    <Modal
      title="批量导入通道"
      open={open}
      width={780}
      onCancel={() => {
        if (running || parsing) return;
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
            disabled={running || parsing}
          >
            关闭
          </Button>
          <Button onClick={reset} disabled={running || parsing}>
            清空
          </Button>
          <Button onClick={parseFiles} disabled={!canParse} loading={parsing}>
            解析 CSV
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
      <Space style={{ width: '100%' }} direction="vertical" size="middle">
        <Upload
          accept=".csv,text/csv,application/vnd.ms-excel"
          multiple={false}
          maxCount={1}
          showUploadList={false}
          beforeUpload={pickChannels}
          disabled={parsing || running}
        >
          <Button icon={<InboxOutlined />}>
            {channelsFile ? `channels.csv：${channelsFile.name}` : '选择 channels.csv'}
          </Button>
        </Upload>
        <Upload
          accept=".csv,text/csv,application/vnd.ms-excel"
          multiple={false}
          maxCount={1}
          showUploadList={false}
          beforeUpload={pickPoints}
          disabled={parsing || running}
        >
          <Button icon={<InboxOutlined />}>
            {pointsFile ? `points.csv：${pointsFile.name}` : '选择 points.csv'}
          </Button>
        </Upload>
        <div style={{ color: 'var(--ems-color-text-tertiary)', fontSize: 12 }}>
          channels.csv 必填表头：name, protocol；按协议另填
          host/port/serialPort/endpointUrl/brokerUrl/pollInterval 等列。 points.csv
          必填表头：channelName, key；按协议填 address/nodeId/topic/virtualMode 等列。
        </div>
      </Space>

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
