import { create } from 'zustand';
import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { RangeType } from '@/api/dashboard';

export interface DashboardFilterState {
  range: RangeType;
  customFrom: string | undefined;
  customTo: string | undefined;
  orgNodeId: number | undefined;
  energyType: string | undefined;
  setRange(r: RangeType): void;
  setCustomRange(from: string | undefined, to: string | undefined): void;
  setOrgNodeId(id: number | undefined): void;
  setEnergyType(t: string | undefined): void;
  reset(): void;
}

const DEFAULT_RANGE: RangeType = 'TODAY';

export const useDashboardFilterStore = create<DashboardFilterState>((set) => ({
  range: DEFAULT_RANGE,
  customFrom: undefined,
  customTo: undefined,
  orgNodeId: undefined,
  energyType: undefined,
  setRange: (range) => set({ range }),
  setCustomRange: (customFrom, customTo) => set({ customFrom, customTo }),
  setOrgNodeId: (orgNodeId) => set({ orgNodeId }),
  setEnergyType: (energyType) => set({ energyType }),
  reset: () =>
    set({
      range: DEFAULT_RANGE,
      customFrom: undefined,
      customTo: undefined,
      orgNodeId: undefined,
      energyType: undefined,
    }),
}));

/** Syncs the store with URL search params (bidirectional on mount). */
export function useDashboardSearchParams() {
  const [searchParams, setSearchParams] = useSearchParams();
  const store = useDashboardFilterStore();

  // On mount: read URL → store
  useEffect(() => {
    const range = (searchParams.get('range') as RangeType | null) ?? DEFAULT_RANGE;
    const customFrom = searchParams.get('from') ?? undefined;
    const customTo = searchParams.get('to') ?? undefined;
    const orgNodeId = searchParams.get('orgNodeId')
      ? Number(searchParams.get('orgNodeId'))
      : undefined;
    const energyType = searchParams.get('energyType') ?? undefined;
    store.setRange(range);
    store.setCustomRange(customFrom, customTo);
    store.setOrgNodeId(orgNodeId);
    store.setEnergyType(energyType);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Whenever store changes: write → URL
  useEffect(() => {
    const p: Record<string, string> = { range: store.range };
    if (store.customFrom) p['from'] = store.customFrom;
    if (store.customTo) p['to'] = store.customTo;
    if (store.orgNodeId != null) p['orgNodeId'] = String(store.orgNodeId);
    if (store.energyType) p['energyType'] = store.energyType;
    setSearchParams(p, { replace: true });
  }, [
    store.range,
    store.customFrom,
    store.customTo,
    store.orgNodeId,
    store.energyType,
    setSearchParams,
  ]);
}
