import { apiClient } from './client';
export interface RoleDTO {
  id: number;
  code: string;
  name: string;
  description?: string;
}
export const roleApi = {
  list: () => apiClient.get<RoleDTO[]>('/roles').then((r) => r.data as unknown as RoleDTO[]),
};
