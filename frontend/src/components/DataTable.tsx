import type { ReactNode } from 'react';
import { Table, type TableProps } from 'antd';

interface Props<T> extends TableProps<T> {
  toolbarLeft?: ReactNode;
  toolbarExtra?: ReactNode;
}

export function DataTable<T extends object>({
  toolbarLeft,
  toolbarExtra,
  ...rest
}: Props<T>) {
  return (
    <div
      style={{
        background: 'var(--ems-color-bg-container)',
        border: '1px solid var(--ems-color-border)',
        borderRadius: 4,
      }}
    >
      {(toolbarLeft || toolbarExtra) && (
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            padding: '12px 16px',
            borderBottom: '1px solid var(--ems-color-border)',
            gap: 12,
          }}
        >
          <div>{toolbarLeft}</div>
          <div>{toolbarExtra}</div>
        </div>
      )}
      <Table
        {...rest}
        pagination={rest.pagination ?? { pageSize: 20, showSizeChanger: true }}
      />
    </div>
  );
}
