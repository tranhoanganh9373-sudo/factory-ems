import dayjs from 'dayjs';

export const formatDateTime = (iso?: string) =>
  iso ? dayjs(iso).format('YYYY-MM-DD HH:mm:ss') : '-';
export const formatDate = (iso?: string) =>
  iso ? dayjs(iso).format('YYYY-MM-DD') : '-';
