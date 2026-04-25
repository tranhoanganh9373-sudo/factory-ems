import { apiClient } from './client';

export type RangeType = 'TODAY' | 'YESTERDAY' | 'THIS_MONTH' | 'LAST_24H' | 'CUSTOM';

export interface DashboardQuery {
  range: RangeType;
  from?: string;
  to?: string;
  orgNodeId?: number;
  energyType?: string;
}

export interface KpiDTO {
  energyType: string;
  unit: string;
  total: number;
  mom: number | null;
  yoy: number | null;
}

export interface Bucket {
  ts: string;
  value: number;
}

export interface SeriesDTO {
  energyType: string;
  unit: string;
  points: Bucket[];
}

export interface CompositionDTO {
  energyType: string;
  unit: string;
  total: number;
  share: number;
}

export interface MeterPoint {
  ts: string;
  value: number;
}

export interface MeterDetailDTO {
  meterId: number;
  code: string;
  name: string;
  energyTypeCode: string;
  unit: string;
  orgNodeId: number;
  total: number;
  series: MeterPoint[];
}

export interface TopNItemDTO {
  meterId: number;
  code: string;
  name: string;
  energyTypeCode: string;
  unit: string;
  orgNodeId: number;
  total: number;
}

type Params = Record<string, string | number | undefined>;

function toParams(q: DashboardQuery): Params {
  return {
    range: q.range,
    from: q.from,
    to: q.to,
    orgNodeId: q.orgNodeId,
    energyType: q.energyType,
  };
}

export const dashboardApi = {
  getKpi: (q: DashboardQuery) =>
    apiClient
      .get<KpiDTO[]>('/dashboard/kpi', { params: toParams(q) })
      .then((r) => r.data as unknown as KpiDTO[]),

  getRealtimeSeries: (q: DashboardQuery) =>
    apiClient
      .get<SeriesDTO[]>('/dashboard/realtime-series', { params: toParams(q) })
      .then((r) => r.data as unknown as SeriesDTO[]),

  getEnergyComposition: (q: Omit<DashboardQuery, 'energyType'>) =>
    apiClient
      .get<CompositionDTO[]>('/dashboard/energy-composition', { params: toParams(q as DashboardQuery) })
      .then((r) => r.data as unknown as CompositionDTO[]),

  getMeterDetail: (id: number, q: Pick<DashboardQuery, 'range' | 'from' | 'to'>) =>
    apiClient
      .get<MeterDetailDTO>(`/dashboard/meter/${id}/detail`, {
        params: { range: q.range, from: q.from, to: q.to },
      })
      .then((r) => r.data as unknown as MeterDetailDTO),

  getTopN: (q: DashboardQuery, limit = 10) =>
    apiClient
      .get<TopNItemDTO[]>('/dashboard/top-n', { params: { ...toParams(q), limit } })
      .then((r) => r.data as unknown as TopNItemDTO[]),
};
