import { Routes, Route, Outlet, Navigate } from 'react-router-dom';
import { ProtectedRoute } from '@/components/ProtectedRoute';
import AppLayout from '@/layouts/AppLayout';
import LoginPage from '@/pages/login';
import ProfilePage from '@/pages/profile';
import ForbiddenPage from '@/pages/forbidden';
import NotFoundPage from '@/pages/not-found';
import OrgTreePage from '@/pages/orgtree';
import MetersPage from '@/pages/meters';
import UserListPage from '@/pages/admin/users/list';
import UserEditPage from '@/pages/admin/users/edit';
import UserPermissionPage from '@/pages/admin/users/permissions';
import RoleListPage from '@/pages/admin/roles/list';
import AuditListPage from '@/pages/admin/audit/list';
import HomePage from '@/pages/home';
import DashboardPage from '@/pages/dashboard';
import ReportPage from '@/pages/report';
import TariffPage from '@/pages/tariff';
import ShiftsPage from '@/pages/production/shifts';
import ProductionEntryPage from '@/pages/production/entry';
import FloorplanListPage from '@/pages/floorplan/list';
import FloorplanEditorPage from '@/pages/floorplan/editor';

export function AppRouter() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/forbidden" element={<ForbiddenPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <AppLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="home" element={<HomePage />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="orgtree" element={<OrgTreePage />} />
        <Route path="meters" element={<MetersPage />} />
        <Route path="report" element={<ReportPage />} />
        <Route path="tariff" element={<TariffPage />} />
        <Route path="production/shifts" element={<ShiftsPage />} />
        <Route path="production/entry" element={<ProductionEntryPage />} />
        <Route path="floorplan" element={<FloorplanListPage />} />
        <Route path="floorplan/editor/:id" element={<FloorplanEditorPage />} />
        <Route
          path="admin"
          element={
            <ProtectedRoute requiredRole="ADMIN">
              <Outlet />
            </ProtectedRoute>
          }
        >
          <Route path="users" element={<UserListPage />} />
          <Route path="users/:id" element={<UserEditPage />} />
          <Route path="users/:id/permissions" element={<UserPermissionPage />} />
          <Route path="roles" element={<RoleListPage />} />
          <Route path="audit" element={<AuditListPage />} />
        </Route>
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
