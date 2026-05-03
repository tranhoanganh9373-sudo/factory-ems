import { useState } from 'react';
import { Form, Input, Button, message, Alert } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { useAuthStore } from '@/stores/authStore';
import { BrandLockup } from '@/components/BrandLockup';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import loginBgUrl from '@/assets/login-bg.jpg';

export default function LoginPage() {
  useDocumentTitle('登录');
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const navigate = useNavigate();
  const location = useLocation();
  const setAuth = useAuthStore((s) => s.setAuth);
  const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/';

  const onFinish = async (v: { username: string; password: string }) => {
    setLoading(true);
    setErrorMsg(null);
    try {
      const r = await authApi.login(v.username, v.password);
      setAuth({ accessToken: r.accessToken, user: r.user, expiresIn: r.expiresIn });
      message.success('登录成功');
      navigate(from, { replace: true });
    } catch (e: unknown) {
      setErrorMsg((e as { message?: string })?.message ?? '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ display: 'flex', minHeight: '100vh' }}>
      <div
        style={{
          flex: '0 0 60%',
          // 深色 navy 渐变叠加在能源/科技背景图上：保持原 #0E1A2B 主色调与文字可读性，
          // 同时透出底图纹理提供"科技风"质感。
          background: `linear-gradient(135deg, rgba(14, 26, 43, 0.82), rgba(14, 26, 43, 0.92)), url(${loginBgUrl}) center/cover no-repeat`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <BrandLockup variant="login" />
      </div>
      <div
        style={{
          flex: 1,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: 'var(--ems-color-bg-container)',
          padding: 32,
        }}
      >
        <div style={{ width: 400, maxWidth: '100%' }}>
          <h2 style={{ marginBottom: 24, fontSize: 20, fontWeight: 600 }}>登录系统</h2>
          {errorMsg && <Alert type="error" message={errorMsg} style={{ marginBottom: 16 }} />}
          <Form layout="vertical" onFinish={onFinish} size="large">
            <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input
                prefix={<UserOutlined />}
                placeholder="用户名"
                autoComplete="username"
                aria-label="用户名"
              />
            </Form.Item>
            <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="密码"
                autoComplete="current-password"
                aria-label="密码"
              />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              登录
            </Button>
          </Form>
          <div
            style={{
              marginTop: 32,
              textAlign: 'center',
              color: 'var(--ems-color-text-tertiary)',
              fontSize: 12,
            }}
          >
            © 2026 松羽科技集团 · 能源管理系统
          </div>
        </div>
      </div>
    </div>
  );
}
