import { useState } from 'react';
import { App as AntApp, Button, Modal, Space, Table, Tag, Upload } from 'antd';
import type { UploadFile } from 'antd/es/upload/interface';
import { InboxOutlined } from '@ant-design/icons';
import { AxiosError } from 'axios';
import { useQueryClient } from '@tanstack/react-query';
import { meterApi, type CreateMeterReq, type MeterImportRow } from '@/api/meter';
import { channelApi } from '@/api/channel';

type RowStatus = 'pending' | 'loading' | 'success' | 'skip' | 'fail';

interface ImportRow {
  index: number;
  code: string;
  name: string;
  channelLabel: string;
  status: RowStatus;
  message?: string;
  body: MeterImportRow;
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
      // 后端 /meters/parse-csv 解析 CSV 为 MeterImportRow[]，比前端 JSON 转换更宽容（BOM/空白行/中文 enabled 等）
      const meters = await meterApi.parseCsv(obj);
      if (meters.length === 0) {
        throw new Error('CSV 中未找到任何有效行');
      }
      setRows(
        meters.map((m, i) => ({
          index: i,
          code: m.code,
          name: m.name,
          channelLabel: m.channelName ?? '—',
          status: 'pending',
          body: m,
        }))
      );
      setFileName(obj.name);
      message.success(`已读取 ${meters.length} 条 meter`);
    } catch (e) {
      const ax = e as AxiosError<{ message?: string }>;
      const backendMsg = ax.response?.data?.message;
      message.error(backendMsg ?? (e instanceof Error ? e.message : '读取失败'));
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

      const { channelName } = row.body;
      let channelId: number | null = null;
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

      // 只有当 channel 真的解析到 id 时才把 channelPointKey 一同提交，
      // 镜像后端 chk_meters_channel_pair_consistent CHECK 约束（V2.3.2）
      const channelPointKey =
        channelId != null ? (row.body.channelPointKey ?? row.body.code) : null;
      const payload: CreateMeterReq = {
        code: row.body.code,
        name: row.body.name,
        energyTypeId: row.body.energyTypeId,
        orgNodeId: row.body.orgNodeId,
        enabled: row.body.enabled ?? true,
        channelId,
        channelPointKey,
        valueKind: row.body.valueKind ?? 'INTERVAL_DELTA',
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
        accept=".csv,text/csv,application/vnd.ms-excel"
        multiple={false}
        showUploadList={false}
        beforeUpload={handleFile}
        disabled={running}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">
          {fileName ? `已选择：${fileName}` : '点击或拖入 meters CSV 文件（Excel 另存为 CSV）'}
        </p>
        <p className="ant-upload-hint">
          表头：code, name, energyTypeId, orgNodeId（必填）；enabled, channelName,
          channelPointKey（可选； 若不填 channelPointKey 则默认与 code 相同，向后兼容）
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
