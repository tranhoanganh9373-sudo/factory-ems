import { apiClient } from './client';

export type Protocol = 'MODBUS_TCP' | 'MODBUS_RTU' | 'OPC_UA' | 'MQTT' | 'VIRTUAL';

export interface ChannelDTO {
  id: number;
  name: string;
  protocol: Protocol;
  enabled: boolean;
  isVirtual: boolean;
  protocolConfig: Record<string, unknown>;
  description?: string;
  createdAt: string;
  updatedAt: string;
}

export interface TestResult {
  success: boolean;
  message: string;
  latencyMs: number | null;
}

export const channelApi = {
  list: () => apiClient.get<ChannelDTO[]>('/channel').then((r) => r.data),
  get: (id: number) => apiClient.get<ChannelDTO>(`/channel/${id}`).then((r) => r.data),
  create: (body: Partial<ChannelDTO>) =>
    apiClient.post<ChannelDTO>('/channel', body).then((r) => r.data),
  update: (id: number, body: Partial<ChannelDTO>) =>
    apiClient.put<ChannelDTO>(`/channel/${id}`, body).then((r) => r.data),
  delete: (id: number) => apiClient.delete(`/channel/${id}`).then((r) => r.data),
  test: (id: number) => apiClient.post<TestResult>(`/channel/${id}/test`).then((r) => r.data),
};
