import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  ConfigProvider,
  Layout,
  Menu,
  Avatar,
  Dropdown,
  Space,
  Drawer,
  Grid,
  Tag,
} from 'antd';

const ROLE_TAG_COLOR: Record<string, string> = {
  ADMIN: 'red',
  OPERATOR: 'blue',
  FINANCE: 'gold',
  VIEWER: 'default',
};

function pickPrimaryRole(roles: string[] | undefined): string | null {
  if (!roles?.length) return null;
  const order = ['ADMIN', 'FINANCE', 'OPERATOR', 'VIEWER'];
  for (const r of order) if (roles.includes(r)) return r;
  return roles[0];
}
import {
  UserOutlined,
  DashboardOutlined,
  FileTextOutlined,
  DollarOutlined,
  AccountBookOutlined,
  BellOutlined,
  SettingOutlined,
  BuildOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MenuOutlined,
} from '@ant-design/icons';
import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import { authApi } from '@/api/auth';
import { AlarmBell } from '@/components/AlarmBell';
import { BrandLockup } from '@/components/BrandLockup';
import { ThemeToggle } from '@/components/ThemeToggle';
import { useDensity } from '@/hooks/useDensity';

const { Header, Sider, Content } = Layout;
const { useBreakpoint } = Grid;

export default function AppLayout() {
  const { user, clear, hasRole } = useAuthStore();
  const location = useLocation();
  const navigate = useNavigate();
  const density = useDensity();
  const screens = useBreakpoint();
  const isMobile = !screens.md;
  const [collapsed, setCollapsed] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);

  useEffect(() => {
    if (isMobile) setDrawerOpen(false);
  }, [location.pathname, isMobile]);

  const menuItems = useMemo(() => {
    const items: {
      key: string;
      icon?: React.ReactNode;
      label: React.ReactNode;
      children?: { key: string; label: React.ReactNode }[];
    }[] = [
      {
        key: '/dashboard',
        icon: <DashboardOutlined />,
        label: <Link to="/dashboard">实时看板</Link>,
      },
      {
        key: 'report',
        icon: <FileTextOutlined />,
        label: '报表',
        children: [
          { key: '/report', label: <Link to="/report">即席查询</Link> },
          { key: '/report/daily', label: <Link to="/report/daily">日报</Link> },
          { key: '/report/monthly', label: <Link to="/report/monthly">月报</Link> },
          { key: '/report/yearly', label: <Link to="/report/yearly">年报</Link> },
          { key: '/report/shift', label: <Link to="/report/shift">班次</Link> },
          { key: '/report/export', label: <Link to="/report/export">异步导出</Link> },
        ],
      },
      {
        key: 'production',
        icon: <BuildOutlined />,
        label: '生产',
        children: [
          { key: '/production/shifts', label: <Link to="/production/shifts">班次管理</Link> },
          { key: '/production/entry', label: <Link to="/production/entry">日产量录入</Link> },
        ],
      },
    ];
    if (hasRole('ADMIN') || hasRole('FINANCE')) {
      items.push({
        key: 'cost',
        icon: <DollarOutlined />,
        label: '成本分摊',
        children: [
          { key: '/tariff', label: <Link to="/tariff">电价方案</Link> },
          { key: '/cost/rules', label: <Link to="/cost/rules">分摊规则</Link> },
          { key: '/cost/runs', label: <Link to="/cost/runs">分摊批次</Link> },
        ],
      });
      items.push({
        key: 'bills',
        icon: <AccountBookOutlined />,
        label: '账单',
        children: [
          { key: '/bills', label: <Link to="/bills">账单列表</Link> },
          { key: '/bills/periods', label: <Link to="/bills/periods">账期管理</Link> },
        ],
      });
    }
    if (hasRole('ADMIN') || hasRole('OPERATOR')) {
      items.push({
        key: 'alarms',
        icon: <BellOutlined />,
        label: '系统健康',
        children: [
          {
            key: '/alarms/health',
            label: <Link to="/alarms/health">健康总览</Link>,
          },
          {
            key: '/alarms/history',
            label: <Link to="/alarms/history">报警历史</Link>,
          },
          {
            key: '/alarms/rules',
            label: <Link to="/alarms/rules">阈值规则</Link>,
          },
          ...(hasRole('ADMIN')
            ? [
                {
                  key: '/alarms/webhook',
                  label: <Link to="/alarms/webhook">Webhook 配置</Link>,
                },
              ]
            : []),
        ],
      });
    }
    if (hasRole('ADMIN')) {
      items.push({
        key: 'admin',
        icon: <SettingOutlined />,
        label: '系统管理',
        children: [
          { key: '/orgtree', label: <Link to="/orgtree">组织树</Link> },
          { key: '/floorplan', label: <Link to="/floorplan">平面图</Link> },
          { key: '/collector', label: <Link to="/collector">采集器状态</Link> },
          { key: '/meters', label: <Link to="/meters">测点管理</Link> },
          { key: '/admin/cert-approval', label: <Link to="/admin/cert-approval">证书审批</Link> },
          { key: '/admin/audit', label: <Link to="/admin/audit">审计日志</Link> },
          { key: '/admin/users', label: <Link to="/admin/users">用户</Link> },
          { key: '/admin/roles', label: <Link to="/admin/roles">角色</Link> },
        ],
      });
    }
    return items;
  }, [hasRole]);

  const userMenu = {
    items: [
      { key: 'profile', label: '个人中心', onClick: () => navigate('/profile') },
      { type: 'divider' as const },
      {
        key: 'logout',
        label: '退出登录',
        onClick: async () => {
          await authApi.logout().catch(() => {});
          clear();
          navigate('/login');
        },
      },
    ],
  };

  const triggerIcon = isMobile ? (
    <MenuOutlined />
  ) : collapsed ? (
    <MenuUnfoldOutlined />
  ) : (
    <MenuFoldOutlined />
  );
  const handleTrigger = () => {
    if (isMobile) setDrawerOpen(true);
    else setCollapsed((c) => !c);
  };

  const sideMenu = (
    <Menu
      mode="inline"
      selectedKeys={[location.pathname]}
      items={menuItems}
      style={{ borderInlineEnd: 0, paddingTop: 8 }}
      onClick={() => {
        if (isMobile) setDrawerOpen(false);
      }}
    />
  );

  return (
    <ConfigProvider componentSize={density}>
      <Layout style={{ minHeight: '100vh' }}>
        <Header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: isMobile ? '0 12px' : '0 24px',
            background: 'var(--ems-color-bg-header)',
            height: 56,
            lineHeight: 'normal',
          }}
        >
          <Space size={isMobile ? 'small' : 'middle'} align="center">
            <Button
              type="text"
              icon={triggerIcon}
              onClick={handleTrigger}
              aria-label={isMobile ? '打开菜单' : collapsed ? '展开侧栏' : '收起侧栏'}
              style={{ color: '#FFFFFF', fontSize: 18 }}
            />
            <BrandLockup variant="header" />
          </Space>
          <Space size={isMobile ? 'small' : 'large'}>
            <ThemeToggle />
            <AlarmBell />
            <Dropdown menu={userMenu} placement="bottomRight">
              <Space size={8} style={{ color: '#FFFFFF', cursor: 'pointer' }}>
                <Avatar size="small" icon={<UserOutlined />} />
                {!isMobile && (
                  <>
                    {(() => {
                      const role = pickPrimaryRole(user?.roles);
                      return role ? (
                        <Tag
                          color={ROLE_TAG_COLOR[role] ?? 'default'}
                          style={{ margin: 0, fontSize: 11, lineHeight: '18px' }}
                        >
                          {role}
                        </Tag>
                      ) : null;
                    })()}
                    <span>{user?.username ?? '用户'}</span>
                  </>
                )}
              </Space>
            </Dropdown>
          </Space>
        </Header>
        <Layout>
          {!isMobile && (
            <Sider
              width={240}
              collapsed={collapsed}
              trigger={null}
              theme="light"
              style={{ background: 'var(--ems-color-bg-sider)' }}
            >
              {sideMenu}
            </Sider>
          )}
          {isMobile && (
            <Drawer
              placement="left"
              open={drawerOpen}
              onClose={() => setDrawerOpen(false)}
              closable
              width={Math.min(280, typeof window !== 'undefined' ? window.innerWidth - 56 : 280)}
              styles={{ body: { padding: 0, background: 'var(--ems-color-bg-sider)' } }}
              title={null}
            >
              {sideMenu}
            </Drawer>
          )}
          <Content
            style={{
              padding: density === 'small' ? 12 : isMobile ? 12 : 24,
              background: 'var(--ems-color-bg-page)',
              minWidth: 0,
            }}
          >
            <Outlet />
          </Content>
        </Layout>
      </Layout>
    </ConfigProvider>
  );
}
