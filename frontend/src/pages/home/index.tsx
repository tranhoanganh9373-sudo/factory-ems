import { Card, Row, Col, Typography, Alert } from 'antd';
import { useAuthStore } from '@/stores/authStore';

export default function HomePage() {
  const user = useAuthStore((s) => s.user);
  return (
    <div>
      <Typography.Title level={3}>欢迎，{user?.displayName || user?.username}</Typography.Title>
      <Alert
        style={{ marginBottom: 24 }}
        type="info"
        showIcon
        message="子项目 1.1 — 地基 & 认证基线"
        description="完整看板与报表将在 Plan 1.2 / 1.3 交付。当前版本可用于用户、角色、组织树、权限和审计管理。"
      />
      <Row gutter={16}>
        {['电（kWh）', '水（m³）', '蒸汽（t）'].map((t) => (
          <Col span={8} key={t}>
            <Card title={t}>
              <div style={{ fontSize: 24, textAlign: 'center' }}>— —</div>
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  );
}
