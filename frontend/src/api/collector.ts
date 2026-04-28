import { apiClient } from './client';

/** 子项目 1.5 / Plan 1.5.1 — Collector 状态查询。 */

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
