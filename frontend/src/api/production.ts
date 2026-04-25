import { apiClient } from './client';
import { useAuthStore } from '@/stores/authStore';
import axios from 'axios';
import type { PageDTO } from './user';

export interface ShiftDTO {
  id: number;
  code: string;
  name: string;
  timeStart: string; // HH:mm:ss
  timeEnd: string;
  enabled: boolean;
  sortOrder: number;
}

export interface CreateShiftReq {
  code: string;
  name: string;
  timeStart: string;
  timeEnd: string;
  sortOrder?: number;
}

export interface UpdateShiftReq {
  name: string;
  timeStart: string;
  timeEnd: string;
  enabled?: boolean;
  sortOrder?: number;
}

export interface ProductionEntryDTO {
  id: number;
  orgNodeId: number;
  shiftId: number;
  entryDate: string; // YYYY-MM-DD
  productCode: string;
  quantity: string | number;
  unit: string;
  remark: string | null;
  createdAt: string;
}

export interface CreateProductionEntryReq {
  orgNodeId: number;
  shiftId: number;
  entryDate: string;
  productCode: string;
  quantity: number;
  unit: string;
  remark?: string;
}

export type UpdateProductionEntryReq = CreateProductionEntryReq;

export interface BulkImportError {
  rowNumber: number;
  message: string;
}

export interface BulkImportResult {
  total: number;
  succeeded: number;
  errors: BulkImportError[];
}

/** 跨零点检测：timeEnd <= timeStart 视为跨零点。 */
export function isCrossMidnight(start: string, end: string): boolean {
  const toMin = (t: string) => {
    const [h, m] = t.split(':').map(Number);
    return (h ?? 0) * 60 + (m ?? 0);
  };
  return toMin(end) <= toMin(start);
}

export const shiftApi = {
  list: (enabledOnly = false) =>
    apiClient
      .get<ShiftDTO[]>('/shifts', { params: { enabledOnly } })
      .then((r) => r.data as unknown as ShiftDTO[]),
  getById: (id: number) =>
    apiClient.get<ShiftDTO>(`/shifts/${id}`).then((r) => r.data as unknown as ShiftDTO),
  create: (req: CreateShiftReq) =>
    apiClient.post<ShiftDTO>('/shifts', req).then((r) => r.data as unknown as ShiftDTO),
  update: (id: number, req: UpdateShiftReq) =>
    apiClient.put<ShiftDTO>(`/shifts/${id}`, req).then((r) => r.data as unknown as ShiftDTO),
  delete: (id: number) => apiClient.delete(`/shifts/${id}`),
};

export const productionEntryApi = {
  search: (params: {
    from: string; // YYYY-MM-DD
    to: string;
    orgNodeId?: number;
    page?: number;
    size?: number;
  }) =>
    apiClient
      .get<PageDTO<ProductionEntryDTO>>('/production/entries', { params })
      .then((r) => r.data as unknown as PageDTO<ProductionEntryDTO>),
  create: (req: CreateProductionEntryReq) =>
    apiClient
      .post<ProductionEntryDTO>('/production/entries', req)
      .then((r) => r.data as unknown as ProductionEntryDTO),
  update: (id: number, req: UpdateProductionEntryReq) =>
    apiClient
      .put<ProductionEntryDTO>(`/production/entries/${id}`, req)
      .then((r) => r.data as unknown as ProductionEntryDTO),
  delete: (id: number) => apiClient.delete(`/production/entries/${id}`),
};

/**
 * Multipart CSV upload bypasses the standard Result-unwrap interceptor because the
 * raw axios + auth header is sufficient and we need the full response (with `data`).
 */
export async function uploadProductionCsv(file: File): Promise<BulkImportResult> {
  const fd = new FormData();
  fd.append('file', file);
  const token = useAuthStore.getState().accessToken;
  const res = await axios.post<{ code: number; message: string; data: BulkImportResult }>(
    '/api/v1/production/entries/import',
    fd,
    {
      headers: {
        Authorization: token ? `Bearer ${token}` : '',
      },
      withCredentials: true,
      timeout: 120_000,
    }
  );
  if (res.data.code !== 0) {
    throw new Error(res.data.message || 'CSV 导入失败');
  }
  return res.data.data;
}
