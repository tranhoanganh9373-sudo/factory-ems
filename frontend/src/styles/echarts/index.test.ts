import { describe, it, expect } from 'vitest';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { CanvasRenderer } from 'echarts/renderers';
import { ensureEchartsThemes, EMS_LIGHT, EMS_DARK } from './index';

echarts.use([LineChart, CanvasRenderer]);

describe('ensureEchartsThemes', () => {
  it('registers ems-light and ems-dark', () => {
    ensureEchartsThemes();
    const div = document.createElement('div');
    Object.assign(div.style, { width: '200px', height: '200px' });
    document.body.appendChild(div);
    expect(() => echarts.init(div, EMS_LIGHT)).not.toThrow();
    expect(() => echarts.init(div, EMS_DARK)).not.toThrow();
  });
});
