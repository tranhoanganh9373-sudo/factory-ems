export const ALARM_STATE_LABEL = {
  OPEN: '未处理',
  ACK: '已确认',
  CLEARED: '已恢复',
} as const;

export const ALARM_SEVERITY_LABEL = {
  CRITICAL: '严重',
  MAJOR: '重要',
  MINOR: '次要',
  WARNING: '提醒',
} as const;

export const METER_STATE_LABEL = {
  ACTIVE: '在线',
  INACTIVE: '离线',
} as const;

export const ENERGY_TYPE_LABEL = {
  ELEC: '电',
  WATER: '水',
  GAS: '气',
  STEAM: '蒸汽',
  OIL: '油',
} as const;

export const TARIFF_PERIOD_LABEL = {
  SHARP: '尖峰',
  PEAK: '高峰',
  FLAT: '平段',
  VALLEY: '低谷',
} as const;

export const AUDIT_ACTION_LABEL = {
  LOGIN: '登录',
  LOGOUT: '登出',
  LOGIN_FAIL: '登录失败',
  CREATE: '新建',
  UPDATE: '更新',
  DELETE: '删除',
  MOVE: '移动',
  CONFIG_CHANGE: '配置变更',
  BILL_GENERATE: '生成账单',
} as const;

export const RESOURCE_TYPE_LABEL = {
  AUTH: '认证',
  USER: '用户',
  ORG_NODE: '组织节点',
  NODE_PERMISSION: '节点权限',
} as const;

export const COLLECTOR_PROTOCOL_LABEL = {
  MODBUS_TCP: 'Modbus TCP',
  MODBUS_RTU: 'Modbus RTU',
  OPC_UA: 'OPC UA',
  MQTT: 'MQTT',
  VIRTUAL: '虚拟（模拟）',
} as const;

export const VIRTUAL_MODE_LABEL = {
  CONSTANT: '恒定值',
  SINE: '正弦波',
  RANDOM_WALK: '随机游走',
  CALENDAR_CURVE: '日历曲线',
} as const;

export const OPCUA_SECURITY_MODE_LABEL = {
  NONE: '无安全',
  SIGN: '仅签名',
  SIGN_AND_ENCRYPT: '签名 + 加密',
} as const;

export const CONNECTION_STATE_LABEL = {
  CONNECTING: '连接中',
  CONNECTED: '已连接',
  DISCONNECTED: '已断开',
  ERROR: '错误',
} as const;

export const NAV_LABEL = {
  dashboard: '综合看板',
  collector: '数据采集',
  floorplan: '设备分布图',
  alarms: '告警',
  meters: '表计',
  cost: '成本核算',
  bills: '账单',
  tariff: '电价',
  report: '报表',
  admin: '系统管理',
  profile: '个人中心',
  home: '首页',
  orgtree: '组织树',
  production: '生产',
} as const;

export function translate<T extends Record<string, string>>(dict: T, key: string): string {
  return dict[key as keyof T] ?? key;
}
