import { apiClient } from './client';

/** 子项目 1.5 / Plan 1.5.1 — Collector 状态查询。 */

export interface PendingCertificate {
  thumbprint: string;
  channelId: number;
  endpointUrl: string;
  firstSeenAt: string; // ISO 8601
  subjectDn: string;
}

export type DeviceState = 'HEALTHY' | 'DEGRADED' | 'UNREACHABLE';

export interface DeviceStatusDTO {
  deviceId: string;
  meterCode: string;
  state: DeviceState;
  lastReadAt: string | null;
  lastTransitionAt: string | null;
  consecutiveErrors: number;
  successCount: number;
  failureCount: number;
  lastError: string | null;
}

export interface RunningInfo {
  running: boolean;
  deviceCount: number;
}

/** Plan 1.5.2 — POST /collector/reload 返回的 diff 摘要。 */
export interface ReloadResult {
  added: string[];
  removed: string[];
  modified: string[];
  unchanged: number;
}

export const collectorApi = {
  status: () => apiClient.get<DeviceStatusDTO[]>('/collector/status').then((r) => r.data),
  running: () => apiClient.get<RunningInfo>('/collector/running').then((r) => r.data),
  reload: () => apiClient.post<ReloadResult>('/collector/reload').then((r) => r.data),
};

export const certApi = {
  listPending: (): Promise<PendingCertificate[]> =>
    apiClient.get<PendingCertificate[]>('/collector/cert-pending').then((r) => r.data),
  trust: (channelId: number, thumbprint: string): Promise<void> =>
    apiClient
      .post(`/collector/${channelId}/trust-cert`, { thumbprint })
      .then(() => undefined),
  reject: (thumbprint: string): Promise<void> =>
    apiClient.delete(`/collector/cert-pending/${thumbprint}`).then(() => undefined),
};
