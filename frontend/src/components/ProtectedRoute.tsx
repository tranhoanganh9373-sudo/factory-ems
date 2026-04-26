import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';

export function ProtectedRoute({
  children,
  requiredRole,
  requiredAnyRole,
}: {
  children: React.ReactNode;
  requiredRole?: string;
  /** 任一角色满足即放行（OR）。与 requiredRole 互斥，便于 cost/bills 这种 FINANCE 或 ADMIN 共有的子树。 */
  requiredAnyRole?: string[];
}) {
  const { accessToken, user, hasRole } = useAuthStore();
  const location = useLocation();
  if (!accessToken || !user) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  if (requiredRole && !hasRole(requiredRole)) {
    return <Navigate to="/forbidden" replace />;
  }
  if (requiredAnyRole && requiredAnyRole.length > 0 && !requiredAnyRole.some((r) => hasRole(r))) {
    return <Navigate to="/forbidden" replace />;
  }
  return <>{children}</>;
}
