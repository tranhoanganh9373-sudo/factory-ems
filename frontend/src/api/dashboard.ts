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

export interface EnergyBreakdownItem {
  meterId: number | null;
  code: string | null;
  name: string;
  value: number;
  share: number | null;
  isResidual: boolean;
}
export interface EnergyBreakdownDTO {
  energyType: string;
  unit: string;
  rootTotal: number;
  items: EnergyBreakdownItem[];
}

export type TopologyConsistencySeverity =
  | 'OK'
  | 'INFO'
  | 'WARN'
  | 'WARN_NEGATIVE'
  | 'ALARM';

export interface TopologyConsistencyDTO {
  parentMeterId: number;
  parentCode: string;
  parentName: string;
  energyType: string;
  unit: string;
  parentReading: number;
  childrenSum: number;
  childrenCount: number;
  residual: number;
  residualRatio: number | null;
  severity: TopologyConsistencySeverity;
}

export interface TariffDistributionDTO {
  unit: string;
  total: number;
  slices: Array<{ periodType: string; value: number; share: number | null }>;
}

export interface EnergyIntensityPoint {
  date: string;
  electricity: number;
  production: number;
  intensity: number | null;
}
export interface EnergyIntensityDTO {
  electricityUnit: string;
  productionUnit: string;
  points: EnergyIntensityPoint[];
}

export interface SankeyDTO {
  nodes: Array<{ id: string; name: string; energyType: string; unit: string }>;
  links: Array<{ source: string; target: string; value: number }>;
}

export interface FloorplanLivePoint {
  pointId: number;
  meterId: number;
  meterCode: string;
  meterName: string;
  energyType: string;
  unit: string;
  xRatio: number | string;
  yRatio: number | string;
  label: string | null;
  value: number;
  level: 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';
}
export interface FloorplanLiveDTO {
  floorplan: {
    id: number;
    name: string;
    orgNodeId: number;
    contentType: string;
    widthPx: number;
    heightPx: number;
    fileSizeBytes: number;
    enabled: boolean;
    createdAt: string;
  };
  points: FloorplanLivePoint[];
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
      .get<
        CompositionDTO[]
      >('/dashboard/energy-composition', { params: toParams(q as DashboardQuery) })
      .then((r) => r.data as unknown as CompositionDTO[]),

  getMeterDetail: (id: number, q: Pick<DashboardQuery, 'range' | 'from' | 'to'>) =>
    apiClient
      .get<MeterDetailDTO>(`/dashboard/meter/${id}/detail`, {
        params: { range: q.range, from: q.from, to: q.to },
      })
      .then((r) => r.data as unknown as MeterDetailDTO),

  getTopN: (q: DashboardQuery, limit = 10, scope: 'LEAVES' | 'ROOTS' | 'ALL' = 'LEAVES') =>
    apiClient
      .get<TopNItemDTO[]>('/dashboard/top-n', {
        params: { ...toParams(q), limit, scope },
      })
      .then((r) => r.data as unknown as TopNItemDTO[]),

  getEnergyBreakdown: (q: DashboardQuery) =>
    apiClient
      .get<EnergyBreakdownDTO>('/dashboard/energy-breakdown', { params: toParams(q) })
      .then((r) => r.data as unknown as EnergyBreakdownDTO),

  getTopologyConsistency: (q: Pick<DashboardQuery, 'range' | 'from' | 'to' | 'orgNodeId'>) =>
    apiClient
      .get<TopologyConsistencyDTO[]>('/dashboard/topology-consistency', {
        params: { range: q.range, from: q.from, to: q.to, orgNodeId: q.orgNodeId },
      })
      .then((r) => r.data as unknown as TopologyConsistencyDTO[]),

  getTariffDistribution: (q: Omit<DashboardQuery, 'energyType'>) =>
    apiClient
      .get<TariffDistributionDTO>('/dashboard/tariff-distribution', {
        params: toParams(q as DashboardQuery),
      })
      .then((r) => r.data as unknown as TariffDistributionDTO),

  getEnergyIntensity: (q: Omit<DashboardQuery, 'energyType'>) =>
    apiClient
      .get<EnergyIntensityDTO>('/dashboard/energy-intensity', {
        params: toParams(q as DashboardQuery),
      })
      .then((r) => r.data as unknown as EnergyIntensityDTO),

  getSankey: (q: DashboardQuery) =>
    apiClient
      .get<SankeyDTO>('/dashboard/sankey', { params: toParams(q) })
      .then((r) => r.data as unknown as SankeyDTO),

  getFloorplanLive: (id: number, q: Omit<DashboardQuery, 'energyType'>) =>
    apiClient
      .get<FloorplanLiveDTO>(`/dashboard/floorplan/${id}/live`, {
        params: toParams(q as DashboardQuery),
      })
      .then((r) => r.data as unknown as FloorplanLiveDTO),
};
