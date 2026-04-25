import { useState } from 'react';
import { App, Button, Space } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import {
  submitExport,
  pollExport,
  downloadBlob,
  type ExportFormat,
  type ExportRequest,
  type ExportTokenDTO,
} from '@/api/reportPreset';

interface Props {
  buildRequest: (fmt: ExportFormat) => ExportRequest;
  defaultFilename?: string;
}

const POLL_INTERVAL_MS = 1500;
const MAX_POLLS = 80; // ~120s

/** Buttons: 导出 CSV / Excel / PDF — submits async job then polls until READY. */
export default function ExportButtons({ buildRequest, defaultFilename = 'report' }: Props) {
  const { message } = App.useApp();
  const [pending, setPending] = useState<ExportFormat | null>(null);

  async function handleExport(fmt: ExportFormat) {
    setPending(fmt);
    try {
      const submitted = await submitExport(buildRequest(fmt));
      if (!submitted) {
        message.warning(`导出端点暂未上线（Phase M），${fmt} 暂不可用`);
        return;
      }
      const token = submitted.token;
      let dto: ExportTokenDTO | null = submitted;
      for (let i = 0; i < MAX_POLLS; i++) {
        await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
        const r = await pollExport(token);
        if (r == null) {
          message.error('导出失败：token 已过期或不存在');
          return;
        }
        if (r instanceof Blob) {
          const ext = fmt === 'EXCEL' ? 'xlsx' : fmt.toLowerCase();
          downloadBlob(r, dto?.filename || `${defaultFilename}.${ext}`);
          message.success(`${fmt} 导出完成`);
          return;
        }
        dto = r;
        if (dto.status === 'FAILED') {
          message.error(`导出失败：${dto.error ?? 'unknown'}`);
          return;
        }
      }
      message.warning('导出仍在排队，请稍后到任务列表查看');
    } catch (e: unknown) {
      const m = e instanceof Error ? e.message : '导出失败';
      message.error(m);
    } finally {
      setPending(null);
    }
  }

  return (
    <Space>
      <Button
        icon={<DownloadOutlined />}
        loading={pending === 'EXCEL'}
        onClick={() => handleExport('EXCEL')}
      >
        导出 Excel
      </Button>
      <Button
        icon={<DownloadOutlined />}
        loading={pending === 'PDF'}
        onClick={() => handleExport('PDF')}
      >
        导出 PDF
      </Button>
      <Button
        icon={<DownloadOutlined />}
        loading={pending === 'CSV'}
        onClick={() => handleExport('CSV')}
      >
        导出 CSV
      </Button>
    </Space>
  );
}
