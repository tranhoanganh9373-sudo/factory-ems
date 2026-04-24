import { apiClient } from './client';
import type { PageDTO } from './user';

export interface AuditLogDTO {
  id: number;
  actorUserId?: number;
  actorUsername?: string;
  action: string;
  resourceType?: string;
  resourceId?: string;
  summary?: string;
  detail?: string;
  ip?: string;
  userAgent?: string;
  occurredAt: string;
}

export interface AuditQuery {
  actorUserId?: number;
  resourceType?: string;
  action?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export const auditApi = {
  search: (q: AuditQuery) =>
    apiClient
      .get<PageDTO<AuditLogDTO>>('/audit-logs', { params: q })
      .then((r) => r.data as unknown as PageDTO<AuditLogDTO>),
};
