import { useAuthStore } from '@/stores/authStore';

export function usePermissions() {
  const user = useAuthStore((s) => s.user);
  const roles = user?.roles ?? [];
  return {
    isAdmin: roles.includes('ADMIN'),
    isViewer: roles.includes('VIEWER'),
    hasRole: (r: string) => roles.includes(r),
  };
}
