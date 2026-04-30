import type { ReactNode } from 'react';

export type StatusTone = 'success' | 'warning' | 'error' | 'info' | 'default';

const TONE_VAR: Record<StatusTone, string> = {
  success: 'var(--ems-color-success)',
  warning: 'var(--ems-color-warning)',
  error: 'var(--ems-color-error)',
  info: 'var(--ems-color-info)',
  default: 'var(--ems-color-text-tertiary)',
};

interface Props {
  tone: StatusTone;
  children: ReactNode;
}

export function StatusTag({ tone, children }: Props) {
  const color = TONE_VAR[tone];
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        height: 22,
        padding: '0 10px',
        borderRadius: 9999,
        fontSize: 12,
        fontWeight: 500,
        color,
        background: `color-mix(in srgb, ${color} 12%, transparent)`,
        border: `1px solid color-mix(in srgb, ${color} 30%, transparent)`,
        whiteSpace: 'nowrap',
      }}
    >
      {children}
    </span>
  );
}
