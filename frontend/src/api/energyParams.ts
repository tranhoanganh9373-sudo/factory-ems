import { apiClient } from './client';
import type { EnergySource } from './meter';

// 上网电价 ────────────────────────────────────────────────────────────

export type FeedInPeriodType = 'PEAK' | 'FLAT' | 'VALLEY' | 'TIP' | 'SHARP';

export interface FeedInTariffDTO {
  id: number;
  region: string;
  energySource: EnergySource;
  periodType: FeedInPeriodType;
  effectiveFrom: string; // ISO YYYY-MM-DD
  price: number;
  createdAt: string; // ISO timestamptz
}

export interface CreateFeedInTariffReq {
  region: string;
  energySource: EnergySource;
  periodType: FeedInPeriodType;
  effectiveFrom: string;
  price: number | string;
}

// 碳排因子 ────────────────────────────────────────────────────────────

export interface CarbonFactorDTO {
  id: number;
  region: string;
  energySource: EnergySource;
  effectiveFrom: string;
  factorKgPerKwh: number;
  createdAt: string;
}

export interface CreateCarbonFactorReq {
  region: string;
  energySource: EnergySource;
  effectiveFrom: string;
  factorKgPerKwh: number | string;
}

// API client ─────────────────────────────────────────────────────────

export const energyParamsApi = {
  listFeedInTariff: () =>
    apiClient
      .get<FeedInTariffDTO[]>('/feed-in-tariff')
      .then((r) => r.data as unknown as FeedInTariffDTO[]),

  createFeedInTariff: (req: CreateFeedInTariffReq) =>
    apiClient
      .post<FeedInTariffDTO>('/feed-in-tariff', req)
      .then((r) => r.data as unknown as FeedInTariffDTO),

  listCarbonFactor: () =>
    apiClient
      .get<CarbonFactorDTO[]>('/carbon-factor')
      .then((r) => r.data as unknown as CarbonFactorDTO[]),

  createCarbonFactor: (req: CreateCarbonFactorReq) =>
    apiClient
      .post<CarbonFactorDTO>('/carbon-factor', req)
      .then((r) => r.data as unknown as CarbonFactorDTO),
};
