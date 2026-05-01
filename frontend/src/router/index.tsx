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
import CertApprovalPage from '@/pages/admin/cert-approval';
import DashboardPage from '@/pages/dashboard';
import ReportPage from '@/pages/report';
import TariffPage from '@/pages/tariff';
import ShiftsPage from '@/pages/production/shifts';
import ProductionEntryPage from '@/pages/production/entry';
import FloorplanListPage from '@/pages/floorplan/list';
import FloorplanEditorPage from '@/pages/floorplan/editor';
import DailyReportPage from '@/pages/report/daily';
import MonthlyReportPage from '@/pages/report/monthly';
import YearlyReportPage from '@/pages/report/yearly';
import ShiftReportPage from '@/pages/report/shift';
import ExportReportPage from '@/pages/report/export';
import CostRulesPage from '@/pages/cost/rules';
import CostRunsPage from '@/pages/cost/runs';
import CostRunDetailPage from '@/pages/cost/run-detail';
import BillsListPage from '@/pages/bills/list';
import BillPeriodsPage from '@/pages/bills/periods';
import BillDetailPage from '@/pages/bills/detail';
import CollectorStatusPage from '@/pages/collector';
import AlarmHealthPage from '@/pages/alarms/health';
import AlarmHistoryPage from '@/pages/alarms/history';
import AlarmRulesPage from '@/pages/alarms/rules';
import AlarmWebhookPage from '@/pages/alarms/webhook';

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
        <Route path="profile" element={<ProfilePage />} />
        <Route path="orgtree" element={<OrgTreePage />} />
        <Route path="meters" element={<MetersPage />} />
        <Route path="report" element={<ReportPage />} />
        <Route path="tariff" element={<TariffPage />} />
        <Route path="production/shifts" element={<ShiftsPage />} />
        <Route path="production/entry" element={<ProductionEntryPage />} />
        <Route path="floorplan" element={<FloorplanListPage />} />
        <Route path="floorplan/editor/:id" element={<FloorplanEditorPage />} />
        <Route path="report/daily" element={<DailyReportPage />} />
        <Route path="report/monthly" element={<MonthlyReportPage />} />
        <Route path="report/yearly" element={<YearlyReportPage />} />
        <Route path="report/shift" element={<ShiftReportPage />} />
        <Route path="report/export" element={<ExportReportPage />} />
        <Route
          path="cost"
          element={
            <ProtectedRoute requiredAnyRole={['FINANCE', 'ADMIN']}>
              <Outlet />
            </ProtectedRoute>
          }
        >
          <Route path="rules" element={<CostRulesPage />} />
          <Route path="runs" element={<CostRunsPage />} />
          <Route path="runs/:id" element={<CostRunDetailPage />} />
        </Route>
        <Route
          path="bills"
          element={
            <ProtectedRoute requiredAnyRole={['FINANCE', 'ADMIN']}>
              <Outlet />
            </ProtectedRoute>
          }
        >
          <Route index element={<BillsListPage />} />
          <Route path="periods" element={<BillPeriodsPage />} />
          <Route path=":id" element={<BillDetailPage />} />
        </Route>
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
          <Route path="cert-approval" element={<CertApprovalPage />} />
        </Route>
        <Route
          path="collector"
          element={
            <ProtectedRoute requiredRole="ADMIN">
              <CollectorStatusPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="alarms"
          element={
            <ProtectedRoute requiredAnyRole={['ADMIN', 'OPERATOR']}>
              <Outlet />
            </ProtectedRoute>
          }
        >
          <Route path="health" element={<AlarmHealthPage />} />
          <Route path="history" element={<AlarmHistoryPage />} />
          <Route path="rules" element={<AlarmRulesPage />} />
          <Route
            path="webhook"
            element={
              <ProtectedRoute requiredRole="ADMIN">
                <AlarmWebhookPage />
              </ProtectedRoute>
            }
          />
        </Route>
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
