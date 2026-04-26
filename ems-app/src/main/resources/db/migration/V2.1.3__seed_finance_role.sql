-- 子项目 2 · Plan 2.2 · FINANCE 角色 seed
-- spec §10.1
-- FINANCE 角色权限：
--   * 看所有账单（默认 viewable = 全部组织，由应用层 @PreAuthorize 控制；无 node_permissions 行 = 隐式全部可见）
--   * 触发 cost_allocation_run（POST /api/v1/cost/runs）
--   * close 账期（PUT /api/v1/bills/periods/{id}/close）
--   * 不能 unlock 账期（unlock 仅 ADMIN）
-- 角色关联（user_roles）由运维通过 admin 控制台/API 给具体用户授予。

INSERT INTO roles (code, name, description) VALUES
  ('FINANCE', '财务', '财务角色：管理分摊规则 / 触发分摊 / 关账期，可见全部组织节点的账单')
ON CONFLICT (code) DO NOTHING;
