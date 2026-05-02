import type { ReactNode } from 'react';
import { Table, type TableProps } from 'antd';
import type { ColumnsType, ColumnType } from 'antd/es/table';

interface Props<T> extends TableProps<T> {
  toolbarLeft?: ReactNode;
  toolbarExtra?: ReactNode;
}

function withAriaSort<T>(columns?: ColumnsType<T>): ColumnsType<T> | undefined {
  if (!columns) return columns;
  return columns.map((col) => {
    const leaf = col as ColumnType<T>;
    if (!leaf.sorter) return col;
    const original = leaf.onHeaderCell;
    return {
      ...leaf,
      onHeaderCell: (column, idx) => {
        const base = original?.(column, idx) ?? {};
        return { 'aria-sort': 'none', ...base } as ReturnType<NonNullable<typeof original>>;
      },
    };
  });
}

export function DataTable<T extends object>({ toolbarLeft, toolbarExtra, ...rest }: Props<T>) {
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
        columns={withAriaSort(rest.columns)}
        pagination={rest.pagination ?? { pageSize: 20, showSizeChanger: true }}
      />
    </div>
  );
}
