export const errorCodeToMessage = (code: number): string => {
  const map: Record<number, string> = {
    400: '请求参数错误',
    40000: '业务规则错误',
    40001: '未登录或登录已过期',
    40003: '权限不足',
    40004: '资源不存在',
    40009: '冲突（如重复或版本不匹配）',
    50000: '服务器内部错误',
  };
  return map[code] || '未知错误';
};
