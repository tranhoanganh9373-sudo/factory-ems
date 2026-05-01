import { apiClient } from './client';

// ───── 枚举类型 ─────
export type AlarmStatus = 'ACTIVE' | 'ACKED' | 'RESOLVED';
export type AlarmType = 'SILENT_TIMEOUT' | 'CONSECUTIVE_FAIL' | 'COMMUNICATION_FAULT';
export type DeliveryStatus = 'SUCCESS' | 'FAILED';
export type ResolvedReason = 'AUTO' | 'MANUAL';

// ───── DTO ─────
export interface AlarmListItemDTO {
  id: number;
  deviceId: number;
  deviceType: string;
  deviceCode: string;
  deviceName: string;
  alarmType: AlarmType;
  severity: string;
  status: AlarmStatus;
  triggeredAt: string;
  lastSeenAt: string | null;
  ackedAt: string | null;
}

export interface AlarmDTO extends AlarmListItemDTO {
  ackedBy: number | null;
  resolvedAt: string | null;
  resolvedReason: ResolvedReason | null;
  detail: Record<string, unknown> | null;
}

export interface PageDTO<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface HealthSummaryDTO {
  onlineCount: number;
  offlineCount: number;
  alarmCount: number;
  maintenanceCount: number;
  topOffenders: Array<{ deviceId: number; deviceCode: string; activeAlarmCount: number }>;
}

export interface DefaultsDTO {
  silentTimeoutSeconds: number;
  consecutiveFailCount: number;
  suppressionWindowSeconds: number;
}

export interface AlarmRuleOverrideDTO {
  deviceId: number;
  silentTimeoutSeconds: number | null;
  consecutiveFailCount: number | null;
  maintenanceMode: boolean;
  maintenanceNote: string | null;
  updatedAt: string;
  updatedBy: number | null;
}

export interface OverrideRequest {
  silentTimeoutSeconds?: number | null;
  consecutiveFailCount?: number | null;
  maintenanceMode: boolean;
  maintenanceNote?: string | null;
}

export interface WebhookConfigDTO {
  enabled: boolean;
  url: string;
  secret: string; // "***" 表示已设置但脱敏
  adapterType: string;
  timeoutMs: number;
  updatedAt: string | null;
}

export interface WebhookConfigRequest {
  enabled: boolean;
  url: string;
  secret?: string; // 留空表示保持原值
  adapterType?: string;
  timeoutMs: number;
}

export interface WebhookTestResultDTO {
  statusCode: number;
  durationMs: number;
  error: string | null;
}

export interface DeliveryLogDTO {
  id: number;
  alarmId: number;
  attempts: number;
  status: DeliveryStatus;
  lastError: string | null;
  responseStatus: number | null;
  responseMs: number | null;
  createdAt: string;
}

// ───── 告警操作 ─────
export const alarmApi = {
  list: (params: {
    status?: AlarmStatus;
    deviceId?: number;
    alarmType?: AlarmType;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
  }) => apiClient.get<PageDTO<AlarmListItemDTO>>('/alarms', { params }).then((r) => r.data),

  getById: (id: number) => apiClient.get<AlarmDTO>(`/alarms/${id}`).then((r) => r.data),

  ack: (id: number) => apiClient.post<void>(`/alarms/${id}/ack`).then((r) => r.data),

  resolve: (id: number) => apiClient.post<void>(`/alarms/${id}/resolve`).then((r) => r.data),

  activeCount: () =>
    apiClient.get<{ count: number }>('/alarms/active/count').then((r) => r.data.count),

  healthSummary: () =>
    apiClient.get<HealthSummaryDTO>('/alarms/health-summary').then((r) => r.data),
};

// ───── 阈值规则 ─────
export const alarmRuleApi = {
  getDefaults: () => apiClient.get<DefaultsDTO>('/alarm-rules/defaults').then((r) => r.data),

  listOverrides: () =>
    apiClient.get<AlarmRuleOverrideDTO[]>('/alarm-rules/overrides').then((r) => r.data),

  getOverride: (deviceId: number) =>
    apiClient.get<AlarmRuleOverrideDTO>(`/alarm-rules/overrides/${deviceId}`).then((r) => r.data),

  setOverride: (deviceId: number, req: OverrideRequest) =>
    apiClient
      .put<AlarmRuleOverrideDTO>(`/alarm-rules/overrides/${deviceId}`, req)
      .then((r) => r.data),

  clearOverride: (deviceId: number) =>
    apiClient.delete<void>(`/alarm-rules/overrides/${deviceId}`).then((r) => r.data),
};

// ───── Webhook ─────
export const webhookApi = {
  get: () => apiClient.get<WebhookConfigDTO>('/webhook-config').then((r) => r.data),

  update: (req: WebhookConfigRequest) =>
    apiClient.put<WebhookConfigDTO>('/webhook-config', req).then((r) => r.data),

  test: (req: WebhookConfigRequest) =>
    apiClient.post<WebhookTestResultDTO>('/webhook-config/test', req).then((r) => r.data),

  listDeliveries: (params: { page?: number; size?: number }) =>
    apiClient.get<PageDTO<DeliveryLogDTO>>('/webhook-deliveries', { params }).then((r) => r.data),

  retry: (id: number) =>
    apiClient.post<void>(`/webhook-deliveries/${id}/retry`).then((r) => r.data),
};
