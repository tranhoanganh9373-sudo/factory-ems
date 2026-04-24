-- 初始角色
INSERT INTO roles (code, name, description) VALUES
  ('ADMIN',  '管理员', '系统管理员，全部权限'),
  ('VIEWER', '查看者', '按节点权限查看数据')
ON CONFLICT (code) DO NOTHING;

-- 初始管理员（密码 BCrypt hash of 'admin123!'，建议首次登录改）
INSERT INTO users (username, password_hash, display_name, enabled)
VALUES ('admin', '$2a$12$UNKJT9ECly1ZCqQn6sT3PuU3fNjdECK2sPgSATgXY43M25/PcDN8G', '系统管理员', TRUE)
ON CONFLICT (username) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r
WHERE u.username = 'admin' AND r.code = 'ADMIN'
ON CONFLICT DO NOTHING;
