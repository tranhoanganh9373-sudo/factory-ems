import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import CollectorPage from './index';
import { collectorDiagApi } from '@/api/collectorDiag';

vi.mock('@/api/collectorDiag', () => ({
  collectorDiagApi: {
    list: vi.fn().mockResolvedValue([
      {
        channelId: 1,
        protocol: 'VIRTUAL',
        connState: 'CONNECTED',
        successCount24h: 10,
        failureCount24h: 0,
        avgLatencyMs: 5,
        protocolMeta: {},
      },
    ]),
    get: vi.fn(),
    test: vi.fn(),
    reconnect: vi.fn(),
  },
}));

vi.mock('@/api/channel', () => ({
  channelApi: { get: vi.fn(), list: vi.fn() },
}));

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AntApp>
        <MemoryRouter>
          <CollectorPage />
        </MemoryRouter>
      </AntApp>
    </QueryClientProvider>,
  );
}

describe('CollectorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders channel runtime state list', async () => {
    renderPage();
    await waitFor(() => {
      expect(collectorDiagApi.list).toHaveBeenCalled();
    });
    expect(await screen.findByText(/虚拟（模拟）/)).toBeInTheDocument();
    expect(screen.getByText(/已连接/)).toBeInTheDocument();
  });

  it('shows 新增通道 button', () => {
    renderPage();
    expect(screen.getByRole('button', { name: /新\s*增\s*通\s*道/ })).toBeInTheDocument();
  });
});
