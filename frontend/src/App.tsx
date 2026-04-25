import { useEffect, useState } from 'react';
import axios from 'axios';
import { Spin } from 'antd';
import { AppRouter } from './router';
import { useAuthStore } from './stores/authStore';

export default function App() {
  const [bootstrapping, setBootstrapping] = useState(true);
  const user = useAuthStore((s) => s.user);
  const accessToken = useAuthStore((s) => s.accessToken);
  const setAuth = useAuthStore((s) => s.setAuth);
  const clear = useAuthStore((s) => s.clear);

  useEffect(() => {
    let cancelled = false;
    async function bootstrap() {
      // 仅当 persist 还原了 user，但内存里没有 accessToken 时尝试静默刷新
      if (user && !accessToken) {
        try {
          const r = await axios.post('/api/v1/auth/refresh', null, { withCredentials: true });
          const data = r.data?.data;
          if (data?.accessToken && !cancelled) {
            setAuth({ accessToken: data.accessToken, user: data.user, expiresIn: data.expiresIn });
          }
        } catch {
          if (!cancelled) clear();
        }
      }
      if (!cancelled) setBootstrapping(false);
    }
    bootstrap();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (bootstrapping) {
    return (
      <div
        style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}
      >
        <Spin size="large" />
      </div>
    );
  }

  return <AppRouter />;
}
