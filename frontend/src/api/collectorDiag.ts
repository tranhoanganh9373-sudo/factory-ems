import { apiClient } from './client';

export interface ChannelRuntimeState {
  channelId: number;
  protocol: string;
  connState: 'CONNECTING' | 'CONNECTED' | 'DISCONNECTED' | 'ERROR';
  lastConnectAt?: string;
  lastSuccessAt?: string;
  lastFailureAt?: string;
  lastErrorMessage?: string;
  successCount24h: number;
  failureCount24h: number;
  avgLatencyMs: number;
  protocolMeta: Record<string, unknown>;
}

export interface DiagTestResult {
  success: boolean;
  message: string;
  latencyMs: number | null;
}

export interface RecentSample {
  pointKey: string;
  timestamp: string;
  value: unknown;
  quality: string;
  tags: Record<string, string>;
}

export const collectorDiagApi = {
  list: () => apiClient.get<ChannelRuntimeState[]>('/collector/state').then((r) => r.data),
  get: (id: number) =>
    apiClient.get<ChannelRuntimeState>(`/collector/${id}/state`).then((r) => r.data),
  test: (id: number) => apiClient.post<DiagTestResult>(`/collector/${id}/test`).then((r) => r.data),
  reconnect: (id: number) => apiClient.post(`/collector/${id}/reconnect`).then((r) => r.data),
  recentSamples: (id: number, limit = 20) =>
    apiClient
      .get<RecentSample[]>(`/collector/${id}/recent-samples?limit=${limit}`)
      .then((r) => r.data),
};
