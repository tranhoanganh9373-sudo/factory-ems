import { Badge, Button } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { alarmApi } from '@/api/alarm';
import { useAuthStore } from '@/stores/authStore';
import { AlarmCenterDrawer } from './AlarmCenterDrawer';

export function AlarmBell() {
  const [open, setOpen] = useState(false);
  const { hasRole } = useAuthStore();
  const visible = hasRole('ADMIN') || hasRole('OPERATOR');

  const { data: count = 0 } = useQuery({
    queryKey: ['alarms', 'active-count'],
    queryFn: () => alarmApi.activeCount(),
    refetchInterval: 30_000,
    enabled: visible,
    refetchOnWindowFocus: false,
  });

  if (!visible) return null;

  return (
    <>
      <Badge count={count} overflowCount={99} offset={[-4, 4]}>
        <Button
          type="text"
          icon={<BellOutlined style={{ fontSize: 18, color: 'white' }} />}
          onClick={() => setOpen(true)}
          aria-label="告警中心"
        />
      </Badge>
      <AlarmCenterDrawer open={open} onClose={() => setOpen(false)} />
    </>
  );
}
