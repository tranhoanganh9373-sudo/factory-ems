import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DataTable } from './DataTable';

describe('DataTable', () => {
  it('renders toolbar extras and column headers', () => {
    render(
      <DataTable
        rowKey="id"
        toolbarExtra={<button>刷新</button>}
        columns={[{ title: '名称', dataIndex: 'name' }]}
        dataSource={[{ id: 1, name: 'A' }]}
      />
    );
    expect(screen.getByRole('button', { name: '刷新' })).toBeInTheDocument();
    expect(screen.getByText('名称')).toBeInTheDocument();
    expect(screen.getByText('A')).toBeInTheDocument();
  });
});
