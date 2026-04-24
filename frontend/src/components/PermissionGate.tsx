import { usePermissions } from '@/hooks/usePermissions';
export function PermissionGate({
  role,
  children,
  fallback = null,
}: {
  role: string;
  children: React.ReactNode;
  fallback?: React.ReactNode;
}) {
  const { hasRole } = usePermissions();
  return <>{hasRole(role) ? children : fallback}</>;
}
