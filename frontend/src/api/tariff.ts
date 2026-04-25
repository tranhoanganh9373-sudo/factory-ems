import { apiClient } from './client';

export type PeriodType = 'SHARP' | 'PEAK' | 'FLAT' | 'VALLEY';

export interface TariffPeriodDTO {
  id?: number | null;
  periodType: PeriodType | string;
  timeStart: string; // "HH:mm" or "HH:mm:ss"
  timeEnd: string;
  pricePerUnit: number | string;
}

export interface TariffPlanDTO {
  id: number;
  name: string;
  energyTypeId: number;
  effectiveFrom: string; // YYYY-MM-DD
  effectiveTo: string | null;
  enabled: boolean;
  periods: TariffPeriodDTO[];
  createdAt: string;
}

export interface CreateTariffPlanReq {
  name: string;
  energyTypeId: number;
  effectiveFrom: string;
  effectiveTo?: string | null;
  periods: TariffPeriodDTO[];
}

export interface UpdateTariffPlanReq {
  name: string;
  effectiveTo?: string | null;
  enabled?: boolean;
  periods?: TariffPeriodDTO[];
}

export interface ResolvedPriceDTO {
  periodType: string;
  pricePerUnit: number;
  planId: number;
}

export const tariffApi = {
  list: () =>
    apiClient.get<TariffPlanDTO[]>('/tariff/plans').then((r) => r.data as unknown as TariffPlanDTO[]),
  getById: (id: number) =>
    apiClient.get<TariffPlanDTO>(`/tariff/plans/${id}`).then((r) => r.data as unknown as TariffPlanDTO),
  create: (req: CreateTariffPlanReq) =>
    apiClient.post<TariffPlanDTO>('/tariff/plans', req).then((r) => r.data as unknown as TariffPlanDTO),
  update: (id: number, req: UpdateTariffPlanReq) =>
    apiClient.put<TariffPlanDTO>(`/tariff/plans/${id}`, req).then((r) => r.data as unknown as TariffPlanDTO),
  delete: (id: number) => apiClient.delete(`/tariff/plans/${id}`),
  resolve: (energyTypeId: number, atIso: string) =>
    apiClient
      .get<ResolvedPriceDTO>('/tariff/resolve', { params: { energyTypeId, at: atIso } })
      .then((r) => r.data as unknown as ResolvedPriceDTO),
};
