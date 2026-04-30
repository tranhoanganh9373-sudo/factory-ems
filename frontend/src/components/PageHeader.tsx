import type { ReactNode } from 'react';

interface Props {
  title: string;
  subtitle?: ReactNode;
  extra?: ReactNode;
}

export function PageHeader({ title, subtitle, extra }: Props) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: 16,
        paddingBottom: 12,
        borderBottom: '1px solid var(--ems-color-border)',
      }}
    >
      <div>
        <div style={{ fontSize: 20, fontWeight: 600, color: 'var(--ems-color-text-primary)' }}>{title}</div>
        {subtitle && (
          <div style={{ fontSize: 13, color: 'var(--ems-color-text-secondary)', marginTop: 4 }}>{subtitle}</div>
        )}
      </div>
      {extra && <div>{extra}</div>}
    </div>
  );
}
