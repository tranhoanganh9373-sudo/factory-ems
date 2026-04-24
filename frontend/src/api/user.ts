import { apiClient } from './client';

export interface UserDTO {
  id: number;
  username: string;
  displayName?: string;
  enabled: boolean;
  roles: string[];
  lastLoginAt?: string;
  createdAt: string;
}
export interface PageDTO<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export const userApi = {
  list: (page = 1, size = 20, keyword?: string) =>
    apiClient
      .get<PageDTO<UserDTO>>('/users', { params: { page, size, keyword } })
      .then((r) => r.data as unknown as PageDTO<UserDTO>),
  getById: (id: number) =>
    apiClient.get<UserDTO>(`/users/${id}`).then((r) => r.data as unknown as UserDTO),
  create: (req: { username: string; password: string; displayName?: string; roleCodes?: string[] }) =>
    apiClient.post<UserDTO>('/users', req).then((r) => r.data as unknown as UserDTO),
  update: (id: number, req: { displayName?: string; enabled?: boolean }) =>
    apiClient.put<UserDTO>(`/users/${id}`, req).then((r) => r.data as unknown as UserDTO),
  delete: (id: number) => apiClient.delete(`/users/${id}`),
  assignRoles: (id: number, roleCodes: string[]) =>
    apiClient.put(`/users/${id}/roles`, { roleCodes }),
  resetPassword: (id: number, newPassword: string) =>
    apiClient.put(`/users/${id}/password/reset`, { newPassword }),
};
