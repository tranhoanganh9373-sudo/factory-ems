import { apiClient } from './client';

export interface OrgNodeDTO {
  id: number;
  parentId: number | null;
  name: string;
  code: string;
  nodeType: string;
  sortOrder: number;
  createdAt: string;
  children: OrgNodeDTO[];
}

export interface CreateOrgNodeReq {
  parentId?: number | null;
  name: string;
  code: string;
  nodeType: string;
  sortOrder?: number;
}
export interface UpdateOrgNodeReq {
  name: string;
  nodeType: string;
  sortOrder?: number;
}

export const orgTreeApi = {
  getTree: (rootId?: number) =>
    apiClient
      .get<OrgNodeDTO[]>('/org-nodes/tree', { params: { rootId } })
      .then((r) => r.data as unknown as OrgNodeDTO[]),
  create: (req: CreateOrgNodeReq) =>
    apiClient.post<OrgNodeDTO>('/org-nodes', req).then((r) => r.data as unknown as OrgNodeDTO),
  update: (id: number, req: UpdateOrgNodeReq) =>
    apiClient.put<OrgNodeDTO>(`/org-nodes/${id}`, req).then((r) => r.data as unknown as OrgNodeDTO),
  move: (id: number, newParentId: number | null) =>
    apiClient.patch(`/org-nodes/${id}/move`, { newParentId }),
  delete: (id: number) => apiClient.delete(`/org-nodes/${id}`),
};
