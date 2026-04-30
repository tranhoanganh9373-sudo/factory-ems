import { describe, it, expect, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useDocumentTitle } from './useDocumentTitle';

describe('useDocumentTitle', () => {
  afterEach(() => {
    document.title = '能源管理系统';
  });

  it('sets title with system suffix', () => {
    renderHook(() => useDocumentTitle('实时看板'));
    expect(document.title).toBe('实时看板 - 能源管理系统');
  });

  it('uses bare system name when pageName empty', () => {
    renderHook(() => useDocumentTitle(''));
    expect(document.title).toBe('能源管理系统');
  });
});
