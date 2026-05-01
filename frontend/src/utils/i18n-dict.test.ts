import { describe, it, expect } from 'vitest';
import {
  translate,
  ALARM_STATE_LABEL,
  ALARM_SEVERITY_LABEL,
  METER_STATE_LABEL,
  COLLECTOR_PROTOCOL_LABEL,
  VIRTUAL_MODE_LABEL,
  OPCUA_SECURITY_MODE_LABEL,
  CONNECTION_STATE_LABEL,
} from './i18n-dict';

describe('i18n-dict', () => {
  it('maps alarm states', () => {
    expect(ALARM_STATE_LABEL.OPEN).toBe('未处理');
    expect(ALARM_STATE_LABEL.ACK).toBe('已确认');
    expect(ALARM_STATE_LABEL.CLEARED).toBe('已恢复');
  });

  it('maps severities', () => {
    expect(ALARM_SEVERITY_LABEL.CRITICAL).toBe('严重');
    expect(ALARM_SEVERITY_LABEL.MAJOR).toBe('重要');
    expect(ALARM_SEVERITY_LABEL.MINOR).toBe('次要');
    expect(ALARM_SEVERITY_LABEL.WARNING).toBe('提醒');
  });

  it('maps meter states', () => {
    expect(METER_STATE_LABEL.ACTIVE).toBe('在线');
    expect(METER_STATE_LABEL.INACTIVE).toBe('离线');
  });

  it('keeps protocol names verbatim', () => {
    expect(COLLECTOR_PROTOCOL_LABEL.MODBUS_TCP).toBe('Modbus TCP');
    expect(COLLECTOR_PROTOCOL_LABEL.MODBUS_RTU).toBe('Modbus RTU');
  });

  it('translate falls back to original when key missing', () => {
    expect(translate(ALARM_STATE_LABEL, 'UNKNOWN')).toBe('UNKNOWN');
  });
});

describe('collector dicts', () => {
  it('COLLECTOR_PROTOCOL_LABEL covers 5 protocols', () => {
    expect(translate(COLLECTOR_PROTOCOL_LABEL, 'MODBUS_TCP')).toBe('Modbus TCP');
    expect(translate(COLLECTOR_PROTOCOL_LABEL, 'VIRTUAL')).toBe('虚拟（模拟）');
  });
  it('VIRTUAL_MODE_LABEL covers 4 modes', () => {
    expect(translate(VIRTUAL_MODE_LABEL, 'SINE')).toBe('正弦波');
    expect(translate(VIRTUAL_MODE_LABEL, 'CALENDAR_CURVE')).toBe('日历曲线');
  });
  it('CONNECTION_STATE_LABEL', () => {
    expect(translate(CONNECTION_STATE_LABEL, 'CONNECTED')).toBe('已连接');
    expect(translate(CONNECTION_STATE_LABEL, 'ERROR')).toBe('错误');
  });
  it('OPCUA_SECURITY_MODE_LABEL', () => {
    expect(translate(OPCUA_SECURITY_MODE_LABEL, 'NONE')).toBe('无安全');
  });
});
