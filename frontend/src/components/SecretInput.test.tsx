import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { SecretInput } from './SecretInput';
import { secretApi } from '@/api/secret';

vi.mock('@/api/secret', () => ({
  secretApi: { write: vi.fn().mockResolvedValue(undefined) },
}));

describe('SecretInput', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows placeholder when no value', () => {
    render(<SecretInput refPrefix="x/y" placeholder="未设置" />);
    expect(screen.getByPlaceholderText('未设置')).toBeInTheDocument();
  });

  it('calls onChange with secret:// ref on save', async () => {
    const onChange = vi.fn();
    render(<SecretInput refPrefix="mqtt/u" onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: /修\s*改/ }));
    fireEvent.change(screen.getByPlaceholderText(/输入明文/), { target: { value: 'p4ss' } });
    fireEvent.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => {
      expect(secretApi.write).toHaveBeenCalledWith(
        expect.stringMatching(/^secret:\/\/mqtt\/u-\d+$/),
        'p4ss'
      );
      expect(onChange).toHaveBeenCalledWith(expect.stringMatching(/^secret:\/\/mqtt\/u-\d+$/));
    });
  });
});
