import { apiClient } from './client';
import { useAuthStore } from '@/stores/authStore';
import axios from 'axios';

export interface FloorplanDTO {
  id: number;
  name: string;
  orgNodeId: number;
  contentType: string;
  widthPx: number;
  heightPx: number;
  fileSizeBytes: number;
  enabled: boolean;
  createdAt: string;
}

export interface FloorplanPointDTO {
  id: number | null;
  meterId: number;
  xRatio: number | string;
  yRatio: number | string;
  label: string | null;
}

export interface FloorplanWithPointsDTO {
  floorplan: FloorplanDTO;
  points: FloorplanPointDTO[];
}

export interface UpdateFloorplanReq {
  name: string;
  enabled: boolean;
}

export interface SetPointEntry {
  meterId: number;
  xRatio: number;
  yRatio: number;
  label?: string | null;
}

export const floorplanApi = {
  list: (orgNodeId?: number) =>
    apiClient
      .get<FloorplanDTO[]>('/floorplans', { params: { orgNodeId } })
      .then((r) => r.data as unknown as FloorplanDTO[]),
  getById: (id: number) =>
    apiClient
      .get<FloorplanWithPointsDTO>(`/floorplans/${id}`)
      .then((r) => r.data as unknown as FloorplanWithPointsDTO),
  update: (id: number, req: UpdateFloorplanReq) =>
    apiClient.put(`/floorplans/${id}`, req),
  delete: (id: number) => apiClient.delete(`/floorplans/${id}`),
  setPoints: (id: number, points: SetPointEntry[]) =>
    apiClient
      .put<FloorplanWithPointsDTO>(`/floorplans/${id}/points`, { points })
      .then((r) => r.data as unknown as FloorplanWithPointsDTO),
};

/** Image URL with browser caching handled by backend. */
export function floorplanImageUrl(id: number): string {
  return `/api/v1/floorplans/${id}/image`;
}

export async function uploadFloorplan(
  file: File,
  name: string,
  orgNodeId: number
): Promise<FloorplanDTO> {
  const fd = new FormData();
  fd.append('file', file);
  fd.append('name', name);
  fd.append('orgNodeId', String(orgNodeId));
  const token = useAuthStore.getState().accessToken;
  const res = await axios.post<{ code: number; message: string; data: FloorplanDTO }>(
    '/api/v1/floorplans/upload',
    fd,
    {
      headers: { Authorization: token ? `Bearer ${token}` : '' },
      withCredentials: true,
      timeout: 120_000,
    }
  );
  if (res.data.code !== 0) {
    throw new Error(res.data.message || '上传失败');
  }
  return res.data.data;
}
