import { useLocation } from 'react-router-dom';
import type { SizeType } from 'antd/es/config-provider/SizeContext';

const COMPACT_PREFIXES = ['/dashboard', '/collector', '/floorplan', '/alarms'];

export function densityForRoute(pathname: string): SizeType {
  return COMPACT_PREFIXES.some((p) => pathname.startsWith(p)) ? 'small' : 'middle';
}

export function useDensity(): SizeType {
  return densityForRoute(useLocation().pathname);
}
