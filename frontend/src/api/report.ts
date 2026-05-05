import axios from 'axios';
import { useAuthStore } from '@/stores/authStore';
import { apiClient } from './client';

// Granularity matches the backend enum com.ems.timeseries.model.Granularity
export type Granularity = 'HOUR' | 'DAY' | 'MONTH';

export interface ReportRequest {
  from: string; // ISO Instant e.g. "2024-01-01T00:00:00Z"
  to: string;
  granularity: Granularity;
  orgNodeId?: number | null;
  energyTypes?: string[] | null; // backend field is energyTypes (plural)
  meterIds?: number[] | null;
}

export interface FileTokenDTO {
  token: string;
  status: 'PENDING' | 'RUNNING' | 'READY' | 'FAILED';
  filename: string;
  createdAt: string;
  expiresAt: string;
  bytes?: number | null;
  error?: string | null;
}

// Raw axios instance that skips the Result<T> unwrap interceptor.
// The auth interceptor is replicated inline so that JWT is still attached.
const rawClient = axios.create({
  baseURL: '/api/v1',
  timeout: 60_000,
  withCredentials: true,
});

rawClient.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/** Trigger a browser download from a Blob. */
export function triggerDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/** Build URLSearchParams, appending multi-value arrays correctly. */
function buildParams(req: ReportRequest): URLSearchParams {
  const p = new URLSearchParams();
  p.set('from', req.from);
  p.set('to', req.to);
  p.set('granularity', req.granularity);
  if (req.orgNodeId != null) p.set('orgNodeId', String(req.orgNodeId));
  if (req.energyTypes?.length) {
    req.energyTypes.forEach((e) => p.append('energyType', e));
  }
  if (req.meterIds?.length) {
    req.meterIds.forEach((id) => p.append('meterId', String(id)));
  }
  return p;
}

/**
 * Synchronous ad-hoc export — streams CSV, returns the Blob.
 * Note: report endpoints are not ADMIN-gated; all authenticated users may access.
 */
export async function downloadAdHocSync(req: ReportRequest): Promise<Blob> {
  const params = buildParams(req);
  const res = await rawClient.get<Blob>(`/report/ad-hoc?${params.toString()}`, {
    responseType: 'blob',
  });
  return res.data;
}

/** Submit async export job; returns the FileTokenDTO (202). */
export async function submitAdHocAsync(req: ReportRequest): Promise<FileTokenDTO> {
  const res = await rawClient.post<FileTokenDTO>('/report/ad-hoc/async', req);
  return res.data;
}

/**
 * Poll or retrieve an async export by token.
 * Returns FileTokenDTO when status is PENDING/RUNNING/FAILED (Content-Type: application/json).
 * Returns Blob when status is READY (Content-Type: text/csv).
 * Throws on 410 (expired/not-found).
 */
export async function getFileToken(
  token: string,
  download: boolean = false
): Promise<FileTokenDTO | Blob> {
  // Polling 默认返 DTO（status 含 READY 让 UI 表格能展示）；?download=true 才取 blob+evict。
  const url = download ? `/report/file/${token}?download=true` : `/report/file/${token}`;
  const res = await rawClient.get<FileTokenDTO | Blob>(url, {
    responseType: 'blob', // always receive as blob, then branch
  });
  const contentType = (res.headers['content-type'] as string) || '';
  if (contentType.includes('text/csv') || contentType.includes('application/octet-stream')) {
    return res.data as Blob;
  }
  // JSON DTO: deserialize the blob back to text then parse
  const text = await (res.data as Blob).text();
  return JSON.parse(text) as FileTokenDTO;
}

export interface CarbonReportDTO {
  selfConsumptionKwh: number;
  gridFactor: number;
  solarFactor: number;
  reductionKg: number;
}

export const fetchCarbon = (params: { orgNodeId: number; from: string; to: string }) =>
  apiClient
    .get<CarbonReportDTO>('/report/carbon', { params })
    .then((r) => r.data as unknown as CarbonReportDTO);
