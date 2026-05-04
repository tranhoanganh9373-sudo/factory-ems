import { Modal, Descriptions, Typography } from 'antd';
import { AuditLogDTO } from '@/api/audit';
import { formatDateTime } from '@/utils/format';

export function DetailModal({ log, onClose }: { log: AuditLogDTO | null; onClose: () => void }) {
  if (!log) return null;
  let detailObj: Record<string, unknown> | null = null;
  try {
    detailObj = log.detail ? JSON.parse(log.detail) : null;
  } catch {
    detailObj = null;
  }
  return (
    <Modal open={!!log} onCancel={onClose} footer={null} title="审计详情" width={800}>
      <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="时间">
          <span style={{ fontVariantNumeric: 'tabular-nums' }}>
            {formatDateTime(log.occurredAt)}
          </span>
        </Descriptions.Item>
        <Descriptions.Item label="操作者">
          {log.actorUsername} (id={log.actorUserId})
        </Descriptions.Item>
        <Descriptions.Item label="动作">{log.action}</Descriptions.Item>
        <Descriptions.Item label="资源">
          {log.resourceType} / {log.resourceId}
        </Descriptions.Item>
        <Descriptions.Item label="概述">{log.summary}</Descriptions.Item>
        <Descriptions.Item label="IP">{log.ip}</Descriptions.Item>
        <Descriptions.Item label="User-Agent">{log.userAgent}</Descriptions.Item>
      </Descriptions>
      <Typography.Title level={5} style={{ marginTop: 16 }}>
        详情
      </Typography.Title>
      <pre style={{ background: '#fafafa', padding: 12, maxHeight: 320, overflow: 'auto' }}>
        {detailObj ? JSON.stringify(detailObj, null, 2) : (log.detail ?? '（无）')}
      </pre>
    </Modal>
  );
}
