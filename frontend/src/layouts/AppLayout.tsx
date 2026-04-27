import { useMemo } from 'react';
import { Layout, Menu, Avatar, Dropdown, Typography } from 'antd';
import {
  UserOutlined,
  LogoutOutlined,
  SettingOutlined,
  ApiOutlined,
  DashboardOutlined,
  FileTextOutlined,
  DollarOutlined,
  AccountBookOutlined,
} from '@ant-design/icons';
import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import { authApi } from '@/api/auth';

const { Header, Sider, Content } = Layout;

export default function AppLayout() {
  const { user, clear, hasRole } = useAuthStore();
  const location = useLocation();
  const navigate = useNavigate();

  const menuItems = useMemo(() => {
    const items: {
      key: string;
      label: React.ReactNode;
      children?: { key: string; label: React.ReactNode }[];
    }[] = [
      {
        key: '/dashboard',
        label: (
          <Link to="/dashboard">
            <DashboardOutlined /> 实时看板
          </Link>
        ),
      },
      { key: '/home', label: <Link to="/home">首页</Link> },
      { key: '/orgtree', label: <Link to="/orgtree">组织树</Link> },
      {
        key: '/meters',
        label: (
          <Link to="/meters">
            <ApiOutlined /> 测点管理
          </Link>
        ),
      },
      {
        key: 'report',
        label: (
          <span>
            <FileTextOutlined /> 报表
          </span>
        ),
        children: [
          { key: '/report', label: <Link to="/report">即席查询</Link> },
          { key: '/report/daily', label: <Link to="/report/daily">日报</Link> },
          { key: '/report/monthly', label: <Link to="/report/monthly">月报</Link> },
          { key: '/report/yearly', label: <Link to="/report/yearly">年报</Link> },
          { key: '/report/shift', label: <Link to="/report/shift">班次</Link> },
          { key: '/report/export', label: <Link to="/report/export">异步导出</Link> },
        ],
      },
      { key: '/tariff', label: <Link to="/tariff">电价方案</Link> },
      {
        key: 'production',
        label: '生产',
        children: [
          { key: '/production/shifts', label: <Link to="/production/shifts">班次管理</Link> },
          { key: '/production/entry', label: <Link to="/production/entry">日产量录入</Link> },
        ],
      },
      { key: '/floorplan', label: <Link to="/floorplan">平面图</Link> },
    ];
    if (hasRole('ADMIN') || hasRole('FINANCE')) {
      items.push({
        key: 'cost',
        label: (
          <span>
            <DollarOutlined /> 成本分摊
          </span>
        ),
        children: [
          { key: '/cost/rules', label: <Link to="/cost/rules">分摊规则</Link> },
          { key: '/cost/runs', label: <Link to="/cost/runs">分摊批次</Link> },
        ],
      });
      items.push({
        key: 'bills',
        label: (
          <span>
            <AccountBookOutlined /> 账单
          </span>
        ),
        children: [
          { key: '/bills', label: <Link to="/bills">账单列表</Link> },
          { key: '/bills/periods', label: <Link to="/bills/periods">账期管理</Link> },
        ],
      });
    }
    if (hasRole('ADMIN')) {
      items.push({
        key: 'admin',
        label: '管理',
        children: [
          { key: '/admin/users', label: <Link to="/admin/users">用户</Link> },
          { key: '/admin/roles', label: <Link to="/admin/roles">角色</Link> },
          { key: '/admin/audit', label: <Link to="/admin/audit">审计日志</Link> },
          { key: '/collector', label: <Link to="/collector">采集器状态</Link> },
        ],
      });
    }
    return items;
  }, [hasRole]);

  const userMenu = {
    items: [
      { key: 'profile', icon: <SettingOutlined />, label: <Link to="/profile">修改密码</Link> },
      {
        key: 'logout',
        icon: <LogoutOutlined />,
        label: '退出登录',
        onClick: async () => {
          try {
            await authApi.logout();
          } catch {
            // ignore
          }
          clear();
          navigate('/login');
        },
      },
    ],
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 24px',
        }}
      >
        <Typography.Title level={4} style={{ color: 'white', margin: 0 }}>
          工厂能源管理系统
        </Typography.Title>
        <Dropdown menu={userMenu} trigger={['click']}>
          <span style={{ color: 'white', cursor: 'pointer' }}>
            <Avatar icon={<UserOutlined />} /> {user?.displayName || user?.username}
          </span>
        </Dropdown>
      </Header>
      <Layout>
        <Sider width={220} theme="light">
          <Menu
            mode="inline"
            selectedKeys={[location.pathname]}
            defaultOpenKeys={['admin']}
            items={menuItems}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Content style={{ padding: 24, background: '#fff', margin: 16 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
