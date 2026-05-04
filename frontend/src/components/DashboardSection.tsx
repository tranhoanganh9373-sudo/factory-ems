import { Card, type CardProps } from 'antd';
import type { ReactNode } from 'react';

export type SectionTone = 'primary' | 'default';

interface DashboardSectionProps {
  tone?: SectionTone;
  children: ReactNode;
  extra?: CardProps['extra'];
}

export function DashboardSection({ tone = 'default', children, extra }: DashboardSectionProps) {
  const isPrimary = tone === 'primary';
  return (
    <Card
      size="small"
      bodyStyle={{ padding: 16 }}
      style={{
        borderLeft: isPrimary ? '3px solid var(--ems-color-brand-primary, #00939F)' : undefined,
        boxShadow: isPrimary ? '0 1px 4px rgba(15, 23, 34, 0.06)' : undefined,
      }}
      extra={extra}
    >
      {children}
    </Card>
  );
}

export default DashboardSection;
