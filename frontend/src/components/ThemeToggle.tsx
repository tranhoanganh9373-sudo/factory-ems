import { Button, Tooltip } from 'antd';
import { BulbOutlined, BulbFilled } from '@ant-design/icons';
import { useThemeStore } from '@/stores/themeStore';

export function ThemeToggle() {
  const mode = useThemeStore((s) => s.mode);
  const toggle = useThemeStore((s) => s.toggle);
  const isLight = mode === 'light';
  const label = isLight ? '切换到深色模式' : '切换到浅色模式';
  return (
    <Tooltip title={label}>
      <Button
        type="text"
        aria-label={label}
        icon={isLight ? <BulbOutlined /> : <BulbFilled />}
        onClick={toggle}
        style={{ color: '#FFFFFF' }}
      />
    </Tooltip>
  );
}
