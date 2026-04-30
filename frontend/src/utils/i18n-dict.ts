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

export const COLLECTOR_PROTOCOL_LABEL = {
  MODBUS_TCP: 'Modbus TCP',
  MODBUS_RTU: 'Modbus RTU',
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
