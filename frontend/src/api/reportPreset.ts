import axios from 'axios';
import { useAuthStore } from '@/stores/authStore';
import { apiClient } from './client';

/**
 * Phase R — Preset reports.
 *
 * NOTE: Phase L (backend) shipped the ReportPresetService but no controller is
 * yet wired. The endpoints below match the documented design from
 * docs/superpowers/plans/2026-04-24-factory-ems-plan-1.3-full-features-skeleton.md
 * and tolerate 404 (return null) so the UI degrades gracefully until backend
 * Phase L-controller / Phase M (export) ship.
 */

export type PresetKind = 'daily' | 'monthly' | 'yearly' | 'shift';

export interface ReportMatrix {
  rowAxis: string; // e.g. "orgNode"
  colAxis: string; // e.g. "hour" / "date" / "month" / "energyType"
  rowLabels: string[];
  colLabels: string[];
  values: (number | null)[][]; // values[r][c]
  unit?: string | null;
}

export interface PresetQueryBase {
  orgNodeId?: number;
  energyTypes?: string[];
}

export interface DailyQuery extends PresetQueryBase {
  date: string; // YYYY-MM-DD
}
export interface MonthlyQuery extends PresetQueryBase {
  month: string; // YYYY-MM
}
export interface YearlyQuery extends PresetQueryBase {
  year: number;
}
export interface ShiftQuery extends PresetQueryBase {
  date: string;
  shiftId: number;
}

async function safeGet<T>(path: string, params: Record<string, unknown>): Promise<T | null> {
  try {
    const res = await apiClient.get<T>(path, { params });
    return res.data as unknown as T;
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status;
    if (status === 404) return null;
    throw e;
  }
}

export const presetReportApi = {
  daily: (q: DailyQuery) =>
    safeGet<ReportMatrix>('/report/preset/daily', {
      date: q.date,
      orgNodeId: q.orgNodeId,
      energyType: q.energyTypes,
    }),
  monthly: (q: MonthlyQuery) =>
    safeGet<ReportMatrix>('/report/preset/monthly', {
      month: q.month,
      orgNodeId: q.orgNodeId,
      energyType: q.energyTypes,
    }),
  yearly: (q: YearlyQuery) =>
    safeGet<ReportMatrix>('/report/preset/yearly', {
      year: q.year,
      orgNodeId: q.orgNodeId,
      energyType: q.energyTypes,
    }),
  shift: (q: ShiftQuery) =>
    safeGet<ReportMatrix>('/report/preset/shift', {
      date: q.date,
      shiftId: q.shiftId,
      orgNodeId: q.orgNodeId,
      energyType: q.energyTypes,
    }),
};

export type ExportFormat = 'CSV' | 'EXCEL' | 'PDF';

export interface ExportRequest {
  preset: PresetKind;
  format: ExportFormat;
  date?: string;
  month?: string;
  year?: number;
  shiftId?: number;
  orgNodeId?: number;
  energyTypes?: string[];
}

export interface ExportTokenDTO {
  token: string;
  status: 'PENDING' | 'RUNNING' | 'READY' | 'FAILED';
  filename: string;
  createdAt: string;
  expiresAt: string;
  bytes?: number | null;
  error?: string | null;
}

const rawClient = axios.create({
  baseURL: '/api/v1',
  timeout: 60_000,
  withCredentials: true,
});
rawClient.interceptors.request.use((cfg) => {
  const t = useAuthStore.getState().accessToken;
  if (t && cfg.headers) cfg.headers.Authorization = `Bearer ${t}`;
  return cfg;
});

/** Submit an async export job. Returns the token DTO; tolerates 404 (returns null). */
export async function submitExport(req: ExportRequest): Promise<ExportTokenDTO | null> {
  try {
    // Endpoint per plan-1.3 spec: POST /api/v1/reports/export
    const res = await rawClient.post<{ code: number; message: string; data: ExportTokenDTO } | ExportTokenDTO>(
      '/reports/export',
      req
    );
    const body = res.data as { code?: number; data?: ExportTokenDTO } & ExportTokenDTO;
    return body && 'data' in body && body.code === 0 ? body.data! : (body as ExportTokenDTO);
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status;
    if (status === 404) return null;
    throw e;
  }
}

/** Poll status for an async export. Returns DTO when not ready, Blob when READY, null on 410/404. */
export async function pollExport(
  token: string
): Promise<ExportTokenDTO | Blob | null> {
  try {
    const res = await rawClient.get<Blob>(`/reports/export/${token}`, {
      responseType: 'blob',
    });
    const ct = (res.headers['content-type'] as string) || '';
    if (
      ct.includes('text/csv') ||
      ct.includes('application/vnd.openxmlformats') ||
      ct.includes('application/pdf') ||
      ct.includes('application/octet-stream')
    ) {
      return res.data;
    }
    const text = await (res.data as Blob).text();
    return JSON.parse(text) as ExportTokenDTO;
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status;
    if (status === 404 || status === 410) return null;
    throw e;
  }
}

export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
