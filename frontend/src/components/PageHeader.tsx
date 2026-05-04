import type { ReactNode } from 'react';
import { Tooltip } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';

interface Props {
  title: string;
  subtitle?: ReactNode;
  extra?: ReactNode;
  /** 标题旁悬停 ⓘ 显示的页面级帮助内容（建议传 ReactNode 而非纯字符串）。 */
  helpContent?: ReactNode;
}

export function PageHeader({ title, subtitle, extra, helpContent }: Props) {
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
        <div
          style={{
            fontSize: 20,
            fontWeight: 600,
            color: 'var(--ems-color-text-primary)',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 8,
          }}
        >
          {title}
          {helpContent && (
            <Tooltip
              title={helpContent}
              overlayStyle={{ maxWidth: 520 }}
              placement="bottomLeft"
            >
              <QuestionCircleOutlined
                style={{ fontSize: 16, color: '#8c8c8c', cursor: 'help' }}
                aria-label="页面帮助"
              />
            </Tooltip>
          )}
        </div>
        {subtitle && (
          <div style={{ fontSize: 13, color: 'var(--ems-color-text-secondary)', marginTop: 4 }}>
            {subtitle}
          </div>
        )}
      </div>
      {extra && <div>{extra}</div>}
    </div>
  );
}
