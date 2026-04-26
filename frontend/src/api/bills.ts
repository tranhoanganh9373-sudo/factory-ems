import { apiClient } from './client';
import type { EnergyTypeCode } from './cost';

export type BillPeriodStatus = 'OPEN' | 'CLOSED' | 'LOCKED';

export interface BillPeriodDTO {
  id: number;
  yearMonth: string;
  status: BillPeriodStatus;
  periodStart: string;
  periodEnd: string;
  closedAt: string | null;
  closedBy: number | null;
  lockedAt: string | null;
  lockedBy: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface BillDTO {
  id: number;
  periodId: number;
  runId: number;
  orgNodeId: number;
  energyType: EnergyTypeCode;
  quantity: string;
  amount: string;
  sharpAmount: string;
  peakAmount: string;
  flatAmount: string;
  valleyAmount: string;
  productionQty: string | null;
  unitCost: string | null;
  unitIntensity: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BillLineDTO {
  id: number;
  billId: number;
  ruleId: number;
  sourceLabel: string;
  quantity: string;
  amount: string;
  createdAt: string;
}

export interface CostDistributionItem {
  orgNodeId: number;
  orgName: string;
  quantity: string;
  amount: string;
  percent: number;
}

export interface CostDistributionDTO {
  runId: number | null;
  runFinishedAt: string | null;
  totalAmount: string;
  items: CostDistributionItem[];
}

export const billsApi = {
  // ----- periods -----
  listPeriods: () =>
    apiClient
      .get<BillPeriodDTO[]>('/bills/periods')
      .then((r) => r.data as unknown as BillPeriodDTO[]),

  getPeriodByYm: (ym: string) =>
    apiClient
      .get<BillPeriodDTO>(`/bills/periods/${ym}`)
      .then((r) => r.data as unknown as BillPeriodDTO),

  ensurePeriod: (ym: string) =>
    apiClient
      .post<BillPeriodDTO>('/bills/periods', null, { params: { ym } })
      .then((r) => r.data as unknown as BillPeriodDTO),

  closePeriod: (id: number) =>
    apiClient
      .put<BillPeriodDTO>(`/bills/periods/${id}/close`)
      .then((r) => r.data as unknown as BillPeriodDTO),

  lockPeriod: (id: number) =>
    apiClient
      .put<BillPeriodDTO>(`/bills/periods/${id}/lock`)
      .then((r) => r.data as unknown as BillPeriodDTO),

  unlockPeriod: (id: number) =>
    apiClient
      .put<BillPeriodDTO>(`/bills/periods/${id}/unlock`)
      .then((r) => r.data as unknown as BillPeriodDTO),

  // ----- bills -----
  listBills: (params: { periodId: number; orgNodeId?: number; energyType?: EnergyTypeCode }) =>
    apiClient.get<BillDTO[]>('/bills', { params }).then((r) => r.data as unknown as BillDTO[]),

  getBill: (id: number) =>
    apiClient.get<BillDTO>(`/bills/${id}`).then((r) => r.data as unknown as BillDTO),

  getBillLines: (id: number) =>
    apiClient
      .get<BillLineDTO[]>(`/bills/${id}/lines`)
      .then((r) => r.data as unknown as BillLineDTO[]),

  // ----- dashboard cost-distribution -----
  costDistribution: (period?: string) =>
    apiClient
      .get<CostDistributionDTO>('/dashboard/cost-distribution', {
        params: period ? { period } : {},
      })
      .then((r) => r.data as unknown as CostDistributionDTO),
};
