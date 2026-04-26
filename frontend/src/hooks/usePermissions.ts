import { useAuthStore } from '@/stores/authStore';

export function usePermissions() {
  const user = useAuthStore((s) => s.user);
  const roles = user?.roles ?? [];
  const isAdmin = roles.includes('ADMIN');
  const isFinance = roles.includes('FINANCE');
  const isViewer = roles.includes('VIEWER');
  return {
    isAdmin,
    isFinance,
    isViewer,
    hasRole: (r: string) => roles.includes(r),
    /** Plan 2.2/2.3：FINANCE 或 ADMIN 可看 cost/bills 菜单和触发分摊/关账期。 */
    canManageBilling: isAdmin || isFinance,
    /** 仅 ADMIN 可解锁账期。 */
    canUnlockPeriod: isAdmin,
  };
}
