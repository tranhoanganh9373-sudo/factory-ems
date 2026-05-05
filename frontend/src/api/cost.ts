import { apiClient } from './client';

export type EnergyTypeCode = 'ELEC' | 'WATER' | 'GAS' | 'STEAM' | 'OIL';
export type AllocationAlgorithm = 'DIRECT' | 'PROPORTIONAL' | 'RESIDUAL' | 'COMPOSITE';
export type RunStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SUPERSEDED';

export interface CostRuleDTO {
  id: number;
  code: string;
  name: string;
  description: string | null;
  energyType: EnergyTypeCode;
  algorithm: AllocationAlgorithm;
  sourceMeterId: number;
  targetOrgIds: number[];
  weights: Record<string, unknown>;
  priority: number;
  enabled: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCostRuleReq {
  code: string;
  name: string;
  description?: string | null;
  energyType: EnergyTypeCode;
  algorithm: AllocationAlgorithm;
  sourceMeterId: number;
  targetOrgIds: number[];
  weights: Record<string, unknown>;
  priority?: number;
  enabled?: boolean;
  effectiveFrom: string;
  effectiveTo?: string | null;
}

export interface UpdateCostRuleReq {
  name?: string;
  description?: string | null;
  algorithm?: AllocationAlgorithm;
  sourceMeterId?: number;
  targetOrgIds?: number[];
  weights?: Record<string, unknown>;
  priority?: number;
  enabled?: boolean;
  effectiveFrom?: string;
  effectiveTo?: string | null;
}

export interface CostLineDTO {
  id: number;
  runId: number;
  ruleId: number;
  targetOrgId: number;
  energyType: EnergyTypeCode;
  quantity: string;
  amount: string;
  sharpQuantity: string;
  peakQuantity: string;
  flatQuantity: string;
  valleyQuantity: string;
  sharpAmount: string;
  peakAmount: string;
  flatAmount: string;
  valleyAmount: string;
  createdAt: string;
}

export interface CostRunDTO {
  id: number;
  periodStart: string;
  periodEnd: string;
  status: RunStatus;
  algorithmVersion: string;
  totalAmount: string | null;
  ruleIds: number[];
  createdBy: number | null;
  createdAt: string;
  finishedAt: string | null;
  errorMessage: string | null;
}

export interface SubmitRunReq {
  periodStart: string;
  periodEnd: string;
  ruleIds?: number[] | null;
}

export interface DryRunReq {
  periodStart: string;
  periodEnd: string;
}

export const costApi = {
  // ----- rules -----
  listRules: () =>
    apiClient.get<CostRuleDTO[]>('/cost/rules').then((r) => r.data as unknown as CostRuleDTO[]),
  getRule: (id: number) =>
    apiClient.get<CostRuleDTO>(`/cost/rules/${id}`).then((r) => r.data as unknown as CostRuleDTO),
  createRule: (req: CreateCostRuleReq) =>
    apiClient.post<CostRuleDTO>('/cost/rules', req).then((r) => r.data as unknown as CostRuleDTO),
  updateRule: (id: number, req: UpdateCostRuleReq) =>
    apiClient
      .put<CostRuleDTO>(`/cost/rules/${id}`, req)
      .then((r) => r.data as unknown as CostRuleDTO),
  deleteRule: (id: number) => apiClient.delete(`/cost/rules/${id}`),

  dryRunRule: (ruleId: number, req: DryRunReq) =>
    apiClient
      .post<CostLineDTO[]>(`/cost/rules/${ruleId}/dry-run`, req)
      .then((r) => r.data as unknown as CostLineDTO[]),

  dryRunAll: (req: DryRunReq) =>
    apiClient
      .post<CostLineDTO[]>('/cost/dry-run-all', req)
      .then((r) => r.data as unknown as CostLineDTO[]),

  // ----- runs -----
  submitRun: (req: SubmitRunReq) =>
    apiClient
      .post<{ runId: number }>('/cost/runs', req)
      .then((r) => r.data as unknown as { runId: number }),

  getRun: (runId: number) =>
    apiClient.get<CostRunDTO>(`/cost/runs/${runId}`).then((r) => r.data as unknown as CostRunDTO),

  getRunLines: (runId: number, orgNodeId?: number) =>
    apiClient
      .get<CostLineDTO[]>(`/cost/runs/${runId}/lines`, {
        params: orgNodeId == null ? {} : { orgNodeId },
      })
      .then((r) => r.data as unknown as CostLineDTO[]),
};

export interface SavingsDTO {
  amount: number;
  feedInRevenue: number;
  netAmount: number;
}

export const fetchSavings = (params: { orgNodeId: number; from: string; to: string }) =>
  apiClient
    .get<SavingsDTO>('/cost/savings', { params })
    .then((r) => r.data as unknown as SavingsDTO);
