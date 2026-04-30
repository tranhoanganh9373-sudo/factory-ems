import { ArrowDownOutlined, ArrowUpOutlined } from '@ant-design/icons';

interface Props {
  label: string;
  value: string | number;
  unit?: string;
  delta?: number;
}

export function KpiCard({ label, value, unit, delta }: Props) {
  const positive = typeof delta === 'number' && delta > 0;
  const negative = typeof delta === 'number' && delta < 0;
  const deltaColor = positive ? 'var(--ems-color-error)' : 'var(--ems-color-success)';
  return (
    <div
      style={{
        background: 'var(--ems-color-bg-container)',
        border: '1px solid var(--ems-color-border)',
        borderRadius: 4,
        padding: 16,
        minWidth: 180,
      }}
    >
      <div style={{ fontSize: 13, color: 'var(--ems-color-text-secondary)' }}>{label}</div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginTop: 6 }}>
        <span
          style={{
            fontSize: 28,
            fontWeight: 600,
            fontFamily: 'var(--ems-font-family-mono)',
            color: 'var(--ems-color-text-primary)',
          }}
        >
          {value}
        </span>
        {unit && <span style={{ color: 'var(--ems-color-text-tertiary)' }}>{unit}</span>}
      </div>
      {typeof delta === 'number' && (
        <div style={{ marginTop: 6, fontSize: 12, color: deltaColor }}>
          {positive ? <ArrowUpOutlined /> : negative ? <ArrowDownOutlined /> : null}
          <span style={{ marginLeft: 4 }}>{Math.abs(delta).toFixed(1)}% 较昨日</span>
        </div>
      )}
    </div>
  );
}
