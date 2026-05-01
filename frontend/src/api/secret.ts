import { apiClient } from './client';

export const secretApi = {
  list: () => apiClient.get<string[]>('/secrets').then((r) => r.data),
  write: (ref: string, value: string) =>
    apiClient.post('/secrets', { ref, value }).then((r) => r.data),
  delete: (ref: string) => apiClient.delete(`/secrets`, { params: { ref } }).then((r) => r.data),
};
