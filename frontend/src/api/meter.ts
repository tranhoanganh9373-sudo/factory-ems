import { apiClient } from './client';

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
  parentMeterId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateMeterReq {
  code: string;
  name: string;
  energyTypeId: number;
  orgNodeId: number;
  influxMeasurement: string;
  influxTagKey: string;
  influxTagValue: string;
  enabled: boolean;
  channelId?: number | null;
}

export interface UpdateMeterReq {
  name: string;
  energyTypeId: number;
  orgNodeId: number;
  influxMeasurement: string;
  influxTagKey: string;
  influxTagValue: string;
  enabled: boolean;
  channelId?: number | null;
}

export interface BindParentMeterReq {
  parentMeterId: number;
}

export interface MeterTopologyEdgeDTO {
  childMeterId: number;
  childMeterCode: string;
  parentMeterId: number;
  parentMeterCode: string;
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
};
