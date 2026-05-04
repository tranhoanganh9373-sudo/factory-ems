import { apiClient } from './client';
import { useQuery } from '@tanstack/react-query';

export interface FeatureFlags {
  pv: boolean;
}

const fetchFeatures = (): Promise<FeatureFlags> =>
  apiClient.get<FeatureFlags>('/features').then((r) => r.data as unknown as FeatureFlags);

export function useFeatureFlags() {
  return useQuery({
    queryKey: ['features'],
    queryFn: fetchFeatures,
    staleTime: Infinity,
    retry: false,
  });
}
