import type { ReactNode } from 'react';
import { Empty } from 'antd';
import {
  DatabaseOutlined,
  LineChartOutlined,
  PieChartOutlined,
  InboxOutlined,
  WarningOutlined,
} from '@ant-design/icons';

export type EmptyKind = 'no-data' | 'no-chart' | 'no-pie' | 'no-result' | 'error';

interface Props {
  kind?: EmptyKind;
  description?: ReactNode;
  action?: ReactNode;
  /** Vertical padding around the empty state. Defaults to 24. */
  padding?: number;
  /** Compact one-row layout (~52px tall) for stacked dashboard panels. */
  compact?: boolean;
}

const ICON: Record<EmptyKind, typeof DatabaseOutlined> = {
  'no-data': DatabaseOutlined,
  'no-chart': LineChartOutlined,
  'no-pie': PieChartOutlined,
  'no-result': InboxOutlined,
  error: WarningOutlined,
};

const DEFAULT_TEXT: Record<EmptyKind, string> = {
  'no-data': '暂无数据',
  'no-chart': '暂无图表数据',
  'no-pie': '暂无构成数据',
  'no-result': '没有匹配结果',
  error: '加载失败',
};

export function EmptyState({
  kind = 'no-data',
  description,
  action,
  padding = 24,
  compact = false,
}: Props) {
  const Icon = ICON[kind];
  const color = kind === 'error' ? 'var(--ems-color-error)' : 'var(--ems-color-text-tertiary)';
  const text = description ?? DEFAULT_TEXT[kind];

  if (compact) {
    return (
      <div
        role="status"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          padding: '12px 16px',
          minHeight: 52,
          color: 'var(--ems-color-text-secondary)',
        }}
      >
        <Icon style={{ fontSize: 20, color }} aria-hidden />
        <span style={{ flex: 1 }}>{text}</span>
        {action}
      </div>
    );
  }

  return (
    <Empty
      image={<Icon style={{ fontSize: 48, color }} />}
      imageStyle={{ height: 48, marginBottom: 12 }}
      description={<span style={{ color: 'var(--ems-color-text-secondary)' }}>{text}</span>}
      style={{ padding: `${padding}px 0` }}
    >
      {action}
    </Empty>
  );
}
