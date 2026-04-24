import { apiClient } from './client';
export interface NodePermissionDTO {
  id: number;
  userId: number;
  orgNodeId: number;
  scope: 'SUBTREE' | 'NODE_ONLY';
  createdAt: string;
}
export const permissionApi = {
  listByUser: (userId: number) =>
    apiClient
      .get<NodePermissionDTO[]>(`/users/${userId}/node-permissions`)
      .then((r) => r.data as unknown as NodePermissionDTO[]),
  assign: (userId: number, orgNodeId: number, scope: 'SUBTREE' | 'NODE_ONLY') =>
    apiClient
      .post<NodePermissionDTO>(`/users/${userId}/node-permissions`, { orgNodeId, scope })
      .then((r) => r.data as unknown as NodePermissionDTO),
  revoke: (userId: number, permissionId: number) =>
    apiClient.delete(`/users/${userId}/node-permissions/${permissionId}`),
};
