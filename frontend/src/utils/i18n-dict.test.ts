import { describe, it, expect } from 'vitest';
import {
  translate,
  ALARM_STATE_LABEL,
  ALARM_SEVERITY_LABEL,
  METER_STATE_LABEL,
  COLLECTOR_PROTOCOL_LABEL,
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
