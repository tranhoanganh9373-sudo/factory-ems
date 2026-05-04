import dayjs from 'dayjs';

export const formatDateTime = (iso?: string) =>
  iso ? dayjs(iso).format('YYYY-MM-DD HH:mm:ss') : '-';
export const formatDate = (iso?: string) => (iso ? dayjs(iso).format('YYYY-MM-DD') : '-');

export const showTotal = (total: number, range: [number, number]) =>
  `第 ${range[0]}-${range[1]} 条 / 共 ${total} 条`;
