import { apiClient } from './client';

export type ValueKind = 'INTERVAL_DELTA' | 'CUMULATIVE_ENERGY' | 'INSTANT_POWER';

export interface MeterImportRow {
  code: string;
  name: string;
  energyTypeId: number;
  orgNodeId: number;
  enabled: boolean | null;
  channelName: string | null;
  channelPointKey: string | null;
  valueKind: ValueKind | null;
}

export interface EnergyTypeDTO {
  id: number;
  code: string;
  name: string;
  unit: string;
}

export interface MeterDTO {
  id: number;
  code: string;
  name: string;
  energyTypeId: number;
  energyTypeCode: string;
  energyTypeName: string;
  unit: string;
  orgNodeId: number;
  influxMeasurement: string;
  influxTagKey: string;
  influxTagValue: string;
  enabled: boolean;
  channelId: number | null;
  channelPointKey: string | null;
  parentMeterId: number | null;
  valueKind: ValueKind;
  createdAt: string;
  updatedAt: string;
}

export interface CreateMeterReq {
  code: string;
  name: string;
  energyTypeId: number;
  orgNodeId: number;
  enabled: boolean;
  channelId?: number | null;
  channelPointKey?: string | null;
  valueKind?: ValueKind | null;
}

export interface UpdateMeterReq {
  code: string;
  name: string;
  energyTypeId: number;
  orgNodeId: number;
  enabled: boolean;
  channelId?: number | null;
  channelPointKey?: string | null;
  valueKind?: ValueKind | null;
}

export interface BindParentMeterReq {
  parentMeterId: number;
}

// 后端 /meter-topology 只回边的两端 id（不含 code）；要 code 时用 meters 列表按 id 查。
export interface MeterTopologyEdgeDTO {
  childMeterId: number;
  parentMeterId: number;
}

export interface ListMetersParams {
  orgNodeId?: number;
  energyTypeId?: number;
  enabled?: boolean;
}

export const meterApi = {
  listMeters: (params?: ListMetersParams) =>
    apiClient.get<MeterDTO[]>('/meters', { params }).then((r) => r.data as unknown as MeterDTO[]),
  getMeter: (id: number) =>
    apiClient.get<MeterDTO>(`/meters/${id}`).then((r) => r.data as unknown as MeterDTO),
  createMeter: (req: CreateMeterReq) =>
    apiClient.post<MeterDTO>('/meters', req).then((r) => r.data as unknown as MeterDTO),
  updateMeter: (id: number, req: UpdateMeterReq) =>
    apiClient.put<MeterDTO>(`/meters/${id}`, req).then((r) => r.data as unknown as MeterDTO),
  deleteMeter: (id: number) => apiClient.delete(`/meters/${id}`),
  listTopology: () =>
    apiClient
      .get<MeterTopologyEdgeDTO[]>('/meter-topology')
      .then((r) => r.data as unknown as MeterTopologyEdgeDTO[]),
  bindParent: (childId: number, parentId: number) =>
    apiClient.put(`/meter-topology/${childId}`, { parentMeterId: parentId }),
  unbindParent: (childId: number) => apiClient.delete(`/meter-topology/${childId}`),
  listEnergyTypes: () =>
    apiClient
      .get<EnergyTypeDTO[]>('/energy-types')
      .then((r) => r.data as unknown as EnergyTypeDTO[]),
  parseCsv: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return apiClient
      .post<MeterImportRow[]>('/meters/parse-csv', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data as unknown as MeterImportRow[]);
  },
};
