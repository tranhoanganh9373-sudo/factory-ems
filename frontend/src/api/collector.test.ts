import { describe, it, expect, vi, beforeEach } from 'vitest';
import { certApi } from './collector';
import { apiClient } from './client';

vi.mock('./client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}));

const mocked = apiClient as unknown as {
  get: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
};

describe('certApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('listPending', () => {
    it('GETs /collector/cert-pending and unwraps r.data', async () => {
      const fixture = [
        {
          thumbprint: 'aabbccdd',
          channelId: 7,
          endpointUrl: 'opc.tcp://10.0.0.1:4840',
          firstSeenAt: '2026-04-30T08:15:33Z',
          subjectDn: 'CN=PLC-Line1',
        },
      ];
      mocked.get.mockResolvedValueOnce({ data: fixture });

      const result = await certApi.listPending();

      expect(mocked.get).toHaveBeenCalledWith('/collector/cert-pending');
      expect(result).toEqual(fixture);
    });
  });

  describe('trust', () => {
    it('POSTs /collector/{channelId}/trust-cert with thumbprint body', async () => {
      mocked.post.mockResolvedValueOnce({ data: undefined });

      await certApi.trust(7, 'aabbccdd');

      expect(mocked.post).toHaveBeenCalledWith('/collector/7/trust-cert', {
        thumbprint: 'aabbccdd',
      });
    });

    it('returns void (resolves without payload)', async () => {
      mocked.post.mockResolvedValueOnce({ data: undefined });
      const result = await certApi.trust(1, 'x');
      expect(result).toBeUndefined();
    });
  });

  describe('reject', () => {
    it('DELETEs /collector/cert-pending/{thumbprint}', async () => {
      mocked.delete.mockResolvedValueOnce({ data: undefined });

      await certApi.reject('aabbccdd');

      expect(mocked.delete).toHaveBeenCalledWith('/collector/cert-pending/aabbccdd');
    });
  });
});
