import { useEffect } from 'react';

const SYSTEM = '能源管理系统';

export function useDocumentTitle(pageName: string): void {
  useEffect(() => {
    document.title = pageName ? `${pageName} - ${SYSTEM}` : SYSTEM;
  }, [pageName]);
}
