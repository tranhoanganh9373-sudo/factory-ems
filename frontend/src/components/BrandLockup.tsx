import logo from '@/assets/logo.png';

interface Props {
  variant: 'header' | 'login';
}

const SYSTEM = '能源管理系统';
const COMPANY = '松羽科技集团';

export function BrandLockup({ variant }: Props) {
  if (variant === 'header') {
    return (
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <span
          style={{
            background: '#FFFFFF',
            padding: '0 10px',
            borderRadius: 9999,
            display: 'inline-flex',
            alignItems: 'center',
            height: 36,
            boxShadow: '0 0 0 1px rgba(255,255,255,0.08)',
          }}
        >
          <img src={logo} alt={COMPANY} style={{ height: 36, display: 'block' }} />
        </span>
        <span style={{ color: '#FFFFFF', fontSize: 16, fontWeight: 500, letterSpacing: 0.2 }}>
          {SYSTEM}
        </span>
      </div>
    );
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
      <img src={logo} alt={COMPANY} style={{ width: 240, display: 'block' }} />
      <div style={{ fontSize: 28, fontWeight: 600, color: '#FFFFFF' }}>{SYSTEM}</div>
      <div style={{ fontSize: 14, color: 'rgba(255,255,255,0.7)' }}>{COMPANY}</div>
    </div>
  );
}
